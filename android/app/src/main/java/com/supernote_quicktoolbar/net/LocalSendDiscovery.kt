package com.supernote_quicktoolbar.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.supernote_quicktoolbar.BuildConfig
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * Shared multicast discovery coordinator on the LocalSend group/port
 * (224.0.0.167:53317), decoupled from the LocalSend HTTP server.
 *
 * Two independent consumers share one socket:
 *  - **AIRelay** listens for the phone's dictation announcements
 *    (`service:"dictation"`) to learn its IP without manual entry. Active
 *    whenever AIRelayCore runs — no LocalSend server needed.
 *  - **LocalSend** (SendPanel/LocalSendModule) listens for LocalSend peers and,
 *    while its HTTP server is up, announces this device every few seconds.
 *
 * The socket stays bound while either consumer needs it (demand
 * refcounting); announcing is a separate flag toggled by the LocalSend
 * server lifecycle. Dictation peers are kept in their own table so they
 * never appear in the LocalSend file-transfer peer list.
 */
object LocalSendDiscovery {
    private const val TAG = "LocalSendDiscovery"
    const val MULTICAST_ADDR = "224.0.0.167"
    const val MULTICAST_PORT = 53317
    private const val ANNOUNCE_INTERVAL_MS = 5_000L
    private const val ANNOUNCE_POLL_MS = 500L

    /** An AIRelay-capable server (RikkaHub's web server) seen on the LAN. */
    data class DictationPeer(
        val alias: String,
        val ip: String,
        val fingerprint: String,
        /** REST + SSE port. */
        val llmPort: Int,
        val authRequired: Boolean = false,
        val lastSeen: Long = System.currentTimeMillis(),
    )

    fun interface DictationPeerListener {
        /**
         * Invoked from the multicast receive thread for every dictation
         * announcement. [endpointChanged] is true on first sight of a
         * fingerprint or when its ip/ports differ from the last packet.
         */
        fun onPeerSeen(peer: DictationPeer, endpointChanged: Boolean)
    }

    private val lifecycleLock = Any()

    @Volatile private var discoveryRunning = false
    @Volatile private var dictationListeningRequested = false
    @Volatile private var localSendListeningRequested = false
    @Volatile private var localSendAnnounceEnabled = false
    @Volatile private var socketGeneration = 0L

    @Volatile private var multicastSocket: MulticastSocket? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var appContext: Context? = null

    /** LocalSend announcement payload; supplied by LocalSendModule.startServer. */
    @Volatile private var announcementProvider: (() -> JSONObject)? = null
    /** LocalSend peer announcements are forwarded here (LocalSendModule handler). */
    @Volatile private var localSendAnnouncementHandler: ((String, JSONObject) -> Unit)? = null

    private val dictationPeers = ConcurrentHashMap<String, DictationPeer>()
    private val dictationPeerListeners = CopyOnWriteArraySet<DictationPeerListener>()

    // ── Consumer entry points ────────────────────────────────────────────

    @JvmStatic
    fun startDictationListening(context: Context) {
        synchronized(lifecycleLock) {
            appContext = context.applicationContext
            dictationListeningRequested = true
            ensureNetworkCallbackLocked()
            ensureDiscoveryRunning()
        }
    }

    @JvmStatic
    fun stopDictationListening() {
        synchronized(lifecycleLock) {
            dictationListeningRequested = false
            stopIfNoDemand()
        }
    }

    @JvmStatic
    fun addDictationPeerListener(listener: DictationPeerListener) {
        dictationPeerListeners += listener
    }

    @JvmStatic
    fun removeDictationPeerListener(listener: DictationPeerListener) {
        dictationPeerListeners -= listener
    }

    /**
     * LocalSend server came up: listen for LocalSend peers and start
     * announcing. Idempotent; if the dictation listener already bound the
     * socket this only flips flags and installs the handlers.
     */
    @JvmStatic
    fun startLocalSendDiscovery(
        context: Context,
        announcementProvider: () -> JSONObject,
        onLocalSendAnnouncement: (senderIp: String, payload: JSONObject) -> Unit,
    ) {
        synchronized(lifecycleLock) {
            appContext = context.applicationContext
            this.announcementProvider = announcementProvider
            this.localSendAnnouncementHandler = onLocalSendAnnouncement
            localSendListeningRequested = true
            ensureNetworkCallbackLocked()
            ensureDiscoveryRunning()
        }
    }

    /** LocalSend server went down. Dictation listening (if any) keeps the socket alive. */
    @JvmStatic
    fun stopLocalSendDiscovery() {
        synchronized(lifecycleLock) {
            localSendListeningRequested = false
            localSendAnnounceEnabled = false
            announcementProvider = null
            localSendAnnouncementHandler = null
            stopIfNoDemand()
        }
    }

    @JvmStatic
    fun setLocalSendAnnouncing(enabled: Boolean) {
        localSendAnnounceEnabled = enabled
    }

