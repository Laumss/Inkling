package com.supernote_quicktoolbar

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.View
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal object FloatingPenGuard {
    private const val APP_NAME = "inkling_guard"
    private const val PAD_PX = 3
    private const val FOLLOW_INTERVAL_MS = 80L
    private const val REMOVE_LINGER_MS = 500L
    private const val HOST_BOOTSTRAP_OWNER = "host-bootstrap"
    private const val HOST_BOOTSTRAP_FALLBACK_MS = 450L
    private const val ROTATION_SYNC_OWNER = "rotation-sync"
    private const val TAG = "FloatingPenGuard"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val rects = linkedMapOf<String, Rect>()
    private val views = linkedMapOf<String, WeakReference<View>>()
    private val viewRotationEpochs = linkedMapOf<String, Long>()
    private val generation = AtomicLong()

    private class PendingSend(
        val clear: Boolean,
        val payload: List<Rect>,
        val logLine: String
    )
    private val pendingSend =
        java.util.concurrent.atomic.AtomicReference<PendingSend?>(null)
    private val pendingRemovals = linkedMapOf<String, Runnable>()
    private var parkActive = false
    private val parkedKeys = linkedSetOf<String>()
    private var followerRunning = false
    private val fullScreenOwners = linkedSetOf<String>()
    private val pendingBaselineReleases = linkedSetOf<String>()
    private var screenWidth = 1920
    private var screenHeight = 2560

    private val ownershipToken = "${android.os.Process.myPid()}-${System.nanoTime()}"
    @Volatile private var ownershipFile: java.io.File? = null
    @Volatile private var ownershipLost = false

    fun claimOwnership(dir: java.io.File) {
        val file = java.io.File(dir, "pen_guard_owner")
        try {
            file.writeText(ownershipToken)
        } catch (e: Exception) {
            Log.e(TAG, "ownership claim failed: ${e.message}")
        }
        ownershipFile = file
        Log.i(TAG, "ownership claimed token=$ownershipToken")
    }

    private fun isOwner(): Boolean {
        if (ownershipLost) return false
        val file = ownershipFile ?: return true
        val current = try { file.readText() } catch (_: Exception) { return true }
        if (current == ownershipToken) return true
        ownershipLost = true
        Log.e(TAG, "ownership lost to $current; this instance stops writing pen areas")
        main.post {
            fullScreenOwners.clear()
            pendingBaselineReleases.clear()
            main.removeCallbacks(hostBootstrapFallback)
        }
        return false
    }
    private var logicalScreenWidth = 1920
    private var logicalScreenHeight = 2560
    private var displayRotation = Surface.ROTATION_0
    private var displayStateReady = false
    private var activeRotationEpoch = 0L
    private val hostBootstrapFallback = Runnable {
        val useLocalFallback = synchronized(rects) {
            if (hostBaselineReady) {
                false
            } else {
                // Historical HandWriteClient output can be empty when the host table was built
                // before log capture. Permit the tracked Inkling rectangles after the safety
                // interval; a later host snapshot will replace this empty fallback baseline.
                hostBaselineReady = true
                true
            }
        }
        Log.i(TAG, "host bootstrap safety release localFallback=$useLocalFallback ${debugState()}")
        setFullScreenActive(false, HOST_BOOTSTRAP_OWNER)
    }

    private fun startHostBootstrapSafety(reason: String) {
        main.removeCallbacks(hostBootstrapFallback)
        Log.i(TAG, "host bootstrap safety start reason=$reason delayMs=$HOST_BOOTSTRAP_FALLBACK_MS")
        setFullScreenActive(true, HOST_BOOTSTRAP_OWNER)
        main.postDelayed(hostBootstrapFallback, HOST_BOOTSTRAP_FALLBACK_MS)
    }

    /**
     * WindowManager owns the logical display coordinate space. Keep its state separate from
     * View.resources: the plugin View can retain the previous Configuration for several frames,
     * and 0<->180 / 90<->270 rotations keep the same width and height.
     */
    fun onDisplayChanged(
        logicalWidth: Int,
        logicalHeight: Int,
        rotation: Int,
        reason: String
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onDisplayChanged(logicalWidth, logicalHeight, rotation, reason) }
            return
        }
        if (logicalWidth <= 0 || logicalHeight <= 0) return

        val normalizedRotation = rotation.takeIf {
            it in Surface.ROTATION_0..Surface.ROTATION_270
        } ?: Surface.ROTATION_0
        val naturalWidth = if (normalizedRotation == Surface.ROTATION_90 ||
            normalizedRotation == Surface.ROTATION_270) logicalHeight else logicalWidth
        val naturalHeight = if (normalizedRotation == Surface.ROTATION_90 ||
            normalizedRotation == Surface.ROTATION_270) logicalWidth else logicalHeight
        val changed = logicalScreenWidth != logicalWidth ||
            logicalScreenHeight != logicalHeight ||
            displayRotation != normalizedRotation ||
            !displayStateReady

        logicalScreenWidth = logicalWidth
        logicalScreenHeight = logicalHeight
        displayRotation = normalizedRotation
        screenWidth = naturalWidth
        screenHeight = naturalHeight
        displayStateReady = true
        if (changed) {
            // The host rebuilds its pen table in the new logical coordinate space. Drop the old
            // baseline and clear the pending payload slot so a portrait/previous-rotation host
            // snapshot cannot be merged with freshly refreshed Inkling rectangles.
            generation.incrementAndGet()
            pendingSend.set(null)
            synchronized(rects) {
                hostSnapshot.clear()
                hostBaselineReady = false
                lastSentSnapshot.clear()
            }
            if (parkedKeys.isNotEmpty()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "display change: drop parked=$parkedKeys")
                synchronized(rects) { parkedKeys.forEach(rects::remove) }
                parkedKeys.clear()
            }
            parkActive = false
            if (activeRotationEpoch == 0L && (views.isNotEmpty() || rects.isNotEmpty())) {
                startHostBootstrapSafety("display change: $reason")
            }
        }

        Log.i(TAG, "display state reason=$reason changed=$changed " +
            "logical=${logicalWidth}x${logicalHeight} rotation=$normalizedRotation " +
            "natural=${naturalWidth}x${naturalHeight} baselineReset=$changed tracked=${views.keys}")

        // A configuration callback can precede the overlay layout pass. Refresh now, then let the
        // existing 80 ms follower pick up the final WindowManager position.
        views.toMap().forEach { (key, reference) ->
            reference.get()?.let { refresh(key, it) } ?: removeIfCurrent(key, reference)
        }
        if (views.isEmpty() && fullScreenOwners.isNotEmpty() && changed) submitSnapshot()
    }

    private val lastSnapshot = mutableListOf<Rect>()

    private val hostSnapshot = mutableListOf<Rect>()

    private val lastSentSnapshot = mutableListOf<Rect>()

    private var hostBaselineReady = false

    fun hasActiveRects(): Boolean = synchronized(rects) { lastSnapshot.isNotEmpty() }

    fun hasBlockingOwnerForRotation(): Boolean =
        fullScreenOwners.any { it != ROTATION_SYNC_OWNER }

    fun beginHostBootstrap(reason: String) {
        val reused = synchronized(rects) {
            if (hostSnapshot.isEmpty()) {
                hostBaselineReady = false
                false
            } else {
                hostBaselineReady = true
                true
            }
        }
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "host bootstrap ${if (reused) "reusing baseline" else "pending"} " +
                "reason=$reason ${debugState()}")
        }
        if (reused) {
            main.removeCallbacks(hostBootstrapFallback)
            main.post {
                setFullScreenActive(false, HOST_BOOTSTRAP_OWNER)
                submitSnapshot()
            }
        } else {
            startHostBootstrapSafety(reason)
        }
    }

    fun updateHostPenAreas(incoming: List<Rect>, commitBaseline: Boolean = true): Boolean {
        val raw = normalize(incoming, keepClearSentinel = true)
        var transientFullScreen = false
        var writableReset = false
        var baselineCommitted = false
        var becameReady = false
        val accepted = synchronized(rects) {
            if (sameRects(raw, lastSentSnapshot)) return@synchronized false
            writableReset = raw.size == 1 && isClearSentinel(raw[0])
            val normalized = raw.filterNot(::isClearSentinel)
            transientFullScreen = normalized.size == 1 && isScreenCovering(normalized[0])
            if (commitBaseline && !writableReset && !transientFullScreen && normalized.isNotEmpty()) {
                hostSnapshot.clear()
                hostSnapshot.addAll(normalized.map(::Rect))
                baselineCommitted = true
                becameReady = !hostBaselineReady
                hostBaselineReady = true
            }
            true
        }
        if (BuildConfig.ENABLE_DEBUG) {
            val status = when {
                !accepted -> "ignored-self-echo"
                writableReset -> "accepted-writable-reset"
                transientFullScreen -> "accepted-transient-full-screen"
                !commitBaseline -> "accepted-transient-ui"
                baselineCommitted -> "accepted-baseline"
                else -> "accepted-no-baseline"
            }
            Log.i(TAG, "host snapshot $status incoming=$raw ${debugState()}")
        }
        if (becameReady) {
            main.removeCallbacks(hostBootstrapFallback)
            main.post {
                setFullScreenActive(false, HOST_BOOTSTRAP_OWNER)
                submitSnapshot()
            }
        }
        if (baselineCommitted) {
            main.post {
                if (pendingBaselineReleases.isNotEmpty()) {
                    val owners = pendingBaselineReleases.toList()
                    pendingBaselineReleases.clear()
                    owners.forEach { setFullScreenActive(false, it) }
                }
            }
        }
        return accepted
    }

    fun debugState(): String = synchronized(rects) {
        "generation=${generation.get()} hostReady=$hostBaselineReady " +
            "tracked=${rects.mapValues { Rect(it.value) }} " +
            "inkling=${lastSnapshot.map(::Rect)} host=${hostSnapshot.map(::Rect)} " +
            "lastSent=${lastSentSnapshot.map(::Rect)}"
    }

    fun reassert(reason: String) {
        val state = synchronized(rects) {
            val inkling = lastSnapshot.map(::Rect)
            Triple(inkling, mergeWithHost(inkling), hostBaselineReady)
        }
        val inklingSnapshot = state.first
        val outgoing = state.second
        val baselineReady = state.third
        if (inklingSnapshot.isEmpty()) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "reassert skipped: empty Inkling snapshot reason=$reason ${debugState()}")
            }
            return
        }
        if (!baselineReady && !isFullScreenSnapshot(inklingSnapshot)) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "reassert deferred: host baseline pending reason=$reason ${debugState()}")
            }
            return
        }
        val version = generation.incrementAndGet()
        Log.i(TAG, "reassert queued reason=$reason version=$version " +
            "inkling=$inklingSnapshot outgoing=$outgoing")
        enqueueSend(PendingSend(clear = false, payload = outgoing,
            logLine = "reassert sending reason=$reason version=$version outgoing=$outgoing"))
    }

    private fun enqueueSend(send: PendingSend) {
        pendingSend.set(send)
        worker.execute {
            val current = pendingSend.getAndSet(null)
            if (current == null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "send coalesced: slot already drained")
                return@execute
            }
            if (!isOwner()) return@execute
            synchronized(rects) {
                lastSentSnapshot.clear()
                lastSentSnapshot.addAll(current.payload.map(::Rect))
            }
            Log.i(TAG, current.logLine)
            try {
                if (current.clear) PenDisableClient.clearDisableAreas(APP_NAME)
                else PenDisableClient.sendDisableAreas(APP_NAME, current.payload)
            } catch (e: Exception) {
                Log.e(TAG, "send failed: ${e.message}")
            }
        }
    }

    fun beginRotationSync(epoch: Long, reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { beginRotationSync(epoch, reason) }
            return
        }
        activeRotationEpoch = epoch
        main.removeCallbacks(hostBootstrapFallback)
        pendingBaselineReleases.remove(ROTATION_SYNC_OWNER)
        fullScreenOwners.remove(HOST_BOOTSTRAP_OWNER)
        viewRotationEpochs.clear()
        Log.i(TAG, "rotation enter epoch=$epoch reason=$reason")
        val changed = fullScreenOwners.add(ROTATION_SYNC_OWNER)
        Log.i(TAG, "fullScreen=true owner=$ROTATION_SYNC_OWNER active=true owners=$fullScreenOwners")
        if (changed) submitSnapshot()
    }

    fun refreshAllForRotation(epoch: Long): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (activeRotationEpoch != epoch) return false
        views.toMap().forEach { (key, reference) ->
            reference.get()?.let { refreshInternal(key, it, epoch) }
                ?: removeIfCurrent(key, reference)
        }
        val ready = views.keys.all { viewRotationEpochs[it] == epoch }
        Log.i(TAG, "views ready epoch=$epoch ready=$ready tracked=${views.keys} " +
            "viewEpochs=$viewRotationEpochs")
        return ready
    }

    fun completeRotationSync(epoch: Long, reason: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (activeRotationEpoch != epoch) return false
        val ready = synchronized(rects) { hostBaselineReady && hostSnapshot.isNotEmpty() }
        if (!ready || !views.keys.all { viewRotationEpochs[it] == epoch }) {
            Log.i(TAG, "rotation commit deferred epoch=$epoch reason=$reason ${debugState()} " +
                "viewEpochs=$viewRotationEpochs")
            return false
        }
        activeRotationEpoch = 0L
        pendingBaselineReleases.remove(ROTATION_SYNC_OWNER)
        val released = fullScreenOwners.remove(ROTATION_SYNC_OWNER)
        Log.i(TAG, "fullScreen=${fullScreenOwners.isNotEmpty()} owner=$ROTATION_SYNC_OWNER " +
            "active=false owners=$fullScreenOwners")
        Log.i(TAG, "rotation commit epoch=$epoch reason=$reason ownerReleased=$released " +
            "outgoing=${synchronized(rects) { mergeWithHost(rects.values.map(::Rect)) }}")
        submitSnapshot()
        return true
    }

    fun cancelRotationSync(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancelRotationSync(reason) }
            return
        }
        if (activeRotationEpoch == 0L && ROTATION_SYNC_OWNER !in fullScreenOwners) return
        val epoch = activeRotationEpoch
        activeRotationEpoch = 0L
        viewRotationEpochs.clear()
        Log.i(TAG, "rotation cancelled epoch=$epoch reason=$reason")
        setFullScreenActive(false, ROTATION_SYNC_OWNER)
    }

    private fun mergeWithHost(inkling: List<Rect>): List<Rect> =
        normalize(hostSnapshot.map(::hostRectToNatural) + inkling, keepClearSentinel = false)

    private fun hostRectToNatural(r: Rect): Rect {
        if (displayRotation == Surface.ROTATION_0) return Rect(r)
        val fitsNatural = r.right <= screenWidth && r.bottom <= screenHeight
        val fitsLogical = r.right <= logicalScreenWidth && r.bottom <= logicalScreenHeight
        if (!fitsLogical && fitsNatural) return Rect(r)
        return toNaturalDisplayRect(r, displayRotation, screenWidth, screenHeight)
    }

    private fun normalize(source: List<Rect>, keepClearSentinel: Boolean): List<Rect> =
        source.asSequence()
            .filter { keepClearSentinel || !isClearSentinel(it) }
            .map(::Rect)
            .distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
            .sortedWith(compareBy<Rect>({ it.top }, { it.left }, { it.bottom }, { it.right }))
            .toList()

    private fun sameRects(a: List<Rect>, b: List<Rect>): Boolean =
        normalize(a, keepClearSentinel = true) == normalize(b, keepClearSentinel = true)

    private fun isFullScreenSnapshot(snapshot: List<Rect>): Boolean =
        snapshot.size == 1 && isScreenCovering(snapshot[0])

    private fun isScreenCovering(rect: Rect): Boolean =
        rect.left <= 0 && rect.top <= 0 && !isClearSentinel(rect) &&
            ((rect.right >= screenWidth && rect.bottom >= screenHeight) ||
                (rect.width() >= 1000 && rect.height() >= 1000))

    private fun isClearSentinel(rect: Rect): Boolean =
        rect.left == 0 && rect.top == 0 && rect.right == 18888 && rect.bottom == 18888

    private val follower = object : Runnable {
        override fun run() {
            if (views.isEmpty()) {
                followerRunning = false
                return
            }
            views.toMap().forEach { (key, reference) ->
                reference.get()?.let { refresh(key, it) } ?: removeIfCurrent(key, reference)
            }
            main.postDelayed(this, FOLLOW_INTERVAL_MS)
        }
    }

    fun track(key: String, view: View) {
        views[key] = WeakReference(view)
        viewRotationEpochs.remove(key)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pendingRemovals.remove(key)?.let(main::removeCallbacks)
            parkedKeys.remove(key)
        } else main.post {
            pendingRemovals.remove(key)?.let(main::removeCallbacks)
            parkedKeys.remove(key)
        }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = refresh(key, v)
            override fun onViewDetachedFromWindow(v: View) = removeIfCurrent(key, v)
        }
        view.addOnAttachStateChangeListener(listener)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> refresh(key, v) }
        view.post { refresh(key, view) }
        if (!followerRunning) {
            followerRunning = true
            main.post(follower)
        }
    }

    fun refresh(key: String, view: View) = refreshInternal(key, view, null)

    private fun refreshInternal(key: String, view: View, rotationEpoch: Long?) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { refreshInternal(key, view, rotationEpoch) }
            return
        }
        if (views[key]?.get() !== view) return
        if (!view.isAttachedToWindow) return
        if (view.visibility != View.VISIBLE) {
            releaseRect(key)
            return
        }
        pendingRemovals.remove(key)?.let(main::removeCallbacks)
        parkedKeys.remove(key)
        val screen = IntArray(2).also(view::getLocationOnScreen)
        val window = IntArray(2).also(view::getLocationInWindow)
        val metrics = view.resources.displayMetrics
        val display = view.display
        val userRotation = try {
            Settings.System.getInt(
                view.context.contentResolver,
                Settings.System.USER_ROTATION,
                Surface.ROTATION_0
            )
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
        val effectiveLogicalWidth = if (displayStateReady) logicalScreenWidth else metrics.widthPixels
        val effectiveLogicalHeight = if (displayStateReady) logicalScreenHeight else metrics.heightPixels
        val logicalLandscape = effectiveLogicalWidth > effectiveLogicalHeight
        val rotation = if (displayStateReady) displayRotation else display?.rotation ?: userRotation
        val effectiveNaturalWidth = if (displayStateReady) screenWidth else metrics.widthPixels
        val effectiveNaturalHeight = if (displayStateReady) screenHeight else metrics.heightPixels
        if (!displayStateReady) {
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
        }
        if (view.width <= 0 || view.height <= 0) return

        val logicalRect = Rect(
            (screen[0] - PAD_PX).coerceAtLeast(0),
            (screen[1] - PAD_PX).coerceAtLeast(0),
            (screen[0] + view.width + PAD_PX).coerceAtMost(effectiveLogicalWidth),
            (screen[1] + view.height + PAD_PX).coerceAtMost(effectiveLogicalHeight)
        )
        if (logicalRect.isEmpty) return
        val drawPathRect = toNaturalDisplayRect(
            logicalRect,
            rotation,
            effectiveNaturalWidth,
            effectiveNaturalHeight
        )
        if (drawPathRect.isEmpty) return
        if (views[key]?.get() !== view) return
        val changed = synchronized(rects) {
            if (rects[key] == drawPathRect) false else {
                rects[key] = Rect(drawPathRect)
                true
            }
        }
        if (rotationEpoch != null && activeRotationEpoch == rotationEpoch) {
            viewRotationEpochs[key] = rotationEpoch
        }
        if (!changed) return
        Log.i(TAG, "refresh key=$key screen=${screen[0]},${screen[1]} " +
            "window=${window[0]},${window[1]} size=${view.width}x${view.height} " +
            "logicalDisplay=${effectiveLogicalWidth}x${effectiveLogicalHeight} " +
            "naturalDisplay=${effectiveNaturalWidth}x${effectiveNaturalHeight} " +
            "logicalLandscape=$logicalLandscape " +
            "userRotation=$userRotation displayRotation=${display?.rotation} " +
            "drawPathRotation=$rotation logicalRect=$logicalRect drawPathRect=$drawPathRect")
        submitSnapshot()
    }

    private fun toNaturalDisplayRect(
        logical: Rect,
        rotation: Int,
        naturalWidth: Int,
        naturalHeight: Int
    ): Rect {
        val transformed = when (rotation) {
            Surface.ROTATION_90 -> Rect(
                naturalWidth - logical.bottom,
                logical.left,
                naturalWidth - logical.top,
                logical.right
            )
            Surface.ROTATION_180 -> Rect(
                naturalWidth - logical.right,
                naturalHeight - logical.bottom,
                naturalWidth - logical.left,
                naturalHeight - logical.top
            )
            Surface.ROTATION_270 -> Rect(
                logical.top,
                naturalHeight - logical.right,
                logical.bottom,
                naturalHeight - logical.left
            )
            else -> Rect(logical)
        }
        transformed.left = transformed.left.coerceIn(0, naturalWidth)
        transformed.right = transformed.right.coerceIn(0, naturalWidth)
        transformed.top = transformed.top.coerceIn(0, naturalHeight)
        transformed.bottom = transformed.bottom.coerceIn(0, naturalHeight)
        return transformed
    }

    fun remove(key: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { remove(key) }
            return
        }
        views.remove(key)
        viewRotationEpochs.remove(key)
        releaseRect(key)
    }

    private fun releaseRect(key: String) {
        if (!synchronized(rects) { rects.containsKey(key) }) return
        if (parkActive) {
            pendingRemovals.remove(key)?.let(main::removeCallbacks)
            parkedKeys.add(key)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "remove key=$key parked")
            return
        }
        if (pendingRemovals.containsKey(key)) return
        val r = Runnable {
            pendingRemovals.remove(key)
            val changed = synchronized(rects) { rects.remove(key) != null }
            if (!changed) return@Runnable
            Log.i(TAG, "remove key=$key")
            submitSnapshot()
        }
        pendingRemovals[key] = r
        main.postDelayed(r, REMOVE_LINGER_MS)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "remove key=$key linger=${REMOVE_LINGER_MS}ms")
    }

    fun beginPark(reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { beginPark(reason) }
            return
        }
        if (!parkActive && BuildConfig.ENABLE_DEBUG) Log.i(TAG, "park begin reason=$reason")
        parkActive = true
        for ((key, r) in pendingRemovals) { main.removeCallbacks(r); parkedKeys.add(key) }
        pendingRemovals.clear()
    }

    fun endPark(reason: String, flush: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { endPark(reason, flush) }
            return
        }
        if (!parkActive && parkedKeys.isEmpty()) return
        parkActive = false
        val released = parkedKeys.filter { flush || views[it]?.get() == null }
        parkedKeys.clear()
        if (released.isEmpty()) return
        val changed = synchronized(rects) { released.count { rects.remove(it) != null } } > 0
        Log.i(TAG, "park end reason=$reason flush=$flush released=$released")
        if (changed) submitSnapshot()
    }

    private fun removeIfCurrent(key: String, view: View) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { removeIfCurrent(key, view) }
            return
        }
        if (views[key]?.get() !== view) return
        remove(key)
    }

    private fun removeIfCurrent(key: String, reference: WeakReference<View>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { removeIfCurrent(key, reference) }
            return
        }
        if (views[key] !== reference) return
        remove(key)
    }

    fun setFullScreenActive(active: Boolean, owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { setFullScreenActive(active, owner) }
            return
        }
        if (!active) pendingBaselineReleases.remove(owner)
        val changed = if (active) fullScreenOwners.add(owner) else fullScreenOwners.remove(owner)
        if (!changed) return
        Log.i(TAG, "fullScreen=${fullScreenOwners.isNotEmpty()} owner=$owner active=$active owners=$fullScreenOwners")
        submitSnapshot()
    }

    fun releaseFullScreenAfterHostBaseline(owner: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { releaseFullScreenAfterHostBaseline(owner) }
            return
        }
        val hasConcreteBaseline = synchronized(rects) { hostSnapshot.isNotEmpty() }
        if (hasConcreteBaseline) {
            setFullScreenActive(false, owner)
        } else {
            pendingBaselineReleases.add(owner)
            Log.i(TAG, "fullScreen release deferred owner=$owner awaiting host baseline")
        }
    }

    private fun submitSnapshot() {
        val state = synchronized(rects) {
            val inkling = if (fullScreenOwners.isNotEmpty()) {
                listOf(Rect(0, 0, screenWidth, screenHeight))
            } else {
                normalize(rects.values.map(::Rect), keepClearSentinel = false)
            }
            lastSnapshot.clear()
            lastSnapshot.addAll(inkling.map(::Rect))
            Pair(
                Triple(inkling, mergeWithHost(inkling), hostBaselineReady),
                lastSentSnapshot.any(::isScreenCovering)
            )
        }
        val inklingSnapshot = state.first.first
        val outgoing = state.first.second
        val baselineReady = state.first.third
        val leavingFullScreen = !isFullScreenSnapshot(inklingSnapshot) && state.second
        if (!baselineReady && !isFullScreenSnapshot(inklingSnapshot) && !leavingFullScreen) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "snapshot deferred: host baseline pending " +
                    "inkling=$inklingSnapshot outgoing=$outgoing ${debugState()}")
            }
            return
        }
        val version = generation.incrementAndGet()
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "snapshot queued version=$version inkling=$inklingSnapshot " +
                "outgoing=$outgoing leavingFullScreen=$leavingFullScreen")
        }
        val forceWritableClear = leavingFullScreen && !baselineReady
        val clear = forceWritableClear || outgoing.isEmpty()
        val payload = if (clear) listOf(Rect(0, 0, 18888, 18888)) else outgoing
        val mode = when {
            forceWritableClear -> "exit-full-screen-clear"
            outgoing.isEmpty() -> "clear"
            inklingSnapshot.isEmpty() -> "host-only"
            else -> "merged"
        }
        enqueueSend(PendingSend(clear = clear, payload = payload,
            logLine = "snapshot sending version=$version mode=$mode " +
                "inkling=$inklingSnapshot outgoing=$outgoing"))
    }
}
