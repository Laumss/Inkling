package com.supernote_quicktoolbar

import android.graphics.Color
import android.graphics.PixelFormat
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
import kotlin.math.abs
import kotlin.math.roundToInt

class FloatingToolbarModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "FloatingToolbar"

    companion object {
        private const val TAG = "FloatingToolbar"

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

        @JvmStatic
        private val activeModeIds: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf())

        @Volatile @JvmStatic
        private var pendingOpenMain: Boolean = false

        @Volatile @JvmStatic
        private var pendingScreen: String = ""

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

        @JvmStatic
        private var screenWidth = 1404
        @JvmStatic
        private var screenHeight = 1872

        @Volatile @JvmStatic
        var lastNotePath: String = ""
        @Volatile @JvmStatic
        var lastPageNum: Int = 0
        @Volatile @JvmStatic
        private var cropReplaceActive: Boolean = false

        @JvmStatic
        fun clearCropReplaceContext() {
            cropReplaceActive = false
        }

        @Volatile @JvmStatic
        private var foregroundMonitorRunning = false
        @Volatile @JvmStatic
        private var wasVisibleBeforeBackground = false
        @Volatile @JvmStatic
        private var isInNoteApp = true

        @Volatile @JvmStatic
        private var captureToastView: View? = null

        private const val NOTE_PACKAGE = "com.ratta.supernote.note"
        private const val NOTE_INSIDE_PAGES_ACTIVITY = "com.ratta.supernote.note.view.NoteInsidePagesActivity"
        private const val PLUGIN_PACKAGE = "com.ratta.supernote.pluginhost"
        private const val DOC_PACKAGE = "com.supernote.document"
        private const val MONITOR_INTERVAL_MS = 800L

        @Volatile @JvmStatic
        private var titleClipFilled: BooleanArray = BooleanArray(4) { false }

        @Volatile @JvmStatic
        private var orientation: String = "vertical"
        private const val ORIENTATION_STORE_KEY = "preset_97"

        @Volatile @JvmStatic private var stickyX: Int = -1
        @Volatile @JvmStatic private var stickyY: Int = -1
        @Volatile @JvmStatic private var positionLoaded: Boolean = false
        private const val POSITION_STORE_KEY_X = "toolbar_pos_x"
        private const val POSITION_STORE_KEY_Y = "toolbar_pos_y"

        @Volatile @JvmStatic
        private var wasBubbleVisible = false

        @Volatile @JvmStatic
        private var penLassoOverlay: PenLassoOverlay? = null

        @Volatile @JvmStatic
        private var isPenLocked: Boolean = false

        @JvmStatic
        private val monitorHandler = Handler(Looper.getMainLooper())

        @Volatile @JvmStatic
        private var currentInstance: FloatingToolbarModule? = null

        @JvmStatic
        private val staticMonitorRunnable = object : Runnable {
            override fun run() {
                if (!foregroundMonitorRunning) return
                val inst = currentInstance ?: return
                inst.runMonitorTick()
                monitorHandler.postDelayed(this, MONITOR_INTERVAL_MS)
            }
        }
    }

    init {
        currentInstance = this
    }

    override fun invalidate() {
        if (currentInstance === this) {
            currentInstance = null
            foregroundMonitorRunning = false
            monitorHandler.removeCallbacks(staticMonitorRunnable)
        }
        super.invalidate()
    }

    private val handler = Handler(Looper.getMainLooper())

    private val longPressRunnable = Runnable {
        longPressTriggered = true
        hideAllNativePanels()
        pendingOpenMain = true
        callShowPluginView()
        emitEvent("onToolbarOpenMain", Arguments.createMap())
    }

    private val BTN_SIZE_DP = 54
    private val BTN_GAP_DP = 4
    private val PANEL_PAD_DP = 8
    private val BORDER_WIDTH = 2
    private val TITLE_ROW_H_DP = 36
    private val TITLE_SEP_DP = 1
    private val CORNER_RADIUS_DP = 0f
    private val BTN_TEXT_SIZE_SP = 22f
    private val TITLE_TEXT_SIZE_SP = 13f

    private val SIDE_INDICATOR_DP = 4
    private val HANDLE_WIDTH_DP = 4
    private val COLLAPSED_WIDTH_DP = 6
    private val COLLAPSED_HEIGHT_DP = 50

    private val SNAP_THRESHOLD = 40
    private val EDGE_COLLAPSE_THRESHOLD = 60
    private val LONG_PRESS_MS = 600L

    data class ToolItem(
        val id: String,
        val name: String,
        val icon: String,
        val action: String,
        val latches: Boolean = false
    )

    private val CLIP_ICON_DP = 32
    private val LAYER_BTN_DP = 20
    private var clipIconViews: Array<TextView?> = arrayOfNulls(4)

    @ReactMethod
    fun show(toolsJson: String) {
        handler.post {
            try {
                loadOrientationFromPrefs()
                parseTools(toolsJson)
                collapsed = false
                removeAll()
                createExpandedToolbar()

                stopForegroundMonitor()
                startForegroundMonitor()
            } catch (e: Exception) { Log.e(TAG, "show: ${e.message}", e) }
        }
    }

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
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            val v = prefs.getString(ORIENTATION_STORE_KEY, null)
            if (v == "vertical" || v == "horizontal") orientation = v
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
        handler.post { try { removeAll() } catch (e: Exception) { Log.e(TAG, "hide: ${e.message}", e) } }
    }

    @ReactMethod
    fun updateTools(toolsJson: String) {
        handler.post {
            parseTools(toolsJson)
            if (!collapsed && expandedRoot != null) {
                rebuildButtons()
            }
        }
    }

    @ReactMethod
    fun collapse() {
        handler.post { switchToCollapsed() }
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

    @ReactMethod
    fun setCollapsed(value: Boolean) {
        handler.post {
            if (value) switchToCollapsed() else switchToExpanded()
        }
    }

    @ReactMethod fun isShowing(promise: Promise) { promise.resolve(rootView != null) }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isShowingSync(): Boolean = rootView != null

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
        Log.i(TAG, "[LASSO-DBG/Kt] ackPendingScreen (was=$pendingScreen)")
        pendingScreen = ""
    }

    @ReactMethod
    fun openPluginView() {
        handler.post {
            Log.i(TAG, "[LASSO-DBG/Kt] openPluginView called (pendingScreen=$pendingScreen)")
            try { callShowPluginView() } catch (e: Exception) {
                Log.e(TAG, "[LASSO-DBG/Kt] openPluginView FAIL: ${e.message}")
            }
        }
    }

    @ReactMethod
    fun openPenLockView() {
        handler.post {
            Log.i(TAG, "openPenLockView: calling showPluginView(1)")
            callShowPluginViewWithType(1)
        }
    }

    @ReactMethod
    fun openPanel(screen: String) {
        handler.post {
            Log.i(TAG, "[LASSO-DBG/Kt] openPanel screen=$screen (prev pendingScreen=$pendingScreen)")
            hideAllNativePanels()
            pendingScreen = screen ?: ""
            removeAll()

            handler.postDelayed({
                emitEvent("onToolbarOpenMain", Arguments.createMap())
            }, 80)
            Log.i(TAG, "[LASSO-DBG/Kt] openPanel done, pendingScreen now=$pendingScreen")
        }
    }

    @ReactMethod
    fun forceClosePluginView() {
        handler.post {
            Log.i(TAG, "[LASSO-DBG/Kt] forceClosePluginView called")
            insertPluginViewClosed = true
            callClosePluginView()
        }
    }

    @ReactMethod
    fun updateTitleClips(json: String) {
        handler.post {
            try {
                val arr = JSONArray(json)
                for (i in 0 until minOf(arr.length(), 4)) {
                    titleClipFilled[i] = arr.getBoolean(i)
                }
                rebuildClipIcons()
            } catch (e: Exception) { Log.w(TAG, "updateTitleClips: ${e.message}") }
        }
    }

    @ReactMethod
    fun showCaptureToast(message: String?) {
        handler.post {
            try {
                val ctx = reactApplicationContext
                val text = if (message.isNullOrBlank()) "截图中…" else message

                captureToastView?.let { existing ->
                    (existing as? TextView)?.text = text
                    Log.i(TAG, "[LASSO-DBG/Kt] showCaptureToast already showing, text updated")
                    return@post
                }

                val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager

                val toast = TextView(ctx).apply {
                    this.text = text
                    setTextColor(Color.WHITE)
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    val padH = dpToPx(28); val padV = dpToPx(16)
                    setPadding(padH, padV, padH, padV)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(12).toFloat()
                        setColor(0xE6000000.toInt())
                        setStroke(dpToPx(1), 0xFFFFFFFF.toInt())
                    }
                }

                val wmType = if (Build.VERSION.SDK_INT >= 26)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    wmType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                    x = 0; y = 0
                }

                wm.addView(toast, lp)
                captureToastView = toast
                Log.i(TAG, "[LASSO-DBG/Kt] showCaptureToast shown: '$text'")
            } catch (e: Exception) {
                Log.e(TAG, "[LASSO-DBG/Kt] showCaptureToast FAIL: ${e.message}", e)
            }
        }
    }

    @ReactMethod
    fun hideCaptureToast() {
        handler.post {
            val v = captureToastView ?: return@post
            try {
                val wm = reactApplicationContext.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
                Log.i(TAG, "[LASSO-DBG/Kt] hideCaptureToast removed")
            } catch (e: Exception) {
                Log.w(TAG, "[LASSO-DBG/Kt] hideCaptureToast: ${e.message}")
            } finally {
                captureToastView = null
            }
        }
    }

    @ReactMethod
    fun savePreset(num: Int, json: String, promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            prefs.edit().putString("preset_$num", json).apply()
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "savePreset: ${e.message}")
            promise.resolve(false)
        }
    }

    @ReactMethod
    fun loadPreset(num: Int, promise: Promise) {
        try {
            val prefs = reactApplicationContext.getSharedPreferences("quicktoolbar_presets", 0)
            val json = prefs.getString("preset_$num", null)
            promise.resolve(json)
        } catch (e: Exception) {
            Log.e(TAG, "loadPreset: ${e.message}")
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun getStickerDir(promise: Promise) {
        try {
            val dir = java.io.File(reactApplicationContext.getExternalFilesDir(null), "stickers")
            promise.resolve(dir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "getStickerDir: ${e.message}")
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun ensureStickerDir(promise: Promise) {
        try {
            val dir = java.io.File(reactApplicationContext.getExternalFilesDir(null), "stickers")
            if (!dir.exists()) {
                val ok = dir.mkdirs()
                Log.i(TAG, "ensureStickerDir: mkdirs=${ok} path=${dir.absolutePath}")
            }
            promise.resolve(dir.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "ensureStickerDir: ${e.message}")
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun checkOverlayPermission(promise: Promise) {
        if (Build.VERSION.SDK_INT >= 23) promise.resolve(Settings.canDrawOverlays(reactApplicationContext))
        else promise.resolve(true)
    }

    @ReactMethod
    fun deleteQueueFile(path: String, promise: Promise) {
        try {
            val f = java.io.File(path)
            if (f.exists()) {
                val deleted = f.delete()
                Log.i(TAG, "[INSERT-DBG/Kt] deleteQueueFile: $path → deleted=$deleted")
                promise.resolve(deleted)
            } else {
                Log.i(TAG, "[INSERT-DBG/Kt] deleteQueueFile: $path already gone")
                promise.resolve(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[INSERT-DBG/Kt] deleteQueueFile error: ${e.message}")
            promise.reject("DELETE_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun launchActivity(pkg: String, cls: String, promise: Promise) {
        try {
            val intent = android.content.Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            reactApplicationContext.startActivity(intent)
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "launchActivity($pkg/$cls): ${e.message}", e)
            promise.reject("LAUNCH_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun requestOverlayPermission() {
        try {
            val ctx = reactApplicationContext
            ctx.startActivity(android.content.Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            Log.e(TAG, "requestOverlayPermission: ${e.message}", e)
            try {
                reactApplicationContext.startActivity(android.content.Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${reactApplicationContext.packageName}")
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Exception) {}
        }
    }

    @ReactMethod
    fun dumpNativePluginManagerMethods() {
        try {
            val catalyst = reactApplicationContext.catalystInstance
            val pm = catalyst.getNativeModule("NativePluginManager") ?: run {
                Log.w(TAG, "[DUMP] NativePluginManager not found"); return
            }
            val clazz = pm::class.java
            Log.i(TAG, "[DUMP] NativePluginManager class: ${clazz.name}")
            clazz.declaredMethods.sortedBy { it.name }.forEach { m ->
                val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                Log.i(TAG, "[DUMP]   ${m.name}($params) -> ${m.returnType.simpleName}")
            }
            Log.i(TAG, "[DUMP] --- inherited ---")
            clazz.methods
                .filter { it.declaringClass != Object::class.java }
                .sortedBy { it.name }
                .forEach { m ->
                    val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                    Log.i(TAG, "[DUMP]   ${m.declaringClass.simpleName}.${m.name}($params)")
                }
            clazz.declaredFields.forEach { f ->
                f.isAccessible = true
                val v = try { f.get(pm) } catch (_: Exception) { "?" }
                Log.i(TAG, "[DUMP-FIELD] ${f.name}: ${f.type.simpleName} = ${v?.javaClass?.name ?: "null"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DUMP] error: ${e.message}", e)
        }
    }

    @ReactMethod
    fun dumpPluginAppFields() {
        try {
            val catalyst = reactApplicationContext.catalystInstance
            val pm = catalyst.getNativeModule("NativePluginManager") ?: run {
                Log.w(TAG, "[DUMP-PA] NativePluginManager not found"); return
            }

            val paField = pm::class.java.declaredFields.firstOrNull { it.name == "pluginApp" } ?: run {
                Log.w(TAG, "[DUMP-PA] pluginApp field not found"); return
            }
            paField.isAccessible = true
            val pa = paField.get(pm) ?: run { Log.w(TAG, "[DUMP-PA] pluginApp is null"); return }

            Log.i(TAG, "[DUMP-PA] pluginApp class: ${pa::class.java.name}")

            pa::class.java.declaredMethods.sortedBy { it.name }.forEach { m ->
                val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                Log.i(TAG, "[DUMP-PA]   method: ${m.name}($params) -> ${m.returnType.simpleName}")
            }
            pa::class.java.methods
                .filter { it.declaringClass != Object::class.java }
                .sortedBy { it.name }
                .forEach { m ->
                    val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                    Log.i(TAG, "[DUMP-PA]   inherited: ${m.declaringClass.simpleName}.${m.name}($params)")
                }

            Log.i(TAG, "[DUMP-PA] --- fields ---")
            pa::class.java.declaredFields.forEach { f ->
                f.isAccessible = true
                val v = try { f.get(pa) } catch (_: Exception) { null }
                val typeName = v?.javaClass?.name ?: f.type.name
                Log.i(TAG, "[DUMP-PA]   field: ${f.name}: ${f.type.simpleName} = $typeName")

                val interesting = listOf("hand", "write", "disable", "area", "draw", "paint", "spaint", "client", "presenter", "note")
                if (v != null && interesting.any { kw ->
                        f.name.lowercase().contains(kw) || typeName.lowercase().contains(kw)
                    }) {
                    Log.i(TAG, "[DUMP-PA]   >>> drilling into ${f.name} <<<")
                    v::class.java.methods
                        .filter { it.declaringClass != Object::class.java }
                        .sortedBy { it.name }
                        .forEach { m ->
                            val params = m.parameterTypes.joinToString(", ") { it.simpleName }
                            Log.i(TAG, "[DUMP-PA]     ${v::class.java.simpleName}.${m.name}($params)")
                        }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DUMP-PA] error: ${e.message}", e)
        }
    }

    private fun isAnyNativePanelOpen(): Boolean =
        NativeImagePanel.currentInstance != null ||
        NativeDocPanel.currentInstance != null ||
        NativeScreenshotPanel.currentInstance != null ||
        NativeSendPanel.currentInstance != null

    private fun callSetFullAuto(enable: Boolean) = withNativePluginManager("callSetFullAuto") { pm ->
        val m = pm::class.java.methods.firstOrNull {
            it.name == "setFullAuto" && it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.java
        } ?: run {
            Log.w(TAG, "callSetFullAuto: setFullAuto method not found"); return@withNativePluginManager
        }
        m.invoke(pm, enable)
        Log.i(TAG, "callSetFullAuto($enable) called")
    }

    @ReactMethod fun enablePenBlock() {
        handler.post {
            callSetFullAuto(true)
            callPluginAppShowPluginView(1, "enablePenBlock")
        }
    }
    @ReactMethod fun disablePenBlock() {
        handler.post {
            callPluginAppShowPluginView(0, "disablePenBlock")
            callSetFullAuto(false)
            if (isPenLocked) {
                isPenLocked = false
                if (!collapsed && toolContainer != null) rebuildButtons()
            }
        }
    }

    private fun callPluginAppShowPluginView(showType: Int, label: String) {
        try {
            val pm = reactApplicationContext.catalystInstance.getNativeModule("NativePluginManager") ?: run {
                Log.w(TAG, "$label: NativePluginManager not found"); return
            }
            val paField = pm::class.java.declaredFields.firstOrNull { it.name == "pluginApp" } ?: run {
                Log.w(TAG, "$label: pluginApp field not found"); return
            }
            paField.isAccessible = true
            val pa = paField.get(pm) ?: run {
                Log.w(TAG, "$label: pluginApp is null"); return
            }
            val showM = pa::class.java.methods.firstOrNull {
                it.name == "showPluginView" && it.parameterCount == 1 &&
                (it.parameterTypes[0] == Int::class.javaPrimitiveType ||
                 it.parameterTypes[0] == java.lang.Integer::class.java)
            } ?: run {
                Log.w(TAG, "$label: PluginApp.showPluginView(int) not found"); return
            }
            showM.invoke(pa, showType)
            Log.i(TAG, "$label: PluginApp.showPluginView($showType) called")
        } catch (e: Exception) {
            Log.e(TAG, "$label: ${e.message}", e)
        }
    }

    @ReactMethod
    fun engagePenLock() {
        handler.post { callPluginAppShowPluginView(1, "engagePenLock") }
    }

    @ReactMethod
    fun releasePenLock() {
        handler.post { callPluginAppShowPluginView(0, "releasePenLock") }
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun isPenLockedSync(): Boolean = isPenLocked

    @ReactMethod
    fun setPenLocked(locked: Boolean) {
        handler.post {
            isPenLocked = locked
            if (!collapsed && toolContainer != null) rebuildButtons()
        }
    }

    private fun callShowPluginView() = withNativePluginManager("callShowPluginView") { pm ->
        val allMethods = pm::class.java.methods.filter { it.name == "showPluginView" }
        if (allMethods.isEmpty()) return@withNativePluginManager
        val noArg = allMethods.firstOrNull { it.parameterCount == 0 }
        if (noArg != null) { noArg.invoke(pm); return@withNativePluginManager }
        val singleArg = allMethods.firstOrNull { it.parameterCount == 1 }
        if (singleArg != null) { singleArg.invoke(pm, null as Any?); return@withNativePluginManager }
        val fallback = allMethods.first()
        fallback.invoke(pm, *arrayOfNulls<Any>(fallback.parameterCount))
    }

    private fun callShowPluginViewWithType(showType: Int) = withNativePluginManager("callShowPluginViewWithType") { pm ->
        val intArgMethod = pm::class.java.methods.firstOrNull {
            it.name == "showPluginView" && it.parameterCount == 1 &&
            (it.parameterTypes[0] == Int::class.javaPrimitiveType || it.parameterTypes[0] == java.lang.Integer::class.java)
        }
        if (intArgMethod != null) {
            intArgMethod.invoke(pm, showType)
            Log.i(TAG, "callShowPluginViewWithType($showType) called")
            return@withNativePluginManager
        }
        Log.w(TAG, "callShowPluginViewWithType: int-arg variant not found, falling back to no-arg")
        callShowPluginView()
    }

    private fun callClosePluginView() = withNativePluginManager("callClosePluginView") { pm ->
        for (name in arrayOf("closePluginView", "hidePluginView")) {
            val methods = pm::class.java.methods.filter { it.name == name }
            if (methods.isEmpty()) continue
            val noArg = methods.firstOrNull { it.parameterCount == 0 }
            if (noArg != null) { noArg.invoke(pm); Log.i(TAG, "$name() called"); return@withNativePluginManager }
            val singleArg = methods.firstOrNull { it.parameterCount == 1 }
            if (singleArg != null) { singleArg.invoke(pm, null as Any?); Log.i(TAG, "$name(null) called"); return@withNativePluginManager }
        }
        Log.w(TAG, "callClosePluginView: no suitable method found")
    }

    private fun switchToCollapsed() {
        if (collapsed && collapsedRoot != null) return
        collapsed = true
        removeAll()
        createCollapsedHandle()
        emitCollapseChange()
    }

    private fun switchToExpanded() {
        if (!collapsed && expandedRoot != null) return
        collapsed = false
        removeAll()
        createExpandedToolbar()
        emitCollapseChange()
    }

    private fun emitCollapseChange() {
        emitEvent("onToolbarCollapseChange", Arguments.createMap().apply {
            putBoolean("collapsed", collapsed)
            putString("side", dockSide)
        })
    }

    private fun createCollapsedHandle() {
        val ctx = reactApplicationContext
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            emitEvent("onToolbarPermissionDenied", Arguments.createMap()); return
        }

        windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = ctx.resources.displayMetrics
        screenWidth = dm.widthPixels; screenHeight = dm.heightPixels

        val w = dpToPx(COLLAPSED_WIDTH_DP)
        val h = dpToPx(COLLAPSED_HEIGHT_DP)

        collapsedRoot = View(ctx).apply {
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

        val wmType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            w, h, wmType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (dockSide == "left") 0 else screenWidth - w
            y = stickyY.takeIf { it >= 0 } ?: (screenHeight / 2 - h / 2)
            y = y.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
        }

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
                    val dx = event.rawX - startRawX; val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        isDragging = true
                        handler.removeCallbacks(collapsedLongPressRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(collapsedLongPressRunnable)
                    if (!isDragging && !longPressTriggered) {
                        switchToExpanded()
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
        Log.i(TAG, "collapsed handle shown, side=$dockSide")
    }

    private fun createExpandedToolbar() {
        val ctx = reactApplicationContext
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            emitEvent("onToolbarPermissionDenied", Arguments.createMap()); return
        }

        windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = ctx.resources.displayMetrics
        screenWidth = dm.widthPixels; screenHeight = dm.heightPixels

        val borderPx = dpToPx(BORDER_WIDTH)
        val cornerR  = dpToPx(CORNER_RADIUS_DP.toInt()).toFloat()
        val titleH   = dpToPx(TITLE_ROW_H_DP)
        val titleSep = dpToPx(TITLE_SEP_DP)

        expandedRoot = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(borderPx, Color.parseColor("#111111"))
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (filled) Color.WHITE else Color.BLACK)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(3).toFloat()
                    if (filled) {
                        setColor(Color.BLACK)
                    } else {
                        setColor(Color.TRANSPARENT)

                    }
                }
                setOnClickListener {
                    emitEvent("onTitleClipTap", Arguments.createMap().apply { putString("slot", slot.toString()) })
                }
                setOnLongClickListener {
                    emitEvent("onTitleClipLongPress", Arguments.createMap().apply { putString("slot", slot.toString()) })
                    true
                }
            }
        }

        fun makeLayerBtn(label: String, action: () -> Unit): TextView =
            TextView(ctx).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(Color.parseColor("#111111"))
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = null
                layoutParams = LinearLayout.LayoutParams(clipIconSz, clipIconSz).apply {
                    marginEnd = dpToPx(1)
                }
                setOnClickListener { action() }
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
                val p = dpToPx(PANEL_PAD_DP)
                setPadding(p, p, dpToPx(2), p)
            }
            bodyRow.addView(toolContainer)

            bodyRow.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#E8E8E5"))
                layoutParams = LinearLayout.LayoutParams(titleSep, LinearLayout.LayoutParams.MATCH_PARENT)
            })

            val titleCol = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setBackgroundColor(Color.parseColor("#FAFAF8"))
                layoutParams = LinearLayout.LayoutParams(titleColW, LinearLayout.LayoutParams.MATCH_PARENT)
                setPadding(dpToPx(3), dpToPx(4), dpToPx(3), dpToPx(4))
            }

            dragSpacer = View(ctx)

            for (i in 0 until 4) {
                val tv = makeClipIcon(i).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(CLIP_ICON_DP), dpToPx(CLIP_ICON_DP)).apply {
                        bottomMargin = dpToPx(2)
                    }
                }
                clipIconViews[i] = tv
                titleCol.addView(tv)
            }

            val penLassoBtnV = TextView(ctx).apply {
                text = "⊞"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = null
                layoutParams = LinearLayout.LayoutParams(clipIconSz, clipIconSz).apply {
                    topMargin = dpToPx(2)
                }
                setOnClickListener { removeAll(); emitEvent("onTitlePenLassoAction", Arguments.createMap()) }
            }
            titleCol.addView(penLassoBtnV)

            titleCol.addView(makeLayerBtn("↑") {
                emitEvent("onTitleLayerAction", Arguments.createMap().apply { putString("direction", "prev") })
            }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dpToPx(2) })
            titleCol.addView(makeLayerBtn("↓") {
                emitEvent("onTitleLayerAction", Arguments.createMap().apply { putString("direction", "next") })
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
                setBackgroundColor(Color.parseColor("#FAFAF8"))
            }
            val clipRow = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, titleH
                )
                setPadding(dpToPx(4), 0, dpToPx(2), 0)
            }
            for (i in 0 until 4) {
                val tv = makeClipIcon(i).apply {
                    layoutParams = LinearLayout.LayoutParams(clipIconSz, clipIconSz).apply {
                        marginEnd = clipIconGap
                    }
                }
                clipIconViews[i] = tv
                clipRow.addView(tv)
            }
            titleRow.addView(clipRow)

            titleRow.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#BBBBBB"))
                layoutParams = LinearLayout.LayoutParams(dpToPx(1), (titleH * 0.6f).toInt()).apply {
                    marginStart = dpToPx(3)
                    marginEnd = dpToPx(3)
                }
            })

            titleRow.addView(TextView(ctx).apply {
                text = "⇩"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.parseColor("#555555"))
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = null
                layoutParams = LinearLayout.LayoutParams(clipIconSz, clipIconSz)
                setOnClickListener {
                    val next = if (orientation == "vertical") "horizontal" else "vertical"
                    setOrientation(next)
                }
            })

            titleRow.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, titleH, 1f)
            })

            dragSpacer = View(ctx)

            titleRow.addView(makeLayerBtn("L↑") {
                emitEvent("onTitleLayerAction", Arguments.createMap().apply { putString("direction", "prev") })
            })
            titleRow.addView(makeLayerBtn("L↓") {
                emitEvent("onTitleLayerAction", Arguments.createMap().apply { putString("direction", "next") })
            })

            val penLassoBtnH = TextView(ctx).apply {
                text = "⊞"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = null
                layoutParams = LinearLayout.LayoutParams(dpToPx(LAYER_BTN_DP + 6), LinearLayout.LayoutParams.MATCH_PARENT)
                setOnClickListener { removeAll(); emitEvent("onTitlePenLassoAction", Arguments.createMap()) }
            }
            titleRow.addView(penLassoBtnH)
            expandedRoot!!.addView(titleRow)

            expandedRoot!!.addView(View(ctx).apply {
                setBackgroundColor(Color.parseColor("#E8E8E5"))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, titleSep)
            })

            toolContainer = LinearLayout(ctx).apply {
                this.orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                val p = dpToPx(PANEL_PAD_DP); setPadding(p, p, p, p)
            }
            expandedRoot!!.addView(toolContainer)
            rebuildButtons()
        }

        val wmType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

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

        val dragTouchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = layoutParams ?: return@OnTouchListener false
                    startX = lp.x; startY = lp.y
                    startRawX = event.rawX; startRawY = event.rawY
                    isDragging = false; longPressTriggered = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = layoutParams ?: return@OnTouchListener false
                    val dx = event.rawX - startRawX; val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        isDragging = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
                    }; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (isDragging) {
                        val lp = layoutParams ?: return@OnTouchListener true
                        val vw = expandedRoot?.measuredWidth ?: 0

                        if (lp.x <= EDGE_COLLAPSE_THRESHOLD) {
                            dockSide = "left"
                            savePositionToPrefs(lp.x, lp.y)
                            switchToCollapsed()
                        } else if (screenWidth - (lp.x + vw) <= EDGE_COLLAPSE_THRESHOLD) {
                            dockSide = "right"
                            savePositionToPrefs(lp.x, lp.y)
                            switchToCollapsed()
                        } else {
                            snapToEdge()
                            savePositionToPrefs(layoutParams!!.x, layoutParams!!.y)
                            emitEvent("onToolbarDragEnd", Arguments.createMap().apply {
                                putInt("x", layoutParams!!.x); putInt("y", layoutParams!!.y)
                            })
                        }
                    } else if (!longPressTriggered) {
                        emitEvent("onToolbarTap", Arguments.createMap())
                    }; true
                }
                else -> false
            }
        }

        expandedRoot!!.setOnTouchListener(dragTouchListener)

        rootView = expandedRoot
        try {
            windowManager?.addView(rootView, layoutParams)
            Log.i(TAG, "expanded toolbar shown (2-row horizontal), ${tools.size} tools")
        } catch (e: Exception) {

            Log.w(TAG, "addView failed (${e.message}), retrying in 500ms")
            rootView = null
            expandedRoot = null; toolContainer = null; layoutParams = null
            handler.postDelayed({ createExpandedToolbar() }, 500)
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
            "pen_lock"              -> "icons/ic_tool_pen.xml"
            "pen_lock_off"          -> "icons/ic_tool_pen_off.xml"
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
            Log.w(TAG, "loadIconFromAssets $assetName failed: ${e.message}")
            null
        }
    }

    private fun rebuildButtons() {
        val c = toolContainer ?: return
        c.removeAllViews()

        val ctx = reactApplicationContext
        val btnSz = dpToPx(if (orientation == "vertical") 48 else BTN_SIZE_DP)
        val gap   = dpToPx(if (orientation == "vertical") 3 else BTN_GAP_DP)

        val n = tools.size

        fun makeToolButton(idx: Int, tool: ToolItem): View {
            val isActive = tool.latches && activeModeIds.contains(tool.id)
            val activeBg = Color.parseColor("#111111")
            val inactiveFg = Color.parseColor("#1A1A1A")

            val iconDrawable = loadIconFromAssets(tool.id, btnSz, if (isActive) android.graphics.Color.WHITE else inactiveFg)
            if (idx == 0) {
                Log.i(TAG, "iconLookup: tool=${tool.id} loaded=${iconDrawable != null}")
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
                        if (orientation == "vertical") 18f else BTN_TEXT_SIZE_SP)
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
                marginStart = gap / 2; marginEnd = gap / 2
            }
            view.setOnClickListener { handleToolTap(tool, view) }
            view.setOnLongClickListener {
                emitEvent("onToolLongPress", Arguments.createMap().apply {
                    putString("toolId", tool.id); putString("toolName", tool.name)
                }); true
            }
            return view
        }

        fun makeSpecialButton(glyph: String, onTap: () -> Unit): TextView {
            return TextView(ctx).apply {
                text = glyph
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (orientation == "vertical") 18f else BTN_TEXT_SIZE_SP)
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT); cornerRadius = 0f
                }
                layoutParams = LinearLayout.LayoutParams(btnSz, btnSz).apply {
                    marginStart = gap / 2; marginEnd = gap / 2
                }
                setOnClickListener { onTap() }
            }
        }

        fun makePenLockButton(sz: Int, gapPx: Int): View {
            val locked = isPenLocked
            val activeBg = Color.parseColor("#111111")
            val fgColor = if (locked) Color.WHITE else Color.parseColor("#1A1A1A")
            val iconId = if (locked) "pen_lock_off" else "pen_lock"
            val iconDrawable = loadIconFromAssets(iconId, sz, fgColor)

            val view: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val padPx = dpToPx(if (orientation == "vertical") 10 else 12)
                    setPadding(padPx, padPx, padPx, padPx)
                    background = GradientDrawable().apply {
                        setColor(if (locked) activeBg else Color.TRANSPARENT)
                        cornerRadius = 0f
                    }
                }
            } else {
                TextView(ctx).apply {
                    text = if (locked) "⊘" else "✏"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (orientation == "vertical") 16f else BTN_TEXT_SIZE_SP - 2f)
                    setTextColor(fgColor)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(if (locked) activeBg else Color.TRANSPARENT)
                        cornerRadius = 0f
                    }
                }
            }

            view.layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                marginStart = gapPx / 2; marginEnd = gapPx / 2
            }
            view.setOnClickListener {
                if (isPenLocked) {
                    isPenLocked = false
                    emitEvent("onPenLockRelease", Arguments.createMap())
                } else {
                    isPenLocked = true
                    emitEvent("onPenLockRequest", Arguments.createMap())
                }
                rebuildButtons()
            }
            return view
        }

        val showSwapBtn = (n >= 6 && n % 2 == 0)
        val showPenLock = (n != 5)

        if (orientation == "vertical") {
            if (n <= 5) {

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
                if (showPenLock) col.addView(makePenLockButton(btnSz, gap))
                c.addView(col)
            } else {

                val extras = mutableListOf<Any>()
                if (showPenLock) extras.add("pen_lock")
                if (showSwapBtn) extras.add("swap")
                val totalSlots = n + extras.size
                val perCol = (totalSlots + 1) / 2
                val colsContainer = LinearLayout(ctx).apply {
                    this.orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val col1 = LinearLayout(ctx).apply {
                    this.orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val col2 = LinearLayout(ctx).apply {
                    this.orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                for (slot in 0 until totalSlots) {
                    val col = if (slot < perCol) col1 else col2
                    val isCol1 = slot < perCol
                    val toolIdx = slot
                    val view: View = if (toolIdx < n) {
                        makeToolButton(toolIdx, tools[toolIdx]).apply {
                            (layoutParams as LinearLayout.LayoutParams).apply {
                                marginStart = if (isCol1) 0 else gap / 2
                                marginEnd = if (isCol1) gap / 2 else 0
                                bottomMargin = gap
                            }
                        }
                    } else {
                        val extraIdx = toolIdx - n
                        if (extras[extraIdx] == "pen_lock") {
                            makePenLockButton(btnSz, gap).apply {
                                (layoutParams as LinearLayout.LayoutParams).apply {
                                    marginStart = if (isCol1) 0 else gap / 2
                                    marginEnd = if (isCol1) gap / 2 else 0
                                    bottomMargin = gap
                                }
                            }
                        } else {
                            makeSpecialButton("⇄") {
                                val next = if (orientation == "vertical") "horizontal" else "vertical"
                                setOrientation(next)
                            }.apply {
                                (layoutParams as LinearLayout.LayoutParams).apply {
                                    marginStart = if (isCol1) 0 else gap / 2
                                    marginEnd = if (isCol1) gap / 2 else 0
                                }
                            }
                        }
                    }
                    col.addView(view)
                }
                colsContainer.addView(col1)
                colsContainer.addView(col2)
                c.addView(colsContainer)
            }
        } else {
            if (n <= 5) {

                val row = LinearLayout(ctx).apply {
                    this.orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                for ((i, t) in tools.withIndex()) row.addView(makeToolButton(i, t))
                if (showPenLock) {
                    row.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dpToPx(8), 1) })
                    row.addView(makePenLockButton(btnSz, gap))
                }
                c.addView(row)
            } else {

                val extras = mutableListOf<Any>()
                if (showPenLock) extras.add("pen_lock")
                if (showSwapBtn) extras.add("swap")
                val totalSlots = n + extras.size
                val perRow = (totalSlots + 1) / 2
                val row1 = LinearLayout(ctx).apply {
                    this.orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val row2 = LinearLayout(ctx).apply {
                    this.orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = gap }
                }
                for (slot in 0 until totalSlots) {
                    val row = if (slot < perRow) row1 else row2
                    val toolIdx = slot
                    val view: View = if (toolIdx < n) {
                        makeToolButton(toolIdx, tools[toolIdx])
                    } else {
                        val extraIdx = toolIdx - n
                        if (extras[extraIdx] == "pen_lock") {
                            makePenLockButton(btnSz, gap)
                        } else {
                            makeSpecialButton("⇄") {
                                val next = if (orientation == "vertical") "horizontal" else "vertical"
                                setOrientation(next)
                            }
                        }
                    }
                    row.addView(view)
                }
                c.addView(row1)
                c.addView(row2)
            }
        }

        try {
            windowManager?.updateViewLayout(rootView, layoutParams)
        } catch (_: Exception) {}
    }

    private fun calcLayout(): Pair<Int, Int> {
        val n = tools.size
        val hasPenLock = n != 5
        val hasSwap = n >= 6 && n % 2 == 0
        val total = n + (if (hasPenLock) 1 else 0) + (if (hasSwap) 1 else 0)
        if (total <= 5) return if (orientation == "vertical") Pair(1, total) else Pair(total, 1)
        val perMajor = (total + 1) / 2
        return if (orientation == "vertical") Pair(2, perMajor) else Pair(perMajor, 2)
    }

    private fun snapToEdge() {
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

    private fun handleToolTap(tool: ToolItem, view: View) {
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

        val isNativePanel = tool.action == "lasso_send"

        if (isNativePanel) {
            removeAll()
            when {
                tool.action == "lasso_send" -> {
                    pendingScreen = "nativeSendHelper"
                    emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "send") })
                    NativeSendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
                    handler.postDelayed({
                        callShowPluginView()
                        emitOpenMainWithRetries("nativeSendHelper")
                    }, 150)
                }
            }
        } else {
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
            if (v is TextView) v.setTextColor(Color.parseColor("#1A1A1A"))
            if (v is ImageView) v.setColorFilter(Color.parseColor("#1A1A1A"))
        }, 120L)
    }

    @ReactMethod
    fun queryActiveMode(promise: Promise) {

        val ids = synchronized(activeModeIds) { activeModeIds.toList() }
        val pick = ids.firstOrNull { it == "insert_text" } ?: ids.firstOrNull()
        promise.resolve(pick)
    }

    @ReactMethod
    fun queryActiveModes(promise: Promise) {
        val arr = Arguments.createArray()
        synchronized(activeModeIds) { activeModeIds.forEach { arr.pushString(it) } }
        promise.resolve(arr)
    }

    @ReactMethod
    fun exitActiveMode() {

        if (activeModeIds.isEmpty()) return
        activeModeIds.clear()
        UiThreadUtil.runOnUiThread { rebuildButtons() }
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
                activeModeIds.clear()
                for (i in 0 until arr.length()) activeModeIds.add(arr.getString(i))
            }
            UiThreadUtil.runOnUiThread { rebuildButtons() }
        } catch (e: Exception) {
            Log.w(TAG, "setActiveModes: ${e.message}")
        }
    }

    @ReactMethod
    fun closeAllForSettings() {
        handler.post {

            NativeImagePanel.currentInstance?.hide()
            NativeDocPanel.currentInstance?.hide()
            NativeSendPanel.currentInstance?.hide()
            NativeLassoScreenshotPanel.currentInstance?.hide()

            FloatingBubbleModule.hideStatic()
            AiBubbleModule.hideStatic()

            removeAll()

            captureToastView?.let {
                try {
                    val wm = reactApplicationContext.getSystemService(
                        android.content.Context.WINDOW_SERVICE
                    ) as WindowManager
                    wm.removeView(it)
                } catch (_: Exception) {}
                captureToastView = null
            }

            activeModeIds.clear()

            pendingScreen = ""
            Log.i(TAG, "closeAllForSettings: all overlays cleared")
        }
    }

    private fun parseTools(json: String) {
        tools.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tools.add(ToolItem(
                    o.getString("id"),
                    o.optString("name",""),
                    o.optString("icon","?"),
                    o.optString("action",""),
                    o.optBoolean("latches", false)
                ))
            }
        } catch (e: Exception) { Log.e(TAG, "parseTools: ${e.message}") }
    }

    private fun removeAll() {
        if (rootView != null) {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null
        }
        expandedRoot = null; toolContainer = null; collapsedRoot = null; layoutParams = null

        clipIconViews = arrayOfNulls(4)
    }

    private fun hideFloatingBubble() {
        try {
            val bubbleModule = reactApplicationContext.catalystInstance
                .getNativeModule("FloatingBubble") ?: return
            val m = bubbleModule::class.java.methods
                .firstOrNull { it.name == "hide" && it.parameterCount == 0 } ?: return
            m.invoke(bubbleModule)
        } catch (_: Exception) {}
    }

    private fun hideAiBubble() {
        try {
            val aiBubbleModule = reactApplicationContext.catalystInstance
                .getNativeModule("AiBubble") ?: return
            val m = aiBubbleModule::class.java.methods
                .firstOrNull { it.name == "hide" && it.parameterCount == 0 } ?: return
            m.invoke(aiBubbleModule)
        } catch (_: Exception) {}
    }

    private fun hideAllNativePanels() {
        NativeImagePanel.currentInstance?.hide()
        NativeDocPanel.currentInstance?.hide()
        NativeSendPanel.currentInstance?.hide()
        NativeLassoScreenshotPanel.currentInstance?.hide()
        NativeScreenshotPanel.currentInstance?.hide()

        FloatingBubbleModule.hideStatic()
        AiBubbleModule.hideStatic()
    }

    private fun suspendAllNativePanels() {
        NativeImagePanel.currentInstance?.suspendVisibility()
        NativeDocPanel.currentInstance?.suspendVisibility()
        NativeSendPanel.currentInstance?.suspendVisibility()
        NativeLassoScreenshotPanel.currentInstance?.suspendVisibility()
        NativeScreenshotPanel.currentInstance?.suspendVisibility()
    }

    private fun resumeAllNativePanels() {
        NativeImagePanel.currentInstance?.resumeVisibility()
        NativeDocPanel.currentInstance?.resumeVisibility()
        NativeSendPanel.currentInstance?.resumeVisibility()
        NativeLassoScreenshotPanel.currentInstance?.resumeVisibility()
        NativeScreenshotPanel.currentInstance?.resumeVisibility()
    }

    private val density: Float get() = reactApplicationContext.resources.displayMetrics.density
    private fun dpToPx(dp: Int): Int = (dp * density).roundToInt()

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
                Log.w(TAG, "$label: NativePluginManager not found"); return
            }
            block(pm)
        } catch (e: Exception) {
            Log.e(TAG, "$label: ${e.message}", e)
        }
    }

    private fun emitEvent(name: String, params: WritableMap) {
        try {
            reactApplicationContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(name, params)
        } catch (e: Exception) { Log.w(TAG, "emitEvent($name): ${e.message}") }
    }

    override fun onCatalystInstanceDestroy() {
        Log.i(TAG, "onCatalystInstanceDestroy — keeping toolbar alive (rootView=${rootView != null})")
        super.onCatalystInstanceDestroy()
    }

    fun requestInsertImage(path: String) {
        Log.i(TAG, "[INSERT-DBG/Kt] requestInsertImage: $path (currentInstance=${currentInstance === this})")
        insertPluginViewClosed = false

        handler.post {
            val retryDelays = longArrayOf(0, 300, 750)
            for (delay in retryDelays) {
                handler.postDelayed({
                    Log.i(TAG, "[INSERT-DBG/Kt] emit nativeInsertImage (delay=${delay}ms)")
                    emitEvent("nativeInsertImage", Arguments.createMap().apply {
                        putString("path", path)

                    })
                }, delay)
            }
            handler.postDelayed({
                Log.i(TAG, "[INSERT-DBG/Kt] safety-net: restoreToolbar (tools=${tools.size})")
                restoreToolbar()

                callPluginAppShowPluginView(0, "insertImage-safetyNet")
                callSetFullAuto(false)
            }, 3500)
        }
    }

    @ReactMethod
    fun restoreToolbar() {
        handler.post {
            Log.i(TAG, "[INSERT-DBG/Kt] restoreToolbar: tools=${tools.size} currentInstance=${currentInstance === this} rootView=${rootView != null}")
            if (tools.isNotEmpty()) {
                collapsed = false
                removeAll()
                try {
                    createExpandedToolbar()
                } catch (e: Exception) {
                    Log.e(TAG, "[INSERT-DBG/Kt] restoreToolbar createExpandedToolbar FAILED: ${e.message}", e)
                }
            }
        }
    }

    fun requestClosePluginView() {
        handler.post {
            callClosePluginView()
        }
    }

    @ReactMethod
    fun setLassoData(text: String, imagePathsJson: String, linkedFilesJson: String?) {
        handler.post {
            val panel = NativeSendPanel.currentInstance
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

    @ReactMethod
    fun showNativeImagePanel() {
        handler.post {
            removeAll()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "image") })
            NativeImagePanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
        }
    }

    @ReactMethod
    fun showNativeDocPanel() {
        handler.post {
            removeAll()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "doc") })
            NativeDocPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
        }
    }

    @ReactMethod
    fun handleDocScreenshot() {
        handler.post {
            removeAll()
            val queueDir = java.io.File("/sdcard/SCREENSHOT/.plugin_staging/queue")
            val queueFiles = (queueDir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".png") }
                .sortedBy { it.name.removeSuffix(".png").toLongOrNull() ?: 0L }
            Log.i(TAG, "[INSERT-DBG/Kt] handleDocScreenshot: queueDir=${queueDir.absolutePath} exists=${queueDir.exists()} queueFiles=${queueFiles.size}")
            for (f in queueFiles) Log.i(TAG, "[INSERT-DBG/Kt]   queue item: ${f.name} size=${f.length()}")
            if (queueFiles.isNotEmpty()) {
                val nextPath = queueFiles.first().absolutePath
                Log.i(TAG, "[INSERT-DBG/Kt] direct insert from queue: $nextPath (queue has ${queueFiles.size} files)")

                kotlin.concurrent.thread(isDaemon = true) {
                    NativeImagePanel.saveToInsertCacheStatic(nextPath, lastNotePath, lastPageNum)
                }
                handler.postDelayed({
                    try { requestInsertImage(nextPath) }
                    catch (e: Exception) { Log.e(TAG, "requestInsertImage failed: ${e.message}"); restoreToolbar() }
                }, 500)
            } else {
                callClosePluginView()
                emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "screenshot") })
                NativeScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
            }
        }
    }

    @ReactMethod
    fun showNativeSendPanelFromBubble() {
        handler.post {
            Log.i(TAG, "showNativeSendPanelFromBubble")
            pendingScreen = "nativeSendHelper"
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "send") })
            NativeSendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(fromBubble = true)
            handler.postDelayed({
                callShowPluginView()
                emitOpenMainWithRetries("nativeSendHelper")
            }, 150)
        }
    }

    @ReactMethod
    fun showNativeLassoScreenshotPanelFromBubble() {
        handler.post {
            Log.i(TAG, "showNativeLassoScreenshotPanelFromBubble")
            removeAll()
            FloatingBubbleModule.hideStatic()
            AiBubbleModule.hideStatic()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "lassoScreenshot") })
            NativeLassoScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                .captureAndShow(fromBubble = true, mode = "ai")
        }
    }

    @ReactMethod
    fun showNativeLassoScreenshotPanelForSendFromBubble() {
        handler.post {
            Log.i(TAG, "showNativeLassoScreenshotPanelForSendFromBubble")
            removeAll()
            FloatingBubbleModule.hideStatic()
            AiBubbleModule.hideStatic()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "lassoScreenshot") })
            NativeLassoScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                .captureAndShow(fromBubble = true, mode = "send")
        }
    }

    fun showPluginView() {
        handler.post { callShowPluginView() }
    }

    fun closePluginView() {
        handler.post { callClosePluginView() }
    }

    fun emitEventPublic(name: String, params: WritableMap) = emitEvent(name, params)

    fun cancelPendingScreen() {
        pendingScreen = ""
    }

    fun destroyAll() {
        handler.post {
            Log.i(TAG, "destroyAll: removing all overlays + closing plugin")

            stopForegroundMonitor()
            pendingScreen = ""
            pendingOpenMain = false
            activeModeIds.clear()

            removeAll()

            captureToastView?.let {
                try {
                    val wm = reactApplicationContext.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
                    wm.removeView(it)
                } catch (_: Exception) {}
                captureToastView = null
            }

            NativeImagePanel.currentInstance?.hide()
            NativeDocPanel.currentInstance?.hide()
            NativeSendPanel.currentInstance?.hide()
            NativeLassoScreenshotPanel.currentInstance?.hide()

            FloatingBubbleModule.hideStatic()
            AiBubbleModule.hideStatic()

            callClosePluginView()
            Log.i(TAG, "destroyAll: done")
        }
    }

    @ReactMethod
    fun destroyAllFromJs() {
        destroyAll()
    }

    @ReactMethod
    fun hideAllNativePanelsFromJs() {
        handler.post { hideAllNativePanels() }
    }

    private fun rebuildClipIcons() {
        for (i in 0 until 4) {
            val tv = clipIconViews[i] ?: continue
            val filled = titleClipFilled.getOrElse(i) { false }
            tv.setTextColor(if (filled) Color.WHITE else Color.BLACK)

            tv.background = if (filled) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(3).toFloat()
                    setColor(Color.BLACK)
                }
            } else {
                null
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
        val inNote = checkIsNoteAppForeground()
        if (inNote != isInNoteApp) {
            isInNoteApp = inNote
            if (!inNote) {
                Log.i(TAG, "foreground monitor: left note app, hiding overlays")
                wasVisibleBeforeBackground = rootView != null
                monitorHandler.post {
                    removeAll()
                    suspendAllNativePanels()
                    FloatingBubbleModule.hideStatic()
                    AiBubbleModule.hideStatic()
                }
            } else {
                Log.i(TAG, "foreground monitor: returned to note app")
                monitorHandler.post {
                    resumeAllNativePanels()
                    val anyPanelOpen = NativeImagePanel.currentInstance != null
                        || NativeDocPanel.currentInstance != null
                        || NativeSendPanel.currentInstance != null
                        || NativeLassoScreenshotPanel.currentInstance != null
                        || NativeScreenshotPanel.currentInstance != null
                    if (!anyPanelOpen) {
                        if (wasVisibleBeforeBackground && tools.isNotEmpty() && rootView == null) {
                            collapsed = false
                            createExpandedToolbar()
                        }
                        FloatingBubbleModule.reshowLast(reactApplicationContext)
                        AiBubbleModule.reshowLast(reactApplicationContext)
                    }
                }
            }
        }
    }

    fun startForegroundMonitor() {
        if (foregroundMonitorRunning) return
        foregroundMonitorRunning = true
        isInNoteApp = true

        monitorHandler.removeCallbacks(staticMonitorRunnable)
        monitorHandler.postDelayed(staticMonitorRunnable, MONITOR_INTERVAL_MS)
        Log.i(TAG, "foreground monitor started")
    }

    fun stopForegroundMonitor() {
        foregroundMonitorRunning = false
        monitorHandler.removeCallbacks(staticMonitorRunnable)
    }

    private val resumedActivityRegex = Regex("""mResumedActivity:.*?(\S+)/(\S+)\s""")

    private fun checkIsNoteAppForeground(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities"))
            val reader = proc.inputStream.bufferedReader()
            var matched: MatchResult? = null
            reader.useLines { lines ->
                for (line in lines) {
                    if ("mResumedActivity" in line) {
                        matched = resumedActivityRegex.find(line)
                        break
                    }
                }
            }
            proc.waitFor()
            if (matched != null) {
                val pkg = matched!!.groupValues[1]
                val cls = matched!!.groupValues[2].let {
                    if (it.startsWith(".")) pkg + it else it
                }
                Log.d(TAG, "foreground: $pkg/$cls")
                pkg == NOTE_PACKAGE || pkg == PLUGIN_PACKAGE
            } else {
                Log.w(TAG, "checkIsNoteAppForeground: no mResumedActivity found")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkIsNoteAppForeground: ${e.message}")
            true
        }
    }

    @ReactMethod
    fun showPenLassoOverlay() {
        handler.post {
            try {
                penLassoOverlay?.dismiss()
                penLassoOverlay = PenLassoOverlay(reactApplicationContext).apply {
                    show(
                        onBbox = { l, t, r, b ->
                            handler.post {
                                penLassoOverlay = null
                                emitEvent("onPenLassoBbox", Arguments.createMap().apply {
                                    putInt("left", l); putInt("top", t)
                                    putInt("right", r); putInt("bottom", b)
                                })
                            }
                        },
                        onCancel = {
                            handler.post {
                                penLassoOverlay = null
                                emitEvent("onPenLassoCancel", Arguments.createMap())
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "showPenLassoOverlay failed: ${e.message}", e)
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

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
