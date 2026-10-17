package com.dertoo.jsonmock.ui

import com.dertoo.jsonmock.model.MockRule
import com.dertoo.jsonmock.settings.MockSettingsService
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object MockFiles {

    private const val DIR = "mocks"

    fun mocksDir(project: Project) = File(project.basePath ?: System.getProperty("user.home"), DIR)

    /** `GET` + `/v1/usuario/{id}` → `GET_v1_usuario_id`. */
    fun suggestName(method: String, path: String): String {
        val m = if (method == MockRule.ANY) "ANY" else method.uppercase()
        return "${m}_${sanitize(path).ifEmpty { "root" }}"
    }

    /** Deja solo letras, dígitos, `.`, `_` y `-`; quita la extensión `.json` si la escribieron. */
    fun sanitize(name: String) = name.trim().removeSuffix(".json").trim('/')
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.')

    fun fileFor(project: Project, name: String) = File(mocksDir(project), "${sanitize(name)}.json")

    /** Crea (si no existe) `mocks/<METHOD>_<ruta>.json` con `{}`, lo abre en el editor y lo devuelve. */
    fun createJsonFor(project: Project, method: String, path: String): File {
        val file = fileFor(project, suggestName(method, path))
        if (!file.exists()) write(file, "{\n  \n}\n")
        openInEditor(project, file)
        return file
    }

    /** Escribe [content] en `mocks/<name>.json` (sobrescribe), lo abre en el editor y lo devuelve. */
    fun writeJson(project: Project, name: String, content: String): File {
        val file = fileFor(project, name)
        write(file, if (content.endsWith("\n")) content else "$content\n")
        openInEditor(project, file)
        return file
    }

    private fun write(file: File, content: String) {
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    fun openInEditor(project: Project, file: File) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    /**
     * Borra los archivos vía VFS (cierra sus editores y refresca el árbol del proyecto).
     * Devuelve los que no se pudieron borrar.
     */
    fun delete(project: Project, files: List<File>): List<File> = files.filter { file ->
        runCatching {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            if (vf != null) WriteAction.run<Exception> { vf.delete(project) } else file.delete()
        }.isFailure || file.exists()
    }

    fun openRuleJson(project: Project, rule: MockRule): Boolean {
        if (rule.jsonFile.isBlank()) return false
        val file = MockSettingsService.getInstance(project).resolveJsonFile(rule.jsonFile)
        if (!file.isFile) return false
        openInEditor(project, file)
        return true
    }
}
