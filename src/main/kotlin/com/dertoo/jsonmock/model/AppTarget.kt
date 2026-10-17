package com.dertoo.jsonmock.model

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI

/**
 * URLs a las que debe apuntar la app (build debug) para pasar por el plugin.
 * El Backend real lleva la URL base de desarrollo completa (`https://testDevelop.com/api/v1`), así que la app
 * apunta a la raíz del plugin y lo que añade detrás (`ruta3`) se pega al backend.
 */
data class AppTarget(
    val emulator: String,
    val usb: String,
    val wifi: String?,
    /** URL base de desarrollo a la que equivale (`https://testDevelop.com/api/v1/`), o null sin backend. */
    val replaces: String?,
    /** false si el Backend real es solo un dominio: si la app usa una ruta base, debe conservarla. */
    val backendHasPath: Boolean,
) {
    fun gradleLine(url: String = emulator) = """buildConfigField("String", "BASE_URL", "\"$url\"")"""

    companion object {
        const val EMULATOR_HOST = "10.0.2.2"

        fun of(backend: String, port: Int, lanIp: String? = localNetworkIp()): AppTarget {
            val b = backend.trim()
            val path = runCatching { URI(b).path }.getOrNull().orEmpty().trim('/')
            return AppTarget(
                emulator = "http://$EMULATOR_HOST:$port/",
                usb = "http://localhost:$port/",
                wifi = lanIp?.let { "http://$it:$port/" },
                replaces = b.takeIf { it.isNotEmpty() }?.trimEnd('/')?.plus("/"),
                backendHasPath = path.isNotEmpty(),
            )
        }

        /** Primera IPv4 de red local (192.168.x, 10.x, 172.16-31.x) de una interfaz activa. */
        fun localNetworkIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
