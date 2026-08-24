package com.supernote_quicktoolbar

import com.supernote_quicktoolbar.bubbles.*
import com.supernote_quicktoolbar.ui_common.PanelRegistry

// Panels self-register in PanelRegistry (via PanelBase.rootView); this facade
// only adds the bubble overlays, which are not PanelBase subclasses.
object ToolRegistry {

    fun hideAll() {
        PanelRegistry.hideAll()
        FloatingBubbleModule.hideStatic()
        AiBubbleModule.hideStatic()
        PaletteBubbleModule.hideStatic()
    }

    fun handleRotation(): Boolean = PanelRegistry.handleRotation()

    fun anyPanelShowing(): Boolean = PanelRegistry.anyShowing()

    /** Hooks the empty↔non-empty edge of the live panel set (see PanelRegistry). */
    fun setPanelActiveEdgeListener(l: ((anyShowing: Boolean) -> Unit)?) {
        PanelRegistry.onActiveEdge = l
    }

    fun suspendAll() = PanelRegistry.suspendAll()

    fun resumeAll() = PanelRegistry.resumeAll()
}
