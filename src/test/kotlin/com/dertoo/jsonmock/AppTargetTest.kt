package com.dertoo.jsonmock

import com.dertoo.jsonmock.model.AppTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTargetTest {

    @Test
    fun backendWithBasePath() {
        val t = AppTarget.of("https://testDevelop.com/api/v1/", 8888, lanIp = "192.168.1.50")
        assertEquals("http://10.0.2.2:8888/", t.emulator)
        assertEquals("http://localhost:8888/", t.usb)
        assertEquals("http://192.168.1.50:8888/", t.wifi)
        assertEquals("https://testDevelop.com/api/v1/", t.replaces)
        assertTrue(t.backendHasPath)
        assertEquals("""buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8888/\"")""", t.gradleLine())
    }

    @Test
    fun backendDomainOnlyAndFallbackPort() {
        val t = AppTarget.of("https://testDevelop.com", 8889, lanIp = null)
        assertEquals("http://10.0.2.2:8889/", t.emulator)
        assertNull(t.wifi)
        assertEquals("https://testDevelop.com/", t.replaces)
        assertFalse(t.backendHasPath)
    }

    @Test
    fun noBackend() {
        val t = AppTarget.of("  ", 8888, lanIp = null)
        assertNull(t.replaces)
        assertFalse(t.backendHasPath)
    }
}
