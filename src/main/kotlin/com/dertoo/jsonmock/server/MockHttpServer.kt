package com.dertoo.jsonmock.server

import com.dertoo.jsonmock.model.MockRule
import com.intellij.openapi.diagnostic.thisLogger
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.zip.GZIPInputStream

/** Resultado de cada petición, para el log de la Tool Window. */
data class RequestLogEntry(
    val method: String,
    val uri: String,
    val status: Int,
    val kind: Kind,
    val durationMs: Long,
    val detail: String = "",
    /** Lo que envió la app y lo que se le respondió, para el panel de detalle del log. */
    val exchange: ExchangeDetails? = null,
) {
    enum class Kind { MOCK, PROXY, NOT_FOUND, ERROR }
}

/** Cabeceras y cuerpos (recortados a [MAX_BODY_CHARS]) de una petición y su respuesta. */
data class ExchangeDetails(
    /** Host al que llamó la app (`localhost:9096`, `10.0.2.2:9096`). */
    val host: String,
    val requestHeaders: List<Pair<String, String>>,
    val requestBody: String,
    /** URL real del backend: a la que se reenvió (PROXY) o la que se sustituyó (MOCK). */
    val backendUrl: String?,
    val responseHeaders: List<Pair<String, String>>,
    val responseBody: String,
) {
    companion object {
        const val MAX_BODY_CHARS = 32 * 1024

        /** Texto legible de un cuerpo: descomprime gzip y recorta. */
        fun bodyText(bytes: ByteArray, contentEncoding: String? = null): String {
            if (bytes.isEmpty()) return ""
            val raw = if (contentEncoding.equals("gzip", ignoreCase = true)) {
                runCatching { GZIPInputStream(bytes.inputStream()).use { it.readBytes() } }.getOrDefault(bytes)
            } else bytes
            val text = String(raw, Charsets.UTF_8)
            if (text.count { it == '\uFFFD' } > text.length / 10) return "(${raw.size} bytes of binary data)"
            return if (text.length > MAX_BODY_CHARS) text.take(MAX_BODY_CHARS) + "\n… (truncated, ${text.length} chars)" else text
        }
    }
}

/**
 * Servidor embebido basado en `com.sun.net.httpserver` (incluido en el JBR, sin dependencias extra).
 * Las reglas y el target se leen en cada petición, así que los cambios en la UI aplican en caliente.
 */
