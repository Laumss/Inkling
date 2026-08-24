package com.supernote_quicktoolbar.ui_common

/**
 * Live set of currently-showing panels. PanelBase registers/deregisters via its
 * rootView setter, so membership exactly mirrors isShowing and enumeration never
 * constructs a panel. Main-thread only (all rootView mutations run on the main
 * looper via PanelBase's handler).
 */
object PanelRegistry {
    private val live = LinkedHashSet<PanelBase>()

    /**
     * Notified on the empty↔non-empty edges of the live set so overlays that are
     * not PanelBase subclasses (the floating bubbles) can hide while any native
     * panel is showing and reshow once the last one closes. Set by
     * FloatingToolbarModule; ui_common must not reach into bubbles directly.
     * Main-thread only (same as all live-set mutations).
     */
    var onActiveEdge: ((anyShowing: Boolean) -> Unit)? = null

    internal fun onShown(panel: PanelBase) {
        val wasEmpty = live.isEmpty()
        live += panel
        if (wasEmpty && live.isNotEmpty()) onActiveEdge?.invoke(true)
    }
    internal fun onHidden(panel: PanelBase) {
        val wasShowing = live.isNotEmpty()
        live -= panel
        if (wasShowing && live.isEmpty()) onActiveEdge?.invoke(false)
    }

    fun anyShowing(): Boolean = live.isNotEmpty()

    fun hideAll() = live.toList().forEach { it.hide() }
    fun suspendAll() = live.toList().forEach { it.suspendVisibility() }
    fun resumeAll() = live.toList().forEach { it.resumeVisibility() }

    fun handleRotation(): Boolean {
        var restoreToolbar = false
        for (panel in live.toList()) if (panel.onRotation()) restoreToolbar = true
        return restoreToolbar
    }
}
