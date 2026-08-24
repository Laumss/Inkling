package com.supernote_quicktoolbar.ui_common

import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.pluginlib.api.HostUIAPI
import com.ratta.supernote.pluginlib.callback.RattaDialogListener
import com.supernote_quicktoolbar.NativeLocale

object Dialog {

    enum class ImageReceiveChoice { INSERT_NOW, KEEP, REJECT }

    fun tip(ctx: ReactApplicationContext, message: String) {
        Handler(Looper.getMainLooper()).post {
            val c: android.content.Context = ctx.currentActivity ?: ctx
            HostUIAPI.getInstance().showTipDialog(
                c, false, message,
                object : RattaDialogListener {
                    override fun onConfirm() {}
                    override fun onCancel() {}
                }
            )
        }
    }

    fun confirmResult(ctx: ReactApplicationContext, message: String, onResult: (Boolean) -> Unit) {
        confirmResult(
            ctx,
            message,
            NativeLocale.t("cancel"),
            NativeLocale.t("confirm"),
            onResult
        )
    }

    fun confirmResult(
        ctx: ReactApplicationContext,
        message: String,
        cancelText: String,
        confirmText: String,
        onResult: (Boolean) -> Unit
    ) {
        val show = {
            val c: android.content.Context = ctx.currentActivity ?: ctx
            HostUIAPI.getInstance().showRattaDialog(
                c, message, cancelText, confirmText, false,
                object : RattaDialogListener {
                    override fun onConfirm() { onResult(true) }
                    override fun onCancel() { onResult(false) }
                }
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) show()
        else Handler(Looper.getMainLooper()).post(show)
    }

    fun chooseImageReceive(
        ctx: ReactApplicationContext,
        message: String,
        onResult: (ImageReceiveChoice) -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
            val c: android.content.Context = ctx.currentActivity ?: ctx
            HostUIAPI.getInstance().showRattaDialog(
                c, message, NativeLocale.t("image_receive_more"), NativeLocale.t("image_receive_insert_now"), false,
                object : RattaDialogListener {
                    override fun onConfirm() { onResult(ImageReceiveChoice.INSERT_NOW) }
                    override fun onCancel() {
                        HostUIAPI.getInstance().showRattaDialog(
                            c, NativeLocale.t("image_receive_keep_ask"),
                            NativeLocale.t("image_receive_reject"), NativeLocale.t("confirm"), false,
                            object : RattaDialogListener {
                                override fun onConfirm() { onResult(ImageReceiveChoice.KEEP) }
                                override fun onCancel() { onResult(ImageReceiveChoice.REJECT) }
                            }
                        )
                    }
                }
            )
        }
    }
}
