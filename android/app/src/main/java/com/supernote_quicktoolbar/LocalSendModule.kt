package com.supernote_quicktoolbar

import android.content.Intent
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import java.io.*
import java.net.*
import java.security.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.*
import org.json.JSONObject
import org.json.JSONArray
import kotlin.concurrent.thread

class LocalSendModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "LocalSendModule"
        private const val PROTOCOL_VERSION = "2.0"
        private const val DEFAULT_PORT = 53317
        private const val MULTICAST_ADDR = "224.0.0.167"
        private const val MULTICAST_PORT = 53317
        private const val API_BASE = "/api/localsend/v2"

        @Volatile private var staticServerSocket: ServerSocket? = null
        @Volatile private var staticMulticastSocket: MulticastSocket? = null
        @Volatile private var staticIsRunning = false

        fun forceCloseAll() {
            staticIsRunning = false
            try { staticServerSocket?.close() } catch (_: Exception) {}
            try { staticMulticastSocket?.close() } catch (_: Exception) {}
            staticServerSocket = null
            staticMulticastSocket = null
        }

        data class PendingText(val id: String, val text: String, val fileName: String)
        private val pendingTexts = ConcurrentHashMap<String, PendingText>()

        fun addPendingText(text: String, fileName: String): PendingText {
            val pt = PendingText(
                id = UUID.randomUUID().toString().substring(0, 8),
                text = text,
                fileName = fileName
            )
            pendingTexts[pt.id] = pt
            return pt
        }

        fun ackPendingText(id: String) {
            pendingTexts.remove(id)
        }

        fun drainPendingTexts(): List<PendingText> {
            val copy = pendingTexts.values.toList()
            pendingTexts.clear()
            return copy
        }

        @Volatile @JvmStatic
        var staticReceiveDir: String = "/sdcard/LocalSend"

        @Volatile @JvmStatic
        var staticDeviceAlias: String = "Supernote"

        @Volatile @JvmStatic
        var staticDeviceFingerprint: String = ""

        @Volatile @JvmStatic
        var staticDiscoveredPeers: ConcurrentHashMap<String, DiscoveredPeer> = ConcurrentHashMap()

        private val sessionReceivedImages = mutableListOf<ReceivedFileInfo>()

        @JvmStatic
        fun addSessionReceivedImage(info: ReceivedFileInfo) {
            synchronized(sessionReceivedImages) { sessionReceivedImages.add(0, info) }
        }

        @JvmStatic
        fun getReceivedImageFiles(): List<ReceivedFileInfo> {
            synchronized(sessionReceivedImages) {
                return sessionReceivedImages.filter { File(it.path).exists() }.toList()
            }
        }

        @JvmStatic
        fun getPeersSnapshot(): List<DiscoveredPeer> {
            val now = System.currentTimeMillis()
            staticDiscoveredPeers.entries.removeIf { now - it.value.lastSeen > 30_000 }
            return staticDiscoveredPeers.values.toList()
        }

        @JvmStatic
        private fun isImageFileStatic(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in listOf("jpg", "jpeg", "png", "bmp", "gif", "webp")
        }

        data class ReceivedFileInfo(
            val name: String, val path: String, val size: Long,
            val modified: Long, val isImage: Boolean
        )

        private val staticTrustAllSsl: SSLContext by lazy {
            val tm = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            })
            SSLContext.getInstance("TLS").apply { init(null, tm, SecureRandom()) }
        }
        private val staticHostnameVerifier = HostnameVerifier { _, _ -> true }

        private fun staticOpenConn(url: String, connectTimeout: Int = 10000, readTimeout: Int = 30000): HttpURLConnection {
            val conn = URL(url).openConnection() as HttpURLConnection
            if (conn is HttpsURLConnection) {
                conn.sslSocketFactory = staticTrustAllSsl.socketFactory
                conn.hostnameVerifier = staticHostnameVerifier
            }
            conn.connectTimeout = connectTimeout
            conn.readTimeout = readTimeout
            return conn
        }

        private fun staticHttpPostJson(url: String, jsonBody: String): String {
            val conn = staticOpenConn(url)
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.doOutput = true
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                throw Exception("HTTP $code: ${err ?: "no body"}")
            }
            conn.disconnect()
            return body
        }

        private fun staticHttpPostBinary(url: String, data: ByteArray) {
            val conn = staticOpenConn(url, readTimeout = 60000)
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", data.size.toString())
            conn.doOutput = true
            conn.outputStream.use { os ->
                var offset = 0
                while (offset < data.size) {
                    val len = minOf(65536, data.size - offset)
                    os.write(data, offset, len)
                    offset += len
                }
                os.flush()
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }
                conn.disconnect()
                throw Exception("Upload HTTP $code: ${err ?: "no body"}")
            }
            conn.disconnect()
        }

        private fun staticSha256Hex(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        private fun buildSingleFileJson(
            fileId: String, fileName: String, size: Int, fileType: String,
            sha: String, preview: String? = null
        ): JSONObject = JSONObject().apply {
            put(fileId, JSONObject().apply {
                put("id", fileId); put("fileName", fileName)
                put("size", size); put("fileType", fileType)
                put("sha256", sha)
                if (preview != null) put("preview", preview)
            })
        }

        private fun staticGuessMimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "txt" -> "text/plain"; "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
                "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun sendTextDirect(ip: String, port: Int, text: String): String {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val fileId = "text-${UUID.randomUUID().toString().substring(0, 8)}"
            val filesJson = buildSingleFileJson(
                fileId, "message.txt", textBytes.size, "text/plain",
                staticSha256Hex(textBytes), preview = text
            )
            return doUploadDirect(ip, port, filesJson, mapOf(fileId to textBytes))
        }

        @JvmStatic
        @Throws(Exception::class)
        fun sendFileDirect(ip: String, port: Int, filePath: String): String {
            val file = File(filePath)
            if (!file.exists()) throw Exception("File not found: $filePath")
            val fileBytes = file.readBytes()
            val fileId = "file-${UUID.randomUUID().toString().substring(0, 8)}"
            val filesJson = buildSingleFileJson(
                fileId, file.name, fileBytes.size, staticGuessMimeType(file.name),
                staticSha256Hex(fileBytes)
            )
            return doUploadDirect(ip, port, filesJson, mapOf(fileId to fileBytes))
        }

        private fun doUploadDirect(
            ip: String, port: Int, filesJson: JSONObject, fileData: Map<String, ByteArray>
        ): String {
            val baseUrl = "https://$ip:$port$API_BASE"
            val prepareBody = JSONObject().apply {
                put("info", JSONObject().apply {
                    put("alias", staticDeviceAlias); put("version", PROTOCOL_VERSION)
                    put("deviceModel", "Supernote"); put("deviceType", "mobile")
                    put("fingerprint", staticDeviceFingerprint)
                })
                put("files", filesJson)
            }
            val prepareResp = staticHttpPostJson("$baseUrl/prepare-upload", prepareBody.toString())
            val prepareJson = JSONObject(prepareResp)
            val sessionId = prepareJson.optString("sessionId", "")
            val tokenMap = prepareJson.optJSONObject("files")
            if (sessionId.isEmpty()) return "auto"
            for ((fileId, data) in fileData) {
                val token = tokenMap?.optString(fileId, "") ?: ""
                if (token.isEmpty()) continue
                val uploadUrl = "$baseUrl/upload?sessionId=$sessionId&fileId=$fileId&token=$token"
                staticHttpPostBinary(uploadUrl, data)
            }
            return sessionId
        }

        @JvmStatic
        fun triggerScan() {
            if (scanRunningStatic) return
            thread(isDaemon = true, name = "NativePanel-Scan") {
                scanRunningStatic = true
                try {
                    val localIp = getLocalIpStatic()
                    if (localIp == "0.0.0.0") return@thread
                    val subnet = localIp.substringBeforeLast('.')
                    val executor = Executors.newFixedThreadPool(20)
                    for (i in 1..254) {
                        val ip = "$subnet.$i"
                        if (ip == localIp) continue
                        executor.submit { probeHostStatic(ip, DEFAULT_PORT) }
                    }
                    executor.shutdown()
                    executor.awaitTermination(6, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.e(TAG, "triggerScan error", e)
                } finally {
                    scanRunningStatic = false
                }
            }
        }

        @Volatile private var scanRunningStatic = false

        private fun getLocalIpStatic(): String {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress ?: "0.0.0.0"
                    }
                }
                "0.0.0.0"
            } catch (_: Exception) { "0.0.0.0" }
        }

        private fun probeHostStatic(ip: String, port: Int) {
            for (scheme in arrayOf("https", "http")) {
                try {
                    val conn = staticOpenConn("$scheme://$ip:$port$API_BASE/info", 2000, 2000)
                    conn.requestMethod = "GET"
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        val data = JSONObject(body)
                        val fp = data.optString("fingerprint", "")
                        if (fp.isNotEmpty() && fp != staticDeviceFingerprint) {
                            staticDiscoveredPeers[fp] = DiscoveredPeer(
                                data.optString("alias", "Unknown"), ip,
                                data.optInt("port", port), data.optString("deviceType", "desktop"), fp
                            )
                        }
                        return
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
            }
        }
    }

    private var serverSocket: ServerSocket?
        get() = staticServerSocket
        set(v) { staticServerSocket = v }
    private var multicastSocket: MulticastSocket?
        get() = staticMulticastSocket
        set(v) { staticMulticastSocket = v }
    private var isRunning: Boolean
        get() = staticIsRunning
        set(v) { staticIsRunning = v }
    private var serverPort = DEFAULT_PORT
    private var deviceAlias = "Supernote"
    private var deviceFingerprint = UUID.randomUUID().toString().replace("-", "")
    private var receiveDir = "/sdcard/LocalSend"
    private var pin = ""

    private val uploadSessions = ConcurrentHashMap<String, UploadSession>()
    private var activeUploadSession: String? = null

    data class DiscoveredPeer(
        val alias: String,
        val ip: String,
        val port: Int,
        val deviceType: String,
        val fingerprint: String,
        val lastSeen: Long = System.currentTimeMillis()
    )
    private val discoveredPeers = ConcurrentHashMap<String, DiscoveredPeer>()

    private val knownSenderIps = Collections.synchronizedSet(LinkedHashSet<String>())

    @Volatile private var scanRunning = false

    override fun getName(): String = "LocalSendModule"

    @ReactMethod
    fun startServer(config: ReadableMap, promise: Promise) {
        if (isRunning) {
            promise.resolve("already_running")
            return
        }

        forceCloseAll()

        try {
            deviceAlias = config.getString("alias") ?: "Supernote"
            serverPort = if (config.hasKey("port")) config.getInt("port") else DEFAULT_PORT
            receiveDir = config.getString("dest") ?: "/sdcard/LocalSend"
            pin = config.getString("pin") ?: ""

            staticReceiveDir = receiveDir
            staticDeviceAlias = deviceAlias
            staticDeviceFingerprint = deviceFingerprint
            staticDiscoveredPeers = discoveredPeers

            File(receiveDir).mkdirs()

            isRunning = true

            thread(isDaemon = true, name = "LocalSend-Server") {
                runHttpServer()
            }

            thread(isDaemon = true, name = "LocalSend-Multicast") {
                runMulticastDiscovery()
            }

            val localIp = getLocalIp()
            Log.i(TAG, "LocalSend server started on $localIp:$serverPort")
            sendEvent("onServerStarted", Arguments.createMap().apply {
                putString("ip", localIp)
                putInt("port", serverPort)
                putString("alias", deviceAlias)
            })
            promise.resolve("started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            isRunning = false
            promise.reject("START_FAILED", e.message)
        }
    }

    @ReactMethod
    fun stopServer(promise: Promise) {
        isRunning = false
        try {
            serverSocket?.close()
            multicastSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing sockets", e)
        }
        serverSocket = null
        multicastSocket = null
        uploadSessions.clear()
        activeUploadSession = null
        discoveredPeers.clear()
        sendEvent("onServerStopped", Arguments.createMap())
        promise.resolve("stopped")
    }

    @ReactMethod
    fun getServerStatus(promise: Promise) {
        val map = Arguments.createMap()
        map.putBoolean("running", isRunning)
        map.putString("ip", getLocalIp())
        map.putInt("port", serverPort)
        map.putString("alias", deviceAlias)
        map.putString("receiveDir", receiveDir)
        map.putInt("activeSessions", uploadSessions.size)
        promise.resolve(map)
    }

    @ReactMethod
    fun getReceivedFiles(promise: Promise) {
        try {
            val dir = File(receiveDir)
            val files = Arguments.createArray()
            if (dir.exists()) {
                dir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { file ->
                        val fileMap = Arguments.createMap()
                        fileMap.putString("name", file.name)
                        fileMap.putString("path", file.absolutePath)
                        fileMap.putDouble("size", file.length().toDouble())
                        fileMap.putDouble("modified", file.lastModified().toDouble())
                        fileMap.putBoolean("isImage", isImageFile(file.name))
                        files.pushMap(fileMap)
                    }
            }
            promise.resolve(files)
        } catch (e: Exception) {
            promise.reject("LIST_ERROR", e.message)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {}

    @ReactMethod
    fun removeListeners(count: Int) {}

    @ReactMethod
    fun getDiscoveredPeers(promise: Promise) {
        val now = System.currentTimeMillis()
        discoveredPeers.entries.removeIf { now - it.value.lastSeen > 30_000 }

        val arr = Arguments.createArray()
        discoveredPeers.values.forEach { peer ->
            arr.pushMap(Arguments.createMap().apply {
                putString("alias", peer.alias)
                putString("ip", peer.ip)
                putInt("port", peer.port)
                putString("deviceType", peer.deviceType)
                putString("fingerprint", peer.fingerprint)
            })
        }
        promise.resolve(arr)
    }

    @ReactMethod
    fun scanForPeers(promise: Promise) {
        if (scanRunning) {
            Log.d(TAG, "scanForPeers: already running, skipping")
            promise.resolve("scan_already_running")
            return
        }
        thread(isDaemon = true, name = "LocalSend-Scan") {
            scanRunning = true
            try {
                val localIp = getLocalIp()
                Log.i(TAG, "scanForPeers: localIp=$localIp")
                if (localIp == "0.0.0.0") {
                    Log.w(TAG, "scanForPeers: no network, aborting")
                    promise.resolve("no_network")
                    return@thread
                }

                val subnet = localIp.substringBeforeLast('.')
                val localSuffix = localIp.substringAfterLast('.').toIntOrNull() ?: 0
                val executor = Executors.newFixedThreadPool(20)

                val knownCopy = synchronized(knownSenderIps) { knownSenderIps.toList() }
                Log.i(TAG, "scanForPeers: phase1 known IPs: $knownCopy")
                for (ip in knownCopy) {
                    executor.submit { probeHost(ip, DEFAULT_PORT) }
                }

                val skipIps = knownCopy.toSet() + localIp
                Log.i(TAG, "scanForPeers: phase2 scanning subnet $subnet.1-254 (skipping ${skipIps.size} IPs)")
                for (i in 1..254) {
                    val ip = "$subnet.$i"
                    if (ip in skipIps) continue
                    executor.submit { probeHost(ip, DEFAULT_PORT) }
                }

                executor.shutdown()
                executor.awaitTermination(6, TimeUnit.SECONDS)
                Log.i(TAG, "scanForPeers: done, discovered ${discoveredPeers.size} peers total")
                promise.resolve("scan_done")
            } catch (e: Exception) {
                Log.e(TAG, "scanForPeers error", e)
                promise.reject("SCAN_ERROR", e.message)
            } finally {
                scanRunning = false
            }
        }
    }

    private fun probeHost(ip: String, port: Int) {

        for (scheme in arrayOf("https", "http")) {
            try {
                val url = "$scheme://$ip:$port$API_BASE/info"
                Log.d(TAG, "probeHost: trying $url")
                val conn = openConn(url, connectTimeout = 2000, readTimeout = 2000)
                conn.requestMethod = "GET"
                val code = conn.responseCode
                Log.d(TAG, "probeHost: $url → HTTP $code")
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    Log.d(TAG, "probeHost: $ip response body: $body")
                    val data = JSONObject(body)
                    val fp = data.optString("fingerprint", "")
                    if (fp.isNotEmpty() && fp != deviceFingerprint) {
                        val peerAlias = data.optString("alias", "Unknown")
                        val peerPort = data.optInt("port", port)
                        val peerDeviceType = data.optString("deviceType", "desktop")
                        Log.i(TAG, "probeHost: FOUND peer $peerAlias @ $ip:$peerPort ($scheme)")
                        discoveredPeers[fp] = DiscoveredPeer(
                            alias = peerAlias,
                            ip = ip,
                            port = peerPort,
                            deviceType = peerDeviceType,
                            fingerprint = fp
                        )
                        sendEvent("onPeerFound", Arguments.createMap().apply {
                            putString("alias", peerAlias)
                            putString("ip", ip)
                            putString("deviceType", peerDeviceType)
                            putInt("port", peerPort)
                            putString("fingerprint", fp)
                        })
                    }
                    return
                } else {
                    conn.disconnect()
                    Log.d(TAG, "probeHost: $url responded $code, trying next scheme")
                }
            } catch (e: Exception) {
                Log.d(TAG, "probeHost: $scheme://$ip:$port failed: ${e.javaClass.simpleName}: ${e.message}")

            }
        }
    }

    @ReactMethod
    fun sendText(ip: String, port: Int, text: String, promise: Promise) {
        thread(isDaemon = true, name = "LocalSend-SendText") {
            try {
                val textBytes = text.toByteArray(Charsets.UTF_8)
                val fileId = "text-${UUID.randomUUID().toString().substring(0, 8)}"
                val filesJson = buildSingleFileJson(
                    fileId, "message.txt", textBytes.size, "text/plain",
                    sha256Hex(textBytes), preview = text
                )
                val result = doLocalSendUpload(ip, port, filesJson, mapOf(fileId to textBytes))
                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "sendText failed", e)
                sendEvent("onSendError", Arguments.createMap().apply {
                    putString("error", e.message ?: "Unknown error")
                })
                promise.reject("SEND_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun sendFile(ip: String, port: Int, filePath: String, promise: Promise) {
        thread(isDaemon = true, name = "LocalSend-SendFile") {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    promise.reject("FILE_NOT_FOUND", "File not found: $filePath")
                    return@thread
                }

                val fileBytes = file.readBytes()
                val fileId = "file-${UUID.randomUUID().toString().substring(0, 8)}"
                val filesJson = buildSingleFileJson(
                    fileId, file.name, fileBytes.size, guessMimeType(file.name),
                    sha256Hex(fileBytes)
                )
                val result = doLocalSendUpload(ip, port, filesJson, mapOf(fileId to fileBytes))
                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "sendFile failed", e)
                sendEvent("onSendError", Arguments.createMap().apply {
                    putString("error", e.message ?: "Unknown error")
                })
                promise.reject("SEND_FAILED", e.message)
            }
        }
    }

    @ReactMethod
    fun flushPendingTexts(promise: Promise) {
        val pending = drainPendingTexts()
        val result = Arguments.createArray()
        for (pt in pending) {
            result.pushMap(Arguments.createMap().apply {
                putString("_pendingId", pt.id)
                putString("text", pt.text)
                putString("fileName", pt.fileName)
            })
        }
        Log.i(TAG, "flushPendingTexts: returning ${pending.size} unacked text(s)")
        promise.resolve(result)
    }

    @ReactMethod
    fun ackPendingText(id: String) {
        Companion.ackPendingText(id)
        Log.d(TAG, "ackPendingText: id=$id, remaining=${pendingTexts.size}")
    }

    private fun doLocalSendUpload(
        ip: String,
        port: Int,
        filesJson: JSONObject,
        fileData: Map<String, ByteArray>
    ): String {
        val baseUrl = "https://$ip:$port$API_BASE"

        val prepareBody = JSONObject().apply {
            put("info", JSONObject().apply {
                put("alias", deviceAlias)
                put("version", PROTOCOL_VERSION)
                put("deviceModel", "Supernote")
                put("deviceType", "mobile")
                put("fingerprint", deviceFingerprint)
            })
            put("files", filesJson)
        }

        Log.i(TAG, "doLocalSendUpload: target=$baseUrl")
        Log.d(TAG, "doLocalSendUpload: prepareBody=${prepareBody.toString().take(500)}")
        val prepareResp = httpPostJson("$baseUrl/prepare-upload", prepareBody.toString())
        Log.d(TAG, "doLocalSendUpload: prepareResp=$prepareResp")
        val prepareJson = JSONObject(prepareResp)

        val sessionId = prepareJson.optString("sessionId", "")
        val tokenMap = prepareJson.optJSONObject("files")

        if (sessionId.isEmpty()) {
            Log.i(TAG, "doLocalSendUpload: receiver auto-accepted (no sessionId), transfer complete")
            sendEvent("onSendComplete", Arguments.createMap().apply {
                putString("sessionId", "auto")
            })
            return "auto"
        }

        Log.i(TAG, "Got sessionId=$sessionId, uploading ${fileData.size} file(s)")
        sendEvent("onSendStarted", Arguments.createMap().apply {
            putString("sessionId", sessionId)
            putInt("fileCount", fileData.size)
            putString("targetIp", ip)
        })

        for ((fileId, data) in fileData) {
            val token = tokenMap?.optString(fileId, "") ?: ""
            if (token.isEmpty()) {
                Log.d(TAG, "doLocalSendUpload: no token for $fileId, skipping upload")
                continue
            }

            val uploadUrl = "$baseUrl/upload?sessionId=$sessionId&fileId=$fileId&token=$token"
            Log.i(TAG, "Uploading fileId=$fileId (${data.size} bytes)")
            httpPostBinary(uploadUrl, data)

            val fileInfo = filesJson.optJSONObject(fileId)
            sendEvent("onSendProgress", Arguments.createMap().apply {
                putString("fileId", fileId)
                putString("fileName", fileInfo?.optString("fileName") ?: "")
                putInt("percent", 100)
            })
        }

        Log.i(TAG, "Send complete for session $sessionId")
        sendEvent("onSendComplete", Arguments.createMap().apply {
            putString("sessionId", sessionId)
        })
        return sessionId
    }

    private fun httpPostJson(url: String, jsonBody: String): String = staticHttpPostJson(url, jsonBody)

    private fun httpPostBinary(url: String, data: ByteArray) = staticHttpPostBinary(url, data)

    private fun sha256Hex(data: ByteArray): String = staticSha256Hex(data)

    private fun guessMimeType(name: String): String = staticGuessMimeType(name)

    private fun runHttpServer() {
        try {

            val sock = ServerSocket()
            sock.reuseAddress = true
            var boundPort = serverPort
            var bindOk = false
            for (attempt in 0..9) {
                val tryPort = serverPort + attempt
                try {
                    sock.bind(InetSocketAddress(tryPort))
                    boundPort = tryPort
                    bindOk = true
                    break
                } catch (e: java.net.BindException) {
                    Log.w(TAG, "Port $tryPort unavailable (${e.message}), trying next...")
                }
            }
            if (!bindOk) {
                sock.close()
                throw java.net.BindException("No available port in range $serverPort..${serverPort+9}")
            }
            serverPort = boundPort
            serverSocket = sock
            Log.i(TAG, "HTTP server listening on port $serverPort")

            while (isRunning) {
                try {
                    val client = serverSocket?.accept() ?: break
                    thread(isDaemon = true) {
                        handleClient(client)
                    }
                } catch (e: SocketException) {
                    if (isRunning) Log.e(TAG, "Socket accept error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP server error", e)
            sendEvent("onServerError", Arguments.createMap().apply {
                putString("error", e.message ?: "Unknown error")
            })
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = BufferedInputStream(socket.inputStream)
            val output = BufferedOutputStream(socket.outputStream)
            val remoteIp = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "0.0.0.0"

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 3) return

            val method = parts[0]
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            var line = readLine(input)
            while (!line.isNullOrEmpty()) {
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx).trim().lowercase()] =
                        line.substring(colonIdx + 1).trim()
                }
                line = readLine(input)
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0

            val qIdx = rawPath.indexOf('?')
            val path = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
            val queryString = if (qIdx >= 0) rawPath.substring(qIdx + 1) else ""
            val queryParams = parseQuery(queryString)

            when {
                method == "GET" && path == "$API_BASE/info" ->
                    handleInfo(output)

                method == "POST" && path == "$API_BASE/register" -> {
                    val body = readBody(input, contentLength)
                    handleRegister(output, body, remoteIp)
                }

                method == "POST" && path == "$API_BASE/prepare-upload" -> {
                    val body = readBody(input, contentLength)
                    handlePrepareUpload(output, body, remoteIp, queryParams)
                }

                method == "POST" && path == "$API_BASE/upload" ->
                    handleUpload(output, input, remoteIp, queryParams, contentLength)

                method == "POST" && path == "$API_BASE/cancel" ->
                    handleCancel(output, queryParams)

                else ->
                    sendHttpResponse(output, 404, """{"error":"Not found"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleInfo(output: BufferedOutputStream) {
        val info = JSONObject().apply {
            put("alias", deviceAlias)
            put("version", PROTOCOL_VERSION)
            put("deviceModel", "Supernote")
            put("deviceType", "mobile")
            put("fingerprint", deviceFingerprint)
            put("download", false)
        }
        sendHttpResponse(output, 200, info.toString())
    }

    private fun handleRegister(output: BufferedOutputStream, body: String, remoteIp: String) {
        try {
            val data = JSONObject(body)
            val peerAlias = data.optString("alias", "Unknown")
            val peerPort = data.optInt("port", DEFAULT_PORT)
            val peerDeviceType = data.optString("deviceType", "desktop")
            val peerFingerprint = data.optString("fingerprint", remoteIp)

            knownSenderIps.add(remoteIp)

            val existing = discoveredPeers[peerFingerprint]
            val isNew     = existing == null
            val isChanged = existing != null && (
                existing.alias != peerAlias ||
                existing.ip    != remoteIp  ||
                existing.port  != peerPort
            )

            discoveredPeers[peerFingerprint] = DiscoveredPeer(
                alias = peerAlias,
                ip = remoteIp,
                port = peerPort,
                deviceType = peerDeviceType,
                fingerprint = peerFingerprint
            )

            if (isNew || isChanged) {
                Log.i(TAG, "Device registered: $peerAlias from $remoteIp")
                sendEvent("onPeerFound", Arguments.createMap().apply {
                    putString("alias", peerAlias)
                    putString("ip", remoteIp)
                    putString("deviceType", peerDeviceType)
                    putInt("port", peerPort)
                    putString("fingerprint", peerFingerprint)
                })
            } else {
                Log.d(TAG, "Device re-registered (no change): $peerAlias from $remoteIp")
            }

            val response = JSONObject().apply {
                put("alias", deviceAlias)
                put("version", PROTOCOL_VERSION)
                put("deviceModel", "Supernote")
                put("deviceType", "mobile")
                put("fingerprint", deviceFingerprint)
                put("port", serverPort)
                put("protocol", "http")
                put("download", false)
            }
            sendHttpResponse(output, 200, response.toString())
        } catch (e: Exception) {
            sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
        }
    }

    private fun handlePrepareUpload(
        output: BufferedOutputStream, body: String,
        remoteIp: String, params: Map<String, String>
    ) {

        if (pin.isNotEmpty()) {
            val pinParam = params["pin"] ?: ""
            if (pinParam != pin) {
                val msg = if (pinParam.isEmpty()) "PIN required" else "Invalid PIN"
                sendHttpResponse(output, 401, """{"error":"$msg"}""")
                return
            }
        }

        activeUploadSession?.let { sid ->
            uploadSessions[sid]?.let { sess ->
                if (sess.isValid()) {
                    sendHttpResponse(output, 409, """{"error":"Blocked by another session"}""")
                    return
                }
            }
            activeUploadSession = null
        }

        try {
            val data = JSONObject(body)
            val files = data.optJSONObject("files")
            val info = data.optJSONObject("info")
            if (files == null || files.length() == 0) {
                sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
                return
            }

            val senderAlias = info?.optString("alias", "Unknown") ?: "Unknown"

            knownSenderIps.add(remoteIp)

            val allPreviews = mutableListOf<String>()
            val keys0 = files.keys()
            while (keys0.hasNext()) {
                val fileId = keys0.next()
                val fileData = files.getJSONObject(fileId)
                val preview = fileData.optString("preview", "")
                val fileType = fileData.optString("fileType", "")
                if (preview.isNotEmpty() && fileType.startsWith("text/")) {
                    allPreviews.add(preview)
                }
            }
            if (allPreviews.isNotEmpty() && allPreviews.size == files.length()) {
                Log.i(TAG, "Text message(s) from [$senderAlias] via preview field: ${allPreviews.size}")
                val combined = allPreviews.joinToString("\n")
                sendTextViaBroadcast(combined)

                val textSessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                val response = JSONObject().apply {
                    put("sessionId", textSessionId)
                    put("files", JSONObject())
                }
                sendHttpResponse(output, 200, response.toString())
                return
            }

            val sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
            val session = UploadSession(sessionId, remoteIp)

            val tokens = JSONObject()
            val fileNames = mutableListOf<String>()

            val keys = files.keys()
            while (keys.hasNext()) {
                val fileId = keys.next()
                val fileData = files.getJSONObject(fileId)
                val fileName = fileData.optString("fileName", "unknown")
                val fileSize = fileData.optLong("size", 0)
                val fileType = fileData.optString("fileType", "application/octet-stream")
                val sha256 = fileData.optString("sha256", "")

                val token = UUID.randomUUID().toString().replace("-", "").substring(0, 22)
                session.files[fileId] = FileInfo(fileId, fileName, fileSize, fileType, sha256)
                session.tokens[fileId] = token
                session.received[fileId] = false
                tokens.put(fileId, token)
                fileNames.add(fileName)
            }

            uploadSessions[sessionId] = session
            activeUploadSession = sessionId

            Log.i(TAG, "Accepted transfer from [$senderAlias]: ${fileNames.size} files")
            fileNames.forEach { Log.i(TAG, "  - $it") }

            sendEvent("onTransferStarted", Arguments.createMap().apply {
                putString("sender", senderAlias)
                putInt("fileCount", fileNames.size)
                putString("sessionId", sessionId)
                val arr = Arguments.createArray()
                fileNames.forEach { arr.pushString(it) }
                putArray("fileNames", arr)
            })

            val response = JSONObject().apply {
                put("sessionId", sessionId)
                put("files", tokens)
            }
            sendHttpResponse(output, 200, response.toString())
        } catch (e: Exception) {
            Log.e(TAG, "prepare-upload error", e)
            sendHttpResponse(output, 400, """{"error":"Invalid body"}""")
        }
    }

    private fun handleUpload(
        output: BufferedOutputStream, input: BufferedInputStream,
        remoteIp: String, params: Map<String, String>, contentLength: Int
    ) {
        val sessionId = params["sessionId"] ?: ""
        val fileId = params["fileId"] ?: ""
        val token = params["token"] ?: ""

        if (sessionId.isEmpty() || fileId.isEmpty() || token.isEmpty()) {
            sendHttpResponse(output, 400, """{"error":"Missing parameters"}""")
            return
        }

        val session = uploadSessions[sessionId]
        if (session == null || !session.isValid()) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }
        if (session.tokens[fileId] != token) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }
        if (session.senderIp != remoteIp) {
            sendHttpResponse(output, 403, """{"error":"Invalid token or IP address"}""")
            return
        }

        val fileInfo = session.files[fileId]
        if (fileInfo == null) {
            sendHttpResponse(output, 400, """{"error":"Invalid file"}""")
            return
        }

        val dir = File(receiveDir)
        dir.mkdirs()
        val destFile = safeFileName(fileInfo.fileName, dir)

        Log.i(TAG, "Receiving file: ${fileInfo.fileName} -> $destFile")

        try {
            var received = 0L
            val sha = MessageDigest.getInstance("SHA-256")
            val fos = FileOutputStream(destFile)
            val buf = ByteArray(65536)
            val total = if (contentLength > 0) contentLength.toLong() else fileInfo.size

            while (received < total) {
                val toRead = minOf(buf.size.toLong(), total - received).toInt()
                val n = input.read(buf, 0, toRead)
                if (n <= 0) break
                fos.write(buf, 0, n)
                sha.update(buf, 0, n)
                received += n

                if (received % 262144 < n.toLong()) {
                    val pct = if (total > 0) (received * 100 / total).toInt() else 0
                    sendEvent("onTransferProgress", Arguments.createMap().apply {
                        putString("fileName", fileInfo.fileName)
                        putDouble("received", received.toDouble())
                        putDouble("total", total.toDouble())
                        putInt("percent", pct)
                    })
                }
            }
            fos.close()

            if (fileInfo.sha256.isNotEmpty()) {
                val computed = sha.digest().joinToString("") { "%02x".format(it) }
                if (!computed.equals(fileInfo.sha256, ignoreCase = true)) {
                    Log.w(TAG, "SHA256 mismatch for ${fileInfo.fileName}")
                }
            }

            session.received[fileId] = true
            Log.i(TAG, "File received: ${fileInfo.fileName} -> $destFile ($received bytes)")

            if (isTextFile(fileInfo.fileName)) {
                val textContent = destFile.readText(Charsets.UTF_8)
                destFile.delete()
                sendTextViaBroadcast(textContent)
            } else {
                if (isImageFile(fileInfo.fileName)) {
                    addSessionReceivedImage(ReceivedFileInfo(
                        fileInfo.fileName, destFile.absolutePath,
                        received, destFile.lastModified(), true
                    ))
                    Log.i(TAG, "Added to session received: ${destFile.absolutePath}, total=${getReceivedImageFiles().size}")
                    NativeImagePanel.currentInstance?.onFileReceived()
                }
                sendEvent("onFileReceived", Arguments.createMap().apply {
                    putString("fileName", fileInfo.fileName)
                    putString("path", destFile.absolutePath)
                    putDouble("size", received.toDouble())
                    putBoolean("isImage", isImageFile(fileInfo.fileName))
                })
            }

            if (session.received.values.all { it }) {
                Log.i(TAG, "Session $sessionId complete!")
                activeUploadSession = null
                sendEvent("onTransferComplete", Arguments.createMap().apply {
                    putString("sessionId", sessionId)
                })
            }

            sendHttpResponse(output, 200, "")
        } catch (e: Exception) {
            Log.e(TAG, "File receive error", e)
            if (destFile.exists()) destFile.delete()
            sendHttpResponse(output, 500, """{"error":"Unknown error by receiver"}""")
        }
    }

    private fun handleCancel(output: BufferedOutputStream, params: Map<String, String>) {
        val sessionId = params["sessionId"] ?: ""
        if (sessionId.isNotEmpty()) {
            uploadSessions.remove(sessionId)
            if (activeUploadSession == sessionId) {
                activeUploadSession = null
            }
            Log.i(TAG, "Session cancelled: $sessionId")
        }
        sendHttpResponse(output, 200, "")
    }

    private fun runMulticastDiscovery() {
        try {
            val group = InetAddress.getByName(MULTICAST_ADDR)
            multicastSocket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(MULTICAST_PORT))
                joinGroup(group)
            }

            Log.i(TAG, "Multicast announce started on $MULTICAST_ADDR:$MULTICAST_PORT")

            while (isRunning) {
                try {
                    val announcement = JSONObject().apply {
                        put("alias", deviceAlias)
                        put("version", PROTOCOL_VERSION)
                        put("deviceModel", "Supernote")
                        put("deviceType", "mobile")
                        put("fingerprint", deviceFingerprint)
                        put("port", serverPort)
                        put("protocol", "http")
                        put("download", false)
                        put("announce", true)
                    }
                    val data = announcement.toString().toByteArray()
                    val packet = DatagramPacket(data, data.size, group, MULTICAST_PORT)
                    multicastSocket?.send(packet)
                } catch (e: Exception) {
                    if (isRunning) Log.d(TAG, "Announce error: ${e.message}")
                }
                Thread.sleep(5000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Multicast announce error", e)
        }
    }

    private fun sendTextViaBroadcast(text: String) {
        Log.i(TAG, "sendTextViaBroadcast: len=${text.length}")
        val intent = Intent("com.dictation.TEXT_TO_PLUGIN").apply {
            putExtra("text", text)
        }
        reactApplicationContext.sendBroadcast(intent)
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        if (eventName == "onTextReceived") {

            val text = params.getString("text") ?: ""
            val fileName = params.getString("fileName") ?: "message.txt"
            val pt = addPendingText(text, fileName)
            Log.d(TAG, "sendEvent → $eventName buffered id=${pt.id}, trying emit")
            params.putString("_pendingId", pt.id)
        }

        if (!reactApplicationContext.hasActiveCatalystInstance()) {
            Log.w(TAG, "sendEvent → $eventName: bridge unavailable, will flush on next activation")
            return
        }

        try {
            reactApplicationContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
            Log.d(TAG, "sendEvent → $eventName OK")
        } catch (e: Exception) {
            Log.w(TAG, "sendEvent → $eventName FAILED (bridge transition): ${e.message}")
        }

    }

    private fun openConn(url: String, connectTimeout: Int = 10_000, readTimeout: Int = 30_000): HttpURLConnection =
        staticOpenConn(url, connectTimeout, readTimeout)

    private fun getLocalIp(): String = getLocalIpStatic()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) {
                try {
                    java.net.URLDecoder.decode(kv[0], "UTF-8") to
                        java.net.URLDecoder.decode(kv[1], "UTF-8")
                } catch (e: Exception) { null }
            } else null
        }.toMap()
    }

    private fun sendHttpResponse(output: BufferedOutputStream, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }

        val bodyBytes = body.toByteArray()
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray())
        if (bodyBytes.isNotEmpty()) {
            output.write(bodyBytes)
        }
        output.flush()
    }

    private fun safeFileName(name: String, dir: File): File {
        var target = File(dir, name)
        if (!target.exists()) return target
        val dotIdx = name.lastIndexOf('.')
        val stem = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""
        var counter = 1
        while (target.exists()) {
            target = File(dir, "${stem}(${counter})${ext}")
            counter++
        }
        return target
    }

    private fun isImageFile(name: String): Boolean = isImageFileStatic(name)

    private fun isTextFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "txt"
    }

    data class FileInfo(
        val id: String,
        val fileName: String,
        val size: Long,
        val fileType: String,
        val sha256: String
    )

    data class UploadSession(
        val sessionId: String,
        val senderIp: String,
        val createdAt: Long = System.currentTimeMillis(),
        val timeout: Long = 600_000L,
        val files: MutableMap<String, FileInfo> = mutableMapOf(),
        val tokens: MutableMap<String, String> = mutableMapOf(),
        val received: MutableMap<String, Boolean> = mutableMapOf()
    ) {
        fun isValid(): Boolean = (System.currentTimeMillis() - createdAt) < timeout
    }
}
