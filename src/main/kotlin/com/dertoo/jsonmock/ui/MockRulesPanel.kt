package com.dertoo.jsonmock.ui

import com.dertoo.jsonmock.adb.AdbReverse
import com.dertoo.jsonmock.detect.AppUrlDetector
import com.dertoo.jsonmock.detect.DetectedUrl
import com.dertoo.jsonmock.model.MockRule
import com.dertoo.jsonmock.model.AppTarget
import com.dertoo.jsonmock.model.PortInput
import com.dertoo.jsonmock.server.MockServerListener
import com.dertoo.jsonmock.server.MockServerService
import com.dertoo.jsonmock.server.RequestLogEntry
import com.dertoo.jsonmock.settings.MockSettingsService
import com.dertoo.jsonmock.model.JsonCheck
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ui.BooleanTableCellRenderer
import com.intellij.ui.PopupHandler
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import javax.swing.JTable
import javax.swing.SwingUtilities
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.whenStateChangedFromUi
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import com.intellij.util.Alarm
import com.dertoo.jsonmock.server.ExchangeDetails
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.text.JTextComponent

/**
 * Contenido de la Tool Window, en dos pestañas: [serverTab] (puerto, backend, URLs para la app y estado)
 * y [mocksTab] (tabla de reglas y log de peticiones).
 */
class MockRulesPanel(private val project: Project) : Disposable {

    private val settings = MockSettingsService.getInstance(project)
    private val server = MockServerService.getInstance(project)

    private val model = ListTableModel<MockRule>(
        arrayOf(EnabledColumn(), TextColumn("Method", 70) { it.method }, TextColumn("Path", 0) { it.path },
            TextColumn("Status", 60) { it.statusCode.toString() }, TextColumn("JSON", 0) { it.jsonFile.ifBlank { "(empty)" } }),
        settings.allRules().toMutableList(),
    )
    private val table = TableView(model)

