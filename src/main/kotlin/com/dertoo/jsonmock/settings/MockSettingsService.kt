package com.dertoo.jsonmock.settings

import com.dertoo.jsonmock.model.MockRule
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import java.io.File

/** Estado persistido por proyecto en `.idea/jsonMockServer.xml`. */
class MockSettingsState {
    var port: Int = 8888
    /** Backend real para passthrough, p.ej. `https://api.miapp.com`. Vacío = 404 para lo no mockeado. */
    var targetBaseUrl: String = ""
    var autoStart: Boolean = false
    /** Si [port] está ocupado, arrancar en el siguiente libre sin cambiar [port]. */
    var autoFreePort: Boolean = true
    /** Interruptor general: en false ninguna regla se aplica (todo va al backend real), sin tocar el check de cada una. */
    var mocksEnabled: Boolean = true
    @get:XCollection(style = XCollection.Style.v2)
    var rules: MutableList<MockRule> = mutableListOf()
}

@State(name = "JsonMockServerSettings", storages = [Storage("jsonMockServer.xml")])
class MockSettingsService(private val project: Project) : PersistentStateComponent<MockSettingsState> {

    private var state = MockSettingsState()

    override fun getState(): MockSettingsState = state

    override fun loadState(loaded: MockSettingsState) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    /** Copia inmutable de las reglas activas, segura para leer desde los hilos del servidor. */
    fun activeRules(): List<MockRule> = synchronized(state) {
        if (!state.mocksEnabled) emptyList() else state.rules.filter { it.enabled }.map { MockRule(it) }
    }

    fun allRules(): List<MockRule> = synchronized(state) { state.rules.toList() }

    /** La UI trabaja sobre su propia lista y la publica aquí tras cada cambio. */
    fun setRules(rules: List<MockRule>) = synchronized(state) {
        state.rules = rules.toMutableList()
    }

    fun resolveJsonFile(path: String): File {
        val f = File(path)
        if (f.isAbsolute) return f
        return File(project.basePath ?: "", path)
    }

    /** Guarda rutas relativas al proyecto cuando es posible, para que la config sea portable. */
    fun toStoredPath(absolute: String): String {
        val base = project.basePath ?: return absolute
        val abs = File(absolute).absoluteFile.normalize()
        val baseFile = File(base).absoluteFile.normalize()
        return if (abs.startsWith(baseFile)) abs.relativeTo(baseFile).path else abs.path
    }

    companion object {
        fun getInstance(project: Project): MockSettingsService =
            project.getService(MockSettingsService::class.java)
    }
}
