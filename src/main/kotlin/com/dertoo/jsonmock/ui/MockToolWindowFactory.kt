package com.dertoo.jsonmock.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class MockToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MockRulesPanel(project)
        Disposer.register(toolWindow.disposable, panel)
        val factory = ContentFactory.getInstance()
        toolWindow.contentManager.addContent(factory.createContent(panel.serverTab, "Server", false))
        toolWindow.contentManager.addContent(factory.createContent(panel.mocksTab, "Mocks", false))
    }
}
