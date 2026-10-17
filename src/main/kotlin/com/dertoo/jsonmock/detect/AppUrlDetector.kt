package com.dertoo.jsonmock.detect

import java.io.File
import java.net.URI

/** Una URL encontrada en los archivos de la app. */
data class DetectedUrl(
    val url: String,
    /** Nombre de la constante/campo (`BASE_URL`, `api_url`, `baseUrl`…) o vacío. */
    val name: String,
    /** Build type / flavor / source set (`debug`, `release`, `staging`…) o vacío. */
    val variant: String,
    /** Ruta relativa a la raíz del proyecto. */
    val file: String,
    val line: Int,
) {
    private val uri: URI? = runCatching { URI(url) }.getOrNull()
    val host: String = uri?.host.orEmpty()
    val port: Int? = uri?.port?.takeIf { it > 0 }
    val path: String = uri?.path.orEmpty()

    /** Apunta al propio ordenador (emulador, adb reverse o red local): es la URL del mock. */
    val isLocal: Boolean get() = AppUrlDetector.isLocalHost(host)

    val origin: String get() = buildString {
        append(uri?.scheme ?: "https").append("://").append(host)
        if (port != null) append(':').append(port)
    }
}

/**
 * Busca URLs base en un proyecto Android leyendo sus archivos:
 * `buildConfigField`/`resValue` en Gradle, `.properties`, `res/values*.xml` y constantes en Kotlin/Java.
 */
object AppUrlDetector {

    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_FILES = 20_000

    private val SKIP_DIRS = setOf("build", "out", "node_modules", "generated", "intermediates", "gradle", "buildSrc-cache")
    private val CODE_EXT = setOf("kt", "kts", "java", "gradle", "properties")

    private val URL = Regex("""https?://[^\s"'<>\\`]+""")
    private val NAME_PATTERNS = listOf(
        Regex("""(?:buildConfigField|resValue)\s*\(?\s*["']\w+["']\s*,\s*["'](\w+)["']"""),
        Regex("""<string\s+name\s*=\s*"(\w+)""""),
        Regex("""\.(baseUrl)\s*\("""),
        Regex("""^\s*([\w.\-]+)\s*[=:]\s*https?://"""),                                  // .properties
        Regex("""(\w+)\s*(?::\s*[\w<>?.]+)?\s*=\s*"https?://"""),                        // Kotlin/Java
    )
    private val COMMENT_PREFIXES = listOf("//", "/*", "*", "#", "<!--")

    /** Hosts de documentación, esquemas y repositorios: nunca son el backend de la app. */
    private val NOISE_HOSTS = setOf(
        "schemas.android.com", "www.w3.org", "w3.org", "www.apache.org", "apache.org", "opensource.org",
        "repo.maven.apache.org", "repo1.maven.org", "maven.google.com", "dl.google.com", "jitpack.io",
        "plugins.gradle.org", "services.gradle.org", "gradle.org", "developer.android.com", "source.android.com",
        "kotlinlang.org", "www.jetbrains.com", "json-schema.org", "schemas.xmlsoap.org", "oss.sonatype.org",
        "s01.oss.sonatype.org", "jcenter.bintray.com", "github.com", "www.github.com", "xmlpull.org",
    )
    private val BLOCK_CONTAINERS = setOf("buildTypes", "productFlavors")

    /**
     * Recorre [root]. [onFile] recibe cada ruta relativa antes de leerla (para mostrar progreso);
     * puede lanzar una excepción para cancelar.
     */
    fun scan(root: File, onFile: (String) -> Unit = {}): List<DetectedUrl> {
        val found = LinkedHashMap<String, DetectedUrl>()
        var count = 0
        root.walkTopDown()
            .onEnter { it == root || (it.name !in SKIP_DIRS && !it.name.startsWith(".")) }
            .filter { it.isFile && isCandidate(it) && it.length() <= MAX_FILE_BYTES }
            .forEach { file ->
                if (++count > MAX_FILES) return found.values.toList()
                val rel = file.relativeTo(root).invariantSeparatorsPath
                onFile(rel)
                scanFile(file, rel).forEach { d -> found.putIfAbsent("${d.url}|${d.name}|${d.variant}", d) }
            }
        return found.values.toList()
    }

    private fun isCandidate(f: File): Boolean {
        val ext = f.extension.lowercase()
        if (ext in CODE_EXT) return true
        return ext == "xml" && f.parentFile?.name?.startsWith("values") == true
    }

    internal fun scanFile(file: File, rel: String): List<DetectedUrl> {
        val isGradle = file.name.endsWith(".gradle") || file.name.endsWith(".gradle.kts")
        val sourceSetVariant = sourceSetOf(rel)
        val blocks = ArrayDeque<String>()
        val result = mutableListOf<DetectedUrl>()

        file.readLines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            val variant = if (isGradle) variantFromBlocks(blocks) else sourceSetVariant
            if (COMMENT_PREFIXES.none { trimmed.startsWith(it) }) {
                val name = NAME_PATTERNS.firstNotNullOfOrNull { it.find(line)?.groupValues?.get(1) }.orEmpty()
                URL.findAll(line).forEach { m ->
                    val url = m.value.trimEnd(')', ',', ';', '.', ']', '}')
                    val d = DetectedUrl(url, name, variant, rel, index + 1)
                    if (d.host.isNotEmpty() && d.host !in NOISE_HOSTS) result += d
                }
            }
            if (isGradle) trackBlocks(line, blocks)
        }
        return result
    }