    /** Wi-Fi changed: rebind the socket on the new interface if anyone still needs it. */
    @JvmStatic
    fun restartForNetworkChange() {
        restartForNetworkChange("external")
    }

    // ── Lifecycle internals ──────────────────────────────────────────────

    private fun hasDiscoveryDemand(): Boolean =
        dictationListeningRequested || localSendListeningRequested

    /** Must hold [lifecycleLock]. */
    private fun ensureDiscoveryRunning() {
        if (discoveryRunning && multicastSocket != null) return
        val ctx = appContext ?: return
        closeSocketLocked()
        discoveryRunning = true
        val generation = ++socketGeneration
        acquireMulticastLock(ctx)
        thread(isDaemon = true, name = "LSDiscovery-Recv") { discoveryLoop(generation) }
        thread(isDaemon = true, name = "LSDiscovery-Announce") { announceLoop(generation) }
    }

    /** Must hold [lifecycleLock]. */
    private fun stopIfNoDemand() {
        if (hasDiscoveryDemand()) return
        discoveryRunning = false
        closeSocketLocked()
        releaseMulticastLock()
        unregisterNetworkCallbackLocked()
        dictationPeers.clear()
    }

    /** Must hold [lifecycleLock]. */
    private fun closeSocketLocked() {
        socketGeneration++
        try { multicastSocket?.close() } catch (_: Exception) {}
        multicastSocket = null
    }

    /** Must hold [lifecycleLock]. AIRelay owns this independently of LocalSend UI. */
    private fun ensureNetworkCallbackLocked() {
        if (networkCallback != null) return
        val ctx = appContext ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                restartForNetworkChange("available")
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                restartForNetworkChange("link=${linkProperties.interfaceName.orEmpty()}")
            }

