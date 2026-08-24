package com.supernote_quicktoolbar

import android.view.MotionEvent

internal object TouchInput {
    fun isFinger(event: MotionEvent): Boolean {
        val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        return event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
    }
}