    /** `app/src/debug/...` → `debug`; `main`, `test`, `androidTest` → vacío. */
    private fun sourceSetOf(rel: String): String {
        val parts = rel.split('/')
        val i = parts.indexOf("src")
        val set = parts.getOrNull(i + 1) ?: return ""
        return if (i < 0 || set == "main" || set.startsWith("test") || set.startsWith("androidTest")) "" else set
    }

    /** El bloque justo dentro de `buildTypes { }` o `productFlavors { }`; `defaultConfig` → "default". */
    private fun variantFromBlocks(blocks: ArrayDeque<String>): String {
        val list = blocks.toList()
        val i = list.indexOfLast { it in BLOCK_CONTAINERS }
        if (i >= 0) return list.getOrNull(i + 1).orEmpty()
        return if ("defaultConfig" in list) "default" else ""
    }

    /** Lleva la pila de bloques Gradle: `debug {`, `getByName("release") {`, `create("staging") {`. */
    private fun trackBlocks(line: String, blocks: ArrayDeque<String>) {
        var segmentStart = 0
        line.forEachIndexed { i, c ->
            when (c) {
                '{' -> {
                    val token = Regex("""\w+""").findAll(line.substring(segmentStart, i)).lastOrNull()?.value.orEmpty()
                    blocks.addLast(token)
                    segmentStart = i + 1
                }
                '}' -> {
                    blocks.removeLastOrNull()
                    segmentStart = i + 1
                }
            }
        }
    }

    fun isLocalHost(host: String): Boolean {
        if (host in setOf("10.0.2.2", "10.0.3.2", "localhost", "127.0.0.1")) return true
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }

    /** Variantes que suelen ser producción: nunca se proponen como backend de desarrollo. */
    fun isProductionVariant(variant: String) = variant.lowercase() in setOf("release", "prod", "production", "store")

    /**
     * Sugerencias iniciales: (URL de la app con puerto, backend real).
     * El backend nunca se propone desde una variante de producción: mandaría el tráfico de desarrollo a prod.
     */
    fun suggest(urls: List<DetectedUrl>): Pair<DetectedUrl?, DetectedUrl?> {
        val app = urls.filter { it.isLocal && it.port != null }
            .maxByOrNull { (if (it.variant == "debug") 2 else 0) + (if (it.name.isNotEmpty()) 1 else 0) }
        val backend = urls.filter { !it.isLocal && !isProductionVariant(it.variant) }.maxByOrNull { d ->
            var score = 0
            if (app != null && d.name.isNotEmpty() && d.name == app.name) score += 8
            if (d.name.contains("url", true) || d.name.contains("host", true) || d.name.contains("api", true)) score += 4
            when (d.variant) { "debug" -> score += 3; "", "default" -> score += 1; else -> score += 2 }
            if (d.file.endsWith(".gradle") || d.file.endsWith(".gradle.kts")) score += 1
            score
        }
        return app to backend
    }

    /**
     * Backend real a configurar. La app manda al mock la ruta que va DESPUÉS de su URL base de debug,
     * así que si la de release lleva una ruta que la de debug no tiene, esa ruta va en el target:
     *  - release `https://example.com/api/v1/`, debug `http://10.0.2.2:9090/`        → `https://example.com/api/v1`
     *  - release `https://example.com/api/v1/`, debug `http://10.0.2.2:9090/api/v1/` → `https://example.com`
     */
    fun targetFor(backend: DetectedUrl, app: DetectedUrl?): String {
        val bp = backend.path.trim('/')
        val mp = app?.path?.trim('/').orEmpty()
        val base = when {
            mp.isEmpty() -> bp
            bp == mp -> ""
            bp.endsWith("/$mp") -> bp.removeSuffix(mp).trim('/')
            else -> bp
        }
        return if (base.isEmpty()) backend.origin else "${backend.origin}/$base"
    }
}