            override fun onLost(network: Network) {
                restartForNetworkChange("lost")
            }
        }
        try {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                callback,
            )
            networkCallback = callback
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Wi-Fi network callback registered")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "network callback failed: ${e.message}")
        }
    }

    private fun restartForNetworkChange(reason: String) {
        synchronized(lifecycleLock) {
            if (!hasDiscoveryDemand()) return
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "network changed ($reason), rebinding discovery")
            closeSocketLocked()
            ensureDiscoveryRunning()
        }
    }

    /** Must hold [lifecycleLock]. */
    private fun unregisterNetworkCallbackLocked() {
        val callback = networkCallback ?: return
        networkCallback = null
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try { cm?.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    private fun openSocket(): MulticastSocket? {
        return try {
            val group = InetAddress.getByName(MULTICAST_ADDR)
            val groupAddress = InetSocketAddress(group, MULTICAST_PORT)
            val interfaces = multicastInterfaces()
            MulticastSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(MULTICAST_PORT))
                timeToLive = 1

                var joined = 0
                interfaces.forEach { iface ->
                    runCatching {
                        joinGroup(groupAddress, iface)
                        joined++
                    }.onFailure {
                        if (BuildConfig.ENABLE_DEBUG) {
                            Log.d(TAG, "join via ${iface.name} failed: ${it.message}")
                        }
                    }
                }
                if (joined == 0) {
                    @Suppress("DEPRECATION")
                    joinGroup(group)
                }

                // This controls LocalSend announcement egress only; AIRelay
                // reception above is joined on every suitable LAN interface.
                preferredWifiInterface()?.let { networkInterface = it }
                if (BuildConfig.ENABLE_DEBUG) {
                    val names = interfaces.joinToString { it.name }.ifEmpty { "default" }
                    Log.i(TAG, "multicast joined on $names; egress=${networkInterface?.name}")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "Multicast bind failed: ${e.message}")
            null
        }
    }

    private fun discoveryLoop(generation: Long) {
        val socket = openSocket() ?: run {
            // Bind failed (port held by another process?). Give up this round;
            // a network change or consumer restart retries.
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "discoveryLoop: no socket, exiting")
            return
        }
        synchronized(lifecycleLock) {
            if (generation != socketGeneration || !discoveryRunning) {
                try { socket.close() } catch (_: Exception) {}
                return
            }
            multicastSocket = socket
        }
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "Discovery listening on $MULTICAST_ADDR:$MULTICAST_PORT (gen=$generation)")
        }
        val buf = ByteArray(8192)
        while (discoveryRunning && generation == socketGeneration) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                socket.receive(pkt)
                val senderIp = pkt.address?.hostAddress ?: continue
                dispatchMulticastPacket(senderIp, String(pkt.data, 0, pkt.length, Charsets.UTF_8))
            } catch (e: Exception) {
                if (discoveryRunning && generation == socketGeneration) {
                    if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "receive error: ${e.message}")
                } else break
            }
        }
        try { socket.close() } catch (_: Exception) {}
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "Discovery receive loop exited (gen=$generation)")
    }

    private fun announceLoop(generation: Long) {
        val group = try { InetAddress.getByName(MULTICAST_ADDR) } catch (_: Exception) { return }
        var lastAnnounceAt = 0L
        var announceCount = 0
        while (discoveryRunning && generation == socketGeneration) {
            val now = System.currentTimeMillis()
            if (localSendAnnounceEnabled && now - lastAnnounceAt >= ANNOUNCE_INTERVAL_MS) {
                val socket = multicastSocket
                val payload = announcementProvider?.invoke()
                if (socket != null && payload != null) {
                    val data = payload.toString().toByteArray(Charsets.UTF_8)
                    val targets = (listOf(group) + lanBroadcastAddresses())
                        .distinctBy { it.hostAddress }
                    var sent = 0
                    targets.forEach { target ->
                        try {
                            socket.send(DatagramPacket(data, data.size, target, MULTICAST_PORT))
                            sent++
                        } catch (e: Exception) {
                            if (discoveryRunning && BuildConfig.ENABLE_DEBUG) {
                                Log.d(TAG, "announce to ${target.hostAddress} failed: ${e.message}")
                            }
                        }
                    }
                    if (sent > 0) {
                        lastAnnounceAt = now
                        announceCount++
                        if (BuildConfig.ENABLE_DEBUG && (announceCount <= 3 || announceCount % 12 == 0)) {
                            Log.i(TAG, "LocalSend announce #$announceCount sent to " +
                                targets.joinToString { it.hostAddress })
                        }
                    }
                }
            }
            try { Thread.sleep(ANNOUNCE_POLL_MS) } catch (_: InterruptedException) { break }
        }
    }

    /** Send a one-off unicast UDP reply (LocalSend register fallback path). */
    @JvmStatic
    fun sendUnicast(ip: String, payload: JSONObject) {
        try {
            val data = payload.toString().toByteArray(Charsets.UTF_8)
            multicastSocket?.send(DatagramPacket(data, data.size, InetAddress.getByName(ip), MULTICAST_PORT))
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "unicast to $ip failed: ${e.message}")
        }
    }

    // ── Packet routing ───────────────────────────────────────────────────

    private fun dispatchMulticastPacket(senderIp: String, msg: String) {
        if (isLocalAddress(senderIp)) return
        val dto = try { JSONObject(msg) } catch (_: Exception) { return }
        if (dto.optString("service") == "dictation") {
            handleDictationAnnouncement(senderIp, dto)
        } else {
            localSendAnnouncementHandler?.invoke(senderIp, dto)
        }
    }

    private fun handleDictationAnnouncement(senderIp: String, dto: JSONObject) {
        val fp = dto.optString("fingerprint", "")
        if (fp.isEmpty()) return
        val peer = DictationPeer(
            alias = dto.optString("alias", "RikkaHub"),
            ip = senderIp,
            fingerprint = fp,
            llmPort = dto.optInt("dictationLlmPort", dto.optInt("port", 8080)),
            authRequired = dto.optBoolean("authRequired", false),
        )
        val previous = dictationPeers.put(fp, peer)
        val endpointChanged = previous == null ||
            previous.ip != peer.ip ||
            previous.llmPort != peer.llmPort
        if (endpointChanged && BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "AIRelay peer ${peer.alias} @ ${peer.ip}:${peer.llmPort} auth=${peer.authRequired} fp=${fp.take(8)}")
        }
        dictationPeerListeners.forEach {
            try { it.onPeerSeen(peer, endpointChanged) } catch (_: Exception) {}
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun acquireMulticastLock(ctx: Context) {
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("InklingDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "MulticastLock failed: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    private fun preferredWifiInterface(): NetworkInterface? {
        val ctx = appContext ?: return null
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val network = cm.activeNetwork ?: return null
            val capabilities = cm.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val name = cm.getLinkProperties(network)?.interfaceName ?: return null
            NetworkInterface.getByName(name)
        } catch (_: Exception) {
            null
        }
    }

    /** Join every private IPv4 LAN so stale/default interface ordering cannot hide AIRelay. */
    private fun multicastInterfaces(): List<NetworkInterface> {
        val result = mutableListOf<NetworkInterface>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val usable = runCatching {
                    iface.isUp && !iface.isLoopback && !iface.isPointToPoint &&
                        iface.interfaceAddresses.any { address ->
                            (address.address as? Inet4Address)?.isSiteLocalAddress == true
                        }
                }.getOrDefault(false)
                if (usable) result += iface
            }
        } catch (_: Exception) {}
        return result
    }

    /** Directed broadcasts cover LANs where multicast egress is filtered. */
    private fun lanBroadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        multicastInterfaces().forEach { iface ->
            iface.interfaceAddresses.forEach { address ->
                val ipv4 = address.address as? Inet4Address
                val broadcast = address.broadcast
                if (ipv4?.isSiteLocalAddress == true && broadcast != null) {
                    result += broadcast
                }
            }
        }
        return result.distinctBy { it.hostAddress }
    }

    private fun isLocalAddress(ip: String): Boolean = ip in localIps()

    private fun localIps(): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { result += it }
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }
}
