package com.dertoo.jsonmock

import com.dertoo.jsonmock.server.PortFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket

class PortFinderTest {

    /** Ocupa un puerto solo en 127.0.0.1, como hacen muchos servidores de desarrollo. */
    private fun occupyLoopback() = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test
    fun usesPreferredPortWhenFree() {
        val preferred = freePort()
        val used = PortFinder.startOnFreePort(preferred, autoFallback = true) { }
        assertEquals(preferred, used)
    }

    @Test
    fun detectsPortTakenOnLoopback() {
        occupyLoopback().use { busy ->
            assertTrue(PortFinder.isInUse(busy.localPort))
        }
    }

    @Test
    fun fallsBackToNextFreePort() {
        occupyLoopback().use { busy ->
            val tried = mutableListOf<Int>()
            val used = PortFinder.startOnFreePort(busy.localPort, autoFallback = true) { tried += it }
            assertNotNull(used)
            assertNotEquals(busy.localPort, used)
            assertTrue(used!! > busy.localPort)
            assertFalse("No debe intentar arrancar en el puerto ocupado", busy.localPort in tried)
        }
    }

    @Test
    fun skipsPortsWhereBindFails() {
        val preferred = freePort()
        val used = PortFinder.startOnFreePort(preferred, autoFallback = true) { p ->
            if (p == preferred) throw BindException("Address already in use")
        }
        assertEquals(preferred + 1, used)
    }

    @Test
    fun withoutFallbackReturnsNullWhenBusy() {
        occupyLoopback().use { busy ->
            assertNull(PortFinder.startOnFreePort(busy.localPort, autoFallback = false) { })
        }
    }

    @Test
    fun describesOwnerOfBusyPort() {
        occupyLoopback().use { busy ->
            // En macOS/Linux con lsof debe identificar este mismo proceso (java).
            val owner = PortFinder.describeOwner(busy.localPort)
            if (owner != null) assertTrue(owner, owner.contains("PID ${ProcessHandle.current().pid()}"))
        }
    }
}
