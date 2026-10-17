package com.dertoo.jsonmock

import com.dertoo.jsonmock.model.MockRule
import com.dertoo.jsonmock.server.MockHttpServer
import com.dertoo.jsonmock.server.RequestLogEntry
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Prueba de extremo a extremo: mock activo, passthrough a un "backend real" local y 404 sin target. */
class MockHttpServerTest {

    private val executor = Executors.newCachedThreadPool()
    private lateinit var backend: HttpServer
    private lateinit var mock: MockHttpServer
    private var port = 0
    private var target = ""
    private val rules = mutableListOf<MockRule>()
    private val log = CopyOnWriteArrayList<RequestLogEntry>()
    private val client = HttpClient.newHttpClient()
    private val dir: File = Files.createTempDirectory("mocks").toFile()

    @Before
    fun setUp() {
        backend = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { ex ->
                val body = """{"real":true,"path":"${ex.requestURI.path}","method":"${ex.requestMethod}"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        File(dir, "usuario.json").writeText("""{"mock":true}""")
        port = ServerSocket(0).use { it.localPort }
        mock = MockHttpServer(port, executor, { rules.filter { it.enabled } }, { target }, { File(dir, it) }, { log += it })
        mock.start()
    }

    @After
    fun tearDown() {
        mock.stop(); backend.stop(0); executor.shutdownNow(); dir.deleteRecursively()
    }

    private fun get(path: String, method: String = "GET") = client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:$port$path"))
            .method(method, if (method == "GET") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString("{}"))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun mockPassthroughAndNotFound() {
        rules += MockRule().apply { path = "/v1/usuario/{id}"; jsonFile = "usuario.json"; statusCode = 201 }
        target = "http://localhost:${backend.address.port}"

        // Regla activa → JSON local
        get("/v1/usuario/7").let {
            assertEquals(201, it.statusCode())
            assertEquals("""{"mock":true}""", it.body())
            assertEquals("mock", it.headers().firstValue("X-Mock-Server").get())
        }
        // No coincide → backend real
        get("/v1/pedidos", "POST").let {
            assertEquals(200, it.statusCode())
            assertEquals("""{"real":true,"path":"/v1/pedidos","method":"POST"}""", it.body())
            assertEquals("proxy", it.headers().firstValue("X-Mock-Server").get())
        }
        // Regla desactivada en caliente → backend real
        rules[0].enabled = false
        assertEquals("proxy", get("/v1/usuario/7").headers().firstValue("X-Mock-Server").get())

        // Sin target → 404
        target = ""
        assertEquals(404, get("/v1/pedidos").statusCode())
    }

    /**
     * Release: https://example.com/api/v1/  ·  debug: http://10.0.2.2:PORT/  ·  Backend real: …/api/v1
     * La app pide /ruta1, /ruta2, /ruta3; solo ruta3 tiene regla.
     */
    @Test
    fun backendWithBasePath_ruleWrittenAnyWay() {
        target = "http://localhost:${backend.address.port}/api/v1"
        File(dir, "ruta3.json").writeText("""{"mock":"ruta3"}""")

        for (rulePath in listOf("/ruta3", "/api/v1/ruta3", "https://example.com/api/v1/ruta3")) {
            rules.clear()
            rules += MockRule().apply { path = rulePath; jsonFile = "ruta3.json" }

            // ruta1 y ruta2 llegan al backend real con su ruta completa
            assertEquals("""{"real":true,"path":"/api/v1/ruta1","method":"GET"}""", get("/ruta1").body())
            assertEquals("""{"real":true,"path":"/api/v1/ruta2","method":"GET"}""", get("/ruta2").body())
            // ruta3 con check → JSON
            assertEquals(rulePath, """{"mock":"ruta3"}""", get("/ruta3").body())
            // ruta3 sin check → servicio real /api/v1/ruta3
            rules[0].enabled = false
            assertEquals(rulePath, """{"real":true,"path":"/api/v1/ruta3","method":"GET"}""", get("/ruta3").body())
        }
    }

    /** El log guarda lo que envió la app y lo que se respondió (para el panel de detalle). */
    @Test
    fun logCapturesRequestAndResponse() {
        target = "http://localhost:${backend.address.port}/api/v1"
        rules += MockRule().apply { path = "/usuario"; method = "POST"; jsonFile = "usuario.json" }

        get("/usuario", "POST")
        get("/pedidos", "POST")
        // El log se publica justo después de responder: puede llegar un instante más tarde.
        val deadline = System.currentTimeMillis() + 2000
        while (log.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10)

        val mock = log.single { it.kind == RequestLogEntry.Kind.MOCK }.exchange!!
        assertEquals("{}", mock.requestBody)
        assertEquals("""{"mock":true}""", mock.responseBody)
        assertEquals("$target/usuario", mock.backendUrl)
        assertTrue(mock.host, mock.host.endsWith(":$port"))
        assertTrue(mock.responseHeaders.contains("X-mock-server" to "mock") || mock.responseHeaders.contains("X-Mock-Server" to "mock"))

        val proxy = log.single { it.kind == RequestLogEntry.Kind.PROXY }.exchange!!
        assertEquals("{}", proxy.requestBody)
        assertEquals("""{"real":true,"path":"/api/v1/pedidos","method":"POST"}""", proxy.responseBody)
        assertEquals("$target/pedidos", proxy.backendUrl)
    }
}
