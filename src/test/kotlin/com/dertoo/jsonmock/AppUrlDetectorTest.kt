package com.dertoo.jsonmock

import com.dertoo.jsonmock.detect.AppUrlDetector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppUrlDetectorTest {

    private val root: File = Files.createTempDirectory("app").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(path: String, content: String) = File(root, path).apply {
        parentFile.mkdirs()
        writeText(content.trimIndent())
    }

    private fun sampleProject() {
        file("app/build.gradle.kts", """
            plugins { id("com.android.application") }
            android {
                namespace = "com.example"
                defaultConfig {
                    applicationId = "com.example"
                }
                buildTypes {
                    getByName("debug") {
                        buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:9090/\"")
                    }
                    release {
                        buildConfigField("String", "BASE_URL", "\"https://example.com/\"")
                    }
                }
            }
            // buildConfigField("String", "OLD_URL", "\"https://old.example.com/\"")
        """)
        file("gradle/wrapper/gradle-wrapper.properties", "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.0-bin.zip")
        file("app/src/main/AndroidManifest.xml", """<manifest xmlns:android="http://schemas.android.com/apk/res/android"/>""")
        file("app/src/main/res/values/strings.xml", """<resources><string name="terms_url">https://example.com/terminos</string></resources>""")
        file("app/src/main/java/com/example/Api.kt", """
            object Api {
                const val PROMOS_HOST = "https://promos.example.com/"
                val retrofit = Retrofit.Builder().baseUrl(BuildConfig.BASE_URL).build()
            }
        """)
        file("app/build/generated/Foo.kt", """const val X = "https://generated.example.com/" """)
    }

    @Test
    fun findsUrlsWithNameVariantAndLine() {
        sampleProject()
        val urls = AppUrlDetector.scan(root)
        val debug = urls.single { it.url == "http://10.0.2.2:9090/" }
        assertEquals("BASE_URL", debug.name)
        assertEquals("debug", debug.variant)
        assertEquals("app/build.gradle.kts", debug.file)
        assertEquals(9, debug.line)
        assertTrue(debug.isLocal)
        assertEquals(9090, debug.port)

        val release = urls.single { it.url == "https://example.com/" }
        assertEquals("release", release.variant)
        assertFalse(release.isLocal)

        assertEquals("terms_url", urls.single { it.url == "https://example.com/terminos" }.name)
        assertEquals("PROMOS_HOST", urls.single { it.host == "promos.example.com" }.name)
    }

    @Test
    fun ignoresNoiseCommentsAndBuildDirs() {
        sampleProject()
        val hosts = AppUrlDetector.scan(root).map { it.host }
        assertFalse("schemas.android.com" in hosts)
        assertFalse("services.gradle.org" in hosts)
        assertFalse("old.example.com" in hosts)       // comentado
        assertFalse("generated.example.com" in hosts)  // build/
    }

    private fun gradle(debugUrl: String) = file("app/build.gradle.kts", """
        android {
            buildTypes {
                debug {
                    buildConfigField("String", "BASE_URL", "\\"$debugUrl\\"")
                }
                release {
                    buildConfigField("String", "BASE_URL", "\\"https://testproduction.com/api/v1/\\"")
                }
            }
        }
    """)

    @Test
    fun beforeChangingDebug_suggestsDevelopmentBackend() {
        gradle("https://testDevelop.com/api/v1/")
        val (app, backend) = AppUrlDetector.suggest(AppUrlDetector.scan(root))
        assertNull(app)
        assertEquals("https://testDevelop.com/api/v1/", backend!!.url)
    }

    @Test
    fun afterChangingDebug_neverSuggestsProduction() {
        gradle("http://10.0.2.2:8888/api/v1/")
        val urls = AppUrlDetector.scan(root)
        val (app, backend) = AppUrlDetector.suggest(urls)
        assertEquals(8888, app!!.port)
        assertNull("No debe proponer testproduction.com", backend)
        assertTrue(urls.any { it.host == "testproduction.com" }) // sigue en la lista para elegirla a mano
    }

    @Test
    fun suggestsFlavorBackendWithSameName() {
        file("app/build.gradle.kts", """
            android {
                productFlavors {
                    create("dev") { buildConfigField("String", "BASE_URL", "\\"https://testDevelop.com/api/v1/\\"") }
                    create("prod") { buildConfigField("String", "BASE_URL", "\\"https://testproduction.com/api/v1/\\"") }
                }
                buildTypes {
                    debug { buildConfigField("String", "BASE_URL", "\\"http://10.0.2.2:8888/api/v1/\\"") }
                }
            }
        """)
        val (app, backend) = AppUrlDetector.suggest(AppUrlDetector.scan(root))
        assertEquals("http://10.0.2.2:8888/api/v1/", app!!.url)
        assertEquals("https://testDevelop.com/api/v1/", backend!!.url)
        assertEquals("https://testDevelop.com", AppUrlDetector.targetFor(backend, app))
    }

    @Test
    fun sourceSetVariant() {
        file("app/src/staging/java/Config.kt", """const val API_URL = "https://staging.example.com/" """)
        assertEquals("staging", AppUrlDetector.scan(root).single().variant)
    }

    @Test
    fun targetKeepsPathTheDebugUrlDoesNotHave() {
        file("a.properties", "release.url=https://example.com/api/v1/\ndebug.url=http://10.0.2.2:8888/\nboth.url=http://10.0.2.2:8888/api/v1/")
        val urls = AppUrlDetector.scan(root)
        val backend = urls.single { it.name == "release.url" }
        assertEquals("https://example.com/api/v1", AppUrlDetector.targetFor(backend, urls.single { it.name == "debug.url" }))
        assertEquals("https://example.com", AppUrlDetector.targetFor(backend, urls.single { it.name == "both.url" }))
        assertEquals("https://example.com/api/v1", AppUrlDetector.targetFor(backend, null))
    }

    @Test
    fun localHosts() {
        listOf("10.0.2.2", "localhost", "127.0.0.1", "192.168.1.50", "10.1.2.3", "172.20.0.1")
            .forEach { assertTrue(it, AppUrlDetector.isLocalHost(it)) }
        listOf("example.com", "172.40.0.1", "8.8.8.8").forEach { assertFalse(it, AppUrlDetector.isLocalHost(it)) }
    }
}
