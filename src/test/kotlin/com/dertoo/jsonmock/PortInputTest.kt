package com.dertoo.jsonmock

import com.dertoo.jsonmock.model.PortInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortInputTest {

    @Test
    fun plainNumber() {
        assertEquals(8888, PortInput.parse("8888"))
        assertEquals(9090, PortInput.parse(" 9090 "))
    }

    @Test
    fun appBaseUrls() {
        assertEquals(9090, PortInput.parse("http://10.0.2.2:9090/"))
        assertEquals(9090, PortInput.parse("http://10.0.2.2:9090"))
        assertEquals(8889, PortInput.parse("http://localhost:8889/api/v1/"))
        assertEquals(7000, PortInput.parse("https://192.168.1.50:7000?x=1"))
        assertEquals(8080, PortInput.parse("localhost:8080"))
        assertEquals(8080, PortInput.parse("10.0.2.2:8080/"))
        assertEquals(3000, PortInput.parse("http://[::1]:3000/"))
        assertEquals(5000, PortInput.parse("http://user:pass@host:5000/"))
    }

    @Test
    fun noPortOrInvalid() {
        assertNull(PortInput.parse("http://10.0.2.2/"))
        assertNull(PortInput.parse("http://localhost"))
        assertNull(PortInput.parse("http://localhost:99999/"))
        assertNull(PortInput.parse("0"))
        assertNull(PortInput.parse("abc"))
        assertNull(PortInput.parse(""))
    }
}
