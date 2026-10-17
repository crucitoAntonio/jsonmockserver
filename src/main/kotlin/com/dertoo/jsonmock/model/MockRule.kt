package com.dertoo.jsonmock.model

/**
 * Regla de mockeo. Es un bean mutable con constructor vacío para que el XmlSerializer
 * de la plataforma pueda persistirlo en `.idea/jsonMockServer.xml`.
 */
class MockRule() {
    var enabled: Boolean = true
    var method: String = "GET"
    var path: String = ""
    var statusCode: Int = 200
    /** Ruta al JSON; relativa al directorio del proyecto si está dentro de él. */
    var jsonFile: String = ""
    var delayMs: Int = 0

    constructor(other: MockRule) : this() {
        copyFrom(other)
    }

    fun copyFrom(other: MockRule) {
        enabled = other.enabled
        method = other.method
        path = other.path
        statusCode = other.statusCode
        jsonFile = other.jsonFile
        delayMs = other.delayMs
    }

    fun matches(requestMethod: String, requestPath: String): Boolean {
        if (method != ANY && !method.equals(requestMethod, ignoreCase = true)) return false
        return PathPattern.matches(path, requestPath)
    }

    companion object {
        const val ANY = "ANY"
        val METHODS = listOf(ANY, "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
    }
}

/**
 * Coincidencia de rutas:
 *  - `/v1/usuario`          exacta (ignora query string y la barra final)
 *  - `/v1/usuario/{id}`     `{x}` o `*` coinciden con un segmento
 *  - `/v1/` + `**`         `**` coincide con cero o más segmentos (p.ej. /v1/ seguido de **)
 *  - `https://host/v1/...`  si se pega una URL completa solo se usa su path
 */
object PathPattern {
    fun matches(pattern: String, requestPath: String): Boolean {
        val p = segments(normalize(pattern))
        val r = segments(normalize(requestPath))
        return match(p, 0, r, 0)
    }

    fun normalize(raw: String): String {
        var s = raw.trim()
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            val slash = s.indexOf('/', scheme + 3)
            s = if (slash >= 0) s.substring(slash) else "/"
        }
        s = s.substringBefore('?').substringBefore('#')
        if (!s.startsWith("/")) s = "/$s"
        return s.trimEnd('/').ifEmpty { "/" }
    }

    private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }

    private fun match(p: List<String>, pi: Int, r: List<String>, ri: Int): Boolean {
        if (pi == p.size) return ri == r.size
        val seg = p[pi]
        if (seg == "**") {
            for (k in ri..r.size) if (match(p, pi + 1, r, k)) return true
            return false
        }
        if (ri == r.size) return false
        val single = seg == "*" || (seg.startsWith("{") && seg.endsWith("}"))
        return (single || seg == r[ri]) && match(p, pi + 1, r, ri + 1)
    }
}
