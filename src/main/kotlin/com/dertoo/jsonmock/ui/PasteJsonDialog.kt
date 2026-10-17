package com.dertoo.jsonmock.ui

import com.dertoo.jsonmock.model.JsonCheck
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * El usuario pega el JSON y solo indica el nombre; se guarda en `mocks/<nombre>.json`.
 * Si el portapapeles ya contiene un JSON válido, aparece pegado; si no, [fallbackJson] (p. ej. la respuesta real).
 */
class PasteJsonDialog(private val project: Project, suggestedName: String, fallbackJson: String? = null) : DialogWrapper(project) {

    private val nameField = JBTextField(suggestedName)
    private val jsonArea = JBTextArea().apply {
        font = JBUI.Fonts.create("Monospaced", 13)
        tabSize = 2
        text = clipboardJson() ?: fallbackJson.orEmpty()
    }
    private val status = JBLabel()
    private val pathHint = JBLabel()

    /** Archivo creado; válido tras OK. */
    var created: File? = null
        private set

    init {
        title = "Paste Response JSON"
        setOKButtonText("Create File")
        jsonArea.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateStatus()
        })
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = updateHint()
        })
        init()
        updateStatus()
        updateHint()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("File name:") {
            cell(nameField).align(AlignX.FILL).focused()
        }
        row { cell(pathHint) }
        row {
            cell(JBScrollPane(jsonArea).apply { preferredSize = Dimension(JBUI.scale(640), JBUI.scale(360)) })
                .align(Align.FILL)
        }.resizableRow()
        row { cell(status) }
    }

    override fun getPreferredFocusedComponent(): JComponent =
        if (jsonArea.text.isBlank()) jsonArea else nameField

    private fun updateHint() {
        val clean = MockFiles.sanitize(nameField.text)
        pathHint.text = if (clean.isEmpty()) " " else "Will be saved to mocks/$clean.json"
    }

    private fun updateStatus() {
        val text = jsonArea.text
        when {
            text.isBlank() -> {
                status.icon = AllIcons.General.Information
                status.text = "Paste the JSON here (Cmd/Ctrl + V)"
            }
            else -> when (val error = JsonCheck.validate(text)) {
                null -> {
                    status.icon = AllIcons.General.InspectionsOK
                    status.text = "Valid JSON"
                }
                else -> {
                    status.icon = AllIcons.General.Warning
                    status.text = "Invalid JSON — $error (you can still save it)"
                }
            }
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (MockFiles.sanitize(nameField.text).isEmpty()) return ValidationInfo("Enter a name", nameField)
        if (jsonArea.text.isBlank()) return ValidationInfo("Paste the JSON content", jsonArea)
        return null
    }

    override fun doOKAction() {
        val name = nameField.text
        val file = MockFiles.fileFor(project, name)

        val error = JsonCheck.validate(jsonArea.text)
        if (error != null && Messages.showYesNoDialog(
                project,
                "The content is not valid JSON ($error).\nSave it anyway? (useful to test parsing errors)",
                "Invalid JSON", "Save", "Fix It", Messages.getWarningIcon(),
            ) != Messages.YES
        ) return

        if (file.exists() && Messages.showYesNoDialog(
                project, "mocks/${file.name} already exists. Replace it?", "File Already Exists",
                "Replace", "Rename", Messages.getQuestionIcon(),
            ) != Messages.YES
        ) return

        created = MockFiles.writeJson(project, name, jsonArea.text)
        super.doOKAction()
    }

    private fun clipboardJson(): String? = runCatching {
        CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
    }.getOrNull()?.trim()?.takeIf { (it.startsWith("{") || it.startsWith("[")) && JsonCheck.isValid(it) }
}
