package com.dertoo.jsonmock.server

import com.dertoo.jsonmock.settings.MockSettingsService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic

interface MockServerListener {
    fun serverStateChanged(running: Boolean, port: Int) {}
    fun requestHandled(entry: RequestLogEntry) {}

    companion object {
        @Topic.ProjectLevel
        val TOPIC = Topic.create("JSON Mock Server events", MockServerListener::class.java)
    }
}

/** Ciclo de vida del servidor embebido; se detiene al cerrar el proyecto (Disposable). */
class MockServerService(private val project: Project) : Disposable {

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("JsonMockServer", 16)

    @Volatile
    private var server: MockHttpServer? = null

    @Volatile
    var runningPort: Int = -1
        private set

    /** Puerto configurado cuando se arrancó; si luego cambia en la UI hace falta reiniciar. */
    @Volatile
    var requestedPort: Int = -1
        private set

    val isRunning: Boolean get() = server != null

    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        val settings = MockSettingsService.getInstance(project)
        val preferred = settings.state.port
        // Se averigua antes de arrancar, para poder decir quién lo ocupaba.
        val owner = if (PortFinder.isInUse(preferred)) PortFinder.describeOwner(preferred) else null

        var started: MockHttpServer? = null
        val port = try {
            PortFinder.startOnFreePort(preferred, settings.state.autoFreePort) { p ->
                val s = MockHttpServer(
                    port = p,
                    executor = executor,
                    rulesProvider = { settings.activeRules() },
                    targetBaseUrlProvider = { settings.state.targetBaseUrl },
                    fileResolver = { settings.resolveJsonFile(it) },
                    onRequest = { publisher().requestHandled(it) },
                )
                s.start()
                started = s
            }
        } catch (e: Exception) {
            notify("Could not start the server: ${e.message}", NotificationType.ERROR)
            return false
        }

        val ownerText = owner?.let { " by $it" } ?: ""
        if (port == null) {
            val hint = if (settings.state.autoFreePort) "None of the next ${PortFinder.MAX_TRIES} ports is free either."
            else "Enable \"Use another free port if busy\" or change the port."
            notify("Port $preferred is in use$ownerText. $hint", NotificationType.ERROR)
            return false
        }

        server = started
        runningPort = port
        requestedPort = preferred
        if (port != preferred) {
            // El puerto configurado no se toca: el siguiente arranque vuelve a intentarlo.
            notify(
                "Port $preferred is in use$ownerText. Using <b>$port</b> instead.<br/>" +
                    "Update your app: emulator <code>http://10.0.2.2:$port/</code> · USB <code>http://localhost:$port/</code>",
                NotificationType.WARNING,
            )
        }
        publisher().serverStateChanged(true, port)
        return true
    }

    /** true si el servidor corre en un puerto distinto del pedido al arrancar (fallback automático). */
    val isOnFallbackPort: Boolean
        get() = isRunning && runningPort != requestedPort

    /** true si el puerto configurado cambió desde que se arrancó: hay que reiniciar para aplicarlo. */
    val needsRestartForPort: Boolean
        get() = isRunning && requestedPort != MockSettingsService.getInstance(project).state.port

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        val port = runningPort
        runningPort = -1
        requestedPort = -1
        if (!project.isDisposed) publisher().serverStateChanged(false, port)
    }

    fun restart(): Boolean {
        stop()
        return start()
    }

    override fun dispose() {
        server?.stop()
        server = null
        executor.shutdownNow()
    }

    private fun publisher() = project.messageBus.syncPublisher(MockServerListener.TOPIC)

    fun notify(message: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JSON Mock Server")
            .createNotification(message, type)
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): MockServerService =
            project.getService(MockServerService::class.java)
    }
}

class MockAutoStartActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (MockSettingsService.getInstance(project).state.autoStart) {
            MockServerService.getInstance(project).start()
        }
    }
}
