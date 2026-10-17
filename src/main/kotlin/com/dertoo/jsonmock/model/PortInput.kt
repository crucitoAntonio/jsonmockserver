package com.dertoo.jsonmock.model

/**
 * Interpreta lo que el usuario escribe o pega en el campo Puerto:
 * un número (`9090`) o la URL base de su app (`http://10.0.2.2:9090/`, `localhost:9090`).
 */
object PortInput {

    private val PORT_IN_URL = Regex("""^(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?(?:[^/@]*@)?(?:\[[^\]]*]|[^/:?#\s]+):(\d{1,5})(?:[/?#].*)?$""")

    /** Puerto válido (1..65535) o null si el texto no contiene uno. */
    fun parse(text: String): Int? {
        val t = text.trim()
        val raw = t.toIntOrNull() ?: PORT_IN_URL.matchEntire(t)?.groupValues?.get(1)?.toIntOrNull()
        return raw?.takeIf { it in 1..65535 }
    }

    /** true si el texto no es solo un número (p. ej. una URL), para normalizar el campo. */
    fun isUrl(text: String) = text.trim().toIntOrNull() == null
}
