package com.dertoo.jsonmock.ui

import com.dertoo.jsonmock.model.MockRule
import com.dertoo.jsonmock.settings.MockSettingsService
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Alta/edición de una regla. Trabaja sobre una copia; [result] solo es válido si OK.
 * Para una regla nueva, [template] la prerrellena (p. ej. desde una petición del log) y
 * [suggestedJson] se ofrece en "Paste JSON…" si el portapapeles no trae un JSON.
 */
class MockRuleDialog(
    private val project: Project,
    original: MockRule?,
    template: MockRule? = null,
    private val suggestedJson: String? = null,
) : DialogWrapper(project) {

    val result = (original ?: template)?.let { MockRule(it) } ?: MockRule()
    private val settings = MockSettingsService.getInstance(project)
    private lateinit var jsonField: TextFieldWithBrowseButton

    /** true si se cerró con "Open" en el JSON: el panel abre el archivo tras guardar la regla. */
    var openJsonAfterClose = false
        private set

    init {
        title = if (original == null) "New Mock Rule" else "Edit Mock Rule"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Method:") {
            comboBox(MockRule.METHODS).bindItem(result::method.toNullableProperty())
        }
        row("Path:") {
            textField()
                .bindText(result::path)
                .align(AlignX.FILL)
                .focused()
                .comment("The path (/route3, /v1/user/{id}, /v1/**) or the full service URL (https://example.com/api/v1/route3)")
        }
        row("HTTP status:") {
            intTextField(100..599).bindIntText(result::statusCode)
        }
        row("JSON file:") {
            jsonField = textFieldWithBrowseButton(
                FileChooserDescriptorFactory.createSingleFileDescriptor("json").withTitle("Select Response JSON"),
                project,
            ).bindText(result::jsonFile).align(AlignX.FILL).component
            jsonField.textField.toolTipText = "Double-click to save the rule and open the file"
            object : DoubleClickListener() {
                override fun onDoubleClick(event: MouseEvent): Boolean {
                    saveAndOpenJson(); return true
                }
            }.installOn(jsonField.textField)
            button("Open") { saveAndOpenJson() }
            button("Create") {
                val created = MockFiles.createJsonFor(project, result.method, currentPath())
                jsonField.text = settings.toStoredPath(created.path)
            }
            button("Paste JSON…") {
                val path = currentPath()
                val dialog = PasteJsonDialog(project, MockFiles.suggestName(result.method, path), suggestedJson)
                if (dialog.showAndGet()) dialog.created?.let { jsonField.text = settings.toStoredPath(it.path) }
            }
        }.rowComment("Pick a file, create an empty one, or paste the content and just give it a name. " +
            "<b>Open</b> (or double-click the field) saves the rule and opens the JSON in the editor")
        row("Delay (ms):") {
            intTextField(0..120_000).bindIntText(result::delayMs)
        }
        row {
            checkBox("Enabled").bindSelected(result::enabled)
        }
    }

    /** El diálogo es modal: el editor quedaría detrás, así que se guarda la regla, se cierra y luego se abre. */
    private fun saveAndOpenJson() {
        applyFields()
        val error = when {
            result.jsonFile.isBlank() -> "Choose, create or paste a JSON file first"
            !settings.resolveJsonFile(result.jsonFile).isFile -> "The JSON file does not exist"
            else -> doValidate()?.message
        }
        if (error != null) {
            setErrorText(error, jsonField)
            return
        }
        openJsonAfterClose = true
        doOKAction()
    }

    private fun currentPath(): String {
        applyFields() // vuelca los campos a `result` para conocer la ruta escrita
        return result.path
    }

    override fun doValidate(): ValidationInfo? {
        applyFields()
        if (result.path.isBlank()) return ValidationInfo("Path is required")
        return null
    }

    override fun doOKAction() {
        applyFields()
        if (result.jsonFile.isNotBlank()) result.jsonFile = settings.toStoredPath(settings.resolveJsonFile(result.jsonFile).path)
        super.doOKAction()
    }
}
