package com.supernote_quicktoolbar.ui_common

import android.widget.TextView

class SelectionButton(val view: TextView) {
    init { update(false) }

    fun update(enabled: Boolean) {
        view.alpha = if (enabled) 1f else 0.4f
        view.isEnabled = enabled
    }
}
