package com.dertoo.jsonmock.adb

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.util.Properties

/**
 * `adb reverse tcp:PORT tcp:PORT` permite que un dispositivo físico (USB) alcance
 * el servidor como http://localhost:PORT. En el emulador basta con http://10.0.2.2:PORT.
 */
object AdbReverse {

    fun run(project: Project, port: Int): Result<String> = runCatching {
        val adb = findAdb(project) ?: error("adb not found. Set ANDROID_HOME or sdk.dir in local.properties.")
        val cmd = GeneralCommandLine(adb.path, "reverse", "tcp:$port", "tcp:$port")
        val out = ExecUtil.execAndGetOutput(cmd, 15_000)
        if (out.exitCode != 0) error(out.stderr.ifBlank { out.stdout }.trim().ifBlank { "adb exited with code ${out.exitCode}" })
        "adb reverse tcp:$port is active: the device can now reach http://localhost:$port/"
    }

    private fun findAdb(project: Project): File? {
        val exe = if (SystemInfo.isWindows) "adb.exe" else "adb"
        val candidates = buildList {
            project.basePath?.let { base ->
                val lp = File(base, "local.properties")
                if (lp.isFile) {
                    val props = Properties().apply { lp.inputStream().use { load(it) } }
                    props.getProperty("sdk.dir")?.let { add(it) }
                }
            }
            System.getenv("ANDROID_HOME")?.let { add(it) }
            System.getenv("ANDROID_SDK_ROOT")?.let { add(it) }
            val home = System.getProperty("user.home")
            when {
                SystemInfo.isMac -> add("$home/Library/Android/sdk")
                SystemInfo.isWindows -> System.getenv("LOCALAPPDATA")?.let { add("$it\\Android\\Sdk") }
                else -> add("$home/Android/Sdk")
            }
        }
        return candidates.map { File(it, "platform-tools/$exe") }.firstOrNull { it.canExecute() }
    }
}