    private val logModel = CollectionListModel<Pair<Long, RequestLogEntry>>()
    private val logList = JBList(logModel)
    private val logDetails = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create("Monospaced", 12)
        emptyText.text = "Select a request to see its headers and bodies"
    }

    private lateinit var startStopButton: JButton
    private lateinit var portField: JTextComponent
    private lateinit var backendField: JTextComponent
    private val statusLabel = JBLabel()
    private val mocksSwitchLabel = JBLabel()
    private val mocksSwitchButton = JButton().apply { addActionListener { setMocksEnabled(!settings.state.mocksEnabled) } }

    private var appTarget = AppTarget.of(settings.state.targetBaseUrl, settings.state.port)
    private val emulatorUrl = urlLabel()
    private val usbUrl = urlLabel()
    private val wifiUrl = urlLabel()
    private val replacesLabel = JBLabel()
    private val adbCommandLabel = urlLabel()
    private lateinit var wifiRow: Row

    /** Aplica el cambio de puerto un momento después de dejar de escribir (sin tener que pulsar Reiniciar). */
    private val portAlarm = Alarm(this)

    /** Va en su propio scroll: en una Tool Window estrecha el texto no se pierde. */
    val serverTab: JComponent = ScrollPaneFactory.createScrollPane(createHeader(), true)

    val mocksTab: JComponent = JBSplitter(true, "JsonMockServer.rules", 0.4f).apply {
        firstComponent = createRulesPanel()
        secondComponent = createLogPanel()
    }

    init {

        model.addTableModelListener { persist() }

        project.messageBus.connect(this).subscribe(MockServerListener.TOPIC, object : MockServerListener {
            override fun serverStateChanged(running: Boolean, port: Int) =
                ApplicationManager.getApplication().invokeLater({ updateStatus() }, project.disposed)

            override fun requestHandled(entry: RequestLogEntry) =
                ApplicationManager.getApplication().invokeLater({ appendLog(entry) }, project.disposed)
        })
        updateStatus()
    }

    // ---------- Cabecera (Kotlin UI DSL) ----------

    private fun createHeader() = panel {
        val state = settings.state
        row("Port:") {
            portField = textField().columns(18).also { it.component.text = state.port.toString() }
                .also { cell -> onTextChange(cell.component) { txt -> applyPortInput(cell.component, txt) } }
                .also { cell -> onCommit(cell.component) { commitPort() } }
                .comment("A number or your app's URL (e.g. http://10.0.2.2:9090/). If the server is running it restarts on the new port")
                .component
        }
        row("Real backend:") {
            backendField = textField().align(AlignX.FILL).also { it.component.text = state.targetBaseUrl }
                .also { onTextChange(it.component) { txt -> state.targetBaseUrl = txt.trim(); refreshAppTarget() } }
                .comment("Your app's development base URL as is (e.g. https://dev.example.com/api/v1/). Anything not mocked is forwarded here")
                .component
        }
        row {
            button("Detect from App…") { detectFromApp() }
                .comment("Looks for base URLs in build.gradle, .properties, resources and code and fills in the fields")
        }
        collapsibleGroup("Your app (debug build) should point to") {
            row("Emulator:") {
                cell(emulatorUrl)
                copyButton { appTarget.emulator }
            }
            row("USB:") {
                cell(usbUrl)
                copyButton { appTarget.usb }
            }.rowComment("Requires <b>adb reverse</b> with the phone connected (see below)")
            wifiRow = row("Wi-Fi:") {
                cell(wifiUrl)
                copyButton { appTarget.wifi }
            }
            row { cell(replacesLabel) }
            row {
                button("Copy Gradle Line") { e -> copy(appTarget.gradleLine(), e.source as JComponent) }
                    .comment("For the <code>debug { }</code> block of <code>app/build.gradle.kts</code> (emulator URL)")
            }
        }.rememberExpanded("JsonMockServer.appTarget.expanded", true)
        collapsibleGroup("Physical device over USB (adb reverse)") {
            row {
                text(
                    "<code>adb reverse</code> makes <code>localhost</code> on the phone reach this computer, " +
                        "so the app can use <code>http://localhost:PORT/</code>.<br/><br/>" +
                        "1. On the phone enable <b>Developer options › USB debugging</b> " +
                        "(tap <i>Build number</i> 7 times in <i>About phone</i> to unlock Developer options).<br/>" +
                        "2. Connect it by USB and accept <i>Allow USB debugging?</i>. " +
                        "<code>adb devices</code> must list it as <code>device</code>.<br/>" +
                        "3. Start the server and press <b>adb reverse</b> (or run the command below in a terminal).<br/>" +
                        "4. Point the app's debug base URL to the <b>USB</b> URL above and allow cleartext HTTP for " +
                        "<code>localhost</code> in its <code>network_security_config.xml</code>.<br/>" +
                        "5. Run it again after unplugging the cable, restarting adb or the phone, or changing the port.<br/><br/>" +
                        "Check it with <code>adb reverse --list</code>; undo it with <code>adb reverse --remove-all</code>. " +
                        "With several devices connected add <code>-s &lt;serial&gt;</code> (from <code>adb devices</code>).",
                )
            }
            row("Command:") {
                cell(adbCommandLabel)
                copyButton { adbCommand() }
                button("Run") { runAdbReverse() }
            }
        }.rememberExpanded("JsonMockServer.adbHelp.expanded", false)
        row {
            checkBox("Start automatically when the project opens").also { it.component.isSelected = state.autoStart }
                .whenStateChangedFromUi { state.autoStart = it }
        }
        row {
            checkBox("Use another free port if busy").also { it.component.isSelected = state.autoFreePort }
                .whenStateChangedFromUi { state.autoFreePort = it }
                .comment("The configured port is always tried first")
        }
        row {
            startStopButton = button("Start") { toggleServer() }.component
            button("Restart") { runServerTask("Restarting JSON Mock Server…") { server.restart() } }
            button("adb reverse") { runAdbReverse() }
                .comment("For a physical device over USB")
        }
        row { cell(statusLabel) }
    }.apply { border = JBUI.Borders.empty(6, 8) }

    /** Recuerda (para todos los proyectos) si la sección estaba abierta o plegada. */
    private fun CollapsibleRow.rememberExpanded(key: String, default: Boolean) = apply {
        val props = PropertiesComponent.getInstance()
        expanded = props.getBoolean(key, default)
        addExpandedListener { props.setValue(key, it, default) }
    }

    private fun adbCommand() = "adb reverse tcp:${currentPort()} tcp:${currentPort()}"

    /** Con el servidor arrancado, el puerto real (puede ser el de reserva); si no, el configurado. */
    private fun currentPort() = if (server.isRunning) server.runningPort else settings.state.port

    private fun urlLabel() = JBLabel().apply {
        setCopyable(true)
        font = JBUI.Fonts.label().deriveFont(Font.BOLD)
    }

    private fun Row.copyButton(value: () -> String?) =
        button("Copy") { e -> value()?.let { copy(it, e.source as JComponent) } }

    private fun copy(text: String, anchor: JComponent) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder("Copied: <code>${StringUtil.escapeXmlEntities(text)}</code>", MessageType.INFO, null)
            .setFadeoutTime(2500)
            .createBalloon()
            .show(RelativePoint.getSouthOf(anchor), Balloon.Position.below)
    }

    /**
     * Recalcula las URLs para la app. Usa el puerto en el que escucha el servidor (puede ser el de reserva)
     * o, apagado, el configurado.
     */
    private fun refreshAppTarget() {
        if (!::wifiRow.isInitialized) return
        appTarget = AppTarget.of(settings.state.targetBaseUrl, currentPort())
        adbCommandLabel.text = adbCommand()
        emulatorUrl.text = appTarget.emulator
        usbUrl.text = appTarget.usb
        wifiUrl.text = appTarget.wifi.orEmpty()
        wifiRow.visible(appTarget.wifi != null)

        val replaces = appTarget.replaces
        replacesLabel.icon = if (replaces == null) AllIcons.General.Warning else AllIcons.General.Information
        replacesLabel.text = when {
            replaces == null ->
                "<html>Enter your app's development base URL in <b>Real backend</b> " +
                    "(e.g. <code>https://dev.example.com/api/v1/</code>).</html>"
            appTarget.backendHasPath ->
                "<html>Instead of <code>$replaces</code>.<br/>Whatever the app appends (e.g. <code>route3</code>) " +
                    "is forwarded to <code>${replaces}route3</code>.</html>"
            else ->
                "<html>Instead of <code>$replaces</code>.<br/>If your app uses a base path (e.g. <code>/api/v1/</code>), " +
                    "include it in <b>Real backend</b> so this URL is exact.</html>"
        }
    }

    /**
     * Acepta un número o la URL base de la app: de `http://10.0.2.2:9090/` toma 9090,
     * lo guarda y deja solo el número en el campo.
     */
    private fun applyPortInput(field: JTextComponent, text: String) {
        val port = PortInput.parse(text) ?: return
        settings.state.port = port
        updateStatus()
        portAlarm.cancelAllRequests()
        portAlarm.addRequest({ commitPort() }, PORT_APPLY_DELAY_MS)
        if (PortInput.isUrl(text)) {
            // No se puede modificar el documento dentro de su propio listener.
            ApplicationManager.getApplication().invokeLater({
                if (field.text == text) field.text = port.toString()
                commitPort() // pegar una URL cuenta como confirmar
            }, project.disposed)
        }
    }

    /** Enter o perder el foco. */
    private fun onCommit(field: JTextComponent, action: () -> Unit) {
        (field as? JTextField)?.addActionListener { action() }
        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = action()
        })
    }

    /**
     * Aplica el puerto configurado. Si el servidor está encendido en otro puerto lo reinicia en el nuevo;
     * si está apagado, solo avisa de que se usará al iniciar.
     */
    private fun commitPort() {
        portAlarm.cancelAllRequests()
        if (server.needsRestartForPort) {
            runServerTask("Restarting JSON Mock Server on port ${settings.state.port}…") { server.restart() }
        } else {
            updateStatus()
        }
    }

    /**
     * Arranca/reinicia en segundo plano (comprobar el puerto puede tardar) mostrando qué se está haciendo:
     * texto en la línea de estado, barra de progreso del IDE y notificación al terminar.
     */
    private var serverTaskRunning = false

    private fun runServerTask(title: String, action: () -> Unit) {
        if (serverTaskRunning) return // p. ej. salir del campo Puerto pulsando "Reiniciar"
        serverTaskRunning = true
        statusLabel.icon = AnimatedIcon.Default.INSTANCE
        statusLabel.text = title
        object : Task.Backgroundable(project, title, false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = title
                action()
            }

            override fun onFinished() {
                serverTaskRunning = false
                updateStatus()
                // Si arrancó en otro puerto o falló, el servicio ya avisó con su propia notificación.
                if (server.isRunning && !server.isOnFallbackPort) {
                    server.notify("JSON Mock Server listening on port ${server.runningPort}")
                }
            }
        }.queue()
    }

    // ---------- Detectar desde la app ----------

    private fun detectFromApp() {
        val base = project.basePath ?: return
        statusLabel.icon = AnimatedIcon.Default.INSTANCE
        statusLabel.text = "Looking for URLs in the app's files…"
        object : Task.Backgroundable(project, "Looking for URLs in the app", true) {
            var found: List<DetectedUrl> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Looking for URLs in build.gradle, .properties, resources and code…"
                found = AppUrlDetector.scan(File(base)) { rel ->
                    indicator.checkCanceled()
                    indicator.text2 = rel
                }
            }

            override fun onSuccess() {
                updateStatus()
                showDetected(found)
            }

            override fun onCancel() {
                updateStatus()
                server.notify("URL search cancelled")
            }
        }.queue()
    }

    private fun showDetected(found: List<DetectedUrl>) {
        if (found.isEmpty()) {
            server.notify(
                "No URLs found in the app (build.gradle, .properties, res/values or code). " +
                    "If the URL is built at runtime, type it by hand.",
                NotificationType.WARNING,
            )
            return
        }
        val dialog = DetectUrlsDialog(project, found)
        if (!dialog.showAndGet()) return
        val choice = dialog.choice
        choice.targetBaseUrl?.let { backendField.text = it }
        choice.port?.let { portField.text = it.toString() } // el listener lo guarda en el estado
        server.notify("Applied from the app: ${choice.summary}")
        commitPort()
    }

    /** Escucha cambios de texto (se registra después de fijar el valor inicial). */
    private fun onTextChange(field: JTextComponent, listener: (String) -> Unit) {
        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = listener(field.text)
        })
    }

    private fun toggleServer() {
        if (server.isRunning) {
            server.stop()
            updateStatus()
        } else {
            runServerTask("Starting JSON Mock Server on port ${settings.state.port}…") { server.start() }
        }
    }

    private fun updateStatus() {
        refreshAppTarget()
        val configured = settings.state.port
        if (server.isRunning) {
            val p = server.runningPort
            startStopButton.text = "Stop"
            val paused = if (settings.state.mocksEnabled) "" else "<br/><font color='orange'>Mocks paused: every request goes to the real backend</font>"
            val urls = "Emulator: <code>http://10.0.2.2:$p/</code> · USB (adb reverse): <code>http://localhost:$p/</code>$paused"
            when {
                server.needsRestartForPort -> {
                    statusLabel.icon = AllIcons.General.Warning
                    statusLabel.text = "<html>Listening on <b>:$p</b> · <font color='orange'>port changed to $configured, " +
                        "applying… (or press Restart)</font><br/>$urls</html>"
                }
                server.isOnFallbackPort -> {
                    statusLabel.icon = AllIcons.General.Warning
                    statusLabel.text = "<html>Listening on <b>:$p</b> <font color='orange'>(port ${server.requestedPort} is busy)</font>" +
                        "<br/>$urls</html>"
                }
                else -> {
                    statusLabel.icon = AllIcons.RunConfigurations.TestPassed
                    statusLabel.text = "<html>Listening on <b>:$p</b><br/>$urls</html>"
                }
            }
        } else {
            startStopButton.text = "Start"
            statusLabel.icon = AllIcons.RunConfigurations.TestIgnored
            statusLabel.text = "Server stopped · will use port $configured when started"
        }
    }

    private fun runAdbReverse() {
        val port = currentPort()
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            AdbReverse.run(project, port)
                .onSuccess { server.notify(it) }
                .onFailure { server.notify("adb reverse failed: ${it.message}", NotificationType.ERROR) }
        }, "Running adb reverse", false, project)
    }

    // ---------- Tabla de reglas ----------

    private fun createRulesPanel(): JPanel {
        val bar = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(mocksSwitchLabel, BorderLayout.CENTER)
            add(mocksSwitchButton, BorderLayout.EAST)
        }
        updateMocksSwitch()
        return JPanel(BorderLayout()).apply {
            add(bar, BorderLayout.NORTH)
            add(createRulesTable(), BorderLayout.CENTER)
        }
    }

    /**
     * Interruptor general: pausar deja de aplicar todas las reglas (todo va al backend real)
     * sin tocar el check de cada una, así que al reanudar vuelven tal como estaban.
     */
    private fun setMocksEnabled(enabled: Boolean) {
        settings.state.mocksEnabled = enabled
        updateMocksSwitch()
        updateStatus()
    }

    private fun updateMocksSwitch() {
        if (settings.state.mocksEnabled) {
            mocksSwitchLabel.icon = AllIcons.RunConfigurations.TestPassed
            mocksSwitchLabel.text = "Mocks are on: enabled rules are answered with their JSON"
            mocksSwitchButton.text = "Pause All Mocks"
            mocksSwitchButton.icon = AllIcons.Actions.Pause
        } else {
            mocksSwitchLabel.icon = AllIcons.General.Warning
            mocksSwitchLabel.text = "<html><b>Mocks paused:</b> every request goes to the real backend</html>"
            mocksSwitchButton.text = "Resume Mocks"
            mocksSwitchButton.icon = AllIcons.Actions.Resume
        }
        table.repaint()
    }

    private fun createRulesTable(): JPanel {
        table.setShowGrid(false)
        table.emptyText.text = "No rules. Press + to mock an endpoint."
        table.toolTipText = "Double-click the JSON column to open the file; anywhere else to edit the rule"
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                val row = table.rowAtPoint(event.point)
                if (row < 0) return false
                val rule = model.getItem(table.convertRowIndexToModel(row))
                if (table.convertColumnIndexToModel(table.columnAtPoint(event.point)) == JSON_COLUMN) openJson(rule)
                else editSelected()
                return true
            }
        }.installOn(table)

        return ToolbarDecorator.createDecorator(table)
            .setAddAction { addRule() }
            .setEditAction { editSelected() }
            .setRemoveAction { removeSelected() }
            .addExtraAction(object : DumbAwareAction("Open JSON", "Open the rule's JSON in the editor", AllIcons.Actions.MenuOpen) {
                override fun actionPerformed(e: AnActionEvent) {
                    openJson(table.selectedObject ?: return)
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedObject != null
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            .createPanel()
    }

    private fun openJson(rule: MockRule) {
        if (!MockFiles.openRuleJson(project, rule)) server.notify("The rule has no existing JSON file", NotificationType.WARNING)
    }

    private fun addRule(template: MockRule? = null, suggestedJson: String? = null) {
        val dialog = MockRuleDialog(project, null, template, suggestedJson)
        if (dialog.showAndGet()) {
            model.addRow(dialog.result)
            table.selection = listOf(dialog.result)
            if (dialog.openJsonAfterClose) openJson(dialog.result)
        }
    }

    private fun editSelected() {
        val rule = table.selectedObject ?: return
        val dialog = MockRuleDialog(project, rule)
        if (dialog.showAndGet()) {
            rule.copyFrom(dialog.result)
            model.fireTableDataChanged()
            table.selection = listOf(rule)
            if (dialog.openJsonAfterClose) openJson(rule)
        }
    }

    /**
     * Quita las reglas seleccionadas y, si el usuario lo confirma, también sus JSON.
     * Si un JSON lo usa otra regla que se queda, se avisa de cuáles antes de borrarlo.
     */
    private fun removeSelected() {
        val selected = table.selectedObjects.toSet()
        if (selected.isEmpty()) return
        val kept = model.items.filter { it !in selected }
        val files = selected.mapNotNull { jsonFileOf(it) }.distinct().filter { it.isFile }
        val usedBy = files.associateWith { f -> kept.filter { jsonFileOf(it) == f } }
        val plural = if (files.size == 1) "" else "s"

        var deleteFiles = false
        if (files.isNotEmpty()) {
            val list = files.joinToString("\n") { f ->
                val others = usedBy.getValue(f)
                "  • " + settings.toStoredPath(f.path) +
                    if (others.isEmpty()) "" else "\n      also used by: " + others.joinToString(", ") { "${it.method} ${it.path}" }
            }
            val shared = usedBy.values.any { it.isNotEmpty() }
            val rules = if (selected.size == 1) "this rule" else "these ${selected.size} rules"
            val message = buildString {
                append("Also delete the JSON file$plural of $rules?\n\n$list\n\n")
                if (shared) append("Some of these files are used by other rules. Those rules will keep pointing to a missing " +
                    "file and answer 500 \"Mock file not found\".\n\n")
                append("Deleted files cannot be recovered from here.")
            }
            when (Messages.showYesNoCancelDialog(
                project, message, "Remove Mock Rule",
                if (shared) "Delete Anyway" else "Delete Rule and File$plural",
                "Delete Rule Only", "Cancel",
                if (shared) Messages.getWarningIcon() else Messages.getQuestionIcon(),
            )) {
                Messages.YES -> deleteFiles = true
                Messages.NO -> {}
                else -> return
            }
        }

        model.items = kept
        if (deleteFiles) {
            val failed = MockFiles.delete(project, files)
            if (failed.isNotEmpty()) server.notify("Could not delete: ${failed.joinToString { it.name }}", NotificationType.WARNING)
        }
    }

    private fun jsonFileOf(rule: MockRule) =
        rule.jsonFile.takeIf { it.isNotBlank() }?.let { settings.resolveJsonFile(it).absoluteFile.normalize() }

    private fun persist() = settings.setRules(model.items)

    private val pausableCheckRenderer = object : BooleanTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ) = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            .also { it.isEnabled = settings.state.mocksEnabled }
    }

    private inner class EnabledColumn : ColumnInfo<MockRule, Boolean>("On") {
        override fun valueOf(item: MockRule) = item.enabled
        // Con los mocks pausados el check se ve en gris y no se puede cambiar.
        override fun isCellEditable(item: MockRule) = settings.state.mocksEnabled
        override fun getColumnClass() = Boolean::class.javaObjectType
        override fun getRenderer(item: MockRule) = pausableCheckRenderer
        override fun getWidth(table: javax.swing.JTable) = 50
        override fun setValue(item: MockRule, value: Boolean) {
            item.enabled = value // aplica en caliente: el servidor lee las reglas en cada petición
            persist()
        }
    }

    private class TextColumn(name: String, private val width: Int, private val getter: (MockRule) -> String) :
        ColumnInfo<MockRule, String>(name) {
        override fun valueOf(item: MockRule) = getter(item)
        override fun getWidth(table: javax.swing.JTable) = if (width > 0) width else -1
    }

    // ---------- Log de peticiones ----------

    private fun createLogPanel(): JComponent {
        logList.emptyText.text = "Requests received will show up here"
        logList.cellRenderer = object : ColoredListCellRenderer<Pair<Long, RequestLogEntry>>() {
            private val fmt = SimpleDateFormat("HH:mm:ss")
            override fun customizeCellRenderer(
                list: JList<out Pair<Long, RequestLogEntry>>, value: Pair<Long, RequestLogEntry>,
                index: Int, selected: Boolean, hasFocus: Boolean,
            ) {
                val (time, e) = value
                val color = when (e.kind) {
                    RequestLogEntry.Kind.MOCK -> JBColor.namedColor("Label.successForeground", JBColor(0x2E7D32, 0x6CC070))
                    RequestLogEntry.Kind.PROXY -> JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                    RequestLogEntry.Kind.NOT_FOUND -> JBColor.namedColor("Label.warningForeground", JBColor(0xB26A00, 0xE0A040))
                    RequestLogEntry.Kind.ERROR -> JBColor.namedColor("Label.errorForeground", JBColor.RED)
                }
                append(fmt.format(Date(time)) + "  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(e.kind.name.padEnd(9), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
                append("${e.status}  ${e.method} ${e.uri}")
                append("  ${e.durationMs} ms  ${e.detail}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        logList.addListSelectionListener {
            logDetails.text = logList.selectedValue?.let { (time, e) -> describe(time, e) }.orEmpty()
            logDetails.caretPosition = 0
        }
        val createMock = object : DumbAwareAction(
            "Create Mock from This Request…", "Open a new mock rule pre-filled with this request's method, path and status",
            AllIcons.General.Add,
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                val entry = logList.selectedValue?.second ?: return
                addRule(templateFrom(entry), entry.exchange?.responseBody?.takeIf { JsonCheck.isValid(it) })
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = logList.selectedValue != null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val clear = object : DumbAwareAction("Clear Log", null, AllIcons.Actions.GC) {
            override fun actionPerformed(e: AnActionEvent) {
                logModel.removeAll()
                logDetails.text = ""
            }
        }
        val curl = object : DumbAwareAction("Copy as cURL", "Copy the selected request as a curl command", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val entry = logList.selectedValue?.second ?: return
                CopyPasteManager.getInstance().setContents(StringSelection(toCurl(entry)))
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = logList.selectedValue != null
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        // El clic derecho selecciona la fila bajo el ratón antes de abrir el menú.
        logList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                val i = logList.locationToIndex(e.point)
                if (i >= 0 && logList.getCellBounds(i, i)?.contains(e.point) == true) logList.selectedIndex = i
            }
        })
        PopupHandler.installPopupMenu(logList, DefaultActionGroup(createMock, curl), "JsonMockServerLog")
        logList.toolTipText = "Right-click a request to create a mock from it"
        val list = ToolbarDecorator.createDecorator(logList)
            .disableAddAction().disableRemoveAction().disableUpDownActions()
            .addExtraAction(curl)
            .addExtraAction(clear)
            .createPanel()
        return JBSplitter(true, "JsonMockServer.logDetails", 0.5f).apply {
            firstComponent = list
            secondComponent = ScrollPaneFactory.createScrollPane(logDetails)
        }
    }

    /**
     * Regla nueva a partir de una petición del log: mismo método y ruta (sin query).
     * El código se toma solo si vino del backend real; si no, 200.
     */
    private fun templateFrom(e: RequestLogEntry) = MockRule().apply {
        method = e.method.uppercase().takeIf { it in MockRule.METHODS } ?: MockRule.ANY
        path = e.uri.substringBefore('?').substringBefore('#')
        statusCode = if (e.kind == RequestLogEntry.Kind.PROXY && e.status in 100..599) e.status else 200
    }

    /** Texto del panel de detalle: lo que envió la app, a dónde fue y qué se le respondió. */
    private fun describe(time: Long, e: RequestLogEntry): String = buildString {
        val x = e.exchange
        appendLine("${e.method} ${requestUrl(e)}")
        appendLine("${SimpleDateFormat("HH:mm:ss.SSS").format(Date(time))} · ${e.kind} · ${e.status} · ${e.durationMs} ms")
        if (e.detail.isNotBlank()) appendLine(e.detail)
        if (x == null) return@buildString
        x.backendUrl?.let {
            appendLine()
            appendLine(if (e.kind == RequestLogEntry.Kind.PROXY) "Forwarded to: $it" else "Real backend URL (not called): $it")
        }
        section("Request headers", x.requestHeaders.joinToString("\n") { (k, v) -> "$k: $v" })
        section("Request body", x.requestBody)
        section("Response headers", x.responseHeaders.joinToString("\n") { (k, v) -> "$k: $v" })
        section("Response body", x.responseBody)
    }

    private fun StringBuilder.section(title: String, content: String) {
        appendLine()
        appendLine("── $title ──")
        appendLine(content.ifEmpty { "(empty)" })
    }

    private fun requestUrl(e: RequestLogEntry): String {
        val host = e.exchange?.host?.ifBlank { null } ?: "localhost:${currentPort()}"
        return "http://$host${e.uri}"
    }

    /** La petición tal como la envió la app, apuntando al mock, para repetirla desde una terminal. */
    private fun toCurl(e: RequestLogEntry): String = buildString {
        fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
        append("curl -i -X ${e.method} ${q(requestUrl(e))}")
        e.exchange?.requestHeaders
            ?.filter { (k, _) -> k.lowercase() !in setOf("host", "content-length", "connection", "accept-encoding") }
            ?.forEach { (k, v) -> append(" \\\n  -H ${q("$k: $v")}") }
        e.exchange?.requestBody?.takeIf { it.isNotEmpty() && !it.startsWith("(") }?.let { append(" \\\n  --data-raw ${q(it)}") }
    }

    private fun appendLog(entry: RequestLogEntry) {
        logModel.add(System.currentTimeMillis() to entry)
        while (logModel.size > MAX_LOG) logModel.remove(0)
        logList.ensureIndexIsVisible(logModel.size - 1)
    }

    override fun dispose() {}

    private companion object {
        const val MAX_LOG = 200
        const val JSON_COLUMN = 4
        const val PORT_APPLY_DELAY_MS = 1000
    }
}