class MockHttpServer(
    private val port: Int,
    private val executor: ExecutorService,
    private val rulesProvider: () -> List<MockRule>,
    private val targetBaseUrlProvider: () -> String,
    private val fileResolver: (String) -> File,
    private val onRequest: (RequestLogEntry) -> Unit,
) {
    private var server: HttpServer? = null

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    fun start() {
        val s = HttpServer.create(InetSocketAddress(port), 0)
        s.createContext("/") { exchange -> exchange.use { handle(it) } }
        s.executor = executor
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        val started = System.currentTimeMillis()
        val method = exchange.requestMethod.uppercase()
        val uri = exchange.requestURI
        val target = targetBaseUrlProvider().trim()
        // La regla puede escribirse como la ve la app (/ruta3) o como la URL real del servicio
        // (https://example.com/api/v1/ruta3 o /api/v1/ruta3): se prueban las dos rutas.
        val realPath = realPathOf(target, uri.path)
        val rule = rulesProvider().firstOrNull { it.matches(method, uri.path) || it.matches(method, realPath) }
        // Se lee el body siempre (antes de responder) para poder mostrarlo en el log.
        val requestBody = runCatching { exchange.requestBody.readAllBytes() }.getOrDefault(ByteArray(0))
        val backendUrl = if (target.isEmpty()) null else realUrlOf(target, uri.toString())
        var responseBody = ByteArray(0)

        val entry = try {
            when {
                rule != null -> serveMock(exchange, rule, backendUrl).also { responseBody = it.second }.first
                target.isNotEmpty() -> proxy(exchange, target, requestBody).also { responseBody = it.second }.first
                else -> {
                    val json = """{"error":"No active mock rule matches and no real backend is configured","path":"${uri.path}"}"""
                    sendJson(exchange, 404, json, "none")
                    responseBody = json.toByteArray()
                    RequestLogEntry(method, uri.toString(), 404, RequestLogEntry.Kind.NOT_FOUND, 0)
                }
            }
        } catch (e: Exception) {
            thisLogger().warn("Error atendiendo $method $uri", e)
            val json = """{"error":"${e.javaClass.simpleName}: ${e.message?.replace("\"", "'")}"}"""
            runCatching { sendJson(exchange, 502, json, "error") }
            responseBody = json.toByteArray()
            RequestLogEntry(method, uri.toString(), 502, RequestLogEntry.Kind.ERROR, 0, e.message ?: e.javaClass.simpleName)
        }
        val details = ExchangeDetails(
            host = exchange.requestHeaders.getFirst("Host").orEmpty(),
            requestHeaders = headerList(exchange.requestHeaders),
            requestBody = ExchangeDetails.bodyText(requestBody, exchange.requestHeaders.getFirst("Content-Encoding")),
            backendUrl = backendUrl,
            responseHeaders = headerList(exchange.responseHeaders),
            responseBody = ExchangeDetails.bodyText(responseBody, exchange.responseHeaders.getFirst("Content-Encoding")),
        )
        onRequest(entry.copy(durationMs = System.currentTimeMillis() - started, exchange = details))
    }

    private fun headerList(headers: Map<String, List<String>>) =
        headers.entries.sortedBy { it.key.lowercase() }.flatMap { (name, values) -> values.map { name to it } }

    /** Devuelve la entrada del log y el cuerpo enviado. */
    private fun serveMock(exchange: HttpExchange, rule: MockRule, replacedUrl: String?): Pair<RequestLogEntry, ByteArray> {
        if (rule.delayMs > 0) Thread.sleep(rule.delayMs.toLong())

        val method = exchange.requestMethod
        val uri = exchange.requestURI.toString()
        val file = if (rule.jsonFile.isBlank()) null else fileResolver(rule.jsonFile)

        if (file != null && !file.isFile) {
            val json = """{"error":"Mock file not found","file":"${file.path.replace("\\", "/")}"}"""
            sendJson(exchange, 500, json, "mock")
            return RequestLogEntry(method, uri, 500, RequestLogEntry.Kind.ERROR, 0, "File not found: ${file.path}") to json.toByteArray()
        }
        // Se lee el archivo en cada petición: editar el JSON surte efecto inmediatamente.
        val body = file?.readBytes() ?: ByteArray(0)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("X-Mock-Server", "mock")
        sendBody(exchange, rule.statusCode, body)
        val detail = (file?.name ?: "(empty)") + (replacedUrl?.let { " instead of $it" } ?: "")
        return RequestLogEntry(method, uri, rule.statusCode, RequestLogEntry.Kind.MOCK, 0, detail) to body
    }

    /** Devuelve la entrada del log y el cuerpo recibido del backend. */
    private fun proxy(exchange: HttpExchange, targetBaseUrl: String, body: ByteArray): Pair<RequestLogEntry, ByteArray> {
        val target = URI.create(realUrlOf(targetBaseUrl, exchange.requestURI.toString()))

        val builder = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(60))
            .method(
                exchange.requestMethod,
                if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body),
            )
        exchange.requestHeaders.forEach { (name, values) ->
            if (name.lowercase() !in RESTRICTED_REQUEST_HEADERS) values.forEach { builder.header(name, it) }
        }

        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        response.headers().map().forEach { (name, values) ->
            if (name != ":status" && name.lowercase() !in HOP_BY_HOP_RESPONSE_HEADERS) {
                values.forEach { exchange.responseHeaders.add(name, it) }
            }
        }
        exchange.responseHeaders.add("X-Mock-Server", "proxy")
        sendBody(exchange, response.statusCode(), response.body())
        val entry = RequestLogEntry(exchange.requestMethod, exchange.requestURI.toString(), response.statusCode(), RequestLogEntry.Kind.PROXY, 0, "→ $target")
        return entry to response.body()
    }

    /** URL real del servicio: `https://example.com/api/v1` + `/ruta3?x=1`. */
    private fun realUrlOf(targetBaseUrl: String, requestUri: String) = targetBaseUrl.trimEnd('/') + requestUri

    private fun sendJson(exchange: HttpExchange, status: Int, json: String, source: String) {
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.set("X-Mock-Server", source)
        sendBody(exchange, status, json.toByteArray())
    }

    private fun sendBody(exchange: HttpExchange, status: Int, body: ByteArray) {
        val noBody = exchange.requestMethod.equals("HEAD", true) || status == 204 || status == 304
        exchange.sendResponseHeaders(status, if (noBody) -1 else if (body.isEmpty()) -1 else body.size.toLong())
        if (!noBody && body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
    }

    companion object {
        /** Ruta real en el backend: path de `https://example.com/api/v1` (`/api/v1`) + `/ruta3`. */
        fun realPathOf(targetBaseUrl: String, requestPath: String): String {
            val basePath = runCatching { URI(targetBaseUrl).path }.getOrNull().orEmpty().trimEnd('/')
            return if (basePath.isEmpty()) requestPath else basePath + requestPath
        }

        // Cabeceras que java.net.http.HttpClient no permite fijar manualmente.
        private val RESTRICTED_REQUEST_HEADERS = setOf("connection", "content-length", "expect", "host", "upgrade", "keep-alive", "transfer-encoding")
        private val HOP_BY_HOP_RESPONSE_HEADERS = setOf("connection", "content-length", "transfer-encoding", "keep-alive")
    }
}
