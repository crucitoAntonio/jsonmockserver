package com.dertoo.jsonmock.server

import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object PortFinder {

    /** Cuántos puertos siguientes se prueban cuando el preferido está ocupado. */
    const val MAX_TRIES = 50

    /**
     * Arranca en [preferred] o, si [autoFallback] y está ocupado, en el siguiente libre.
     * [starter] debe lanzar [BindException] si el puerto no se puede usar.
     * Devuelve el puerto usado o null si ninguno sirvió.
     */
    fun startOnFreePort(preferred: Int, autoFallback: Boolean, starter: (Int) -> Unit): Int? {
        val last = if (autoFallback) minOf(preferred + MAX_TRIES, 65535) else preferred
        for (port in preferred..last) {
            if (isInUse(port)) continue
            try {
                starter(port)
                return port
            } catch (_: BindException) {
                // ocupado por alguien que no responde a conexiones locales: probar el siguiente
            }
        }
        return null
    }

    /**
     * true si ya hay algo escuchando en localhost:[port].
     * Es necesario además del bind: en macOS/Linux Java usa SO_REUSEADDR y puede enlazar 0.0.0.0
     * aunque otro proceso escuche en 127.0.0.1, y el emulador (10.0.2.2 → 127.0.0.1) hablaría con ese otro.
     */
    fun isInUse(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
        true
    } catch (_: IOException) {
        false
    }

    /** "node (PID 1234)" del proceso que escucha en [port], o null si no se puede averiguar. */
    fun describeOwner(port: Int): String? = try {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        if (windows) describeOwnerWindows(port) else describeOwnerUnix(port)
    } catch (_: Exception) {
        null
    }

    private fun describeOwnerUnix(port: Int): String? {
        // -F pc → líneas "p<pid>" y "c<comando>"
        val out = run("lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-Fpc") ?: return null
        val pid = out.lineSequence().firstOrNull { it.startsWith("p") }?.drop(1) ?: return null
        val cmd = out.lineSequence().firstOrNull { it.startsWith("c") }?.drop(1)
        return if (cmd != null) "$cmd (PID $pid)" else "PID $pid"
    }

    private fun describeOwnerWindows(port: Int): String? {
        val out = run("netstat", "-ano", "-p", "TCP") ?: return null
        val pid = out.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 5 && it[1].endsWith(":$port") && it[3].equals("LISTENING", true) }
            ?.get(4) ?: return null
        val name = run("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH")
            ?.lineSequence()?.firstOrNull()?.split("\",\"")?.firstOrNull()?.trim('"')
            ?.takeIf { it.isNotBlank() && !it.startsWith("INFO") }
        return if (name != null) "$name (PID $pid)" else "PID $pid"
    }

    private fun run(vararg cmd: String): String? {
        // Salida a un archivo temporal: netstat puede escribir mucho y llenar el pipe antes del waitFor.
        val tmp = File.createTempFile("jsonmock-port", ".txt")
        try {
            val process = ProcessBuilder(*cmd).redirectErrorStream(true).redirectOutput(tmp).start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return null
            }
            return tmp.readText()
        } finally {
            tmp.delete()
        }
    }
}
