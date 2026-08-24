package com.supernote_quicktoolbar.ui_common

import com.facebook.react.bridge.WritableMap

/**
 * The slice of FloatingToolbarModule that ui_common is allowed to see.
 * Keeps the dependency pointing one way: panels/ui_common -> ToolbarHost,
 * with FloatingToolbarModule as the sole implementation.
 */
interface ToolbarHost {
    val screenW: Int
    val screenH: Int
    fun refreshScreenDimensions()
    fun restoreToolbar()
    fun emitEvent(name: String, params: WritableMap)
}
