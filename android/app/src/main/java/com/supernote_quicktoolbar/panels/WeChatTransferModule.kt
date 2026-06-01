package com.supernote_quicktoolbar.panels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class WeChatTransferModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "WeChatTransferModule"

    companion object {
        private const val TAG = "WeChatTransfer"

        private const val BASE_URL = "http://platform.moaansmart.com/api/moaan-platform-business/ilink"
        private const val REGISTER_URL = "$BASE_URL/register"
        private const val GET_TICKET_URL = "$BASE_URL/getDeviceTicket"
        private const val GET_QRCODE_URL = "$BASE_URL/getQrCode"
        private const val CHECK_BIND_URL = "$BASE_URL/checkBind"
        private const val GET_SEND_URL = "$BASE_URL/getSend"
        private const val DELETE_SEND_URL = "$BASE_URL/deleteSend"
        private const val UNBIND_URL = "$BASE_URL/resetDevice"

        private const val CUSTOMER_NAME = "dedao"
        private const val DEVICE_MODE = "EPD103"
        private const val DEVICE_ID = 0x1107
        private const val DEVICE_DISPLAY_NAME = "Supernote"

        private const val POLL_INTERVAL_MS = 3000L
        private const val RECEIVE_DIR = "/sdcard/INBOX"

        private const val PREF_NAME = "wechat_transfer"
        private const val PREF_KEY_LINK_ID = "ilink_im_sdk_id"
        private const val PREF_KEY_DEVICE_SN = "device_sn"

        @Volatile var isPolling = false
            private set
        @Volatile var isBound = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var linkImSdkId: String = ""
    private var pollRunnable: Runnable? = null

    init {
        loadSavedState()
        File(RECEIVE_DIR).mkdirs()
    }

    private fun prefs() = reactApplicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun loadSavedState() {
        val savedCustomer = prefs().getString("saved_customer", "") ?: ""
        if (savedCustomer != CUSTOMER_NAME) {
            prefs().edit().remove(PREF_KEY_LINK_ID).remove(PREF_KEY_DEVICE_SN)
                .putString("saved_customer", CUSTOMER_NAME).apply()
            linkImSdkId = ""
            isBound = false
            Log.i(TAG, "customer changed ($savedCustomer → $CUSTOMER_NAME), cleared saved state")
            return
        }
        linkImSdkId = prefs().getString(PREF_KEY_LINK_ID, "") ?: ""
        if (linkImSdkId.isNotEmpty()) isBound = true
    }

    private fun saveLinkId(id: String) {
        linkImSdkId = id
        prefs().edit().putString(PREF_KEY_LINK_ID, id).apply()
    }

    private fun getDeviceSN(): String {
        var sn = prefs().getString(PREF_KEY_DEVICE_SN, "") ?: ""
        if (sn.isEmpty()) {
            sn = "SN" + System.currentTimeMillis().toString(36).uppercase() +
                 (1000..9999).random().toString()
            prefs().edit().putString(PREF_KEY_DEVICE_SN, sn).apply()
        }
        return sn
    }

    @ReactMethod
    fun register(promise: Promise) {
        thread {
            try {
                if (linkImSdkId.isNotEmpty()) {
                    promise.resolve(linkImSdkId)
                    return@thread
                }
                val body = JSONObject().apply {
                    put("product", DEVICE_MODE)
                    put("sn", getDeviceSN())
                    put("productId", DEVICE_ID)
                    put("customer", CUSTOMER_NAME)
                }
                Log.i(TAG, "register: POST $REGISTER_URL body=$body")
                val resp = httpPost(REGISTER_URL, body.toString())
                Log.i(TAG, "register: response=$resp")
                val id = extractIdFromResponse(resp)
                if (id != null) {
                    saveLinkId(id)
                    Log.i(TAG, "registered, ilinkImSdkId=$id")
                    promise.resolve(id)
                } else {
                    promise.reject("REG_FAIL", "register failed: $resp")
                }
            } catch (e: Exception) {
                Log.e(TAG, "register error", e)
                promise.reject("REG_ERR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getQrCodeUrl(promise: Promise) {
        thread {
            try {
                ensureRegistered()
                if (linkImSdkId.isEmpty()) {
                    promise.reject("NO_ID", "registration completed but ilinkImSdkId is still empty")
                    return@thread
                }
                val body = JSONObject().apply {
                    put("ilinkImSdkId", linkImSdkId)
                    put("customer", CUSTOMER_NAME)
                }
                val resp = httpPost(GET_QRCODE_URL, body.toString())
                Log.i(TAG, "getQrCodeUrl: response=$resp")
                val json = JSONObject(resp)
                val success = json.optBoolean("success", false)
                val code = json.optInt("code", -1)
                if (success || code == 200 || code == 0) {
                    val data = json.optJSONObject("data")
                        ?: json.optJSONObject("result")?.takeIf { json.opt("result") is JSONObject }
                    val ticket = (data?.optString("ilink_device_ticket", "") ?: "")
                        .ifEmpty { data?.optString("ticket", "") ?: "" }
                    val qrUrl = (data?.optString("device_qrcode_url", "") ?: "")
                        .ifEmpty { data?.optString("qrCodeUrl", "") ?: "" }
                    Log.i(TAG, "getQrCodeUrl: ticket=$ticket qrUrl=${qrUrl.take(80)}")
                    val result = Arguments.createMap().apply {
                        putString("ticket", ticket)
                        putString("url", qrUrl)
                        putString("qrCodeUrl", qrUrl)
                        putString("ilinkImSdkId", linkImSdkId)
                    }
                    promise.resolve(result)
                } else {
                    promise.reject("QR_FAIL", "getQrCode failed: $resp")
                }
            } catch (e: Exception) {
                Log.e(TAG, "getQrCodeUrl error", e)
                promise.reject("QR_ERR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun downloadQrImage(qrUrl: String, promise: Promise) {
        thread {
            try {
                val conn = URL(qrUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val bytes = conn.inputStream.readBytes()
                conn.disconnect()
                val outFile = File(reactApplicationContext.cacheDir, "wechat_qr.png")
                outFile.writeBytes(bytes)
                promise.resolve(outFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "downloadQrImage error", e)
                promise.reject("DL_ERR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun checkBind(promise: Promise) {
        thread {
            try {
                if (linkImSdkId.isEmpty()) {
                    promise.resolve(false)
                    return@thread
                }
                val body = JSONObject().apply {
                    put("ilinkImSdkId", linkImSdkId)
                }
                val resp = httpPost(CHECK_BIND_URL, body.toString())
                Log.i(TAG, "checkBind: response=$resp")
                val json = JSONObject(resp)
                val success = json.optBoolean("success", false)
                val code = json.optInt("code", -1)
                if (success || code == 200 || code == 0) {
                    val data = json.optJSONObject("data")
                        ?: json.optJSONObject("result")?.takeIf { json.opt("result") is JSONObject }
                    val bound = data?.optBoolean("bindStatus", false)
                        ?: data?.optBoolean("bind", false)
                        ?: (json.optString("result", "") == "true")
                    isBound = bound
                    promise.resolve(bound)
                } else {
                    promise.resolve(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkBind error", e)
                promise.reject("BIND_ERR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun startPolling(promise: Promise) {
        if (isPolling) { promise.resolve("already polling"); return }
        if (linkImSdkId.isEmpty()) { promise.reject("NO_ID", "not registered"); return }
        isPolling = true
        schedulePoll()
        promise.resolve("polling started")
        sendEvent("onWeChatPollingStarted", Arguments.createMap())
    }

    @ReactMethod
    fun stopPolling(promise: Promise) {
        isPolling = false
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        promise.resolve("polling stopped")
        sendEvent("onWeChatPollingStopped", Arguments.createMap())
    }

    @ReactMethod
    fun unbind(promise: Promise) {
        thread {
            try {
                if (linkImSdkId.isEmpty()) {
                    promise.resolve("not bound")
                    return@thread
                }
                val body = JSONObject().apply {
                    put("ilinkImSdkId", linkImSdkId)
                    put("customer", CUSTOMER_NAME)
                }
                httpPost(UNBIND_URL, body.toString())
                isBound = false
                saveLinkId("")
                isPolling = false
                pollRunnable?.let { handler.removeCallbacks(it) }
                promise.resolve("unbound")
                sendEvent("onWeChatUnbound", Arguments.createMap())
            } catch (e: Exception) {
                Log.e(TAG, "unbind error", e)
                promise.reject("UNBIND_ERR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun getStatus(promise: Promise) {
        promise.resolve(Arguments.createMap().apply {
            putBoolean("registered", linkImSdkId.isNotEmpty())
            putBoolean("bound", isBound)
            putBoolean("polling", isPolling)
            putString("ilinkImSdkId", linkImSdkId)
            putString("receiveDir", RECEIVE_DIR)
            putString("deviceName", DEVICE_DISPLAY_NAME)
        })
    }

    private fun schedulePoll() {
        if (!isPolling) return
        pollRunnable = Runnable {
            thread { pollOnce() }
        }
        handler.postDelayed(pollRunnable!!, POLL_INTERVAL_MS)
    }

    private fun pollOnce() {
        try {
            val body = JSONObject().apply {
                put("ilinkImSdkId", linkImSdkId)
            }
            val resp = httpPost(GET_SEND_URL, body.toString())
            val json = JSONObject(resp)
            val success = json.optBoolean("success", false)
            val code = json.optInt("code", json.optInt("errorCode", -1))
            if (!success && code != 200 && code != 0) {
                schedulePoll()
                return
            }
            val list = json.optJSONArray("result")
                ?: json.optJSONObject("data")?.optJSONArray("list")
                ?: JSONArray()
            if (list.length() == 0) {
                schedulePoll()
                return
            }
            Log.i(TAG, "pollOnce: got ${list.length()} items")

            val receivedIds = mutableListOf<String>()
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                if (i == 0) Log.i(TAG, "pollOnce: item[0] keys=${item.keys().asSequence().toList()}, full=${item.toString().take(500)}")
                val id = item.optString("id", "")
                val name = item.optString("name", "file_$i")
                val downloadUrl = item.optString("downloadUrl", "")
                val type = item.optString("type", "file")
                val keyBase64 = item.optString("keyBase64", "")
                val ivBase64 = item.optString("ivBase64", "")
                val tagBase64 = item.optString("tagBase64", "")
                val fileSize = item.optString("fileSize", "0")
                val url = item.optString("url", "")

                if (type == "url" && url.isNotEmpty()) {
                    sendEvent("onWeChatLinkReceived", Arguments.createMap().apply {
                        putString("name", name)
                        putString("url", url)
                        putString("id", id)
                    })
                    if (id.isNotEmpty()) receivedIds.add(id)
                    continue
                }

                if (downloadUrl.isEmpty()) continue

                sendEvent("onWeChatDownloadStarted", Arguments.createMap().apply {
                    putString("name", name)
                    putString("id", id)
                    putString("fileSize", fileSize)
                })

                try {
                    val outFile = downloadAndDecrypt(downloadUrl, name, keyBase64, ivBase64, tagBase64)
                    if (id.isNotEmpty()) receivedIds.add(id)
                    sendEvent("onWeChatFileReceived", Arguments.createMap().apply {
                        putString("name", name)
                        putString("path", outFile.absolutePath)
                        putDouble("size", outFile.length().toDouble())
                        putString("id", id)
                        putBoolean("isImage", name.matches(Regex(".*\\.(png|jpg|jpeg|gif|bmp|webp)$", RegexOption.IGNORE_CASE)))
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "download/decrypt failed: $name", e)
                    sendEvent("onWeChatDownloadError", Arguments.createMap().apply {
                        putString("name", name)
                        putString("error", e.message ?: "unknown")
                    })
                }
            }

            if (receivedIds.isNotEmpty()) {
                confirmReceived(receivedIds)
            }
        } catch (e: Exception) {
            Log.e(TAG, "poll error", e)
        }
        schedulePoll()
    }

    private fun downloadAndDecrypt(
        downloadUrl: String, fileName: String,
        keyB64: String, ivB64: String, tagB64: String
    ): File {
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        val raw = conn.inputStream.readBytes()
        conn.disconnect()

        val outFile = File(RECEIVE_DIR, fileName)
        if (keyB64.isNotEmpty() && ivB64.isNotEmpty() && tagB64.isNotEmpty()) {
            val decrypted = aesGcmDecrypt(keyB64, ivB64, tagB64, raw)
            outFile.writeBytes(decrypted)
            Log.i(TAG, "decrypted & saved: ${outFile.absolutePath} (${decrypted.size} bytes)")
        } else {
            outFile.writeBytes(raw)
            Log.i(TAG, "saved (no encryption): ${outFile.absolutePath} (${raw.size} bytes)")
        }
        return outFile
    }

    private fun aesGcmDecrypt(keyB64: String, ivB64: String, tagB64: String, ciphertext: ByteArray): ByteArray {
        val key = Base64.decode(keyB64, Base64.DEFAULT)
        val iv = Base64.decode(ivB64, Base64.DEFAULT)
        val tag = Base64.decode(tagB64, Base64.DEFAULT)

        if (key.size != 32) throw IllegalArgumentException("AES key must be 32 bytes, got ${key.size}")

        val cipherWithTag = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, cipherWithTag, 0, ciphertext.size)
        System.arraycopy(tag, 0, cipherWithTag, ciphertext.size, tag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(cipherWithTag)
    }

    private fun confirmReceived(ids: List<String>) {
        try {
            val idListStr = ids.joinToString(",")
            val body = JSONObject().apply {
                put("idList", "[$idListStr]")
            }
            httpPost(DELETE_SEND_URL, body.toString())
            Log.i(TAG, "confirmed receipt: $idListStr")
        } catch (e: Exception) {
            Log.w(TAG, "confirmReceived failed: ${e.message}")
        }
    }

    private fun ensureRegistered() {
        if (linkImSdkId.isNotEmpty()) return
        val body = JSONObject().apply {
            put("product", DEVICE_MODE)
            put("sn", getDeviceSN())
            put("productId", DEVICE_ID)
            put("customer", CUSTOMER_NAME)
        }
        Log.i(TAG, "ensureRegistered: POST $REGISTER_URL body=$body")
        val resp = httpPost(REGISTER_URL, body.toString())
        Log.i(TAG, "ensureRegistered: response=$resp")
        val id = extractIdFromResponse(resp)
        if (id != null) {
            saveLinkId(id)
            Log.i(TAG, "ensureRegistered: got id=$id")
        } else {
            throw RuntimeException("register failed: $resp")
        }
    }

    private fun extractIdFromResponse(resp: String): String? {
        val json = JSONObject(resp)
        val success = json.optBoolean("success", false)
        if (success) {
            val result = json.optString("result", "")
            if (result.isNotEmpty() && !result.contains("Exception")) return result
        }
        val code = json.optInt("code", -1)
        if (code == 200 || code == 0) {
            val data = json.optJSONObject("data")
            val id = (data?.optString("ilinkImSdkId", "") ?: "")
                .ifEmpty { data?.optString("result", "") ?: "" }
                .ifEmpty { json.optString("result", "") }
            if (id.isNotEmpty() && !id.contains("Exception")) return id
        }
        return null
    }

    private fun httpPost(url: String, jsonBody: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Cache-Control", "no-cache")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.outputStream.write(jsonBody.toByteArray(Charsets.UTF_8))
        conn.outputStream.flush()

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        Log.d(TAG, "POST $url → $code: ${resp.take(200)}")
        return resp
    }

    private fun sendEvent(name: String, params: WritableMap) {
        try {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(name, params)
        } catch (e: Exception) {
            Log.w(TAG, "sendEvent($name) failed: ${e.message}")
        }
    }
}
