package com.supernote_quicktoolbar.ui_common

import android.app.Activity
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.pluginlib.api.HostUIAPI
import com.ratta.supernote.pluginlib.callback.RattaDialogListener
import com.supernote_quicktoolbar.NativeLocale

object Dialog {

    fun confirm(
        activity: Activity?,
        message: String,
        cancelText: String = NativeLocale.t("cancel"),
        confirmText: String = NativeLocale.t("confirm"),
        onConfirm: () -> Unit
    ) {
        activity ?: return
        HostUIAPI.getInstance().showRattaDialog(
            activity, message, cancelText, confirmText, false,
            object : RattaDialogListener {
                override fun onConfirm() { onConfirm() }
                override fun onCancel() {}
            }
        )
    }

    fun tip(activity: Activity?, message: String) {
        activity ?: return
        HostUIAPI.getInstance().showTipDialog(
            activity, false, message,
            object : RattaDialogListener {
                override fun onConfirm() {}
                override fun onCancel() {}
            }
        )
    }

    fun confirm(ctx: ReactApplicationContext, message: String, onConfirm: () -> Unit) =
        confirm(ctx.currentActivity, message, onConfirm = onConfirm)

    fun tip(ctx: ReactApplicationContext, message: String) =
        tip(ctx.currentActivity, message)
}
