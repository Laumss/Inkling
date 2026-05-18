package com.supernote_quicktoolbar.overlays
import com.supernote_quicktoolbar.*
import com.supernote_quicktoolbar.panels.*
import com.supernote_quicktoolbar.bubbles.*

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.LinearLayout

class TouchSinkLayout(context: Context) : LinearLayout(context) {

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        super.dispatchTouchEvent(ev)
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.isFromSource(InputDevice.SOURCE_STYLUS)) return true
        return super.dispatchGenericMotionEvent(ev)
    }
}
