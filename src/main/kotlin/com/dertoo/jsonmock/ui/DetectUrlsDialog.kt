package com.dertoo.jsonmock.ui

import com.dertoo.jsonmock.detect.AppUrlDetector
import com.dertoo.jsonmock.detect.DetectedUrl
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBoxTableRenderer
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ListTableModel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/** Resultado elegido por el usuario. */
data class DetectedChoice(val targetBaseUrl: String?, val port: Int?, val summary: String)

/**
 * Lista las URLs encontradas en la app. El usuario marca cuál es el backend real y cuál
 * la URL que usa la app en debug (de la que se toma el puerto).
 */
class DetectUrlsDialog(private val project: Project, urls: List<DetectedUrl>) : DialogWrapper(project) {

    enum class Use(private val label: String) {
        NONE("—"), BACKEND("Real backend"), APP("App URL (port)");
        override fun toString() = label
    }

    class Row(val url: DetectedUrl, var use: Use = Use.NONE)

    private val rows: List<Row> = urls.map { Row(it) }
    private val model = ListTableModel<Row>(arrayOf(UseColumn(), Col("Variant", 90) { it.variant.ifEmpty { "—" } },
        Col("Name", 130) { it.name.ifEmpty { "—" } }, Col("URL", 0) { it.url }, Col("File", 0) { "${it.file}:${it.line}" }),
        rows.toMutableList())
    private val table = TableView(model)
    private val preview = JBLabel()

    init {
        title = "URLs Found in the App"
        setOKButtonText("Apply")
        val (app, backend) = AppUrlDetector.suggest(urls)
        rows.firstOrNull { it.url === app }?.use = Use.APP
        rows.firstOrNull { it.url === backend }?.use = Use.BACKEND
        init()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent {
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(24)
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (table.columnAtPoint(event.point) == 0) return false
                openSource(table.selectedObject ?: return false)
                return true
            }
        }.installOn(table)

        return JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(JBLabel("<html>Under <b>Use</b>, pick which one is the <b>Real backend</b> and which is your <b>App URL</b> in debug " +
                "(the port is taken from it). Double-click a row to open the file.</html>"), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table).apply { preferredSize = Dimension(JBUI.scale(900), JBUI.scale(300)) },
                BorderLayout.CENTER)
            add(preview, BorderLayout.SOUTH)
        }
    }

    private fun openSource(row: Row) {
        val vf = LocalFileSystem.getInstance().findFileByIoFile(File(project.basePath, row.url.file)) ?: return
        OpenFileDescriptor(project, vf, row.url.line - 1, 0).navigate(true)
    }

    private fun backendRow() = rows.firstOrNull { it.use == Use.BACKEND }
    private fun appRow() = rows.firstOrNull { it.use == Use.APP }

    val choice: DetectedChoice
        get() {
            val app = appRow()?.url
            val target = backendRow()?.url?.let { AppUrlDetector.targetFor(it, app) }
            val port = app?.port
            val parts = buildList {
                if (target != null) add("Real backend = $target")
                if (port != null) add("Port = $port")
            }
            return DetectedChoice(target, port, parts.joinToString(" · ").ifEmpty { "Nothing selected" })
        }

    private fun updatePreview() {
        val c = choice
        val prod = backendRow()?.url?.takeIf { AppUrlDetector.isProductionVariant(it.variant) }
        val noBackend = backendRow() == null && rows.any { !it.url.isLocal }
        preview.text = "<html>Will apply: <b>${c.summary}</b>" +
            (if (prod != null) "<br/><font color='orange'><b>Warning:</b> this is the <b>${prod.variant}</b> (production) URL. " +
                "Anything you don't mock will go to production.</font>" else "") +
            (if (noBackend && prod == null) "<br/><small>No real backend suggested: the public URLs found are production ones " +
                "or it is unclear which one is for development. Pick it under <b>Use</b> or type it by hand.</small>" else "") +
            (if (appRow() != null && backendRow() != null && c.targetBaseUrl != backendRow()!!.url.url.trimEnd('/'))
                "<br/><small>The backend path is adjusted to line up with the debug URL path.</small>" else "") +
            "</html>"
        isOKActionEnabled = c.targetBaseUrl != null || c.port != null
    }

    private inner class UseColumn : ColumnInfo<Row, Use>("Use") {
        override fun valueOf(item: Row) = item.use
        override fun isCellEditable(item: Row) = true
        override fun getWidth(table: JTable) = JBUI.scale(170)
        override fun getRenderer(item: Row): TableCellRenderer = ComboBoxTableRenderer(Use.entries.toTypedArray())
        override fun getEditor(item: Row): TableCellEditor = ComboBoxTableRenderer(options(item))

        override fun setValue(item: Row, value: Use) {
            if (value == Use.APP && item.url.port == null) return // sin puerto no sirve como URL de la app
            // Solo puede haber un backend y una URL de app.
            if (value != Use.NONE) rows.filter { it !== item && it.use == value }.forEach { it.use = Use.NONE }
            item.use = value
            model.fireTableDataChanged()
            updatePreview()
        }

        private fun options(item: Row) =
            if (item.url.port == null) arrayOf(Use.NONE, Use.BACKEND) else Use.entries.toTypedArray()
    }

    private class Col(name: String, private val width: Int, private val getter: (DetectedUrl) -> String) :
        ColumnInfo<Row, String>(name) {
        override fun valueOf(item: Row) = getter(item.url)
        override fun getWidth(table: JTable) = if (width > 0) JBUI.scale(width) else -1
    }
}
