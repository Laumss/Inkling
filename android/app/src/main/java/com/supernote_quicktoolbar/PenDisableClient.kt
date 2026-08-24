package com.supernote_quicktoolbar

import android.graphics.Rect
import android.os.IBinder
import android.os.Parcel
import android.util.Log

object PenDisableClient {

    private const val TAG = "PenDisableClient"

    private val SERVICE_NAME_CANDIDATES = arrayOf("service_myservice", "service.myservice")
    private const val INTERFACE_TOKEN = "android.demo.IMyService"

    const val CODE_DISABLE_AREA_INFO = 1

    private fun getServiceBinder(): IBinder? {
        for (name in SERVICE_NAME_CANDIDATES) {
            val binder = try {
                val sm = Class.forName("android.os.ServiceManager")
                val get = sm.getMethod("getService", String::class.java)
                get.invoke(null, name) as? IBinder
            } catch (e: Throwable) {
                Log.e(TAG, "[diag] getServiceBinder reflection failed: ${e.javaClass.name}: ${e.message}", e)
                return null
            }
            if (binder != null) {
                Log.i(TAG, "[diag] got binder via \"$name\"")
                return binder
            }
            Log.e(TAG, "[diag] getService(\"$name\") returned null (reflection OK)")
        }
        return null
    }

    private fun resolveToken(binder: IBinder): String {
        val descriptor = try {
            binder.interfaceDescriptor
        } catch (e: Throwable) {
            Log.w(TAG, "[diag] interfaceDescriptor failed: ${e.javaClass.name}: ${e.message}")
            null
        }
        Log.i(TAG, "[diag] interfaceDescriptor=\"$descriptor\"")
        return if (descriptor.isNullOrEmpty()) INTERFACE_TOKEN else descriptor
    }

    private fun logServiceList() {
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val list = sm.getMethod("listServices")
            @Suppress("UNCHECKED_CAST")
            val services = list.invoke(null) as? Array<String>
            if (services == null) {
                Log.e(TAG, "[diag] listServices returned null")
                return
            }
            Log.i(TAG, "[diag] listServices total=${services.size}")
            val hits = services.filter {
                it.contains("myservice", ignoreCase = true) || it.contains("demo", ignoreCase = true)
            }
            if (hits.isEmpty()) {
                Log.i(TAG, "[diag] no service name contains 'myservice'/'demo'")
            } else {
                for (name in hits) {
                    Log.i(TAG, "[diag] matched service: $name")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[diag] listServices failed: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    fun sendDisableAreas(
        appName: String,
        rects: List<Rect>,
    ): String? {
        if (rects.isEmpty()) return null
        val code = CODE_DISABLE_AREA_INFO
        Log.i(TAG, "===== EXPERIMENT START code=$code app=\"$appName\" rects=${rects.size} " +
            "uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} =====")
        var binder: IBinder? = null
        for (attempt in 1..3) {
            binder = getServiceBinder()
            if (binder != null) break
            Log.e(TAG, "binder is null (attempt $attempt/3)")
            logServiceList()
            if (attempt < 3) {
                try {
                    Thread.sleep(1000)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        if (binder == null) return null
        val token = resolveToken(binder)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(token)
            data.writeString(appName)
            data.writeInt(rects.size)
            for (r in rects) {
                data.writeInt(r.left)
                data.writeInt(r.top)
                data.writeInt(r.width())
                data.writeInt(r.height())
                data.writeInt(0) // flag
            }
            Log.i(TAG, "[diag] payload token=\"$token\" app=\"$appName\" count=${rects.size} " +
                rects.joinToString(" ") { "[l=${it.left},t=${it.top},w=${it.width()},h=${it.height()},f=0]" })
            Log.i(TAG, "[diag] parcel dataSize=${data.dataSize()} hex=${data.marshall().toHexPreview(96)}")
            val ok = binder.transact(code, data, reply, 0)
            val replyStr = reply.readString()
            val replySize = reply.dataSize()
            val replyPos = reply.dataPosition()
            var extra = ""
            if (replyPos < replySize) {
                extra = try { " extraInt=${reply.readInt()} (remain=${replySize - reply.dataPosition()}B)" } catch (_: Exception) { " (unreadable remain=${replySize - replyPos}B)" }
            }
            Log.i(TAG, "===== EXPERIMENT END code=$code app=\"$appName\" transact=$ok " +
                "reply=$replyStr replySize=$replySize$extra rects=$rects =====")
            replyStr
        } catch (e: Exception) {
            Log.e(TAG, "===== EXPERIMENT FAILED code=$code app=\"$appName\": ${e.message} =====", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun ByteArray.toHexPreview(max: Int): String {
        val n = minOf(size, max)
        val sb = StringBuilder(n * 2 + 8)
        for (i in 0 until n) sb.append(String.format("%02x", this[i]))
        if (size > max) sb.append("…(${size}B)")
        return sb.toString()
    }

    fun clearDisableAreas(appName: String): String? {
        return sendDisableAreas(appName, listOf(Rect(0, 0, 18888, 18888)))
    }

}
