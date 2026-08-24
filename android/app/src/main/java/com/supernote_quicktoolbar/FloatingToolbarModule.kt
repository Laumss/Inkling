package com.supernote_quicktoolbar
import com.supernote_quicktoolbar.panels.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.bubbles.*

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingToolbarModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), com.supernote_quicktoolbar.ui_common.ToolbarHost {

    override fun getName() = "FloatingToolbar"

    override val screenW: Int get() = screenWidth
    override val screenH: Int get() = screenHeight

    override fun getConstants(): MutableMap<String, Any> =
        mutableMapOf("ENABLE_DEBUG" to BuildConfig.ENABLE_DEBUG)

    companion object {
        private const val TAG = "FloatingToolbar"
        private const val PLUGIN_FILE_READ_PERMISSION = "plugin.permission.FILE:READ"
        private const val PLUGIN_FILE_WRITE_PERMISSION = "plugin.permission.FILE:WRITE"
        private const val PLUGIN_FILE_DELETE_PERMISSION = "plugin.permission.FILE:DELETE"
        private const val PLUGIN_INTERNET_PERMISSION = "plugin.permission.INTERNET"
        private const val SCREENSHOT_STATE_PREFS = "screenshot_state"
        private const val STITCH_SESSION_ACTIVE_KEY = "stitch_session_active"

        private val RETRY_DELAYS_MS = longArrayOf(50, 250, 750, 1500, 2500)

        @Volatile @JvmStatic
        private var windowManager: WindowManager? = null
        @Volatile @JvmStatic
        private var rootView: View? = null
        @Volatile @JvmStatic
        private var layoutParams: WindowManager.LayoutParams? = null

        @Volatile @JvmStatic
        private var expandedRoot: LinearLayout? = null
        @Volatile @JvmStatic
        private var toolContainer: LinearLayout? = null

        @Volatile @JvmStatic
        private var collapsedRoot: View? = null

        @Volatile @JvmStatic
        private var collapsed: Boolean = false
        @Volatile @JvmStatic
        private var dockSide: String = "left"
        @Volatile @JvmStatic
        private var tools: MutableList<ToolItem> = mutableListOf()

        private val LATCHING_ACTIONS: Set<String> = setOf(
            "insert_text", "text_recv_nospacing", "text_recv_paragraph", "voice_transcribe"
        )

        private fun defaultToolNameKey(id: String): String = when (id) {
            "insert_image" -> "config_tool_image"
            "insert_doc_screenshot" -> "config_tool_doc"
            "insert_text" -> "config_tool_text"
            "send_ai" -> "config_tool_lasso_ai"
            "insert_link" -> "config_tool_link"
            "voice_transcribe" -> "config_tool_voice"
            "invert_ink" -> "config_tool_palette"
            else -> ""
        }
        private const val DEFAULT_TOOLS_JSON =
            "[{\"id\":\"insert_image\",\"action\":\"insert_image\",\"icon\":\"Im\"}," +
            "{\"id\":\"insert_doc_screenshot\",\"action\":\"insert_doc_screenshot\",\"icon\":\"Sc\"}," +
            "{\"id\":\"insert_text\",\"action\":\"insert_text\",\"icon\":\"Tx\"}," +
            "{\"id\":\"send_ai\",\"action\":\"lasso_smart_send\",\"icon\":\"AI\"}," +
            "{\"id\":\"insert_link\",\"action\":\"insert_link\",\"icon\":\"Lk\"}," +
            "{\"id\":\"voice_transcribe\",\"action\":\"voice_transcribe\",\"icon\":\"Vc\"}]"

        internal const val AI_RECEIVE_TOOL_ID = "voice_transcribe"
        internal const val PALETTE_TOOL_ID = "invert_ink"

        private fun isAiReceiveTool(id: String, action: String) =
            id == AI_RECEIVE_TOOL_ID || action == AI_RECEIVE_TOOL_ID

        private fun isPaletteTool(id: String, action: String) =
            id == PALETTE_TOOL_ID || action == PALETTE_TOOL_ID

        internal fun isDebugToolCatalogVisible(id: String, action: String = id): Boolean =
            BuildConfig.ENABLE_DEBUG || !isAiReceiveTool(id, action)

        internal fun <T> sanitizeDebugTools(
            items: List<T>,
            idOf: (T) -> String,
            actionOf: (T) -> String = idOf
        ): List<T> {
            if (BuildConfig.ENABLE_DEBUG) return items
            val hasReceive = items.any { isAiReceiveTool(idOf(it), actionOf(it)) }
            val hasPalette = items.any { isPaletteTool(idOf(it), actionOf(it)) }
            val dropPalette = hasReceive && hasPalette
            return items.filter { item ->
                val id = idOf(item)
                val action = actionOf(item)
                when {
                    isAiReceiveTool(id, action) -> false
                    dropPalette && isPaletteTool(id, action) -> false
                    else -> true
                }
            }
        }

        @JvmStatic
        private val activeModeIds: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf())

        @Volatile @JvmStatic
        private var pendingOpenMain: Boolean = false

        @Volatile @JvmStatic
        private var pendingScreen: String = ""

        @Volatile @JvmStatic
        private var selfShowPluginViewAt = 0L
        private const val SELF_SHOW_GRACE_MS = 3_000L

        @JvmStatic
        private fun isSelfInitiatedHostShow(): Boolean =
            android.os.SystemClock.elapsedRealtime() - selfShowPluginViewAt < SELF_SHOW_GRACE_MS

        @Volatile @JvmStatic
        private var pendingShow: Boolean = false

        @Volatile @JvmStatic
        private var insertPluginViewClosed: Boolean = false

        @Volatile @JvmStatic
        private var startX = 0
        @Volatile @JvmStatic
        private var startY = 0
        @Volatile @JvmStatic
        private var startRawX = 0f
        @Volatile @JvmStatic
        private var startRawY = 0f
        @Volatile @JvmStatic
        private var isDragging = false
        @Volatile @JvmStatic
        private var longPressTriggered = false

        private const val AUTO_COLLAPSE_MS = 9000L
        private const val DOCK_FLUSH_TOLERANCE = 6

        @JvmStatic
        internal var screenWidth = 1404
        @JvmStatic
        internal var screenHeight = 1872

        @Volatile @JvmStatic
        private var configCallbackRegistered = false
        @Volatile @JvmStatic
        private var displayListenerRegistered = false
        @Volatile @JvmStatic
        private var lastDisplayRotation = -1

        @Volatile @JvmStatic
        var lastNotePath: String = ""
        @Volatile @JvmStatic
        var lastPageNum: Int = 0

        @Volatile @JvmStatic
        private var foregroundMonitorRunning = false
        @JvmStatic
        private var pendingRestoreRunnable: Runnable? = null
        @Volatile @JvmStatic
        private var isInNoteApp = true

        @Volatile @JvmStatic
        private var lastForegroundKey: String? = null

        /**
         * elapsedRealtime when the note app last RETURNED to the foreground.
         * 0 = "long ago" (monitor start assumes we're already in the note).
         * TextInserter reads this synchronously right before insertText so a
         * fresh page reload can never race an insert (event delivery through
         * the RN bridge is too slow to be the only gate).
         */
        @Volatile @JvmStatic
        private var noteForegroundSinceMs = 0L

        @JvmStatic
        fun isInNoteApp(): Boolean = isInNoteApp

        @Volatile @JvmStatic
        private var isPluginHostWindowShowing = false

        @JvmStatic
        private var windowStateMonitor: WindowStateMonitor? = null

        @Volatile @JvmStatic
        private var toolbarRestorePending = false

        @Volatile @JvmStatic
        private var terminateAfterPluginMenuClose = false

        @Volatile @JvmStatic
        private var hostButtonChannelBroken = false

        @JvmStatic
        private val subviewListener = object : SubviewLogMonitor.Listener {
            override fun onSubviewOpened(generation: Int) = onNoteSubviewChanged(true, generation)
            override fun onSubviewClosed(generation: Int) = onNoteSubviewChanged(false, generation)
            override fun onToolbarMenuChanged(open: Boolean, pluginListMenu: Boolean, generation: Int) =
                handleToolbarMenuChanged(open, pluginListMenu, generation)
            override fun onLassoMenuChanged(open: Boolean, generation: Int) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                lassoWritableResetPending = false
                monitorHandler.post {
                    if (!SubviewLogMonitor.isCurrentGeneration(generation)) return@post
                    monitorHandler.removeCallbacks(lassoFullScreenReleaseRunnable)
                    if (open) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host lasso menu full-screen=true")
                        FloatingPenGuard.setFullScreenActive(true, "host-lasso-menu")
                        endInsertImageGuard()
                    } else {
                        if (BuildConfig.ENABLE_DEBUG) {
                            Log.i(TAG, "host lasso menu close: schedule full-screen release " +
                                "delayMs=$LASSO_RELEASE_GRACE_MS")
                        }
                        monitorHandler.postDelayed(
                            lassoFullScreenReleaseRunnable,
                            LASSO_RELEASE_GRACE_MS
                        )
                        endInsertImageGuard()
                        scheduleRestore(RESTORE_DELAY_NORMAL, "lasso menu closed")
                    }
                }
            }
            override fun onNativePenAreasChanged(rects: List<Rect>, generation: Int) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                val isWritableReset = rects.size == 1 && rects[0].let {
                    it.left == 0 && it.top == 0 && it.right == 18888 && it.bottom == 18888
                }
                val isFullScreenSnapshot = rects.size == 1 && rects[0].let {
                    !isWritableReset && it.left <= 0 && it.top <= 0 &&
                        it.width() >= 1000 && it.height() >= 1000
                }
                if (isWritableReset && SubviewLogMonitor.isLassoMenuOpen()) {
                    lassoWritableResetPending = true
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "lasso close candidate: writable reset observed")
                    }
                } else if (lassoWritableResetPending && !isFullScreenSnapshot) {
                    lassoWritableResetPending = false
                    SubviewLogMonitor.closeLassoMenuFromFallback("writable reset completed")
                }
                val rotationWaitingForBaseline = rotationEpoch > 0L &&
                    rotationUserConfirmed && rotationDialogDismissed
                val rotationPostDismissCandidate = rotationEpoch > 0L &&
                    (rotationDialogDismissed || rotationDialogShown) && !isWritableReset &&
                    !isFullScreenSnapshot && rects.isNotEmpty()
                if (rotationPostDismissCandidate) {
                    rotationPostDismissBaseline = rects.map(::Rect)
                }
                val transientHostUi = SubviewLogMonitor.isToolbarMenuOpen() ||
                    SubviewLogMonitor.isLassoMenuOpen() ||
                    SubviewLogMonitor.isSubviewOpen() ||
                    SubviewLogMonitor.isOwnedRotationDialogOpen()
                val accepted = FloatingPenGuard.updateHostPenAreas(
                    incoming = rects,
                    commitBaseline = !transientHostUi &&
                        (rotationEpoch == 0L || rotationWaitingForBaseline)
                )
                val guardActive = FloatingPenGuard.hasActiveRects()
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "host native pen snapshot count=${rects.size} rects=$rects " +
                        "accepted=$accepted transientUi=$transientHostUi " +
                        "writableReset=$isWritableReset guardActive=$guardActive " +
                        "guard=${FloatingPenGuard.debugState()}")
                }
                if (rotationWaitingForBaseline && accepted && !isWritableReset &&
                    !isFullScreenSnapshot && rects.isNotEmpty()) {
                    rotationBaselineReady = false
                    monitorHandler.removeCallbacks(rotationBaselineStableRunnable)
                    monitorHandler.postDelayed(
                        rotationBaselineStableRunnable,
                        ROTATION_BASELINE_STABLE_MS
                    )
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "rotation host baseline candidate epoch=$rotationEpoch " +
                            "stableDelayMs=$ROTATION_BASELINE_STABLE_MS rects=$rects")
                    }
                } else if (accepted && guardActive && !isWritableReset && rotationEpoch == 0L) {
                    scheduleGuardReassert(
                        reason = "host native pen snapshot",
                        delayMs = HOST_SNAPSHOT_REASSERT_DELAY_MS
                    )
                } else if (accepted && isWritableReset && rotationEpoch == 0L) {
                    monitorHandler.removeCallbacks(guardReassertRunnable)
                    if (guardActive) {
                        scheduleGuardReassert(
                            reason = "writable reset fallback",
                            delayMs = GUARD_REASSERT_DELAY_MS
                        )
                    }
                    if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "writable reset: cancelled stale task, awaiting next host snapshot " +
                            "fallbackScheduled=$guardActive")
                    }
                }

                if (accepted && rotationEpoch != 0L) {
                    FloatingPenGuard.reassert("rotation full-screen hold")
                }

                if (insertImageFlowActive && !SubviewLogMonitor.isLassoMenuOpen()) {
                    if (isWritableReset) {
                        insertPlacementResetSeen = true
                    } else if (insertPlacementResetSeen && accepted &&
                        !isFullScreenSnapshot && rects.isNotEmpty()) {
                        monitorHandler.post {
                            if (!insertImageFlowActive) return@post
                            if (BuildConfig.ENABLE_DEBUG) {
                                Log.i(TAG, "insert placement settled (reset→baseline fallback)")
                            }
                            endInsertImageGuard()
                            scheduleRestore(RESTORE_DELAY_NORMAL, "insert placement settled")
                        }
                    }
                }
            }

            override fun onHostFullScreenDisableArea(generation: Int) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                if (insertImageFlowActive) {
                    monitorHandler.removeCallbacks(insertGuardTimeoutRunnable)
                    monitorHandler.postDelayed(insertGuardTimeoutRunnable, INSERT_PLACEMENT_TIMEOUT_MS)
                }
                if (rotationEpoch == 0L) scheduleGuardReassert("host pen table rebuild")
                else FloatingPenGuard.reassert("rotation full-screen hold (host rebuild)")
            }

            override fun onOwnedRotationDialogChanged(
                open: Boolean,
                token: Long,
                generation: Int
            ) {
                if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
                if (token != rotationEpoch || rotationEpoch == 0L) return
                rotationDialogShown = open
                if (!open) {
                    rotationDialogArmed = false
                    rotationDialogDismissed = true
                }
                monitorHandler.post {
                    if (token != rotationEpoch || rotationEpoch == 0L) return@post
                    if (open) {
                        Log.i(TAG, "owned dialog shown epoch=$token")
                    } else {
                        Log.i(TAG, "owned dialog dismissed epoch=$token confirmed=$rotationUserConfirmed")
                        if (rotationUserConfirmed) {
                            consumeRotationBaselineCandidate("dialog dismissed")
                        } else {
                            monitorHandler.postDelayed(
                                rotationDialogRetryRunnable,
                                ROTATION_DIALOG_RETRY_MS
                            )
                        }
                    }
                }
            }
        }

        @Volatile
        private var pendingGuardReassertReason = "unknown"

        @Volatile
        private var lassoWritableResetPending = false

        @JvmStatic
        private val lassoFullScreenReleaseRunnable = Runnable {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "host lasso menu full-screen release after grace")
            }
            FloatingPenGuard.setFullScreenActive(false, "host-lasso-menu")
        }

        @Volatile @JvmStatic
        private var insertImageFlowActive = false

        @Volatile @JvmStatic
        private var insertPlacementResetSeen = false

        @JvmStatic
        private val insertGuardTimeoutRunnable = Runnable {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "insert-image guard timeout release")
            FloatingPenGuard.setFullScreenActive(false, "insert-image")
            if (insertImageFlowActive) {
                insertImageFlowActive = false
                scheduleRestore(RESTORE_DELAY_NORMAL, "insert-image timeout")
            }
        }

        @JvmStatic
        internal fun beginInsertImageGuard() {
            FloatingPenGuard.setFullScreenActive(true, "insert-image")
            insertImageFlowActive = true
            insertPlacementResetSeen = false
            monitorHandler.removeCallbacks(insertGuardTimeoutRunnable)
            monitorHandler.postDelayed(insertGuardTimeoutRunnable, INSERT_GUARD_TIMEOUT_MS)
        }

        @JvmStatic
        private fun endInsertImageGuard() {
            if (!insertImageFlowActive) return
            insertImageFlowActive = false
            insertPlacementResetSeen = false
            monitorHandler.removeCallbacks(insertGuardTimeoutRunnable)
            FloatingPenGuard.setFullScreenActive(false, "insert-image")
        }

        @JvmStatic
        private fun scheduleGuardReassert(
            reason: String,
            allowEmptyAtSchedule: Boolean = false,
            delayMs: Long = GUARD_REASSERT_DELAY_MS
        ) {
            val active = FloatingPenGuard.hasActiveRects()
            if (!active && !allowEmptyAtSchedule) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "guard reassert not scheduled: no active rects reason=$reason " +
                        "guard=${FloatingPenGuard.debugState()}")
                }
                return
            }
            pendingGuardReassertReason = reason
            monitorHandler.removeCallbacks(guardReassertRunnable)
            monitorHandler.postDelayed(guardReassertRunnable, delayMs)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "guard reassert scheduled reason=$reason active=$active " +
                    "allowEmpty=$allowEmptyAtSchedule delayMs=$delayMs " +
                    "guard=${FloatingPenGuard.debugState()}")
            }
        }

        @JvmStatic
        private val guardReassertRunnable = Runnable {
            val reason = pendingGuardReassertReason
            val active = FloatingPenGuard.hasActiveRects()
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "guard reassert fired reason=$reason active=$active " +
                    "guard=${FloatingPenGuard.debugState()}")
            }
            FloatingPenGuard.reassert(reason)
        }

        @JvmStatic
        private val guardReassertFollowupRunnable = Runnable {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "guard reassert followup fired guard=${FloatingPenGuard.debugState()}")
            }
            FloatingPenGuard.reassert("foreground change followup")
        }

        @JvmStatic
        private fun scheduleForegroundGuardReassert(fgKey: String) {
            if (!FloatingPenGuard.hasActiveRects()) return
            scheduleGuardReassert(
                reason = "foreground changed: $fgKey",
                delayMs = HOST_SNAPSHOT_REASSERT_DELAY_MS
            )
            monitorHandler.removeCallbacks(guardReassertFollowupRunnable)
            monitorHandler.postDelayed(guardReassertFollowupRunnable, FOREGROUND_REASSERT_FOLLOWUP_MS)
        }

        @JvmStatic
        fun onNoteSubviewChanged(open: Boolean, generation: Int) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onNoteSubviewChanged: open=$open gen=$generation")
            if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
            val inst = currentInstance ?: return
            val applyState = Runnable {
                if (!SubviewLogMonitor.isCurrentGeneration(generation) ||
                    SubviewLogMonitor.isSubviewOpen() != open) return@Runnable
                if (open) {
                    markToolbarForRestore()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "note subview opened, hiding overlays (toolbarRestorePending=$toolbarRestorePending)")
                    SubviewLogMonitor.closeLassoMenuFromFallback("subview opened")
                    FloatingPenGuard.setFullScreenActive(true, "note-subview")
                    if (rootView != null) inst.removeAll()
                    inst.suspendAllNativePanels()
                    FloatingBubbleModule.hideStatic()
                    AiBubbleModule.hideStatic()
                    PaletteBubbleModule.hideStatic()
                    ScreenshotBubble.hideForSettings()
                    StickyNotes.hideTemp()
                } else {
                    FloatingPenGuard.setFullScreenActive(false, "note-subview")

                    if (!isPluginHostWindowShowing && ScreenshotBubble.hiddenBySettings) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "note subviews closed, restoring ScreenshotBubble independently")
                        ScreenshotBubble.reshowIfHiddenBySettings()
                    }
                    if (!isPluginHostWindowShowing) StickyNotes.reshowIfTemp()

                    scheduleRestore(RESTORE_DELAY_NORMAL, "note subview closed")
                    scheduleGuardReassert(
                        reason = "note subview restored",
                        allowEmptyAtSchedule = true
                    )
                }
            }
            if (open) monitorHandler.post(applyState)
            else monitorHandler.postDelayed(applyState, RESTORE_DELAY_NORMAL)
        }

        @JvmStatic
        private fun handleToolbarMenuChanged(open: Boolean, pluginListMenu: Boolean, generation: Int) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleToolbarMenuChanged: open=$open pluginList=$pluginListMenu gen=$generation isCurrent=${SubviewLogMonitor.isCurrentGeneration(generation)}")
            if (!SubviewLogMonitor.isCurrentGeneration(generation)) return
            if (open) {
                monitorHandler.post {
                    if (!SubviewLogMonitor.isCurrentGeneration(generation) ||
                        !SubviewLogMonitor.isToolbarMenuOpen()) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu open: state stale, skip")
                        return@post
                    }
                    val inst = currentInstance ?: return@post
                    val toolbarWasRunning = rootView != null || toolbarRestorePending
                    if (pluginListMenu && toolbarWasRunning && hostButtonChannelBroken) {
                        terminateAfterPluginMenuClose = true
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "plugin list menu open: arm Inkling termination on close (button channel broken)")
                    }
                    markToolbarForRestore()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu open: hiding overlays toolbarRestorePending=$toolbarRestorePending")
                    FloatingPenGuard.setFullScreenActive(true, "host-toolbar-menu")
                    if (rootView != null) inst.removeAll()
                    FloatingBubbleModule.hideStatic()
                    AiBubbleModule.hideStatic()
                    PaletteBubbleModule.hideStatic()
                }
            } else {
                monitorHandler.postDelayed({
                    if (!SubviewLogMonitor.isCurrentGeneration(generation)) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu close: generation stale, skip")
                        return@postDelayed
                    }
                    if (SubviewLogMonitor.isToolbarMenuOpen()) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toolbar menu close: still open (SubviewLogMonitor), skip restore")
                        return@postDelayed
                    }
                    val inst = currentInstance ?: return@postDelayed
                    if (terminateAfterPluginMenuClose) {
                        terminateAfterPluginMenuClose = false
                        toolbarRestorePending = false
                        cancelScheduledRestore()
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "plugin list menu close: terminating Inkling")
                        FloatingPenGuard.releaseFullScreenAfterHostBaseline("host-toolbar-menu")
                        inst.destroyAll()
                        return@postDelayed
                    }
                    if (SubviewLogMonitor.isSubviewOpen()) {
                        FloatingPenGuard.setFullScreenActive(false, "host-toolbar-menu")
                    } else {
                        FloatingPenGuard.releaseFullScreenAfterHostBaseline("host-toolbar-menu")
                    }
                    scheduleRestore(RESTORE_DELAY_SHORT, "toolbar menu closed")
                }, RESTORE_DELAY_LONG)
            }
        }

        @JvmStatic
        private fun onNativePanelActiveEdge(anyShowing: Boolean) {
            val inst = currentInstance ?: return
            if (anyShowing) {
                FloatingBubbleModule.hideStatic()
                AiBubbleModule.hideStatic()
                PaletteBubbleModule.hideStatic()
            } else {
                monitorHandler.postDelayed({
                    if (ToolRegistry.anyPanelShowing()) return@postDelayed
                    evaluateRestore("native panel closed")
                }, RESTORE_DELAY_NORMAL)
            }
        }

        @JvmStatic
        internal fun anyNativeOverlayOwnsScreen(): Boolean =
            ToolRegistry.anyPanelShowing() || penLassoOverlay != null

        private val configFallbackRunnable = Runnable {
            val inst = currentInstance ?: return@Runnable
            if (isSelfInitiatedHostShow() || pendingScreen.isNotEmpty() ||
                SendPanel.currentInstance?.isShowing == true) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG,
                    "configFallback: plugin-purpose session active (pendingScreen=$pendingScreen), skip")
                return@Runnable
            }
            val lastShowType = readLastShowType(inst.reactApplicationContext)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "configFallback: lastShowType=$lastShowType")
            if (lastShowType != 1) return@Runnable
            if (ConfigPanel.currentInstance?.isShowing == true) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "configFallback: ConfigPanel already showing, keep PluginHost session open")
                return@Runnable
            }
            if (!BuildConfig.ENABLE_DEBUG) return@Runnable
            Log.i(TAG, "configFallback: lastShowType=1 → opening ConfigPanel from native fallback")
            inst.collapsePluginHostContainerForNativePanel()
            inst.removeAll()
            inst.emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "config") })
            ConfigPanel.getInstance(inst.reactApplicationContext, inst).show()
        }

        @JvmStatic
        private fun readLastShowType(ctx: ReactApplicationContext): Int {
            return try {
                val pm = ctx.catalystInstance?.getNativeModule("NativePluginManager") ?: return -1
                val paField = findDeclaredFieldStatic(pm.javaClass, "pluginApp") ?: return -1
                paField.isAccessible = true
                val pa = paField.get(pm) ?: return -1
                val stField = findDeclaredFieldStatic(pa.javaClass, "lastShowType") ?: return -1
                stField.isAccessible = true
                (stField.get(pa) as? Number)?.toInt() ?: -1
            } catch (_: Exception) { -1 }
        }

        @JvmStatic
        private fun findDeclaredFieldStatic(cls: Class<*>, name: String): java.lang.reflect.Field? {
            var c: Class<*>? = cls
            while (c != null) {
                c.declaredFields.firstOrNull { it.name == name }?.let { return it }
                c = c.superclass
            }
            return null
        }

        @JvmStatic
        fun onPluginHostStateChanged(showing: Boolean) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onPluginHostStateChanged: showing=$showing")
            isPluginHostWindowShowing = showing

            val inst = currentInstance ?: return
            if (showing) {
                val selfInitiated = isSelfInitiatedHostShow() || pendingScreen.isNotEmpty()
                if (rootView != null) {
                    markToolbarForRestore()
                    monitorHandler.post {
                        inst.removeAll()
                        if (!selfInitiated) inst.suspendAllNativePanels()
                        FloatingBubbleModule.hideStatic()
                        AiBubbleModule.hideStatic()
                        PaletteBubbleModule.hideStatic()
                    }
                }
                monitorHandler.removeCallbacks(configFallbackRunnable)
                if (!selfInitiated) {
                    monitorHandler.postDelayed(configFallbackRunnable, 200)
                } else if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "onPluginHostStateChanged: self-initiated show (pendingScreen=$pendingScreen), skip panel suspend + config fallback")
                }
            } else {
                monitorHandler.removeCallbacks(configFallbackRunnable)
                scheduleRestore(RESTORE_DELAY_SHORT, "PluginHost hidden")
            }
        }

        @JvmStatic
        private val deniedPluginPermissions: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf())

        private const val NOTE_PACKAGE = "com.ratta.supernote.note"
        private const val NOTE_INSIDE_PAGES_ACTIVITY = "com.ratta.supernote.note.view.NoteInsidePagesActivity"
        private const val PLUGIN_PACKAGE = "com.ratta.supernote.pluginhost"
        private const val DOC_PACKAGE = "com.supernote.document"
        private const val WEREAD_PACKAGE = "com.tencent.weread.eink"

        private const val SETTINGS_PACKAGE = "com.ratta.settings"
        private const val SETTINGS_ACTIVITY = "com.ratta.settings.SettingsActivity"
        private const val SETTINGS_PLUGIN_DETAIL_ACTION = "com.ratta.settings.application.PluginDetailFragment"
        private const val PLUGIN_ID = "Inkling"
        private const val MONITOR_INTERVAL_MS = 800L

        private const val RESTORE_DELAY_SHORT = 150L
        private const val RESTORE_DELAY_NORMAL = 350L
        private const val RESTORE_DELAY_LONG = 700L

        private const val GUARD_REASSERT_DELAY_MS = 600L
        private const val HOST_SNAPSHOT_REASSERT_DELAY_MS = 120L
        private const val FOREGROUND_REASSERT_FOLLOWUP_MS = 1000L
        private const val LASSO_RELEASE_GRACE_MS = 300L
        private const val INSERT_GUARD_TIMEOUT_MS = 2500L
        private const val INSERT_PLACEMENT_TIMEOUT_MS = 30_000L
        private const val ROTATION_DISPLAY_SETTLE_MS = 250L
        private const val ROTATION_BASELINE_STABLE_MS = 180L
        private const val ROTATION_DIALOG_RETRY_MS = 800L

        @Volatile @JvmStatic
        private var titleClipFilled: BooleanArray = BooleanArray(6) { false }

        @Volatile @JvmStatic
        private var clipPage: Int = 0

        @Volatile @JvmStatic
        private var orientation: String = "vertical"
        private const val ORIENTATION_STORE_KEY = "preset_97"

        @Volatile @JvmStatic private var stickyX: Int = -1
        @Volatile @JvmStatic private var stickyY: Int = -1
        @Volatile @JvmStatic private var preDockX: Int = -1
        @Volatile @JvmStatic private var preDockY: Int = -1
        @Volatile @JvmStatic private var positionLoaded: Boolean = false
        private const val POSITION_STORE_KEY_X = "toolbar_pos_x"
        private const val POSITION_STORE_KEY_Y = "toolbar_pos_y"

        @Volatile @JvmStatic
        private var wasBubbleVisible = false

        @Volatile @JvmStatic
        private var insertNextChainActive = false

        @Volatile @JvmStatic
        private var docScreenshotRoutePending = false

        @Volatile @JvmStatic
        private var stitchCommitPending = false

        @Volatile @JvmStatic
        private var screenshotCapturePending = false

        @Volatile @JvmStatic
        private var knownStitchSessionActive = false

        @Volatile @JvmStatic
        private var penLassoOverlay: PenLassoOverlay? = null

        @Volatile @JvmStatic
        private var rotationEpoch = 0L
        @Volatile @JvmStatic
        private var rotationStartForegroundKey: String? = null
        @Volatile @JvmStatic
        private var rotationDialogArmed = false
        @Volatile @JvmStatic
        private var rotationDialogShown = false
        @Volatile @JvmStatic
        private var rotationUserConfirmed = false
        @Volatile @JvmStatic
        private var rotationDialogDismissed = false
        @Volatile @JvmStatic
        private var rotationViewsReady = false
        @Volatile @JvmStatic
        private var rotationBaselineReady = false
        @Volatile @JvmStatic
        private var rotationPostDismissBaseline: List<Rect> = emptyList()

        @JvmStatic
        private val rotationDisplaySettledRunnable = Runnable {
            currentInstance?.onRotationDisplaySettled(rotationEpoch)
        }

        @JvmStatic
        private val rotationBaselineStableRunnable = Runnable {
            currentInstance?.onRotationBaselineStable(rotationEpoch)
        }

        @JvmStatic
        private fun consumeRotationBaselineCandidate(reason: String) {
            if (rotationEpoch == 0L || !rotationUserConfirmed || !rotationDialogDismissed) return
            val cached = rotationPostDismissBaseline
            if (cached.isEmpty()) {
                Log.i(TAG, "rotation baseline candidate empty reason=$reason epoch=$rotationEpoch")
                return
            }
            val accepted = FloatingPenGuard.updateHostPenAreas(cached, commitBaseline = true)
            if (accepted) {
                rotationBaselineReady = false
                monitorHandler.removeCallbacks(rotationBaselineStableRunnable)
                monitorHandler.postDelayed(rotationBaselineStableRunnable, ROTATION_BASELINE_STABLE_MS)
                Log.i(TAG, "rotation cached baseline consumed reason=$reason " +
                    "epoch=$rotationEpoch rects=$cached")
            }
        }

        @JvmStatic
        private val rotationDialogRetryRunnable = Runnable {
            currentInstance?.showRotationDialogWhenReady(rotationEpoch)
        }

        @JvmStatic
        private val monitorHandler = Handler(Looper.getMainLooper())

        @Volatile @JvmStatic
        internal var currentInstance: FloatingToolbarModule? = null

        @JvmStatic
        private val staticMonitorRunnable = object : Runnable {
            override fun run() {
                if (!foregroundMonitorRunning) return
                val inst = currentInstance ?: return
                inst.runMonitorTick()
                monitorHandler.postDelayed(this, MONITOR_INTERVAL_MS)
            }
        }

        @JvmStatic
        private fun scheduleRestore(delay: Long, reason: String) {
            pendingRestoreRunnable?.let { monitorHandler.removeCallbacks(it) }
            val r = Runnable { pendingRestoreRunnable = null; evaluateRestore(reason) }
            pendingRestoreRunnable = r
            monitorHandler.postDelayed(r, delay)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "scheduleRestore: delay=${delay}ms reason=$reason")
        }

        @JvmStatic
        private fun cancelScheduledRestore() {
            pendingRestoreRunnable?.let { monitorHandler.removeCallbacks(it) }
            pendingRestoreRunnable = null
        }

        @JvmStatic
        private fun evaluateRestore(reason: String) {
            val inst = currentInstance ?: return

            if (!isInNoteApp) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, not in note")
                return
            }
            if (SubviewLogMonitor.isSubviewOpen()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, subview open")
                return
            }
            if (SubviewLogMonitor.isToolbarMenuOpen()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, toolbar menu open")
                return
            }
            if (isPluginHostWindowShowing) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, PluginHost showing")
                return
            }
            if (docScreenshotRoutePending) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, doc screenshot route pending")
                return
            }
            if (insertImageFlowActive) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, insert-image in flight")
                return
            }
            if (SubviewLogMonitor.isLassoMenuOpen()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: blocked, lasso menu open")
                return
            }

            inst.resumeAllNativePanels()
            val anyPanelOpen = anyNativeOverlayOwnsScreen()

            if (!anyPanelOpen) {
                if (toolbarRestorePending && rootView == null && tools.isNotEmpty()) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: restoring toolbar (collapsed=$collapsed)")
                    inst.restoreToolbarWindow()
                }
                toolbarRestorePending = false
            }

            if (!anyPanelOpen) {
                val ctx = inst.reactApplicationContext
                FloatingBubbleModule.reshowLast(ctx)
                AiBubbleModule.reshowLast(ctx)
                PaletteBubbleModule.reshowLast(ctx)
                FloatingPenGuard.endPark("restore evaluated[$reason]")
            }

            if (!isPluginHostWindowShowing) StickyNotes.reshowIfTemp()

            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "evaluateRestore[$reason]: done anyPanel=$anyPanelOpen")
        }

        @JvmStatic
        private fun markToolbarForRestore(parkGuards: Boolean = true) {
            if (parkGuards) FloatingPenGuard.beginPark("hide for restore")
            toolbarRestorePending = toolbarRestorePending || rootView != null
        }
    }

    init {
        currentInstance = this
        // Settings can reopen configuration while the RN runtime is mounted but
        // paused. Start the in-process PluginContainer visibility hook with the
        // native module itself so that recovery does not depend on the toolbar or
        // a bubble having been shown earlier.
        if (windowStateMonitor == null) {
            windowStateMonitor = WindowStateMonitor(reactApplicationContext)
        }
        windowStateMonitor?.start()
        ToolRegistry.setPanelActiveEdgeListener { anyShowing -> onNativePanelActiveEdge(anyShowing) }
        knownStitchSessionActive = reactApplicationContext
            .getSharedPreferences(SCREENSHOT_STATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(STITCH_SESSION_ACTIVE_KEY, knownStitchSessionActive)
        if (!configCallbackRegistered) {
            configCallbackRegistered = true
            reactApplicationContext.applicationContext.registerComponentCallbacks(
                object : android.content.ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        Handler(Looper.getMainLooper()).post {
                            currentInstance?.handleOrientationChange("configuration")
                        }
                    }
                    override fun onLowMemory() {}
                    override fun onTrimMemory(level: Int) {}
                }
            )
        }
        if (!displayListenerRegistered) {
            try {
                val displayManager = reactApplicationContext.applicationContext
                    .getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                displayManager.registerDisplayListener(
                    object : DisplayManager.DisplayListener {
                        override fun onDisplayAdded(displayId: Int) {}
                        override fun onDisplayRemoved(displayId: Int) {}
                        override fun onDisplayChanged(displayId: Int) {
                            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                                Handler(Looper.getMainLooper()).post {
                                    currentInstance?.handleOrientationChange("display-listener")
                                }
                            }
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
                displayListenerRegistered = true
            } catch (e: Exception) {
                Log.w(TAG, "display listener registration failed: ${e.message}")
            }
        }
        Handler(Looper.getMainLooper()).post {
            if (currentInstance === this) handleOrientationChange("module-init")
        }
    }

    override fun invalidate() {
        if (currentInstance === this) {
            cancelRotationSync("module invalidated")
            currentInstance = null
            foregroundMonitorRunning = false
            monitorHandler.removeCallbacks(staticMonitorRunnable)
        }
        super.invalidate()
    }

    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        if (!BuildConfig.ENABLE_DEBUG) return@Runnable
        hideAllNativePanels()
        removeAll()
        ConfigPanel.getInstance(reactApplicationContext, this).show()
    }

    private val BTN_SIZE_DP = 54
    private val BTN_GAP_DP = 2
    private val PANEL_PAD_DP = 8
    private val BORDER_WIDTH = 2
    private val TITLE_ROW_H_DP = 38
    private val TITLE_SEP_DP = 1
    private val CORNER_RADIUS_DP = 0f
    private val BTN_TEXT_SIZE_SP = 22f
    private val TITLE_TEXT_SIZE_SP = 13f

    private val CLR_BTN_FG   = Color.parseColor("#1A1A1A")
    private val CLR_BTN_ACT  = Color.parseColor("#111111")
    private val CLR_SIDEBAR_BG = Color.WHITE
    private val CLR_SEP      = Color.parseColor("#E8E8E5")
    private val CLR_BORDER   = Color.parseColor("#111111")

    private val SIDE_INDICATOR_DP = 4
    private val HANDLE_WIDTH_DP = 4
    private val COLLAPSED_WIDTH_DP = 7
    // Touch window is wider than the visible stripe: a 7dp target at the very
    // screen edge was too hard to hit (low expand trigger rate).
    private val COLLAPSED_HIT_WIDTH_DP = 22
    private val COLLAPSED_HEIGHT_DP = 80

    private val SNAP_THRESHOLD = 40
    private val EDGE_COLLAPSE_THRESHOLD = 20
    private val LONG_PRESS_MS = 600L

    data class ToolItem(
        val id: String,
        val storedName: String,
        val icon: String,
        val action: String,
        val latches: Boolean = false,
        val nameKey: String = ""
    ) {
        val name: String
            get() {
                val key = nameKey.ifEmpty { defaultToolNameKey(id) }
                if (key.isNotEmpty()) {
                    val translated = NativeLocale.t(key)
                    if (translated != key) return translated
                }
                return storedName
            }
    }

    private val CLIP_ICON_DP = 32
    private val CLIP_RADIUS_DP = 3
    private val LAYER_BTN_DP = 20
    private var clipIconViews: Array<TextView?> = arrayOfNulls(6)

    @ReactMethod
    fun setOrientation(value: String) {
        handler.post {
            val normalized = if (value == "vertical") "vertical" else "horizontal"
            if (normalized == orientation) return@post
            orientation = normalized
            try {
                reactApplicationContext
                    .getSharedPreferences("quicktoolbar_presets", 0)
                    .edit().putString(ORIENTATION_STORE_KEY, normalized).apply()
            } catch (_: Exception) {}
            if (rootView != null && !collapsed) {
                removeAll()
                createExpandedToolbar()
            }
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getOrientationSync(): String {
        loadOrientationFromPrefs()
        return orientation
    }

    private fun loadOrientationFromPrefs() {
        orientation = "vertical"
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            prefs.edit().putString(ORIENTATION_STORE_KEY, "vertical").apply()
        } catch (_: Exception) {}
    }

    private fun loadPositionFromPrefs() {
        if (positionLoaded) return
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            stickyX = prefs.getInt(POSITION_STORE_KEY_X, -1)
            stickyY = prefs.getInt(POSITION_STORE_KEY_Y, -1)
        } catch (_: Exception) {}
        positionLoaded = true
    }

    private fun savePositionToPrefs(x: Int, y: Int) {
        stickyX = x; stickyY = y; positionLoaded = true
        try {
            reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
                .edit().putInt(POSITION_STORE_KEY_X, x).putInt(POSITION_STORE_KEY_Y, y).apply()
        } catch (_: Exception) {}
    }

    @ReactMethod
    fun hide() {
        handler.post { try { removeAll() } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "hide: ${e.message}", e) } }
    }

    @ReactMethod
    fun toggleFromPluginButton() {
        handler.post {
            val active = rootView != null || toolbarRestorePending
            Log.i(TAG, "toggleFromPluginButton active=$active root=${rootView != null} toolbarRestorePending=$toolbarRestorePending")
            if (active) {
                toolbarRestorePending = false
                cancelScheduledRestore()
                FloatingPenGuard.setFullScreenActive(false, "host-toolbar-menu")
                destroyAll()
                closeHostPluginView()
            } else {
                showToolbarNow()
            }
        }
    }

    @ReactMethod
    fun inspectAndFlushHostEntry(promise: Promise) {
        handler.post {
            var result = "none"
            try {
                val pm = reactApplicationContext.catalystInstance
                    ?.getNativeModule("NativePluginManager")
                if (pm == null) {
                    Log.i(TAG, "hostdiag: NativePluginManager module=null")
                    promise.resolve(result); return@post
                }
                val pa = findDeclaredField(pm.javaClass, "pluginApp")
                    ?.apply { isAccessible = true }?.get(pm)
                if (pa == null) {
                    Log.i(TAG, "hostdiag: pluginApp=null")
                    promise.resolve(result); return@post
                }
                fun readField(name: String): Any? = try {
                    findDeclaredField(pa.javaClass, name)
                        ?.apply { isAccessible = true }?.get(pa)
                } catch (_: Exception) { null }
                val state = readField("state")
                val stateName = (state as? Enum<*>)?.name ?: state?.toString()
                val cache = readField("pluginEventCacheList") as? List<*>
                val eventNames = cache?.map { it?.javaClass?.simpleName ?: "null" } ?: emptyList()
                val moduleRef = readField("mPluginModule")
                val lastShowType = (readField("lastShowType") as? Number)?.toInt()
                Log.i(TAG, "hostdiag: state=$stateName cache=${cache?.size} [${eventNames.joinToString(",")}] pluginModule=${moduleRef != null} lastShowType=$lastShowType")

                fun eventButtonId(event: Any?): Int? {
                    if (event == null || !event.javaClass.simpleName.contains("PluginButtonEvent")) return null
                    return try {
                        val button = findDeclaredField(event.javaClass, "button")
                            ?.apply { isAccessible = true }?.get(event) ?: return null
                        (button.javaClass.methods.firstOrNull {
                            it.name == "getId" && it.parameterCount == 0
                        }?.invoke(button) as? Number)?.toInt()
                    } catch (_: Exception) { null }
                }

                val hasButtonEvent = eventNames.any { it.contains("PluginButtonEvent") }
                val hasConfigEvent = eventNames.any { it.contains("ConfigButtonPressEvent") }
                val hasLocalSendEvent = cache?.any { eventButtonId(it) == 200 } == true
                if (stateName == "initialized" && (hasButtonEvent || hasConfigEvent)) {
                    Log.i(TAG, "hostdiag: state stuck at initialized, flushing entry events via PluginApp.onMounted()")
                    try {
                        pa.javaClass.getMethod("onMounted").invoke(pa)
                        result = when {
                            hasConfigEvent -> "config"
                            hasLocalSendEvent -> "localsend"
                            else -> "flushed"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "hostdiag: onMounted invoke failed: ${e.message}")
                    }
                } else if (lastShowType == 1) {
                    val pluginViewVisible = (readField("pluginView") as? View)?.visibility == View.VISIBLE
                    if (pluginViewVisible && !isSelfInitiatedHostShow()) {
                        result = "config"
                    } else if (BuildConfig.ENABLE_DEBUG) {
                        Log.i(TAG, "hostdiag: stale lastShowType=1 ignored (visible=$pluginViewVisible, selfShow=${isSelfInitiatedHostShow()})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "hostdiag: error: ${e.message}")
            }
            promise.resolve(result)
        }
    }

    private fun findDeclaredField(cls: Class<*>, name: String): java.lang.reflect.Field? =
        findDeclaredFieldStatic(cls, name)

    @ReactMethod
    fun reportHostButtonChannel(working: Boolean) {
        hostButtonChannelBroken = !working
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "host button channel report working=$working")
    }

    @ReactMethod
    fun reportHostButtonRaw(payload: String) {
        Log.i(TAG, "host button raw: $payload")
    }

    @ReactMethod
    fun showCurrent() {
        handler.post { showToolbarNow() }
    }

    private fun showToolbarNow() {
        Log.i(TAG, "showToolbarNow root=${rootView != null} foregroundMonitor=$foregroundMonitorRunning")
        try {
            ensureToolsLoaded()
            collapsed = false
            removeAll()
            if (!foregroundMonitorRunning) startForegroundMonitor()
            createExpandedToolbar()
        } catch (e: Exception) {
            Log.e(TAG, "showToolbarNow: ${e.message}", e)
        }
        closeHostPluginView()
    }
    private fun ensureToolsLoaded() {
        if (tools.isNotEmpty()) return
        val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
        val json = prefs.getString("preset_1", null)
        if (json != null) {
            try {
                val toolsArr = JSONObject(json).optJSONArray("tools")
                if (toolsArr != null) parseTools(toolsArr.toString(), persistIfChanged = true)
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "ensureToolsLoaded parse prefs: ${e.message}") }
        }
        if (tools.isEmpty()) parseTools(DEFAULT_TOOLS_JSON)
    }

    @ReactMethod
    fun collapse() {
        handler.post { switchToCollapsed() }
    }

    @ReactMethod
    fun dockToEdge() {
        handler.post { dockToNearestEdge() }
    }

    @ReactMethod
    fun expand() {
        handler.post { switchToExpanded() }
    }

    @ReactMethod
    fun setSide(side: String) {
        handler.post {
            dockSide = if (side == "right") "right" else "left"
            if (collapsed) {
                removeAll()
                createCollapsedHandle()
            } else if (expandedRoot != null) {

                removeAll()
                createExpandedToolbar()
            }
        }
    }

    @ReactMethod fun isShowing(promise: Promise) { promise.resolve(rootView != null) }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isShowingSync(): Boolean = rootView != null || pendingShow

    @ReactMethod
    fun checkPendingOpenMain(promise: Promise) {
        val v = pendingOpenMain
        pendingOpenMain = false
        promise.resolve(v)
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun checkPendingOpenMainSync(): Boolean = pendingOpenMain

    @ReactMethod
    fun ackOpenMain() { pendingOpenMain = false }

    @ReactMethod
    fun setPendingScreen(name: String) { pendingScreen = name ?: "" }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getPendingScreenSync(): String = pendingScreen

    @ReactMethod
    fun ackPendingScreen() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] ackPendingScreen (was=$pendingScreen)")
        pendingScreen = ""
    }

    @ReactMethod
    fun openPluginView() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] openPluginView called (pendingScreen=$pendingScreen)")
            try { callShowPluginView() } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[LASSO-DBG/Kt] openPluginView FAIL: ${e.message}")
            }
        }
    }

    // Single funnel for opening a native panel: every open path must announce
    // itself to JS via onNativePanelOpen exactly once, so JS can suspend its UI.
    private fun openNativePanel(name: String, show: () -> Unit) {
        emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", name) })
        show()
    }

    @ReactMethod
    fun openPanel(screen: String) {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] openPanel screen=$screen (prev pendingScreen=$pendingScreen)")

            // Native fallback and JS plugin_config_event may request the config
            // panel in the same opening cycle. Deduplicate before hideAllNativePanels:
            // hiding first queues ConfigPanel.hide(), then the isShowing check skips
            // the replacement and leaves Settings with a blank full-screen session.
            if (screen == "config" || screen == "main") {
                if (!BuildConfig.ENABLE_DEBUG) return@post
                if (ConfigPanel.currentInstance?.isShowing == true) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "openPanel config: already showing, keep existing panel")
                    return@post
                }
                hideAllNativePanels()
                pendingScreen = ""
                collapsePluginHostContainerForNativePanel()
                removeAll()
                openNativePanel("config") {
                    ConfigPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
                }
                return@post
            }

            hideAllNativePanels()

            if (screen == "nativeSendClipboard") {
                pendingScreen = "nativeSendHelper"
                removeAll()
                openNativePanel("send") {
                    SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(syncClipboard = true)
                }
                handler.postDelayed({
                    if (pendingScreen != "nativeSendHelper") return@postDelayed
                    callShowPluginView()
                    emitOpenMainWithRetries("nativeSendHelper")
                }, 150)
                return@post
            }

            if (screen == "airRelay" || screen.startsWith("airRelayDetail:")) {
                pendingScreen = ""
                removeAll()
                val detailId = screen.removePrefix("airRelayDetail:").takeIf { screen.startsWith("airRelayDetail:") }
                openNativePanel("airRelay") {
                    val panel = RelayInboxPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                    if (detailId == null) panel.showList() else panel.showDetail(detailId)
                }
                return@post
            }

            pendingScreen = screen ?: ""
            removeAll()

            handler.postDelayed({
                emitEvent("onToolbarOpenMain", Arguments.createMap())
            }, 80)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] openPanel done, pendingScreen now=$pendingScreen")
        }
    }

    @ReactMethod
    fun forceClosePluginView() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[LASSO-DBG/Kt] forceClosePluginView called")
            insertPluginViewClosed = true
            callClosePluginView()
        }
    }

    @ReactMethod
    fun updateTitleClips(json: String) {
        handler.post {
            try {
                val arr = JSONArray(json)
                for (i in 0 until minOf(arr.length(), 6)) {
                    titleClipFilled[i] = arr.getBoolean(i)
                }
                rebuildClipIcons()
            } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "updateTitleClips: ${e.message}") }
        }
    }

    @ReactMethod
    fun setLocale(loc: String) {
        NativeLocale.setLocale(loc)
    }

    @ReactMethod
    fun savePreset(num: Int, json: String, promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            prefs.edit().putString("preset_$num", json).apply()
            promise.resolve(true)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "savePreset: ${e.message}")
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun paletteSnapshotsChanged() {
        com.supernote_quicktoolbar.panels.PalettePanel.currentInstance?.reloadSnapshots()
    }

    @ReactMethod
    fun loadPreset(num: Int, promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            val json = prefs.getString("preset_$num", null)
            promise.resolve(json)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "loadPreset: ${e.message}")
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun getStickerDir(promise: Promise) {
        try {
            val dir = java.io.File(reactApplicationContext.getExternalFilesDir(null), "stickers")
            promise.resolve(dir.absolutePath)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "getStickerDir: ${e.message}")
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun ensureStickerDir(promise: Promise) {
        try {
            val dir = java.io.File(reactApplicationContext.getExternalFilesDir(null), "stickers")
            if (!dir.exists()) {
                val ok = dir.mkdirs()
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "ensureStickerDir: mkdirs=${ok} path=${dir.absolutePath}")
            }
            promise.resolve(dir.absolutePath)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "ensureStickerDir: ${e.message}")
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun deleteQueueFile(path: String, promise: Promise) {
        kotlin.concurrent.thread(isDaemon = true) {
            try {
                val f = java.io.File(path)
                val fileName = f.name
                DocScreenshotService.unmarkInsertNext(fileName)
                if (f.exists()) {
                    val deleted = f.delete()
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] deleteQueueFile: $path → deleted=$deleted")
                    promise.resolve(deleted)
                } else {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] deleteQueueFile: $path already gone")
                    promise.resolve(false)
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[INSERT-DBG/Kt] deleteQueueFile error: ${e.message}")
                promise.reject("DELETE_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun openPluginSettings() {
        try {
            val intent = Intent(SETTINGS_PLUGIN_DETAIL_ACTION).apply {
                component = ComponentName(SETTINGS_PACKAGE, SETTINGS_ACTIVITY)
                putExtra("pluginId", PLUGIN_ID)
                putExtra("fromAPP", PLUGIN_ID)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactApplicationContext.startActivity(intent)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "openPluginSettings: launched plugin detail for $PLUGIN_ID")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "openPluginSettings plugin detail failed: ${e.message}", e)
            try {
                reactApplicationContext.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    setPackage(SETTINGS_PACKAGE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (fallback: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "openPluginSettings fallback failed: ${fallback.message}", fallback)
            }
        }
    }

    fun openPluginSettingsAfterFileReadDenied() {
        if (shouldOpenPluginSettingsAfterDenied(PLUGIN_FILE_READ_PERMISSION)) openPluginSettings()
    }

    fun openPluginSettingsAfterFileWriteDenied() {
        if (shouldOpenPluginSettingsAfterDenied(PLUGIN_FILE_WRITE_PERMISSION)) openPluginSettings()
    }

    @ReactMethod
    fun openPluginSettingsAfterFileWritePermissionDenied() {
        openPluginSettingsAfterFileWriteDenied()
    }

    private fun shouldOpenPluginSettingsAfterDenied(permission: String): Boolean {
        return if (deniedPluginPermissions.contains(permission)) {
            true
        } else {
            deniedPluginPermissions.add(permission)
            false
        }
    }

    private fun clearPluginPermissionDenied(permission: String) {
        deniedPluginPermissions.remove(permission)
    }

    private fun parsePermissionStatus(value: Any?): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun isPluginPermissionGranted(status: Int): Boolean = status == 1 || status == 2

    private fun permissionRequestDesc(permission: String): String = when (permission) {
        PLUGIN_INTERNET_PERMISSION -> NativeLocale.t("perm_desc_internet")
        PLUGIN_FILE_WRITE_PERMISSION -> NativeLocale.t("perm_desc_file_write")
        PLUGIN_FILE_DELETE_PERMISSION -> NativeLocale.t("perm_desc_file_delete")
        else -> NativeLocale.t("perm_desc_file_read")
    }

    private fun closeHostPluginView() {
        try {
            val pm = reactApplicationContext.catalystInstance
                ?.getNativeModule("NativePluginManager") ?: return
            val m = pm::class.java.methods.firstOrNull {
                it.name == "closePluginView" && it.parameterCount == 1
            } ?: return
            m.invoke(pm, PromiseImpl(Callback { }, Callback { }))
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "closeHostPluginView: ${e.message}")
        }
    }

    fun requestPluginPermission(
        permission: String,
        onResult: ((Boolean) -> Unit)? = null,
        requestIfMissing: Boolean = true
    ) {
        handler.post {
            try {
                val pm = reactApplicationContext.catalystInstance.getNativeModule("NativePluginManager") ?: run {
                    Log.w(TAG, "requestPluginPermission($permission): NativePluginManager not found")
                    onResult?.invoke(false)
                    return@post
                }
                val hasMethod = pm::class.java.methods.firstOrNull {
                    it.name == "hasPermission" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
                } ?: run {
                    Log.w(TAG, "requestPluginPermission($permission): hasPermission(String, Promise) not found")
                    onResult?.invoke(false)
                    return@post
                }
                val requestMethod = if (requestIfMissing) {
                    pm::class.java.methods.firstOrNull {
                        it.name == "requestPermission" && it.parameterCount == 3 && it.parameterTypes[0] == String::class.java
                    } ?: pm::class.java.methods.firstOrNull {
                        it.name == "requestPermission" && it.parameterCount == 2 && it.parameterTypes[0] == String::class.java
                    } ?: run {
                        Log.w(TAG, "requestPluginPermission($permission): requestPermission(String, [String,] Promise) not found")
                        onResult?.invoke(false)
                        return@post
                    }
                } else {
                    null
                }
                val requestPromise = PromiseImpl(
                    Callback { args ->
                        val status = parsePermissionStatus(args.firstOrNull())
                        val granted = isPluginPermissionGranted(status)
                        if (granted) clearPluginPermissionDenied(permission)
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "requestPluginPermission($permission) request resolved: status=$status granted=$granted")
                        handler.post { onResult?.invoke(granted) }
                    },
                    Callback { args ->
                        if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "requestPluginPermission($permission) rejected: ${args.joinToString()}")
                        handler.post { onResult?.invoke(false) }
                    }
                )
                fun doRequest() {
                    if (requestMethod!!.parameterCount == 3) {
                        requestMethod.invoke(pm, permission, permissionRequestDesc(permission), requestPromise)
                    } else {
                        requestMethod.invoke(pm, permission, requestPromise)
                    }
                }
                val hasPromise = PromiseImpl(
                    Callback { args ->
                        val status = parsePermissionStatus(args.firstOrNull())
                        val alreadyGranted = isPluginPermissionGranted(status)
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "requestPluginPermission($permission) hasPermission resolved: status=$status alreadyGranted=$alreadyGranted")
                        if (alreadyGranted) {
                            clearPluginPermissionDenied(permission)
                            handler.post { onResult?.invoke(true) }
                        } else if (requestIfMissing) {
                            doRequest()
                        } else {
                            handler.post { onResult?.invoke(false) }
                        }
                    },
                    Callback { args ->
                        if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "requestPluginPermission($permission) hasPermission rejected: ${args.joinToString()}")
                        if (requestIfMissing) {
                            doRequest()
                        } else {
                            handler.post { onResult?.invoke(false) }
                        }
                    }
                )
                hasMethod.invoke(pm, permission, hasPromise)
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "requestPluginPermission($permission): ${e.message}", e)
                onResult?.invoke(false)
            }
        }
    }

    fun requestPluginFileReadPermission(onResult: ((Boolean) -> Unit)? = null) {
        requestPluginPermission(PLUGIN_FILE_READ_PERMISSION, onResult)
    }

    fun requestPluginFileWritePermission(onResult: ((Boolean) -> Unit)? = null) {
        requestPluginPermission(PLUGIN_FILE_WRITE_PERMISSION, onResult)
    }

    fun requestPluginFileDeletePermission(onResult: ((Boolean) -> Unit)? = null) {
        requestPluginPermission(PLUGIN_FILE_DELETE_PERMISSION, onResult)
    }

    private fun checkPluginPermission(permission: String, onResult: ((Boolean) -> Unit)? = null) {
        handler.post {
            val storedStatus = readStoredPermissionStatus(permission)
            if (storedStatus == 2) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "checkPluginPermission($permission): stored status=2 (always) → granted")
                onResult?.invoke(true)
                return@post
            }
            requestPluginPermission(permission, onResult, requestIfMissing = true)
        }
    }

    private fun readStoredPermissionStatus(permission: String): Int {
        return try {
            val pm = reactApplicationContext.catalystInstance?.getNativeModule("NativePluginManager") ?: return -1
            val pa = findDeclaredField(pm.javaClass, "pluginApp")
                ?.apply { isAccessible = true }?.get(pm) ?: return -1
            val perms = findDeclaredField(pa.javaClass, "usesPermissions")
                ?.apply { isAccessible = true }?.get(pa) as? List<*> ?: return -1
            for (p in perms) {
                if (p == null) continue
                val name = p.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                    ?.invoke(p) as? String ?: continue
                if (name == permission) {
                    val status = p.javaClass.methods.firstOrNull { it.name == "getStatus" && it.parameterCount == 0 }
                        ?.invoke(p) as? Number ?: return -1
                    return status.toInt()
                }
            }
            -1
        } catch (_: Exception) { -1 }
    }

    private fun showScreenshotMessage(key: String) {
        handler.post {
            android.widget.Toast.makeText(
                reactApplicationContext,
                NativeLocale.t(key),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestScreenshotStoragePermissions(
        action: String,
        onGranted: () -> Unit,
        onDenied: () -> Unit
    ) {
        requestPluginFileReadPermission { readGranted ->
            if (!readGranted) {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "[CROP-DBG/Kt] $action denied: FILE:READ")
                showScreenshotMessage("file_read_permission_needed")
                openPluginSettingsAfterFileReadDenied()
                onDenied()
                return@requestPluginFileReadPermission
            }
            requestPluginFileWritePermission { writeGranted ->
                if (!writeGranted) {
                    if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "[CROP-DBG/Kt] $action denied: FILE:WRITE")
                    showScreenshotMessage("file_write_permission_needed")
                    openPluginSettingsAfterFileWriteDenied()
                    onDenied()
                    return@requestPluginFileWritePermission
                }
                onGranted()
            }
        }
    }

    fun requestPluginInternetPermission(onResult: ((Boolean) -> Unit)? = null) {
        requestPluginPermission(PLUGIN_INTERNET_PERMISSION, onResult)
    }

    @ReactMethod
    fun checkFileReadPermission(promise: Promise) {
        checkPluginPermission(PLUGIN_FILE_READ_PERMISSION) { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun checkFileWritePermission(promise: Promise) {
        checkPluginPermission(PLUGIN_FILE_WRITE_PERMISSION) { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun checkFileDeletePermission(promise: Promise) {
        checkPluginPermission(PLUGIN_FILE_DELETE_PERMISSION) { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun checkInternetPermission(promise: Promise) {
        checkPluginPermission(PLUGIN_INTERNET_PERMISSION) { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun requestFileReadPermission(promise: Promise) {
        requestPluginFileReadPermission { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun requestFileWritePermission(promise: Promise) {
        requestPluginFileWritePermission { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun requestFileDeletePermission(promise: Promise) {
        requestPluginFileDeletePermission { granted -> promise.resolve(granted) }
    }

    @ReactMethod
    fun requestInternetPermission(promise: Promise) {
        requestPluginInternetPermission { granted -> promise.resolve(granted) }
    }

    private fun callShowPluginView() = withNativePluginManager("callShowPluginView") { pm ->
        selfShowPluginViewAt = android.os.SystemClock.elapsedRealtime()
        val allMethods = pm::class.java.methods.filter { it.name == "showPluginView" }
        if (allMethods.isEmpty()) return@withNativePluginManager
        val noArg = allMethods.firstOrNull { it.parameterCount == 0 }
        if (noArg != null) { noArg.invoke(pm); return@withNativePluginManager }
        val singleArg = allMethods.firstOrNull { it.parameterCount == 1 }
        if (singleArg != null) { singleArg.invoke(pm, PromiseImpl(null, null)); return@withNativePluginManager }
    }

    /**
     * Keep PluginApp/clientUid alive for the Settings transaction, but remove the
     * host's full-screen TYPE_PHONE shell from the input/display path so that the
     * native ConfigPanel can be seen and touched. The next Settings press calls
     * attachPluginContainer(1), which restores MATCH_PARENT before showing again.
     */
    private fun collapsePluginHostContainerForNativePanel() {
        withNativePluginManager("collapsePluginHostContainerForNativePanel") { pm ->
            try {
                val pa = getHostPluginApp(pm) ?: return@withNativePluginManager
                val pluginView = findDeclaredField(pa.javaClass, "pluginView")
                    ?.apply { isAccessible = true }?.get(pa) as? View ?: return@withNativePluginManager
                val lp = pluginView.layoutParams as? WindowManager.LayoutParams
                    ?: return@withNativePluginManager
                if (lp.width == 0 && lp.height == 0) return@withNativePluginManager
                lp.width = 0
                lp.height = 0
                val wm = reactApplicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.updateViewLayout(pluginView, lp)
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "collapsed PluginHost container to 0x0 for native config panel")
                }
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.w(TAG, "collapse PluginHost container failed: ${e.message}")
                }
            }
        }
    }

    private fun callClosePluginView() = withNativePluginManager("callClosePluginView") { pm ->
        notifySettingsClientClosed(pm)
        for (name in arrayOf("closePluginView", "hidePluginView")) {
            val methods = pm::class.java.methods.filter { it.name == name }
            if (methods.isEmpty()) continue
            val noArg = methods.firstOrNull { it.parameterCount == 0 }
            if (noArg != null) { noArg.invoke(pm); if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "$name() called"); return@withNativePluginManager }
            val singleArg = methods.firstOrNull { it.parameterCount == 1 }
            if (singleArg != null) { singleArg.invoke(pm, PromiseImpl(null, null)); if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "$name(promise) called"); return@withNativePluginManager }
        }
        if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "callClosePluginView: no suitable method found")
    }

    private fun getHostPluginApp(pm: Any): Any? =
        pm.javaClass.methods.firstOrNull {
            it.name == "getPluginApp" && it.parameterCount == 0
        }?.invoke(pm) ?: findDeclaredField(pm.javaClass, "pluginApp")
            ?.apply { isAccessible = true }?.get(pm)

    private fun getHostClientUid(pluginApp: Any): Int =
        (pluginApp.javaClass.methods.firstOrNull {
            it.name == "getClientUid" && it.parameterCount == 0
        }?.invoke(pluginApp) as? Number)?.toInt() ?: -1

    /**
     * PluginCore normally reports state=0 from PluginContainer's visibility listener.
     * The Settings config path can remove that container before the listener dispatches,
     * leaving Settings stuck in its "plugin showing" state. Notify Settings explicitly
     * while PluginApp still owns the client uid; ordinary NOTE/DOC closes keep using the
     * host's normal visibility notification path.
     */
    private fun notifySettingsClientClosed(pm: Any) {
        try {
            val pa = getHostPluginApp(pm) ?: return
            val clientUid = getHostClientUid(pa)
            if (clientUid < 0) return

            val loader = pa.javaClass.classLoader
            val managerClass = loader.loadClass(
                "com.ratta.supernote.pluginhost.manager.PluginManager"
            )
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val binder = managerClass.getMethod(
                "getCurrentClientBinder",
                Integer.TYPE
            ).invoke(manager, clientUid) ?: return
            val packageName = binder.javaClass.methods.firstOrNull {
                it.name == "getServicePackageName" && it.parameterCount == 0
            }?.invoke(binder) as? String
            if (packageName != "com.ratta.settings") return

            val serviceClass = loader.loadClass(
                "com.ratta.supernote.pluginhost.services.PluginHostService"
            )
            val hostContext = findDeclaredField(serviceClass, "mContext")
                ?.apply { isAccessible = true }?.get(null)
            val service = when {
                serviceClass.isInstance(hostContext) -> hostContext
                hostContext is android.content.ContextWrapper -> hostContext.baseContext
                else -> null
            }
            if (service == null || !serviceClass.isInstance(service)) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.w(TAG, "settings close state notify skipped: PluginHostService unavailable")
                }
                return
            }
            serviceClass.getMethod(
                "notifyClientPluginState",
                Integer.TYPE,
                Integer.TYPE
            ).invoke(service, clientUid, 0)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "settings close state notified: uid=$clientUid state=0")
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(TAG, "settings close state notify failed: ${e.message}")
            }
        }
    }

    private val autoCollapseRunnable = Runnable { switchToCollapsed() }

    private fun cancelAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
    }

    private fun isNearEdge(): Boolean {
        val lp = layoutParams ?: return false
        val vw = expandedRoot?.measuredWidth?.takeIf { it > 0 }
            ?: expandedRoot?.width?.takeIf { it > 0 }
            ?: return false
        refreshScreenDimensions()
        val flushLeft = lp.x <= DOCK_FLUSH_TOLERANCE
        val flushRight = (screenWidth - (lp.x + vw)) <= DOCK_FLUSH_TOLERANCE
        return flushLeft || flushRight
    }

    private fun inferDockSideFromPosition() {
        val lp = layoutParams ?: return
        val vw = expandedRoot?.measuredWidth?.takeIf { it > 0 }
            ?: expandedRoot?.width?.takeIf { it > 0 } ?: return
        refreshScreenDimensions()
        val distLeft = lp.x
        val distRight = screenWidth - (lp.x + vw)
        dockSide = if (distLeft <= distRight) "left" else "right"
    }

    private fun resetAutoCollapse() {
        handler.removeCallbacks(autoCollapseRunnable)
        if (isNearEdge()) {
            handler.postDelayed(autoCollapseRunnable, AUTO_COLLAPSE_MS)
        }
    }

    private fun switchToCollapsed() {
        if (collapsed && collapsedRoot != null) return
        cancelAutoCollapse()
        val lp = layoutParams as? WindowManager.LayoutParams
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "switchToCollapsed: x=${lp?.x} y=${lp?.y} side=$dockSide screen=${screenWidth}x${screenHeight}")
        inferDockSideFromPosition()
        collapsed = true
        removeAll()
        createCollapsedHandle()
        emitCollapseChange()
    }

    private fun switchToExpanded() {
        if (!collapsed && expandedRoot != null) return
        if (preDockX >= 0) {
            savePositionToPrefs(preDockX, preDockY)
            preDockX = -1; preDockY = -1
        }
        collapsed = false
        removeAll()
        createExpandedToolbar()
        emitCollapseChange()

        expandedRoot?.post { resetAutoCollapse() }
    }

    private fun emitCollapseChange() {
        emitEvent("onToolbarCollapseChange", Arguments.createMap().apply {
            putBoolean("collapsed", collapsed)
            putString("side", dockSide)
        })
    }

    override fun refreshScreenDimensions() {
        try {
            val wm = reactApplicationContext.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = wm.currentWindowMetrics.bounds
                screenWidth = bounds.width(); screenHeight = bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val size = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
                screenWidth = size.x; screenHeight = size.y
            }
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun readDisplayRotation(): Int = try {
        val wm = reactApplicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.rotation
    } catch (_: Exception) {
        Surface.ROTATION_0
    }

    private fun handleOrientationChange(reason: String) {
        val oldW = screenWidth; val oldH = screenHeight
        val oldRotation = lastDisplayRotation
        refreshScreenDimensions()
        val rotation = readDisplayRotation()
        lastDisplayRotation = rotation
        FloatingPenGuard.onDisplayChanged(screenWidth, screenHeight, rotation, reason)
        if (oldW == screenWidth && oldH == screenHeight && oldRotation == rotation) return
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "orientation changed reason=$reason ${oldW}x${oldH}@${oldRotation} " +
                "→ ${screenWidth}x${screenHeight}@$rotation")
        }

        val foreground = foregroundActivity()
        val supportsHandwriting = foreground?.let {
            (it.packageName == NOTE_PACKAGE && it.activityName == NOTE_INSIDE_PAGES_ACTIVITY) ||
                it.packageName == DOC_PACKAGE
        } == true
        val foregroundKey = foreground?.let { "${it.packageName}/${it.activityName}" }
        if (oldRotation >= 0 && supportsHandwriting && foregroundKey != null &&
            FloatingPenGuard.hasActiveRects()) {
            rotationEpoch += 1L
            rotationStartForegroundKey = foregroundKey
            rotationUserConfirmed = false
            rotationDialogDismissed = false
            rotationViewsReady = false
            rotationBaselineReady = false
            rotationPostDismissBaseline = emptyList()
            monitorHandler.removeCallbacks(rotationDisplaySettledRunnable)
            monitorHandler.removeCallbacks(rotationBaselineStableRunnable)
            monitorHandler.removeCallbacks(rotationDialogRetryRunnable)
            if (rotationDialogArmed || rotationDialogShown) {
                SubviewLogMonitor.retargetOwnedRotationDialog(rotationEpoch)
            }
            FloatingPenGuard.beginRotationSync(rotationEpoch, reason)
            monitorHandler.postDelayed(rotationDisplaySettledRunnable, ROTATION_DISPLAY_SETTLE_MS)
            Log.i(TAG, "rotation state DISPLAY_SETTLING epoch=$rotationEpoch foreground=$foregroundKey")
        } else if (rotationEpoch != 0L) {
            cancelRotationSync("orientation changed outside handwriting page")
        }

        val hadPanelOpen = ToolRegistry.handleRotation()

        if (hadPanelOpen && rootView == null && tools.isNotEmpty()) {
            restoreToolbar()
        }

        val lp = layoutParams
        if (lp != null && collapsed && collapsedRoot != null) {
            val w = collapsedRoot!!.width.takeIf { it > 0 } ?: dpToPx(COLLAPSED_HIT_WIDTH_DP)
            val h = collapsedRoot!!.height.takeIf { it > 0 } ?: dpToPx(COLLAPSED_HEIGHT_DP)
            lp.x = if (dockSide == "left") 0 else screenWidth - w
            lp.y = lp.y.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
            try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
        } else if (lp != null && !collapsed && expandedRoot != null) {
            val vw = expandedRoot!!.measuredWidth.takeIf { it > 0 } ?: expandedRoot!!.width
            val vh = expandedRoot!!.measuredHeight.takeIf { it > 0 } ?: expandedRoot!!.height
            if (vw > 0 && vh > 0) {
                lp.x = lp.x.coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
                lp.y = lp.y.coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
            }
            try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
        }

        FloatingBubbleModule.handleOrientationChange()
        AiBubbleModule.handleOrientationChange()
        PaletteBubbleModule.handleOrientationChange()
    }

    private fun onRotationDisplaySettled(epoch: Long) {
        if (epoch == 0L || epoch != rotationEpoch) return
        val foreground = foregroundActivity()
        val currentKey = foreground?.let { "${it.packageName}/${it.activityName}" }
        if (currentKey != rotationStartForegroundKey) {
            cancelRotationSync("foreground changed during display settle")
            return
        }
        rotationViewsReady = FloatingPenGuard.refreshAllForRotation(epoch)
        Log.i(TAG, "display settled epoch=$epoch viewsReady=$rotationViewsReady")
        if (!rotationViewsReady) {
            monitorHandler.postDelayed(rotationDisplaySettledRunnable, 80L)
            return
        }
        showRotationDialogWhenReady(epoch)
    }

    private fun showRotationDialogWhenReady(epoch: Long) {
        if (epoch == 0L || epoch != rotationEpoch || rotationDialogShown || rotationDialogArmed ||
            rotationUserConfirmed) return
        if (SubviewLogMonitor.isSubviewOpen() || SubviewLogMonitor.isToolbarMenuOpen() ||
            SubviewLogMonitor.isLassoMenuOpen() || ToolRegistry.anyPanelShowing() ||
            FloatingPenGuard.hasBlockingOwnerForRotation()) {
            monitorHandler.postDelayed(rotationDialogRetryRunnable, ROTATION_DIALOG_RETRY_MS)
            return
        }
        if (!SubviewLogMonitor.armOwnedRotationDialog(epoch)) {
            monitorHandler.postDelayed(rotationDialogRetryRunnable, ROTATION_DIALOG_RETRY_MS)
            return
        }
        rotationDialogArmed = true
        rotationDialogDismissed = false
        rotationPostDismissBaseline = emptyList()
        Log.i(TAG, "rotation state WAIT_DIALOG epoch=$epoch")
        com.supernote_quicktoolbar.ui_common.Dialog.confirmResult(
            reactApplicationContext,
            NativeLocale.t("rotation_sync_message"),
            NativeLocale.t("rotation_sync_wait"),
            NativeLocale.t("rotation_sync_done")
        ) { confirmed ->
            monitorHandler.post {
                if (epoch != rotationEpoch || rotationEpoch == 0L) return@post
                rotationUserConfirmed = confirmed
                rotationBaselineReady = false
                if (confirmed) {
                    monitorHandler.removeCallbacks(rotationDialogRetryRunnable)
                    consumeRotationBaselineCandidate("user confirmed")
                }
                Log.i(TAG, "rotation user result epoch=$epoch confirmed=$confirmed")
            }
        }
    }

    private fun onRotationBaselineStable(epoch: Long) {
        if (epoch == 0L || epoch != rotationEpoch || !rotationUserConfirmed ||
            !rotationDialogDismissed) return
        rotationBaselineReady = true
        rotationViewsReady = FloatingPenGuard.refreshAllForRotation(epoch)
        maybeCompleteRotationSync(epoch)
    }

    private fun maybeCompleteRotationSync(epoch: Long) {
        if (epoch != rotationEpoch || !rotationUserConfirmed || !rotationDialogDismissed ||
            !rotationBaselineReady || !rotationViewsReady) return
        val foreground = foregroundActivity()
        val currentKey = foreground?.let { "${it.packageName}/${it.activityName}" }
        if (currentKey != rotationStartForegroundKey) {
            cancelRotationSync("foreground changed before commit")
            return
        }
        if (FloatingPenGuard.completeRotationSync(epoch, "dialog-confirmed stable baseline")) {
            Log.i(TAG, "rotation state STABLE epoch=$epoch")
            rotationEpoch = 0L
            rotationStartForegroundKey = null
            rotationDialogArmed = false
            rotationDialogShown = false
            rotationUserConfirmed = false
            rotationDialogDismissed = false
            rotationViewsReady = false
            rotationBaselineReady = false
            rotationPostDismissBaseline = emptyList()
        }
    }

    private fun cancelRotationSync(reason: String) {
        val epoch = rotationEpoch
        if (epoch != 0L) SubviewLogMonitor.cancelOwnedRotationDialog(epoch)
        monitorHandler.removeCallbacks(rotationDisplaySettledRunnable)
        monitorHandler.removeCallbacks(rotationBaselineStableRunnable)
        monitorHandler.removeCallbacks(rotationDialogRetryRunnable)
        rotationEpoch = 0L
        rotationStartForegroundKey = null
        rotationDialogArmed = false
        rotationDialogShown = false
        rotationUserConfirmed = false
        rotationDialogDismissed = false
        rotationViewsReady = false
        rotationBaselineReady = false
        rotationPostDismissBaseline = emptyList()
        FloatingPenGuard.cancelRotationSync(reason)
    }

    private fun createCollapsedHandle() {
        val ctx = reactApplicationContext
        windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        refreshScreenDimensions()

        val w = dpToPx(COLLAPSED_WIDTH_DP)
        val hitW = dpToPx(COLLAPSED_HIT_WIDTH_DP)
        val h = dpToPx(COLLAPSED_HEIGHT_DP)

        val stripe = View(ctx).apply {
            background = object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint().apply { isAntiAlias = false }
                override fun draw(canvas: android.graphics.Canvas) {
                    val b = bounds
                    val stripeW = dpToPx(4).toFloat()
                    var y = 0f; var dark = true
                    while (y < b.height()) {
                        paint.color = if (dark) Color.parseColor("#666666") else Color.parseColor("#AAAAAA")
                        canvas.drawRect(0f, y, b.width().toFloat(), (y + stripeW).coerceAtMost(b.height().toFloat()), paint)
                        y += stripeW; dark = !dark
                    }
                }
                override fun setAlpha(a: Int) {}
                override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
                @Deprecated("deprecated") override fun getOpacity() = PixelFormat.OPAQUE
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpToPx(2).toFloat())
                }
            }
        }

        // Wider transparent container: only the stripe is visible (flush with
        // the docked edge), the rest widens the touch target.
        collapsedRoot = android.widget.FrameLayout(ctx).apply {
            addView(stripe, android.widget.FrameLayout.LayoutParams(w, h).apply {
                gravity = if (dockSide == "left") Gravity.START else Gravity.END
            })
        }

        @Suppress("DEPRECATION")
        val wmType = WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            hitW, h, wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (dockSide == "left") 0 else screenWidth - hitW
            y = stickyY.takeIf { it >= 0 } ?: (screenHeight / 2 - h / 2)
            y = y.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
        }

        val SWIPE_THRESHOLD = 30
        val collapsedLongPressRunnable = Runnable {
            longPressTriggered = true
            destroyAll()
        }
        collapsedRoot!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX; startRawY = event.rawY
                    isDragging = false; longPressTriggered = false
                    handler.postDelayed(collapsedLongPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!TouchInput.isFinger(event)) return@setOnTouchListener true
                    val dx = event.rawX - startRawX; val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        isDragging = true
                        handler.removeCallbacks(collapsedLongPressRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(collapsedLongPressRunnable)
                    if (!longPressTriggered) {
                        if (isDragging) {
                            val dx = event.rawX - startRawX
                            val inward = if (dockSide == "left") dx > SWIPE_THRESHOLD else dx < -SWIPE_THRESHOLD
                            if (inward) switchToExpanded()
                        } else {
                            // Plain tap expands too — swipe-only made the thin
                            // handle very hard to trigger.
                            switchToExpanded()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(collapsedLongPressRunnable)
                    true
                }
                else -> false
            }
        }

        rootView = collapsedRoot
        windowManager?.addView(rootView, layoutParams)
        rootView?.let { FloatingPenGuard.track("toolbar", it) }
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "collapsed handle shown, side=$dockSide")
    }

    private fun createExpandedToolbar() {
        val ctx = android.view.ContextThemeWrapper(reactApplicationContext, android.R.style.Theme_DeviceDefault_Light)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(TAG, "createExpandedToolbar package=${ctx.packageName}")
        }

        windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        refreshScreenDimensions()

        val borderPx = dpToPx(BORDER_WIDTH)
        val cornerR  = dpToPx(CORNER_RADIUS_DP.toInt()).toFloat()
        val titleH   = dpToPx(TITLE_ROW_H_DP)
        val titleSep = dpToPx(TITLE_SEP_DP)

        expandedRoot = object : LinearLayout(ctx) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val lp = layoutParams as? WindowManager.LayoutParams ?: return false
                        startX = lp.x; startY = lp.y
                        startRawX = ev.rawX; startRawY = ev.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!TouchInput.isFinger(ev)) return false
                        if (!isDragging && (abs(ev.rawX - startRawX) > 10 || abs(ev.rawY - startRawY) > 10)) {
                            isDragging = true
                            return true
                        }
                    }
                }
                return false
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val lp = layoutParams as? WindowManager.LayoutParams ?: return true
                        startX = lp.x; startY = lp.y
                        startRawX = ev.rawX; startRawY = ev.rawY
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!TouchInput.isFinger(ev)) return true
                        val lp = layoutParams as? WindowManager.LayoutParams ?: return true
                        val dx = ev.rawX - startRawX; val dy = ev.rawY - startRawY
                        if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            refreshScreenDimensions()
                            val lp = layoutParams as? WindowManager.LayoutParams ?: return true
                            val vw = expandedRoot?.measuredWidth ?: 0
                            val distLeft = lp.x
                            val distRight = screenWidth - (lp.x + vw)
                            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "dragEnd: x=${lp.x} y=${lp.y} vw=$vw distLeft=$distLeft distRight=$distRight threshold=$EDGE_COLLAPSE_THRESHOLD")
                            if (distLeft <= EDGE_COLLAPSE_THRESHOLD) {
                                dockSide = "left"
                                savePositionToPrefs(0, lp.y)
                                switchToCollapsed()
                            } else if (distRight <= EDGE_COLLAPSE_THRESHOLD) {
                                dockSide = "right"
                                savePositionToPrefs(screenWidth - vw, lp.y)
                                switchToCollapsed()
                            } else {
                                snapToEdge()
                                val snapLp = layoutParams as? WindowManager.LayoutParams
                                savePositionToPrefs(snapLp!!.x, snapLp.y)
                                resetAutoCollapse()
                                emitEvent("onToolbarDragEnd", Arguments.createMap().apply {
                                    val endLp = layoutParams as? WindowManager.LayoutParams
                                    putInt("x", endLp!!.x)
                                    putInt("y", endLp.y)
                                })
                            }
                        }
                    }
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(borderPx, CLR_BORDER)
                cornerRadius = cornerR
            }

            setPadding(borderPx, borderPx, borderPx, borderPx)
            clipToPadding = true
        }

        val clipIconSz = dpToPx(CLIP_ICON_DP)
        val clipIconGap = dpToPx(3)
        fun makeClipIcon(i: Int): TextView {
            val slot = i + 1
            val filled = titleClipFilled.getOrElse(i) { false }
            return TextView(ctx).apply {
                text = slot.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f))
                setTextColor(if (filled) Color.WHITE else Color.BLACK)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(CLIP_RADIUS_DP).toFloat()
                    if (filled) {
                        setColor(Color.BLACK)
                    } else {
                        setColor(Color.TRANSPARENT)

                    }
                }
                setOnClickListener {
                    resetAutoCollapse()
                    emitEvent("onTitleClipTap", Arguments.createMap().apply { putString("slot", slot.toString()) })
                }
                setOnLongClickListener {
                    resetAutoCollapse()
                    emitEvent("onTitleClipLongPress", Arguments.createMap().apply { putString("slot", slot.toString()) })
                    true
                }
            }
        }

        val layerBtnSz = dpToPx(28)
        fun makeLayerBtn(iconId: String, fallbackText: String, action: () -> Unit): View {
            val iconDrawable = loadIconFromAssets(iconId, layerBtnSz, CLR_BTN_FG)
            val view: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dpToPx(3), dpToPx(3), dpToPx(3), dpToPx(3))
                }
            } else {
                TextView(ctx).apply {
                    text = fallbackText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(12f))
                    setTextColor(CLR_BTN_FG)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }
            }
            view.background = null
            view.layoutParams = LinearLayout.LayoutParams(layerBtnSz, layerBtnSz).apply {
                marginEnd = dpToPx(1)
            }
            view.setOnClickListener { resetAutoCollapse(); action() }
            return view
        }

        fun makeSidebarAppendBtn(sz: Int): View {
            val iconDrawable = loadIconFromAssets("sticky_note", sz, CLR_BTN_FG)
            val view: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                }
            } else {
                TextView(ctx).apply {
                    text = "P+"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f))
                    setTextColor(CLR_BTN_FG)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }
            }
            view.background = null
            view.layoutParams = LinearLayout.LayoutParams(sz, sz)
            view.setOnClickListener { resetAutoCollapse(); startAppendPageCapture() }
            return view
        }

        fun makeSidebarSwapBtn(sz: Int): View {
            val iconDrawable = loadIconFromAssets("more_vert", sz, CLR_BTN_FG)
            val view: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5))
                }
            } else {
                TextView(ctx).apply {
                    text = "⋮"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14f))
                    setTextColor(CLR_BTN_FG)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }
            }
            view.background = null
            view.layoutParams = LinearLayout.LayoutParams(sz, sz)
            view.setOnClickListener {
                dockToNearestEdge()
            }
            return view
        }

        var dragSpacer: View

        if (orientation == "vertical") {

            val titleColW = dpToPx(CLIP_ICON_DP + 6)

            val bodyRow = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            toolContainer = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val p = dpToPx(BORDER_WIDTH + PANEL_PAD_DP) - borderPx
                setPadding(p, p, dpToPx(2), p)
            }
            bodyRow.addView(toolContainer)

            bodyRow.addView(View(ctx).apply {
                setBackgroundColor(CLR_SEP)
                layoutParams = LinearLayout.LayoutParams(titleSep, LinearLayout.LayoutParams.MATCH_PARENT)
            })

            val titleCol = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundColor(CLR_SIDEBAR_BG)
                layoutParams = LinearLayout.LayoutParams(titleColW, LinearLayout.LayoutParams.MATCH_PARENT)
                setPadding(dpToPx(3), dpToPx(7), dpToPx(3), dpToPx(4))
            }

            dragSpacer = View(ctx)

            val showAllVerticalClips = tools.size >= 7
            if (showAllVerticalClips) clipPage = 0

            var clipSwipeStartY = 0f
            var clipSwipeStartX = 0f
            var clipSwipeCaptured = false
            val clipCol = object : LinearLayout(ctx) {
                override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
                    if (showAllVerticalClips) return false
                    when (ev.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            clipSwipeStartX = ev.rawX
                            clipSwipeStartY = ev.rawY
                            clipSwipeCaptured = false
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = Math.abs(ev.rawX - clipSwipeStartX)
                            val dy = Math.abs(ev.rawY - clipSwipeStartY)
                            if (!clipSwipeCaptured && dy > dpToPx(12) && dy > dx) {
                                clipSwipeCaptured = true
                                return true
                            } else if (!clipSwipeCaptured && dx > dpToPx(12) && dx >= dy) {
                                parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                        android.view.MotionEvent.ACTION_UP,
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            clipSwipeCaptured = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    return false
                }
                override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                    if (showAllVerticalClips) return false
                    when (ev.action) {
                        android.view.MotionEvent.ACTION_UP -> {
                            if (clipSwipeCaptured) {
                                val dy = ev.rawY - clipSwipeStartY
                                if (dy < -dpToPx(15) && clipPage == 0) {
                                    clipPage = 1; rebuildClipIcons()
                                } else if (dy > dpToPx(15) && clipPage == 1) {
                                    clipPage = 0; rebuildClipIcons()
                                }
                            }
                            clipSwipeCaptured = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            clipSwipeCaptured = false
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                    return clipSwipeCaptured || ev.action == android.view.MotionEvent.ACTION_UP
                }
            }.apply {
                this.orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val visibleClipCount = if (showAllVerticalClips) 6 else 4
            val clipOffset = if (showAllVerticalClips) 0 else clipPage * 2
            for (vi in 0 until visibleClipCount) {
                val di = clipOffset + vi
                val tv = makeClipIcon(di).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(CLIP_ICON_DP), dpToPx(CLIP_ICON_DP)).apply {
                        bottomMargin = dpToPx(2)
                    }
                }
                clipIconViews[vi] = tv
                clipCol.addView(tv)
            }
            titleCol.addView(clipCol)

            titleCol.addView(makeLayerBtn("layer_up", "L↑") {
                emitEvent("onTitleLayerAction", Arguments.createMap().apply { putString("direction", "prev") })
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dpToPx(12) })
            titleCol.addView(makeLayerBtn("layer_down", "L↓") {
                emitEvent("onTitleLayerAction", Arguments.createMap().apply { putString("direction", "next") })
            })

            if (BuildConfig.ENABLE_DEBUG) {
                titleCol.addView(makeSidebarAppendBtn(clipIconSz).apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = dpToPx(12)
                })
            }

            titleCol.addView(makeSidebarSwapBtn(clipIconSz).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dpToPx(if (BuildConfig.ENABLE_DEBUG) 2 else 12)
            })
            bodyRow.addView(titleCol)

            expandedRoot!!.addView(bodyRow)
            rebuildButtons()

        } else {

            val titleRow = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, titleH
                )
                setBackgroundColor(CLR_SIDEBAR_BG)
            }
            val clipRow = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, titleH
                )
                setPadding(dpToPx(2), 0, dpToPx(1), 0)
            }
            for (i in 0 until 6) {
                val tv = makeClipIcon(i).apply {
                    layoutParams = LinearLayout.LayoutParams(clipIconSz, clipIconSz).apply {
                        marginEnd = dpToPx(2)
                    }
                }
                clipIconViews[i] = tv
                clipRow.addView(tv)
            }
            titleRow.addView(clipRow)

            titleRow.addView(View(ctx).apply {
                setBackgroundColor(CLR_SEP)
                layoutParams = LinearLayout.LayoutParams(dpToPx(1), (titleH * 0.6f).toInt()).apply {
                    marginStart = dpToPx(2)
                    marginEnd = dpToPx(2)
                }
            })

            dragSpacer = View(ctx)

            titleRow.addView(makeLayerBtn("layer_up", "L↑") {
                emitEvent("onTitleLayerAction", Arguments.createMap().apply { putString("direction", "prev") })
            })
            titleRow.addView(makeLayerBtn("layer_down", "L↓") {
                emitEvent("onTitleLayerAction", Arguments.createMap().apply { putString("direction", "next") })
            })

            titleRow.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, titleH, 1f)
            })

            if (BuildConfig.ENABLE_DEBUG) titleRow.addView(makeSidebarAppendBtn(layerBtnSz))
            titleRow.addView(makeSidebarSwapBtn(layerBtnSz))
            expandedRoot!!.addView(titleRow)

            expandedRoot!!.addView(View(ctx).apply {
                setBackgroundColor(CLR_SEP)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, titleSep)
            })

            toolContainer = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val p = dpToPx(BORDER_WIDTH + PANEL_PAD_DP) - borderPx; setPadding(p, p, p, p)
            }
            expandedRoot!!.addView(toolContainer)
            rebuildButtons()
        }

        @Suppress("DEPRECATION")
        val wmType = WindowManager.LayoutParams.TYPE_PHONE

        loadPositionFromPrefs()
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START

            x = if (stickyX >= 0) stickyX else dpToPx(24)
            y = if (stickyY >= 0) stickyY else screenHeight / 2 - dpToPx(80)
        }

        rootView = expandedRoot
        try {
            windowManager?.addView(rootView, layoutParams)
            if (BuildConfig.ENABLE_DEBUG) {
                Log.i(TAG, "createExpandedToolbar addView complete type=$wmType x=${layoutParams?.x} y=${layoutParams?.y}")
            }
            startForegroundMonitor()
            rootView?.let { FloatingPenGuard.track("toolbar", it) }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "expanded toolbar shown (2-row horizontal), ${tools.size} tools")

            // (Re)arm the near-edge auto-collapse timer here, centrally: every
            // rebuild path (pen lock toggle, panel restore, retry) goes through
            // this function, and removeAll() has just cancelled any pending
            // timer — without this the toolbar never collapses again.
            expandedRoot?.post { resetAutoCollapse() }
        } catch (e: Exception) {

            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "addView failed (${e.message}), retrying in 500ms")
            rootView = null
            expandedRoot = null; toolContainer = null; layoutParams = null
            handler.postDelayed({ if (!isInNoteApp) return@postDelayed; createExpandedToolbar() }, 500)
        }
    }

    private fun loadIconFromAssets(toolId: String, sizePx: Int, tintColor: Int): android.graphics.drawable.Drawable? {
        val assetName = when (toolId) {
            "insert_image"          -> "icons/ic_tool_image.xml"
            "insert_doc_screenshot" -> "icons/ic_tool_doc.xml"
            "insert_text"           -> "icons/ic_tool_text.xml"
            "insert_link"           -> "icons/ic_tool_link.xml"
            "voice_transcribe"      -> "icons/ic_tool_voice.xml"
            "invert_ink"            -> "icons/ic_tool_invert.xml"
            "send_ai", "screenshot_ai" -> "icons/ic_tool_lasso_ai.xml"
            "layer_up"              -> "icons/ic_tool_layer_up.xml"
            "layer_down"            -> "icons/ic_tool_layer_down.xml"
            "sticky_note"           -> "icons/ic_tool_sticky.xml"
            "more_vert"             -> "icons/ic_tool_more.xml"
            else                    -> return null
        }
        return try {
            val ctx = reactApplicationContext
            val paths = mutableListOf<Pair<String, Boolean>>()
            ctx.assets.open(assetName).use { stream ->
                val parser = android.util.Xml.newPullParser()
                parser.setFeature(android.util.Xml.FEATURE_RELAXED, true)
                parser.setInput(stream, "UTF-8")
                var evt = parser.eventType
                while (evt != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (evt == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "path") {
                        val pd = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "pathData") ?: ""
                        val fill = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "fillColor")
                        val isFill = fill != null && fill != "#00000000"
                        if (pd.isNotEmpty()) paths.add(pd to isFill)
                    }
                    evt = parser.next()
                }
            }
            if (paths.isEmpty()) return null

            val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val scale = sizePx / 24f
            canvas.scale(scale, scale)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = tintColor }
            for ((pd, isFill) in paths) {
                val p = android.graphics.Path()
                try {
                    val cls = Class.forName("android.util.PathParser")
                    val method = cls.getMethod("createPathFromPathData", String::class.java)
                    (method.invoke(null, pd) as? android.graphics.Path)?.let { p.set(it) }
                } catch (_: Exception) {}
                if (isFill) {
                    paint.style = android.graphics.Paint.Style.FILL
                } else {
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = 1.5f / scale * scale
                    paint.strokeCap = android.graphics.Paint.Cap.ROUND
                    paint.strokeJoin = android.graphics.Paint.Join.ROUND
                }
                canvas.drawPath(p, paint)
            }
            android.graphics.drawable.BitmapDrawable(reactApplicationContext.resources, bmp)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "loadIconFromAssets $assetName failed: ${e.message}")
            null
        }
    }

    /**
     * When a plugin JS exception makes the host destroy our React instance,
     * the Context's AssetManager is closed — constructing any View then
     * crashes the whole pluginhost process (ArrayIndexOutOfBoundsException in
     * TextView's theme lookup). Check before building views.
     */
    private fun isReactContextUsable(): Boolean = try {
        reactApplicationContext.hasActiveReactInstance()
    } catch (_: Throwable) { false }

    private fun rebuildButtons() {
        val c = toolContainer ?: return
        if (!isReactContextUsable()) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "rebuildButtons: react instance destroyed, removing overlays")
            try { removeAll() } catch (_: Exception) {}
            return
        }
        c.removeAllViews()
        try {
        val ctx = reactApplicationContext
        val btnSz = dpToPx(if (orientation == "vertical") 48 else BTN_SIZE_DP)
        val gap = dpToPx(BTN_GAP_DP)

        val n = tools.size

        fun makeToolButton(idx: Int, tool: ToolItem): View {

            val isActive = (tool.latches && activeModeIds.contains(tool.id)) ||
                activeModeIds.contains(tool.action)
            val activeBg = CLR_BTN_ACT
            val inactiveFg = CLR_BTN_FG

            val iconDrawable = loadIconFromAssets(tool.id, btnSz, if (isActive) android.graphics.Color.WHITE else inactiveFg)
            if (idx == 0) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "iconLookup: tool=${tool.id} loaded=${iconDrawable != null}")
            }

            val view: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val padPx = dpToPx(if (orientation == "vertical") 10 else 12)
                    setPadding(padPx, padPx, padPx, padPx)
                    background = GradientDrawable().apply {
                        setColor(if (isActive) activeBg else Color.TRANSPARENT)
                        cornerRadius = 0f
                    }
                }
            } else {
                TextView(ctx).apply {
                    text = tool.icon
                    setTextSize(TypedValue.COMPLEX_UNIT_SP,
                        sp(if (orientation == "vertical") 18f else BTN_TEXT_SIZE_SP))
                    setTextColor(if (isActive) Color.WHITE else inactiveFg)
                    typeface = Typeface.DEFAULT
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(if (isActive) activeBg else Color.TRANSPARENT)
                        cornerRadius = 0f
                    }
                }
            }

            view.layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                marginStart = gap / 2; marginEnd = gap - gap / 2
            }
            view.setOnClickListener { handleToolTap(tool, view) }
            val hasLongPressAction = tool.action == "insert_doc_screenshot" ||
                tool.action == "lasso_smart_send" || tool.action == "invert_ink"
            val pressGuardOwner = "toolbar-long-press:${tool.id}"
            if (hasLongPressAction) {
                view.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN ->
                            FloatingPenGuard.setFullScreenActive(true, pressGuardOwner)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            handler.postDelayed({
                                FloatingPenGuard.setFullScreenActive(false, pressGuardOwner)
                            }, 180L)
                    }
                    false
                }
            }
            view.setOnLongClickListener {
                if (tool.action == "insert_doc_screenshot") {
                    openDocScreenshotPanel()
                } else if (tool.action == "lasso_smart_send") {
                    handleSendLongPress()
                } else if (tool.action == "invert_ink") {
                    PaletteBubbleModule.toggleStatic()
                } else {
                    emitEvent("onToolLongPress", Arguments.createMap().apply {
                        putString("toolId", tool.id); putString("toolName", tool.name)
                    })
                }
                handler.postDelayed({
                    FloatingPenGuard.setFullScreenActive(false, pressGuardOwner)
                }, 350L)
                true
            }
            return view
        }

        if (orientation == "vertical") {

            val col = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for ((i, t) in tools.withIndex()) {
                col.addView(makeToolButton(i, t).apply {
                    (layoutParams as LinearLayout.LayoutParams).apply { bottomMargin = gap }
                })
            }
            c.addView(col)
        } else {

            val row = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for ((i, t) in tools.withIndex()) row.addView(makeToolButton(i, t))
            c.addView(row)
        }

        try {
            windowManager?.updateViewLayout(rootView, layoutParams)
        } catch (_: Exception) {}
        } catch (t: Throwable) {
            // Last-ditch: never let a stale AssetManager kill the pluginhost.
            Log.w(TAG, "rebuildButtons failed (react context gone?): ${t.message}")
            try { removeAll() } catch (_: Exception) {}
        }
    }

    private fun snapToEdge() {
        refreshScreenDimensions()
        val lp = layoutParams ?: return; val root = rootView ?: return
        val vw = root.measuredWidth.takeIf { it > 0 } ?: root.width
        val vh = root.measuredHeight.takeIf { it > 0 } ?: root.height
        if (vw <= 0 || vh <= 0) return
        val cx = lp.x + vw / 2

        if (lp.x < SNAP_THRESHOLD) lp.x = 0
        else if (screenWidth - (lp.x + vw) < SNAP_THRESHOLD) lp.x = screenWidth - vw
        else if (abs(cx - screenWidth / 2) < SNAP_THRESHOLD) lp.x = screenWidth / 2 - vw / 2

        if (lp.y < SNAP_THRESHOLD) lp.y = 0
        else if (screenHeight - (lp.y + vh) < SNAP_THRESHOLD) lp.y = screenHeight - vh

        lp.x = lp.x.coerceIn(0, (screenWidth - vw).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenHeight - vh).coerceAtLeast(0))
        try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
    }

    private fun dockToNearestEdge() {
        refreshScreenDimensions()
        val lp = layoutParams ?: return
        val vw = expandedRoot?.measuredWidth?.takeIf { it > 0 }
            ?: expandedRoot?.width?.takeIf { it > 0 } ?: 0
        val cx = lp.x + vw / 2
        dockSide = if (cx <= screenWidth / 2) "left" else "right"
        preDockX = lp.x; preDockY = lp.y
        val snapX = if (dockSide == "left") 0 else screenWidth - vw
        savePositionToPrefs(snapX, lp.y)
        switchToCollapsed()
    }

    private fun handleToolTap(tool: ToolItem, view: View) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[BUBBLE-DBG] handleToolTap id=${tool.id} action=${tool.action} latches=${tool.latches}")
        if (tool.latches) {
            if (activeModeIds.contains(tool.id)) {
                activeModeIds.remove(tool.id)
                emitEvent("onToolModeExit", Arguments.createMap().apply {
                    putString("toolId", tool.id)
                    putString("toolAction", tool.action)
                })
                rebuildButtons()
                return
            } else {
                activeModeIds.add(tool.id)
                rebuildButtons()
            }
        } else {
            flashCell(view)
        }

        if (tool.action == "invert_ink") {
            emitEvent("onToolTap", Arguments.createMap().apply {
                putString("toolId", tool.id)
                putString("toolAction", tool.action)
            })
            return
        }

        val isNativePanel = tool.action == "lasso_send"

        if (isNativePanel) {
            removeAll()
            when {
                tool.action == "lasso_send" -> {
                    // Ask for FILE:READ BEFORE opening the panel: the lasso
                    // extraction (text/image read) starts as soon as the panel
                    // opens, so a grant dialog popping mid-extraction comes too
                    // late — after granting, the already-failed extraction
                    // cannot be re-triggered from inside the panel.
                    requestPluginFileReadPermission { granted ->
                        handler.post {
                            if (!granted) {
                                android.widget.Toast.makeText(
                                    reactApplicationContext,
                                    NativeLocale.t("file_read_permission_needed"),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                openPluginSettingsAfterFileReadDenied()
                                restoreToolbar()
                                return@post
                            }
                            pendingScreen = "nativeSendHelper"
                            openNativePanel("send") {
                                SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
                            }
                            handler.postDelayed({
                                if (pendingScreen != "nativeSendHelper") return@postDelayed
                                callShowPluginView()
                                emitOpenMainWithRetries("nativeSendHelper")
                            }, 150)
                        }
                    }
                }
            }
        } else {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[BUBBLE-DBG] emit onToolTap id=${tool.id} action=${tool.action}")
            emitEvent("onToolTap", Arguments.createMap().apply {
                putString("toolId", tool.id); putString("toolAction", tool.action)
                putString("toolName", tool.name)
            })
        }
    }

    private fun flashCell(v: View) {
        val bg = v.background as? GradientDrawable ?: return
        bg.setColor(Color.parseColor("#0F0F10"))
        if (v is TextView) v.setTextColor(Color.WHITE)
        if (v is ImageView) v.setColorFilter(Color.WHITE)
        handler.postDelayed({
            bg.setColor(Color.TRANSPARENT)
            if (v is TextView) v.setTextColor(CLR_BTN_FG)
            if (v is ImageView) v.setColorFilter(CLR_BTN_FG)
        }, 120L)
    }

    @ReactMethod
    fun queryActiveMode(promise: Promise) {

        val ids = synchronized(activeModeIds) { activeModeIds.toList() }
        val pick = ids.firstOrNull { it == "insert_text" } ?: ids.firstOrNull()
        promise.resolve(pick)
    }

    @ReactMethod
    fun exitActiveMode() {
        UiThreadUtil.runOnUiThread { rebuildButtons() }
        val changed = activeModeIds.removeAll(LATCHING_ACTIONS)
        if (changed) UiThreadUtil.runOnUiThread { rebuildButtons() }
    }

    @ReactMethod
    fun exitMode(toolId: String) {
        if (activeModeIds.remove(toolId)) {
            UiThreadUtil.runOnUiThread { rebuildButtons() }
        }
    }

    @ReactMethod
    fun setActiveModes(json: String) {
        try {
            val arr = org.json.JSONArray(json)
            synchronized(activeModeIds) {

                val keepPalette = activeModeIds.contains("invert_ink")
                activeModeIds.clear()
                for (i in 0 until arr.length()) activeModeIds.add(arr.getString(i))
                if (keepPalette) activeModeIds.add("invert_ink")
            }
            UiThreadUtil.runOnUiThread { rebuildButtons() }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "setActiveModes: ${e.message}")
        }
    }

    fun setToolActive(toolId: String, active: Boolean) {
        val changed = if (active) activeModeIds.add(toolId) else activeModeIds.remove(toolId)
        if (changed) {
            UiThreadUtil.runOnUiThread {
                if (!collapsed && toolContainer != null) rebuildButtons()
            }
        }
    }

    @ReactMethod
    fun closeAllForSettings() {
        handler.post {

            ToolRegistry.hideAll()

            removeAll()

            activeModeIds.clear()

            pendingScreen = ""
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "closeAllForSettings: all overlays cleared")
        }
    }

    private fun parseTools(json: String, persistIfChanged: Boolean = false) {
        tools.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val action = o.optString("action", "")
                val id = o.getString("id")
                tools.add(ToolItem(
                    id,
                    o.optString("name",""),
                    o.optString("icon","?"),
                    action,
                    action in LATCHING_ACTIONS,
                    o.optString("nameKey", defaultToolNameKey(id))
                ))
            }
        } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "parseTools: ${e.message}") }
        applyDebugToolFilter(persist = persistIfChanged)
    }

    private fun applyDebugToolFilter(persist: Boolean) {
        val sanitized = sanitizeDebugTools(tools, { it.id }, { it.action })
        if (sanitized.size == tools.size) return
        tools.clear()
        tools.addAll(sanitized)
        if (persist) persistSanitizedTools()
    }

    private fun persistSanitizedTools() {
        try {
            val arr = JSONArray()
            for (tool in tools) {
                arr.put(JSONObject().apply {
                    put("id", tool.id)
                    put("nameKey", tool.nameKey)
                    put("name", tool.storedName)
                    put("icon", tool.icon)
                    put("action", tool.action)
                    put("latches", tool.latches)
                })
            }
            val data = JSONObject().put("tools", arr)
            reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
                .edit().putString("preset_1", data.toString()).apply()
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "persistSanitizedTools: ${e.message}")
        }
    }

    private fun removeAll() {
        pendingShow = false
        handler.removeCallbacks(autoCollapseRunnable)
        if (rootView != null) {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null
        }
        expandedRoot = null; toolContainer = null; collapsedRoot = null; layoutParams = null

        clipIconViews = arrayOfNulls(6)
    }

    private fun hideAllNativePanels() = ToolRegistry.hideAll()
    private fun suspendAllNativePanels() = ToolRegistry.suspendAll()
    private fun resumeAllNativePanels() = ToolRegistry.resumeAll()

    private val density: Float get() = reactApplicationContext.resources.displayMetrics.density

    private val toolbarExtraScale: Float get() {
        val dm = reactApplicationContext.resources.displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        return if (longSide == 2560 && shortSide == 1920) 1.1f else 1.0f
    }
    private val scaleFactor: Float get() = maxOf(0.86f, com.supernote_quicktoolbar.ui_common.ScreenScale.toolbarFactor(reactApplicationContext)) * toolbarExtraScale
    private fun dpToPx(dp: Int): Int = (dp * density * scaleFactor).roundToInt()
    private fun sp(v: Float): Float = v * scaleFactor

    private fun emitOpenMainWithRetries(guardScreen: String) {
        for (delay in RETRY_DELAYS_MS) {
            handler.postDelayed({
                if (pendingScreen == guardScreen) {
                    emitEvent("onToolbarOpenMain", Arguments.createMap())
                }
            }, delay)
        }
    }

    private inline fun withNativePluginManager(label: String, block: (Any) -> Unit) {
        try {
            val pm = reactApplicationContext.catalystInstance.getNativeModule("NativePluginManager") ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "$label: NativePluginManager not found"); return
            }
            block(pm)
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "$label: ${e.message}", e)
        }
    }

    override fun emitEvent(name: String, params: WritableMap) {
        try {
            reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(name, params)
        } catch (e: Exception) { if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "emitEvent($name): ${e.message}") }
    }

    override fun onCatalystInstanceDestroy() {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "onCatalystInstanceDestroy — keeping toolbar alive (rootView=${rootView != null})")
        super.onCatalystInstanceDestroy()
    }

    fun requestInsertImage(path: String) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] requestInsertImage: $path (currentInstance=${currentInstance === this})")
        insertPluginViewClosed = false
        beginInsertImageGuard()

        handler.post {
            val retryDelays = longArrayOf(0, 300, 750)
            for (delay in retryDelays) {
                handler.postDelayed({
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] emit nativeInsertImage (delay=${delay}ms)")
                    emitEvent("nativeInsertImage", Arguments.createMap().apply {
                        putString("path", path)
                        if (insertNextChainActive) putBoolean("fromInsertNext", true)
                    })
                }, delay)
            }
        }
    }

    @ReactMethod
    override fun restoreToolbar() {
        handler.post {
            if (!isInNoteApp) return@post
            if (anyNativeOverlayOwnsScreen()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "restoreToolbar: skipped, native overlay on screen")
                return@post
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] restoreToolbar: tools=${tools.size} currentInstance=${currentInstance === this} rootView=${rootView != null} collapsed=$collapsed")
            if (tools.isNotEmpty()) {
                removeAll()
                try {
                    restoreToolbarWindow()
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[INSERT-DBG/Kt] restoreToolbar FAILED: ${e.message}", e)
                }
            }
        }
    }

    internal fun restoreToolbarWindow() {
        if (collapsed) createCollapsedHandle() else createExpandedToolbar()
    }

    @ReactMethod
    fun requestClosePluginView() {
        handler.post {
            callClosePluginView()
        }
    }

    @ReactMethod
    fun setLassoData(text: String, imagePathsJson: String, linkedFilesJson: String?) {
        handler.post {
            val panel = SendPanel.currentInstance
            if (panel != null) {
                val paths = mutableListOf<String>()
                try {
                    val arr = JSONArray(imagePathsJson)
                    for (i in 0 until arr.length()) paths.add(arr.getString(i))
                } catch (_: Exception) {}

                val linkedFiles = mutableListOf<Triple<String, Int, String>>()
                try {
                    val lfArr = JSONArray(linkedFilesJson ?: "[]")
                    for (i in 0 until lfArr.length()) {
                        val obj = lfArr.getJSONObject(i)
                        linkedFiles.add(Triple(
                            obj.getString("path"),
                            obj.optInt("linkType", -1),
                            obj.optString("label", "file")
                        ))
                    }
                } catch (_: Exception) {}

                panel.updateLassoData(text, paths, linkedFiles)
            }
            pendingScreen = ""
        }
    }

    /**
     * Milliseconds since the note app returned to the foreground.
     * -1  = note app is NOT in the foreground right now.
     * Big = has been foreground since before the monitor noticed a switch.
     * Read synchronously by TextInserter before every insertText.
     */
    @ReactMethod(isBlockingSynchronousMethod = true)
    fun getNoteForegroundElapsedMs(): Double {
        if (!isInNoteApp()) return -1.0
        val since = noteForegroundSinceMs
        if (since == 0L) return 1e9
        return (android.os.SystemClock.elapsedRealtime() - since).toDouble()
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun drainImageQueue(): String? {
        synchronized(ImagePanel::class.java) {
            val queue = ImagePanel.imageQueue
            if (queue.isEmpty()) return null
            val json = org.json.JSONArray(queue).toString()
            queue.clear()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[QUEUE-DBG] drainImageQueue: drained $json")
            return json
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun drainReceivedDeletes(): String? {
        synchronized(ImagePanel::class.java) {
            val list = ImagePanel.pendingReceivedDeletes
            if (list.isEmpty()) return null
            val json = org.json.JSONArray(list).toString()
            list.clear()
            return json
        }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun drainDocLinkQueue(): String? {
        synchronized(DocLinkPanel::class.java) {
            val queue = DocLinkPanel.docLinkQueue
            if (queue.isEmpty()) return null
            val json = org.json.JSONArray(queue).toString()
            queue.clear()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[QUEUE-DBG] drainDocLinkQueue: drained $json")
            return json
        }
    }

    @ReactMethod
    fun showImagePanel() {
        handler.post {
            markToolbarForRestore()
            removeAll()
            openNativePanel("image") {
                ImagePanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
            }
        }
    }

    @ReactMethod
    fun showDocLinkPanel(currentFilePath: String?) {
        handler.post {
            removeAll()
            openNativePanel("doc") {
                DocLinkPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(currentFilePath)
            }
        }
    }

    @ReactMethod
    fun showPalettePanel(infoJson: String) {
        handler.post {
            removeAll()
            openNativePanel("palette") {
                PalettePanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(infoJson)
            }
        }
    }

    @ReactMethod
    fun toggleScreenshotBubble() {
        handler.post {
            if (ScreenshotBubble.isShowing) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toggleScreenshotBubble: hiding")
                ScreenshotBubble.pendingReshow = false
                ScreenshotBubble.hide()
            } else {
                if (stitchCommitPending || screenshotCapturePending || ScreenshotBubble.pendingReshow) {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toggleScreenshotBubble: ignored, screenshot flow pending")
                    return@post
                }
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "toggleScreenshotBubble: showing")
                startForegroundMonitor()
                isInNoteApp = checkIsNoteAppForeground()
                ScreenshotBubble.show(reactApplicationContext, this@FloatingToolbarModule)
            }
        }
    }

    @ReactMethod
    fun handleDocScreenshotCrop() {
        handler.post {
            if (stitchCommitPending || screenshotCapturePending) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] capture ignored while another screenshot operation is pending")
                return@post
            }
            screenshotCapturePending = true
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] handleDocScreenshotCrop: starting screencap")
            removeAll()
            val cacheDir = reactApplicationContext.cacheDir.absolutePath
            kotlin.concurrent.thread(isDaemon = false) {
                try {
                    val ts = System.currentTimeMillis()
                    val outPath = "$cacheDir/screenshot_crop_$ts.png"
                    val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
                    val completed = process.waitFor(15, TimeUnit.SECONDS)
                    if (!completed) process.destroyForcibly()
                    val exitCode = if (completed) process.exitValue() else -1
                    val file = java.io.File(outPath)
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] screencap completed=$completed exit=$exitCode size=${file.length()}")
                    if (!completed || exitCode != 0 || !file.exists() || file.length() <= 500) {
                        if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] screencap failed")
                        handler.post {
                            screenshotCapturePending = false
                            showScreenshotMessage("screencap_failed")
                            restoreAfterCropFlow()
                        }
                        return@thread
                    }
                    val dims = DocScreenshotService.getImageDimensions(outPath)
                    if (dims == null) {
                        if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] screencap decode failed")
                        handler.post {
                            screenshotCapturePending = false
                            showScreenshotMessage("screencap_failed")
                            restoreAfterCropFlow()
                        }
                        return@thread
                    }
                    val imgW = dims.first
                    val imgH = dims.second
                    val mayHaveSession = knownStitchSessionActive || DocScreenshotService.hasActiveSession()
                    if (!mayHaveSession) {
                        handler.post {
                            screenshotCapturePending = false
                            openCropPanelForDoc(outPath, imgW, imgH)
                        }
                        return@thread
                    }

                    handler.post {
                        requestScreenshotStoragePermissions(
                            action = "append captured stitch image",
                            onGranted = {
                                kotlin.concurrent.thread(isDaemon = true) {
                                    val activeSession = DocScreenshotService.loadSession()
                                    if (activeSession == null || activeSession.images.isEmpty()) {
                                        handler.post {
                                            screenshotCapturePending = false
                                            setKnownStitchSessionActive(false)
                                            openCropPanelForDoc(outPath, imgW, imgH)
                                        }
                                        return@thread
                                    }
                                    val updated = DocScreenshotService.addImage(outPath, imgW, imgH)
                                    handler.post {
                                        screenshotCapturePending = false
                                        if (updated != null && updated.images.size >= 2) {
                                            setKnownStitchSessionActive(true)
                                            openStitchPanel(updated)
                                        } else {
                                            showScreenshotMessage("screenshot_save_failed")
                                            restoreAfterCropFlow()
                                        }
                                    }
                                }
                            },
                            onDenied = {
                                screenshotCapturePending = false
                                restoreAfterCropFlow()
                            }
                        )
                    }
                } catch (e: Exception) {
                    if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] screencap error: ${e.message}", e)
                    handler.post {
                        screenshotCapturePending = false
                        showScreenshotMessage("screencap_failed")
                        restoreAfterCropFlow()
                    }
                } catch (e: OutOfMemoryError) {
                    if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] screencap out of memory", e)
                    handler.post {
                        screenshotCapturePending = false
                        showScreenshotMessage("screencap_failed")
                        restoreAfterCropFlow()
                    }
                }
            }
        }
    }

    private fun restoreAfterCropFlow() {
        ScreenshotBubble.reshowIfPending()
        scheduleRestore(RESTORE_DELAY_SHORT, "crop flow done")
    }

    private fun setKnownStitchSessionActive(active: Boolean) {
        knownStitchSessionActive = active
        reactApplicationContext
            .getSharedPreferences(SCREENSHOT_STATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(STITCH_SESSION_ACTIVE_KEY, active)
            .apply()
    }

    private fun openCropPanelForDoc(screenshotPath: String, imgW: Int, imgH: Int, fromStitch: Boolean = false) {
        val hasStitch = fromStitch || knownStitchSessionActive
        CropPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
            .showWithFooter(
                path = screenshotPath,
                hasStitchSession = hasStitch,
                onConfirm = { crop, stayOpen ->
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] crop confirm (insertNext): ${crop.width}x${crop.height} multi=$stayOpen")
                    requestScreenshotStoragePermissions(
                        action = "stage screenshot for insert",
                        onGranted = {
                            kotlin.concurrent.thread(isDaemon = true) {
                                val result = DocScreenshotService.stageToQueue(screenshotPath, crop, insertNext = true)
                                val clearedSession = result != null && fromStitch && DocScreenshotService.clearSession()
                                handler.post {
                                    if (clearedSession) setKnownStitchSessionActive(false)
                                    if (result == null) showScreenshotMessage("screenshot_save_failed")
                                    if (!stayOpen) restoreAfterCropFlow()
                                }
                            }
                        },
                        onDenied = { if (!stayOpen) restoreAfterCropFlow() }
                    )
                },
                onLongScreenshot = {
                    if (fromStitch) {
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] long screenshot: session kept, returning to capture")
                        setKnownStitchSessionActive(true)
                        CropPanel.currentInstance?.hide()
                        restoreAfterCropFlow()
                    } else if (!stitchCommitPending) {
                        stitchCommitPending = true
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] long screenshot: committing stitch session")
                        requestScreenshotStoragePermissions(
                            action = "start long screenshot",
                            onGranted = {
                                kotlin.concurrent.thread(isDaemon = true) {
                                    val existing = DocScreenshotService.loadSession()
                                    val session = if (existing != null) {
                                        DocScreenshotService.addImage(screenshotPath, imgW, imgH)
                                    } else {
                                        DocScreenshotService.startSession(screenshotPath, imgW, imgH)
                                    }
                                    handler.post {
                                        stitchCommitPending = false
                                        CropPanel.currentInstance?.hide()
                                        if (session != null) {
                                            setKnownStitchSessionActive(true)
                                            if (existing != null && session.images.size >= 2) {
                                                openStitchPanel(session)
                                            } else {
                                                restoreAfterCropFlow()
                                            }
                                        } else {
                                            showScreenshotMessage("screenshot_save_failed")
                                            restoreAfterCropFlow()
                                        }
                                    }
                                }
                            },
                            onDenied = {
                                stitchCommitPending = false
                                CropPanel.currentInstance?.hide()
                                restoreAfterCropFlow()
                            }
                        )
                    }
                },
                onSendToOtherDevices = { crop ->
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] send crop: ${crop.width}x${crop.height}")
                    val cameFromScreenshotBubble = ScreenshotBubble.pendingReshow
                    kotlin.concurrent.thread(isDaemon = true) {
                        val croppedPath = "${reactApplicationContext.cacheDir.absolutePath}/doc_crop_send_${System.currentTimeMillis()}.png"
                        val saved = DocScreenshotService.cropAndSave(screenshotPath, crop, croppedPath)
                        if (fromStitch) {
                            val cleared = DocScreenshotService.clearSession()
                            if (cleared) handler.post { setKnownStitchSessionActive(false) }
                        }
                        handler.post {
                            if (saved) {
                                val defaultPeer = LocalSendModule.getDefaultPeer(reactApplicationContext)
                                if (defaultPeer != null) {
                                    kotlin.concurrent.thread(isDaemon = true) {
                                        try {
                                            LocalSendModule.sendFileDirect(
                                                defaultPeer.ip, defaultPeer.port, croppedPath, defaultPeer.useTls)
                                            com.supernote_quicktoolbar.ui_common.Dialog.tip(
                                                reactApplicationContext, NativeLocale.t("send_success"))
                                        } catch (e: Exception) {
                                            val msg = if (e.message?.contains("403") == true)
                                                NativeLocale.t("image_send_rejected")
                                            else "${NativeLocale.t("send_failed")}: ${e.message}"
                                            com.supernote_quicktoolbar.ui_common.Dialog.tip(reactApplicationContext, msg)
                                        } finally {
                                            try { java.io.File(croppedPath).delete() } catch (_: Exception) {}
                                            handler.post { restoreAfterCropFlow() }
                                        }
                                    }
                                } else {
                                    val sendPanel = SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                                    sendPanel.show(
                                        fromBubble = cameFromScreenshotBubble,
                                        onShowResult = { shown ->
                                            if (shown) {
                                                sendPanel.updateLassoData("", listOf(croppedPath))
                                            } else {
                                                showScreenshotMessage("screenshot_panel_failed")
                                                restoreAfterCropFlow()
                                            }
                                        }
                                    )
                                }
                            } else {
                                showScreenshotMessage("screenshot_save_failed")
                                restoreAfterCropFlow()
                            }
                        }
                    }
                },
                onAddToHistory = { crop, stayOpen ->
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] add to history: ${crop.width}x${crop.height} multi=$stayOpen")
                    requestScreenshotStoragePermissions(
                        action = "save screenshot history",
                        onGranted = {
                            kotlin.concurrent.thread(isDaemon = true) {
                                val result = DocScreenshotService.saveToHistory(screenshotPath, crop)
                                val clearedSession = result != null && fromStitch && DocScreenshotService.clearSession()
                                handler.post {
                                    if (clearedSession) setKnownStitchSessionActive(false)
                                    if (result == null) showScreenshotMessage("screenshot_save_failed")
                                    if (!stayOpen) restoreAfterCropFlow()
                                }
                            }
                        },
                        onDenied = { if (!stayOpen) restoreAfterCropFlow() }
                    )
                },
                onCancel = {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] crop cancel")
                    if (fromStitch || knownStitchSessionActive) {
                        requestScreenshotStoragePermissions(
                            action = "clear long screenshot",
                            onGranted = {
                                kotlin.concurrent.thread(isDaemon = true) {
                                    val cleared = DocScreenshotService.clearSession()
                                    handler.post {
                                        if (cleared) setKnownStitchSessionActive(false)
                                        restoreAfterCropFlow()
                                    }
                                }
                            },
                            onDenied = { restoreAfterCropFlow() }
                        )
                    } else {
                        restoreAfterCropFlow()
                    }
                },
                onShowResult = { shown ->
                    if (!shown) {
                        showScreenshotMessage("screenshot_panel_failed")
                        restoreAfterCropFlow()
                    }
                }
            )
    }

    private fun openStitchPanel(session: DocScreenshotService.StitchSessionData) {
        StitchPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
            .show(
                session = session,
                onConfirm = { finalSession ->
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] stitch confirm: compositing ${finalSession.images.size} images...")
                    kotlin.concurrent.thread(isDaemon = false) {
                        try {
                            val nativeParams = org.json.JSONObject().apply {
                                put("direction", finalSession.params.direction)
                                put("overlap", finalSession.params.overlap)
                                put("topLayerIndex", finalSession.params.topLayerIndex)
                                put("cols", finalSession.params.cols)
                                put("gridOrder", finalSession.params.gridOrder)
                                put("images", org.json.JSONArray().apply {
                                    for (img in finalSession.images) {
                                        put(org.json.JSONObject().apply {
                                            put("path", img.path)
                                            put("width", img.width)
                                            put("height", img.height)
                                            put("crop", org.json.JSONObject().apply {
                                                put("cropTop", img.cropTop.toDouble())
                                                put("cropBottom", img.cropBottom.toDouble())
                                                put("cropLeft", img.cropLeft.toDouble())
                                                put("cropRight", img.cropRight.toDouble())
                                            })
                                        })
                                    }
                                })
                            }
                            if (!DocScreenshotService.updateSession(finalSession)) {
                                handler.post {
                                    StitchPanel.currentInstance?.hide()
                                    showScreenshotMessage("screenshot_save_failed")
                                    restoreAfterCropFlow()
                                }
                                return@thread
                            }
                            val compositePath = compositeImagesSync(nativeParams.toString())
                            if (compositePath != null) {
                                val compDims = DocScreenshotService.getImageDimensions(compositePath)
                                val compW = compDims?.first ?: 1920
                                val compH = compDims?.second ?: 2560
                                handler.post {
                                    StitchPanel.currentInstance?.hide()
                                    openCropPanelForDoc(compositePath, compW, compH, fromStitch = true)
                                }
                            } else {
                                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] composite returned null")
                                handler.post { StitchPanel.currentInstance?.hide(); restoreAfterCropFlow() }
                            }
                        } catch (e: Exception) {
                            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[CROP-DBG/Kt] composite error: ${e.message}", e)
                            handler.post { StitchPanel.currentInstance?.hide(); restoreAfterCropFlow() }
                        }
                    }
                },
                onCancel = {
                    if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CROP-DBG/Kt] stitch cancel: clearing session")
                    kotlin.concurrent.thread(isDaemon = true) {
                        val cleared = DocScreenshotService.clearSession()
                        handler.post {
                            if (cleared) setKnownStitchSessionActive(false)
                            restoreAfterCropFlow()
                        }
                    }
                },
                onShowResult = { shown ->
                    if (!shown) {
                        showScreenshotMessage("screenshot_panel_failed")
                        restoreAfterCropFlow()
                    }
                }
            )
    }

    private fun compositeImagesSync(paramsJson: String): String? {
        val json = org.json.JSONObject(paramsJson)
        val imagesArr = json.getJSONArray("images")
        if (imagesArr.length() < 2) return null

        data class ImgInfo(
            val path: String, val width: Int, val height: Int,
            val cropTop: Float, val cropBottom: Float,
            val cropLeft: Float, val cropRight: Float
        )
        val imgs = (0 until imagesArr.length()).map { i ->
            val obj = imagesArr.getJSONObject(i)
            val crop = obj.optJSONObject("crop")
            ImgInfo(
                obj.getString("path"), obj.getInt("width"), obj.getInt("height"),
                crop?.optDouble("cropTop", 0.0)?.toFloat() ?: 0f,
                crop?.optDouble("cropBottom", 0.0)?.toFloat() ?: 0f,
                crop?.optDouble("cropLeft", 0.0)?.toFloat() ?: 0f,
                crop?.optDouble("cropRight", 0.0)?.toFloat() ?: 0f,
            )
        }

        val cols = json.optInt("cols", 0)
        if (cols > 0 && imgs.size > 2) {
            val overlap = json.optInt("overlap", 0)
            val gridOrder = json.optString("gridOrder", "row")
            return compositeGrid(imagesArr, cols, overlap, gridOrder)
        }

        val direction = json.getString("direction")
        val overlap = json.getInt("overlap")
        val topLayerIndex = json.getInt("topLayerIndex")
        val bitmaps = imgs.map { android.graphics.BitmapFactory.decodeFile(it.path) ?: throw Exception("decode failed: ${it.path}") }
        val srcRects = imgs.map { img ->
            android.graphics.Rect(
                (img.width * img.cropLeft).toInt(),
                (img.height * img.cropTop).toInt(),
                (img.width * (1f - img.cropRight)).toInt(),
                (img.height * (1f - img.cropBottom)).toInt()
            )
        }
        val effW = srcRects.map { it.width() }
        val effH = srcRects.map { it.height() }
        val canvasW: Int; val canvasH: Int
        if (direction == "vertical") {
            canvasW = maxOf(effW[0], effW[1])
            canvasH = effH[0] + effH[1] - overlap
        } else {
            canvasW = effW[0] + effW[1] - overlap
            canvasH = maxOf(effH[0], effH[1])
        }
        if (canvasW <= 0 || canvasH <= 0) return null

        val result = android.graphics.Bitmap.createBitmap(canvasW, canvasH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        val dstRects = Array(2) { android.graphics.RectF() }
        if (direction == "vertical") {
            dstRects[0].set(0f, 0f, effW[0].toFloat(), effH[0].toFloat())
            dstRects[1].set(0f, (effH[0] - overlap).toFloat(), effW[1].toFloat(), (effH[0] - overlap + effH[1]).toFloat())
        } else {
            dstRects[0].set(0f, 0f, effW[0].toFloat(), effH[0].toFloat())
            dstRects[1].set((effW[0] - overlap).toFloat(), 0f, (effW[0] - overlap + effW[1]).toFloat(), effH[1].toFloat())
        }
        val drawOrder = if (topLayerIndex == 0) intArrayOf(1, 0) else intArrayOf(0, 1)
        for (idx in drawOrder) canvas.drawBitmap(bitmaps[idx], srcRects[idx], dstRects[idx], paint)

        val outPath = "${reactApplicationContext.cacheDir.absolutePath}/stitch_result_${System.currentTimeMillis()}.png"
        java.io.FileOutputStream(outPath).use { fos -> result.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos) }
        result.recycle(); bitmaps.forEach { it.recycle() }
        return outPath
    }

    private fun compositeGrid(imagesArr: org.json.JSONArray, cols: Int, overlap: Int, gridOrder: String = "row"): String? {
        val n = imagesArr.length()
        val rowCount = (n + cols - 1) / cols

        data class CellInfo(
            val path: String, val width: Int, val height: Int,
            val cropLeft: Float, val cropTop: Float, val cropRight: Float, val cropBottom: Float
        )
        val cells = (0 until n).map { i ->
            val obj = imagesArr.getJSONObject(i)
            val crop = obj.optJSONObject("crop")
            CellInfo(
                obj.getString("path"), obj.getInt("width"), obj.getInt("height"),
                crop?.optDouble("cropLeft", 0.0)?.toFloat() ?: 0f,
                crop?.optDouble("cropTop", 0.0)?.toFloat() ?: 0f,
                crop?.optDouble("cropRight", 0.0)?.toFloat() ?: 0f,
                crop?.optDouble("cropBottom", 0.0)?.toFloat() ?: 0f,
            )
        }

        val effWs = cells.map { ((1f - it.cropLeft - it.cropRight) * it.width).roundToInt() }
        val effHs = cells.map { ((1f - it.cropTop - it.cropBottom) * it.height).roundToInt() }

        val isVerticalStrip = cols == 1
        val isHorizontalStrip = cols >= n

        val canvasW: Int
        val canvasH: Int
        if (isVerticalStrip) {
            canvasW = effWs.max()
            canvasH = effHs.sum() - overlap * (n - 1)
        } else if (isHorizontalStrip) {
            canvasW = effWs.sum() - overlap * (n - 1)
            canvasH = effHs.max()
        } else {
            val cellW = effWs.max()
            val cellH = effHs.max()
            canvasW = cellW * cols
            canvasH = cellH * rowCount
        }
        if (canvasW <= 0 || canvasH <= 0) return null

        val result = android.graphics.Bitmap.createBitmap(canvasW, canvasH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

        fun decodeSrcRect(cell: CellInfo) = android.graphics.Rect(
            (cell.width * cell.cropLeft).toInt(),
            (cell.height * cell.cropTop).toInt(),
            (cell.width * (1f - cell.cropRight)).toInt(),
            (cell.height * (1f - cell.cropBottom)).toInt()
        )

        if (isVerticalStrip) {
            var yOff = 0f
            for ((i, cell) in cells.withIndex()) {
                val bmp = android.graphics.BitmapFactory.decodeFile(cell.path) ?: continue
                val ox = (canvasW - effWs[i]) / 2f
                canvas.drawBitmap(bmp, decodeSrcRect(cell),
                    android.graphics.RectF(ox, yOff, ox + effWs[i], yOff + effHs[i]), paint)
                bmp.recycle()
                yOff += effHs[i] - overlap
            }
        } else if (isHorizontalStrip) {
            var xOff = 0f
            for ((i, cell) in cells.withIndex()) {
                val bmp = android.graphics.BitmapFactory.decodeFile(cell.path) ?: continue
                val oy = (canvasH - effHs[i]) / 2f
                canvas.drawBitmap(bmp, decodeSrcRect(cell),
                    android.graphics.RectF(xOff, oy, xOff + effWs[i], oy + effHs[i]), paint)
                bmp.recycle()
                xOff += effWs[i] - overlap
            }
        } else {
            val cellW = effWs.max()
            val cellH = effHs.max()
            val isColOrder = gridOrder == "col"
            for ((i, cell) in cells.withIndex()) {
                val col = if (isColOrder) i / rowCount else i % cols
                val row = if (isColOrder) i % rowCount else i / cols
                val bmp = android.graphics.BitmapFactory.decodeFile(cell.path) ?: continue
                val scaleToFit = kotlin.math.min(cellW.toFloat() / effWs[i], cellH.toFloat() / effHs[i])
                val drawW = effWs[i] * scaleToFit
                val drawH = effHs[i] * scaleToFit
                val ox = col * cellW + (cellW - drawW) / 2f
                val oy = row * cellH + (cellH - drawH) / 2f
                canvas.drawBitmap(bmp, decodeSrcRect(cell),
                    android.graphics.RectF(ox, oy, ox + drawW, oy + drawH), paint)
                bmp.recycle()
            }
        }

        val outPath = "${reactApplicationContext.cacheDir.absolutePath}/stitch_grid_${System.currentTimeMillis()}.png"
        java.io.FileOutputStream(outPath).use { fos -> result.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos) }
        result.recycle()
        return outPath
    }

    @ReactMethod
    fun handleDocScreenshot() {
        handler.post {
            docScreenshotRoutePending = true
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] doc screenshot route pending=true")
            FloatingPenGuard.setFullScreenActive(true, "insert-image")
            markToolbarForRestore()
            removeAll()
            kotlin.concurrent.thread(isDaemon = true) {
                val nextFile = try {
                    DocScreenshotService.firstInsertNextFile()
                } catch (e: Exception) {
                    handler.post {
                        docScreenshotRoutePending = false
                        FloatingPenGuard.setFullScreenActive(false, "insert-image")
                        if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[INSERT-DBG/Kt] doc screenshot route failed: ${e.message}", e)
                        scheduleRestore(RESTORE_DELAY_NORMAL, "doc screenshot route failed")
                    }
                    return@thread
                }
                handler.post {
                    if (nextFile != null) {
                        val path = nextFile.absolutePath
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] handleDocScreenshot: insertNext=$path")
                        insertNextChainActive = true
                        kotlin.concurrent.thread(isDaemon = true) {
                            ImagePanel.saveToInsertCacheStatic(path, lastNotePath, lastPageNum)
                        }
                        handler.postDelayed({
                            try {
                                requestInsertImage(path)
                                docScreenshotRoutePending = false
                                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] doc screenshot route handed to insert-image")
                            } catch (e: Exception) {
                                docScreenshotRoutePending = false
                                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "requestInsertImage failed: ${e.message}")
                                endInsertImageGuard()
                                FloatingPenGuard.setFullScreenActive(false, "insert-image")
                                insertNextChainActive = false; restoreToolbar()
                            }
                        }, 500)
                    } else {
                        insertNextChainActive = false
                        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[INSERT-DBG/Kt] handleDocScreenshot: no insertNext, opening panel")
                        openNativePanel("screenshot") {
                            val panel = DocScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                            try {
                                panel.show("queue") { shown ->
                                    docScreenshotRoutePending = false
                                    if (shown && SubviewLogMonitor.isSubviewOpen()) {
                                        panel.suspendVisibility()
                                    }
                                    FloatingPenGuard.setFullScreenActive(false, "insert-image")
                                    if (BuildConfig.ENABLE_DEBUG) {
                                        Log.i(TAG, "[INSERT-DBG/Kt] doc screenshot panel settled shown=$shown subview=${SubviewLogMonitor.isSubviewOpen()}")
                                    }
                                    if (!shown) scheduleRestore(RESTORE_DELAY_NORMAL, "doc screenshot panel open failed")
                                }
                            } catch (e: Exception) {
                                docScreenshotRoutePending = false
                                FloatingPenGuard.setFullScreenActive(false, "insert-image")
                                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "[INSERT-DBG/Kt] doc screenshot panel open failed: ${e.message}", e)
                                scheduleRestore(RESTORE_DELAY_NORMAL, "doc screenshot panel open failed")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = reactApplicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun handleSendLongPress() {
        handler.post {
            if (!isWifiConnected()) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleSendLongPress: no wifi")
                com.supernote_quicktoolbar.ui_common.Dialog.tip(
                    reactApplicationContext, NativeLocale.t("no_wifi"))
                return@post
            }
            if (!LocalSendModule.staticIsRunning) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleSendLongPress: auto-start LocalSend")
                emitEvent("startLocalSendFromNative",
                    Arguments.createMap().apply { putBoolean("openClipboardSync", true) })
                return@post
            }
            val defaultPeer = LocalSendModule.getDefaultPeer(reactApplicationContext)
            if (defaultPeer != null) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleSendLongPress: direct-send to ${defaultPeer.alias}")
                directSendClipboard(defaultPeer)
                return@post
            }
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "handleSendLongPress: LocalSend running, open panel")
            openSendPanelClipboardSync()
        }
    }

    private fun directSendClipboard(peer: LocalSendModule.DiscoveredPeer) {
        kotlin.concurrent.thread(isDaemon = true) {
            try {
                val zipPath = LocalSendModule.packageClipboardStatic(reactApplicationContext)
                if (zipPath == null) {
                    com.supernote_quicktoolbar.ui_common.Dialog.tip(
                        reactApplicationContext, NativeLocale.t("sync_clipboard_empty"))
                    return@thread
                }
                LocalSendModule.sendFileDirect(peer.ip, peer.port, zipPath, peer.useTls)
                try { java.io.File(zipPath).delete() } catch (_: Exception) {}
                com.supernote_quicktoolbar.ui_common.Dialog.tip(
                    reactApplicationContext, NativeLocale.t("send_success"))
            } catch (e: Exception) {
                val msg = if (e.message?.contains("403") == true)
                    NativeLocale.t("sync_rejected")
                else "${NativeLocale.t("send_failed")}: ${e.message}"
                com.supernote_quicktoolbar.ui_common.Dialog.tip(reactApplicationContext, msg)
            }
        }
    }

    @ReactMethod
    fun openSendPanelClipboardSync() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "openSendPanelClipboardSync")
            removeAll()
            openNativePanel("send") {
                SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(syncClipboard = true)
            }
        }
    }

    fun openDocScreenshotPanel() {
        handler.post {
            markToolbarForRestore()
            removeAll()
            openNativePanel("screenshot") {
                DocScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show("queue")
            }
        }
    }

    private fun startAppendPageCapture() {
        startRegionCapture(mode = "appendPage", fromBubble = false)
    }

    private fun startRegionCapture(mode: String, fromBubble: Boolean) {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "startRegionCapture mode=$mode fromBubble=$fromBubble")
            removeAll()
            if (fromBubble) {
                FloatingBubbleModule.hideStatic()
                AiBubbleModule.hideStatic()
            }
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "lassoScreenshot") })
            try {
                penLassoOverlay?.dismissSilently()
                penLassoOverlay = null
                val overlay = PenLassoOverlay(reactApplicationContext)
                penLassoOverlay = overlay
                val actions = when (mode) {
                    "send" -> listOf(
                        PenLassoOverlay.Action.SEND_TO_DEVICES,
                        PenLassoOverlay.Action.ADD_TO_DOC_SCREENSHOTS,
                        PenLassoOverlay.Action.CANCEL,
                    )
                    "appendPage" -> listOf(
                        PenLassoOverlay.Action.INSERT_CURRENT_PAGE,
                        PenLassoOverlay.Action.ADD_TO_DOC_SCREENSHOTS,
                        PenLassoOverlay.Action.APPEND_PAGE_WITH_LINK,
                        PenLassoOverlay.Action.CANCEL,
                    )
                    else -> listOf(
                        PenLassoOverlay.Action.CONFIRM,
                        PenLassoOverlay.Action.CANCEL,
                    )
                }
                overlay.show(
                    actions = actions,
                    onAction = { action, l, t, r, b ->
                        handler.post { if (penLassoOverlay === overlay) penLassoOverlay = null }
                        val captureMode = when (action) {
                            PenLassoOverlay.Action.CONFIRM -> mode
                            PenLassoOverlay.Action.SEND_TO_DEVICES -> "send"
                            PenLassoOverlay.Action.ADD_TO_DOC_SCREENSHOTS -> "docScreenshot"
                            PenLassoOverlay.Action.INSERT_CURRENT_PAGE -> "insertCurrent"
                            PenLassoOverlay.Action.APPEND_PAGE_WITH_LINK -> "appendPage"
                            PenLassoOverlay.Action.CANCEL -> return@show
                        }
                        if (action == PenLassoOverlay.Action.ADD_TO_DOC_SCREENSHOTS) {
                            requestScreenshotStoragePermissions(
                                action = "save region to doc screenshots",
                                onGranted = {
                                    RegionCaptureFlow.capture(
                                        reactApplicationContext, this@FloatingToolbarModule,
                                        captureMode, fromBubble, l, t, r, b)
                                },
                                onDenied = {
                                    RegionCaptureFlow.emitCloseAndRestore(
                                        this@FloatingToolbarModule, reactApplicationContext, fromBubble, null)
                                }
                            )
                        } else {
                            RegionCaptureFlow.capture(
                                reactApplicationContext, this@FloatingToolbarModule,
                                captureMode, fromBubble, l, t, r, b)
                        }
                    },
                    onCancel = {
                        handler.post { if (penLassoOverlay === overlay) penLassoOverlay = null }
                        RegionCaptureFlow.emitCloseAndRestore(
                            this@FloatingToolbarModule, reactApplicationContext, fromBubble, null)
                    }
                )
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "startRegionCapture failed: ${e.message}", e)
                RegionCaptureFlow.emitCloseAndRestore(
                    this@FloatingToolbarModule, reactApplicationContext, fromBubble, null)
            }
        }
    }

    @ReactMethod
    fun showSendPanelFromBubble() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "showSendPanelFromBubble")
            pendingScreen = "nativeSendHelper"
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "send") })
            SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(fromBubble = true)
            handler.postDelayed({
                if (pendingScreen != "nativeSendHelper") return@postDelayed
                callShowPluginView()
                emitOpenMainWithRetries("nativeSendHelper")
            }, 150)
        }
    }

    @ReactMethod
    fun showLassoScreenshotPanelFromBubble() {
        startRegionCapture(mode = "ai", fromBubble = true)
    }

    @ReactMethod
    fun showLassoScreenshotPanelForSendFromBubble() {
        startRegionCapture(mode = "send", fromBubble = true)
    }

    fun showPluginView() {
        handler.post { callShowPluginView() }
    }

    fun closePluginView() {
        handler.post { callClosePluginView() }
    }

    fun onAppendPageCaptured(
        imagePath: String,
        cropX: Int = -1, cropY: Int = -1, cropW: Int = -1, cropH: Int = -1,
        imgW: Int = -1, imgH: Int = -1,
    ) {
        handler.post {
            emitEvent("onAppendPageAction", Arguments.createMap().apply {
                putString("imagePath", imagePath)
                if (cropX >= 0) {
                    putInt("cropX", cropX)
                    putInt("cropY", cropY)
                    putInt("cropW", cropW)
                    putInt("cropH", cropH)
                    putInt("imgW", imgW)
                    putInt("imgH", imgH)
                }
            })
        }
    }

    fun cancelPendingScreen() {
        pendingScreen = ""
    }

    fun destroyAll() {
        handler.post {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "destroyAll: removing all overlays + closing plugin")

            emitEvent("onToolbarDestroyAll", Arguments.createMap())

            stopForegroundMonitor()
            pendingScreen = ""
            pendingOpenMain = false
            activeModeIds.clear()
            tools.clear()

            removeAll()

            ToolRegistry.hideAll()
            FloatingBubbleModule.hideAndForget()
            AiBubbleModule.hideAndForget()
            ScreenshotBubble.pendingReshow = false
            ScreenshotBubble.hide()
            StickyNotes.clearAll()

            callClosePluginView()
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "destroyAll: done")
        }
    }

    @ReactMethod
    fun destroyAllFromJs() {
        destroyAll()
    }

    private fun rebuildClipIcons() {
        val showAllVerticalClips = orientation == "vertical" && tools.size >= 7
        val count = if (orientation == "vertical" && !showAllVerticalClips) 4 else 6
        val offset = if (orientation == "vertical" && !showAllVerticalClips) clipPage * 2 else 0
        for (vi in 0 until count) {
            val tv = clipIconViews[vi] ?: continue
            val di = offset + vi
            val filled = titleClipFilled.getOrElse(di) { false }
            val slot = di + 1
            tv.text = slot.toString()
            tv.setTextColor(if (filled) Color.WHITE else Color.BLACK)
            tv.background = if (filled) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(CLIP_RADIUS_DP).toFloat()
                    setColor(Color.BLACK)
                }
            } else {
                null
            }
            tv.setOnClickListener {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CLIP-DBG] onTitleClipTap slot=$slot filled=$filled")
                emitEvent("onTitleClipTap", Arguments.createMap().apply { putString("slot", slot.toString()) })
            }
            tv.setOnLongClickListener {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "[CLIP-DBG] onTitleClipLongPress slot=$slot")
                emitEvent("onTitleClipLongPress", Arguments.createMap().apply { putString("slot", slot.toString()) })
                true
            }
            tv.invalidate()
        }

        try {
            val rv = rootView; val lp = layoutParams; val wm = windowManager
            if (rv != null && lp != null && wm != null) {
                wm.updateViewLayout(rv, lp)
            }
        } catch (_: Exception) {}
    }

    private fun runMonitorTick() {
        val foreground = foregroundActivity() ?: return
        val fgPkg = foreground.packageName
        val noteCanvasForeground = fgPkg == NOTE_PACKAGE &&
            foreground.activityName == NOTE_INSIDE_PAGES_ACTIVITY
        val supportsHandwriting = noteCanvasForeground || fgPkg == DOC_PACKAGE
        val supportsScreenshotBubble = supportsHandwriting || fgPkg == WEREAD_PACKAGE
        if (!supportsHandwriting && rotationEpoch != 0L) {
            cancelRotationSync("left handwriting page")
        }

        FloatingPenGuard.setFullScreenActive(!supportsHandwriting, "foreign-activity")
        if (!supportsScreenshotBubble && ScreenshotBubble.isShowing) {
            monitorHandler.post { ScreenshotBubble.hideForInactiveCanvas() }
        }
        if (!supportsScreenshotBubble) StickyNotes.hideTemp()

        val fgKey = "$fgPkg/${foreground.activityName}"
        if (fgKey != lastForegroundKey) {
            val previous = lastForegroundKey
            lastForegroundKey = fgKey
            if (previous != null) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.i(TAG, "foreground activity changed: $previous → $fgKey")
                }
                scheduleForegroundGuardReassert(fgKey)
            }
        }

        if (fgPkg == SETTINGS_PACKAGE) {
            StickyNotes.hideTemp()
            if (ScreenshotBubble.isShowing && !ScreenshotBubble.hiddenByInactiveCanvas) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: in ratta settings, hiding ScreenshotBubble")
                monitorHandler.post { ScreenshotBubble.hideForSettings() }
            }
        } else if ((ScreenshotBubble.hiddenBySettings || ScreenshotBubble.hiddenByInactiveCanvas)
            && supportsScreenshotBubble
            && !SubviewLogMonitor.isSubviewOpen() && !isPluginHostWindowShowing) {
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: safe to restore ScreenshotBubble (fg=$fgPkg)")
            monitorHandler.post {
                if (ScreenshotBubble.hiddenBySettings) {
                    ScreenshotBubble.reshowIfHiddenBySettings()
                } else {
                    ScreenshotBubble.reshowIfHiddenByInactiveCanvas()
                }
                StickyNotes.reshowIfTemp()
            }
        }

        val inNote = fgPkg == PLUGIN_PACKAGE || noteCanvasForeground
        if (inNote != isInNoteApp) {
            if (inNote) noteForegroundSinceMs = android.os.SystemClock.elapsedRealtime()
            isInNoteApp = inNote
            // Tell JS: TextInserter must hold inserts while the note app
            // re-loads its page after returning to the foreground — inserting
            // during that window can SIGSEGV the note's native redraw.
            emitEvent("onNoteForegroundChanged", com.facebook.react.bridge.Arguments.createMap().apply {
                putBoolean("inNote", inNote)
            })
            if (!inNote) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: left note canvas, hiding overlays")
                markToolbarForRestore(parkGuards = false)
                terminateAfterPluginMenuClose = false
                FloatingPenGuard.setFullScreenActive(false, "host-toolbar-menu")
                FloatingPenGuard.setFullScreenActive(false, "host-lasso-menu")
                FloatingPenGuard.setFullScreenActive(false, "note-subview")
                monitorHandler.post {
                    FloatingPenGuard.endPark("left note canvas", flush = true)
                    removeAll()
                    suspendAllNativePanels()
                    FloatingBubbleModule.hideStatic()
                    AiBubbleModule.hideStatic()
                    PaletteBubbleModule.hideStatic()
                }
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor: returned to note canvas")
                scheduleRestore(RESTORE_DELAY_SHORT, "returned to note canvas")
            }
        }
    }

    fun startForegroundMonitor() {
        if (foregroundMonitorRunning) return
        foregroundMonitorRunning = true
        isInNoteApp = true

        if (windowStateMonitor == null) {
            windowStateMonitor = WindowStateMonitor(reactApplicationContext)
        }
        windowStateMonitor?.start()

        FloatingPenGuard.claimOwnership(reactApplicationContext.filesDir)

        FloatingPenGuard.beginHostBootstrap("foreground monitor start")
        SubviewLogMonitor.start(subviewListener)

        monitorHandler.removeCallbacks(staticMonitorRunnable)
        monitorHandler.postDelayed(staticMonitorRunnable, MONITOR_INTERVAL_MS)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "foreground monitor started")
    }

    fun stopForegroundMonitor() {
        cancelRotationSync("foreground monitor stopped")
        foregroundMonitorRunning = false
        monitorHandler.removeCallbacks(staticMonitorRunnable)
        // PluginHost visibility is also the recovery signal for a later Settings
        // config press. Keep this lightweight in-process hook alive after the
        // toolbar/panels are destroyed; otherwise the second showType=1 entry has
        // no native fallback when plugin_config_event is missed by the paused RN
        // runtime. It will be reused by the next startForegroundMonitor().
        SubviewLogMonitor.stop()
        FloatingPenGuard.setFullScreenActive(false, "host-toolbar-menu")
        monitorHandler.removeCallbacks(lassoFullScreenReleaseRunnable)
        FloatingPenGuard.setFullScreenActive(false, "host-lasso-menu")
        FloatingPenGuard.setFullScreenActive(false, "note-subview")
        FloatingPenGuard.setFullScreenActive(false, "foreign-activity")
        endInsertImageGuard()
        FloatingPenGuard.setFullScreenActive(false, "insert-image")
        docScreenshotRoutePending = false
        lassoWritableResetPending = false
        toolbarRestorePending = false
        cancelScheduledRestore()
        FloatingPenGuard.endPark("foreground monitor stopped", flush = true)
        terminateAfterPluginMenuClose = false
    }

    private data class ForegroundActivity(val packageName: String, val activityName: String)

    private val resumedActivityRegex = Regex("""mResumedActivity:.*?(\S+)/(\S+)\s""")

    private fun foregroundActivity(): ForegroundActivity? {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities"))
            val reader = proc.inputStream.bufferedReader()
            var matched: MatchResult? = null
            reader.useLines { lines ->
                for (line in lines) {
                    if ("mResumedActivity" in line) {
                        matched = resumedActivityRegex.find(line)
                    }
                }
            }
            proc.waitFor()
            if (matched != null) {
                val pkg = matched!!.groupValues[1]
                val rawActivity = matched!!.groupValues[2]
                val activity = if (rawActivity.startsWith('.')) pkg + rawActivity else rawActivity
                if (BuildConfig.ENABLE_DEBUG) Log.d(TAG, "foreground: $pkg/$activity")
                ForegroundActivity(pkg, activity)
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "foregroundActivity: no mResumedActivity found")
                null
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "foregroundActivity: ${e.message}")
            null
        }
    }

    private fun isNoteCanvasForeground(): Boolean {
        val foreground = foregroundActivity() ?: return false
        return foreground.packageName == NOTE_PACKAGE &&
            foreground.activityName == NOTE_INSIDE_PAGES_ACTIVITY
    }

    private fun checkIsNoteAppForeground(): Boolean {
        val foreground = foregroundActivity() ?: return true
        return foreground.packageName == PLUGIN_PACKAGE ||
            (foreground.packageName == NOTE_PACKAGE && foreground.activityName == NOTE_INSIDE_PAGES_ACTIVITY)
    }

    @ReactMethod
    fun showPenLassoOverlay() {
        Log.i(TAG, "showPenLassoOverlay: bridge call received")
        handler.post {
            try {
                Log.i(TAG, "showPenLassoOverlay: creating overlay (prev=${penLassoOverlay != null})")
                penLassoOverlay?.dismissSilently()
                penLassoOverlay = null
                val overlay = PenLassoOverlay(reactApplicationContext)
                penLassoOverlay = overlay
                overlay.show(
                    actions = listOf(
                        PenLassoOverlay.Action.CONFIRM,
                        PenLassoOverlay.Action.CANCEL,
                    ),
                    onAction = { _, l, t, r, b ->
                        handler.post {
                            if (penLassoOverlay === overlay) penLassoOverlay = null
                            emitEvent("onPenLassoBbox", Arguments.createMap().apply {
                                putInt("left", l); putInt("top", t)
                                putInt("right", r); putInt("bottom", b)
                            })
                        }
                    },
                    onCancel = {
                        handler.post {
                            if (penLassoOverlay === overlay) penLassoOverlay = null
                            emitEvent("onPenLassoCancel", Arguments.createMap())
                        }
                    }
                )
            } catch (e: Exception) {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "showPenLassoOverlay failed: ${e.message}", e)
                emitEvent("onPenLassoCancel", Arguments.createMap())
            }
        }
    }

    @ReactMethod
    fun dismissPenLassoOverlay() {
        handler.post {
            penLassoOverlay?.dismiss()
            penLassoOverlay = null
        }
    }

    private var insertTimerRunning = false
    private var insertTimerInterval = 800L
    private val insertTimerRunnable = object : Runnable {
        override fun run() {
            if (!insertTimerRunning) return
            try {
                reactApplicationContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                    .emit("onInsertTimerTick", null)
            } catch (_: Exception) {}
            handler.postDelayed(this, insertTimerInterval)
        }
    }

    @ReactMethod
    fun startInsertTimer(ms: Int) {
        handler.removeCallbacks(insertTimerRunnable)
        insertTimerInterval = ms.toLong().coerceAtLeast(100L)
        insertTimerRunning = true
        handler.postDelayed(insertTimerRunnable, insertTimerInterval)
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "startInsertTimer intervalMs=$insertTimerInterval")
    }

    @ReactMethod
    fun stopInsertTimer() {
        insertTimerRunning = false
        handler.removeCallbacks(insertTimerRunnable)
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
