package com.supernote_quicktoolbar

import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.panels.*
import com.supernote_quicktoolbar.bubbles.*
import com.supernote_quicktoolbar.ui_common.PanelBase

object ToolRegistry {

    private lateinit var toolbarModule: FloatingToolbarModule

    private val panelFactories = mutableMapOf<String, () -> PanelBase>()

    private val activePanels: List<PanelBase>
        get() = panelFactories.values.mapNotNull { it().takeIf { p -> p.isShowing } }

    fun init(module: FloatingToolbarModule, ctx: ReactApplicationContext) {
        toolbarModule = module

        panelFactories["image"] = { ImagePanel.getInstance(ctx, module) }
        panelFactories["docLink"] = { DocLinkPanel.getInstance(ctx, module) }
        panelFactories["docScreenshot"] = { DocScreenshotPanel.getInstance(ctx, module) }
        panelFactories["send"] = { SendPanel.getInstance(ctx, module) }
        panelFactories["lassoScreenshot"] = { LassoScreenshotPanel.getInstance(ctx, module) }
    }

    fun registerPanel(id: String, factory: () -> PanelBase) {
        panelFactories[id] = factory
    }

    fun getPanel(id: String): PanelBase? = panelFactories[id]?.invoke()

    fun hideAll() {
        ImagePanel.currentInstance?.hide()
        DocLinkPanel.currentInstance?.hide()
        SendPanel.currentInstance?.hide()
        LassoScreenshotPanel.currentInstance?.hide()
        DocScreenshotPanel.currentInstance?.hide()
        FloatingBubbleModule.hideStatic()
        AiBubbleModule.hideStatic()
    }

    fun suspendAll() {
        ImagePanel.currentInstance?.suspendVisibility()
        DocLinkPanel.currentInstance?.suspendVisibility()
        SendPanel.currentInstance?.suspendVisibility()
        LassoScreenshotPanel.currentInstance?.suspendVisibility()
        DocScreenshotPanel.currentInstance?.suspendVisibility()
    }

    fun resumeAll() {
        ImagePanel.currentInstance?.resumeVisibility()
        DocLinkPanel.currentInstance?.resumeVisibility()
        SendPanel.currentInstance?.resumeVisibility()
        LassoScreenshotPanel.currentInstance?.resumeVisibility()
        DocScreenshotPanel.currentInstance?.resumeVisibility()
    }

    fun destroyAll() {
        hideAll()
    }
}
