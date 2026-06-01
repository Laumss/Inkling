package com.supernote_quicktoolbar
import com.supernote_quicktoolbar.panels.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.bubbles.*

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
import android.content.res.Configuration
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
        private const val NEAR_EDGE_THRESHOLD = 50

        @JvmStatic
        internal var screenWidth = 1404
        @JvmStatic
        internal var screenHeight = 1872

        @Volatile @JvmStatic
        private var configCallbackRegistered = false

        @Volatile @JvmStatic
        var lastNotePath: String = ""
        @Volatile @JvmStatic
        var lastPageNum: Int = 0

        @Volatile @JvmStatic
        private var foregroundMonitorRunning = false
        @Volatile @JvmStatic
        private var wasVisibleBeforeBackground = false
        @Volatile @JvmStatic
        private var isInNoteApp = true

        @JvmStatic
        fun isInNoteApp(): Boolean = isInNoteApp

        private const val NOTE_PACKAGE = "com.ratta.supernote.note"
        private const val NOTE_INSIDE_PAGES_ACTIVITY = "com.ratta.supernote.note.view.NoteInsidePagesActivity"
        private const val PLUGIN_PACKAGE = "com.ratta.supernote.pluginhost"
        private const val DOC_PACKAGE = "com.supernote.document"

        private const val SETTINGS_PACKAGE = "com.ratta.settings"
        private const val MONITOR_INTERVAL_MS = 800L

        @Volatile @JvmStatic
        private var titleClipFilled: BooleanArray = BooleanArray(6) { false }

        @Volatile @JvmStatic
        private var clipPage: Int = 0

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
        private var strokeEraserOverlay: StrokeEraserOverlay? = null

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
        if (!configCallbackRegistered) {
            configCallbackRegistered = true
            reactApplicationContext.applicationContext.registerComponentCallbacks(
                object : android.content.ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: Configuration) {
                        Handler(Looper.getMainLooper()).post { handleOrientationChange() }
                    }
                    override fun onLowMemory() {}
                    override fun onTrimMemory(level: Int) {}
                }
            )
        }
    }

    override fun initialize() {
        super.initialize()
        try {
            ToolRegistry.init(this, reactApplicationContext)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "ToolRegistry.init failed: ${e.message}", e)
        }
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
    private val COLLAPSED_HEIGHT_DP = 80

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
    private var clipIconViews: Array<TextView?> = arrayOfNulls(6)

    @ReactMethod
    fun show(toolsJson: String) {
        pendingShow = true
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
            pendingShow = false
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
                for (i in 0 until minOf(arr.length(), 6)) {
                    titleClipFilled[i] = arr.getBoolean(i)
                }
                rebuildClipIcons()
            } catch (e: Exception) { Log.w(TAG, "updateTitleClips: ${e.message}") }
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
        ImagePanel.currentInstance != null ||
        DocLinkPanel.currentInstance != null ||
        DocScreenshotPanel.currentInstance != null ||
        SendPanel.currentInstance != null

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
        if (singleArg != null) { singleArg.invoke(pm, PromiseImpl(null, null)); return@withNativePluginManager }
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
            if (singleArg != null) { singleArg.invoke(pm, PromiseImpl(null, null)); Log.i(TAG, "$name(promise) called"); return@withNativePluginManager }
        }
        Log.w(TAG, "callClosePluginView: no suitable method found")
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
        val nearLeft = lp.x <= NEAR_EDGE_THRESHOLD
        val nearRight = (screenWidth - (lp.x + vw)) <= NEAR_EDGE_THRESHOLD
        return nearLeft || nearRight
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
        inferDockSideFromPosition()
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

        expandedRoot?.post { resetAutoCollapse() }
    }

    private fun emitCollapseChange() {
        emitEvent("onToolbarCollapseChange", Arguments.createMap().apply {
            putBoolean("collapsed", collapsed)
            putString("side", dockSide)
        })
    }

    internal fun refreshScreenDimensions() {
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

    private fun handleOrientationChange() {
        val oldW = screenWidth; val oldH = screenHeight
        refreshScreenDimensions()
        if (oldW == screenWidth && oldH == screenHeight) return
        Log.i(TAG, "orientation changed: ${oldW}x${oldH} → ${screenWidth}x${screenHeight}")

        val hadPanelOpen = ToolRegistry.handleRotation()

        if (hadPanelOpen && rootView == null && tools.isNotEmpty()) {
            restoreToolbar()
        }

        val lp = layoutParams ?: return
        if (collapsed && collapsedRoot != null) {
            val w = collapsedRoot!!.width.takeIf { it > 0 } ?: dpToPx(COLLAPSED_WIDTH_DP)
            val h = collapsedRoot!!.height.takeIf { it > 0 } ?: dpToPx(COLLAPSED_HEIGHT_DP)
            lp.x = if (dockSide == "left") 0 else screenWidth - w
            lp.y = lp.y.coerceIn(0, (screenHeight - h).coerceAtLeast(0))
            try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
        } else if (!collapsed && expandedRoot != null) {
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
    }

    private fun createCollapsedHandle() {
        val ctx = reactApplicationContext
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            emitEvent("onToolbarPermissionDenied", Arguments.createMap()); return
        }

        windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        refreshScreenDimensions()

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

        @Suppress("DEPRECATION")
        val wmType = WindowManager.LayoutParams.TYPE_PHONE

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
                    val dx = event.rawX - startRawX; val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        isDragging = true
                        handler.removeCallbacks(collapsedLongPressRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(collapsedLongPressRunnable)
                    if (!longPressTriggered && isDragging) {
                        val dx = event.rawX - startRawX
                        val inward = if (dockSide == "left") dx > SWIPE_THRESHOLD else dx < -SWIPE_THRESHOLD
                        if (inward) switchToExpanded()
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
        refreshScreenDimensions()

        val borderPx = dpToPx(BORDER_WIDTH)
        val cornerR  = dpToPx(CORNER_RADIUS_DP.toInt()).toFloat()
        val titleH   = dpToPx(TITLE_ROW_H_DP)
        val titleSep = dpToPx(TITLE_SEP_DP)

        expandedRoot = LinearLayout(ctx).apply {
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
                    cornerRadius = dpToPx(3).toFloat()
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

        fun makeSidebarPenLockBtn(sz: Int): View {
            val locked = isPenLocked
            val fgColor = if (locked) Color.WHITE else CLR_BTN_FG
            val iconId = if (locked) "pen_lock_off" else "pen_lock"
            val iconDrawable = loadIconFromAssets(iconId, sz, fgColor)

            val view: View = if (iconDrawable != null) {
                ImageView(ctx).apply {
                    setImageDrawable(iconDrawable)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                    background = GradientDrawable().apply {
                        setColor(if (locked) CLR_BTN_ACT else Color.TRANSPARENT)
                        cornerRadius = dpToPx(3).toFloat()
                    }
                }
            } else {
                TextView(ctx).apply {
                    text = if (locked) "⊘" else "✏"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(14f))
                    setTextColor(fgColor)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply {
                        setColor(if (locked) CLR_BTN_ACT else Color.TRANSPARENT)
                        cornerRadius = dpToPx(3).toFloat()
                    }
                }
            }
            view.layoutParams = LinearLayout.LayoutParams(sz, sz)
            view.setOnClickListener {
                resetAutoCollapse()
                isPenLocked = !isPenLocked
                if (isPenLocked) {
                    handler.post {
                        callSetFullAuto(true)
                        callPluginAppShowPluginView(1, "penLockBtn")
                    }
                } else {
                    handler.post {
                        callPluginAppShowPluginView(0, "penLockBtn")
                        callSetFullAuto(false)
                    }
                }
                emitEvent(if (isPenLocked) "onPenLockRequest" else "onPenLockRelease", Arguments.createMap())
                if (rootView != null && !collapsed) { removeAll(); createExpandedToolbar() }
            }
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
                resetAutoCollapse()
                val next = if (orientation == "vertical") "horizontal" else "vertical"
                setOrientation(next)
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
                val p = dpToPx(PANEL_PAD_DP)
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
                setPadding(dpToPx(3), dpToPx(4), dpToPx(3), dpToPx(4))
            }

            dragSpacer = View(ctx)

            var clipSwipeStartY = 0f
            var clipSwipeCaptured = false
            val clipCol = object : LinearLayout(ctx) {
                override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
                    when (ev.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            clipSwipeStartY = ev.rawY; clipSwipeCaptured = false
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            if (!clipSwipeCaptured && Math.abs(ev.rawY - clipSwipeStartY) > dpToPx(12)) {
                                clipSwipeCaptured = true
                                return true
                            }
                        }
                    }
                    return false
                }
                override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
                    if (ev.action == android.view.MotionEvent.ACTION_UP && clipSwipeCaptured) {
                        val dy = ev.rawY - clipSwipeStartY
                        if (dy < -dpToPx(15) && clipPage == 0) {
                            clipPage = 1; rebuildClipIcons()
                        } else if (dy > dpToPx(15) && clipPage == 1) {
                            clipPage = 0; rebuildClipIcons()
                        }
                    }
                    return clipSwipeCaptured
                }
            }.apply {
                this.orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val clipOffset = clipPage * 2
            for (vi in 0 until 4) {
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

            titleCol.addView(makeSidebarAppendBtn(clipIconSz).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dpToPx(12)
            })

            titleCol.addView(makeSidebarPenLockBtn(clipIconSz).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dpToPx(2)
            })

            titleCol.addView(makeSidebarSwapBtn(clipIconSz).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = dpToPx(2)
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

            titleRow.addView(makeSidebarAppendBtn(layerBtnSz))
            titleRow.addView(makeSidebarPenLockBtn(layerBtnSz))
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
                val p = dpToPx(PANEL_PAD_DP); setPadding(p, p, p, p)
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

        val dragTouchListener = View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = layoutParams ?: return@OnTouchListener false
                    startX = lp.x; startY = lp.y
                    startRawX = event.rawX; startRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = layoutParams ?: return@OnTouchListener false
                    val dx = event.rawX - startRawX; val dy = event.rawY - startRawY
                    if (!isDragging && (abs(dx) > 10 || abs(dy) > 10)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { windowManager?.updateViewLayout(rootView, lp) } catch (_: Exception) {}
                    }; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        refreshScreenDimensions()
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
                            resetAutoCollapse()
                            emitEvent("onToolbarDragEnd", Arguments.createMap().apply {
                                putInt("x", layoutParams!!.x); putInt("y", layoutParams!!.y)
                            })
                        }
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

            startForegroundMonitor()
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
            val activeBg = CLR_BTN_ACT
            val inactiveFg = CLR_BTN_FG

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
                marginStart = gap / 2; marginEnd = gap / 2
            }
            view.setOnClickListener { handleToolTap(tool, view) }
            view.setOnLongClickListener {
                if (tool.action == "insert_doc_screenshot") {

                    openDocScreenshotPanel()
                } else {
                    emitEvent("onToolLongPress", Arguments.createMap().apply {
                        putString("toolId", tool.id); putString("toolName", tool.name)
                    })
                }
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
    }

    private fun calcLayout(): Pair<Int, Int> {
        val n = tools.size
        val hasPenLock = n != 5
        val total = n + (if (hasPenLock) 1 else 0)
        if (total <= 5) return if (orientation == "vertical") Pair(1, total) else Pair(total, 1)
        val perMajor = (total + 1) / 2
        return if (orientation == "vertical") Pair(2, perMajor) else Pair(perMajor, 2)
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

    private fun handleToolTap(tool: ToolItem, view: View) {
        resetAutoCollapse()
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
                    SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
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

            ToolRegistry.hideAll()

            removeAll()

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
        pendingShow = false
        handler.removeCallbacks(autoCollapseRunnable)
        if (rootView != null) {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null
        }
        expandedRoot = null; toolContainer = null; collapsedRoot = null; layoutParams = null

        clipIconViews = arrayOfNulls(6)
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
    private val scaleFactor: Float get() = maxOf(0.86f, com.supernote_quicktoolbar.ui_common.ScreenScale.factor(reactApplicationContext)) * toolbarExtraScale
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

    @ReactMethod(isBlockingSynchronousMethod = true)
    fun drainImageQueue(): String? {
        synchronized(ImagePanel::class.java) {
            val queue = ImagePanel.imageQueue
            if (queue.isEmpty()) return null
            val json = org.json.JSONArray(queue).toString()
            queue.clear()
            Log.i(TAG, "[QUEUE-DBG] drainImageQueue: drained $json")
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
            Log.i(TAG, "[QUEUE-DBG] drainDocLinkQueue: drained $json")
            return json
        }
    }

    @ReactMethod
    fun showImagePanel() {
        handler.post {
            removeAll()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "image") })
            ImagePanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
        }
    }

    @ReactMethod
    fun showDocLinkPanel() {
        handler.post {
            removeAll()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "doc") })
            DocLinkPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
        }
    }

    @ReactMethod
    fun handleDocScreenshotCrop() {
        handler.post {
            Log.i(TAG, "[CROP-DBG/Kt] handleDocScreenshotCrop: starting screencap")
            removeAll()
            val cacheDir = reactApplicationContext.cacheDir.absolutePath
            kotlin.concurrent.thread(isDaemon = false) {
                try {
                    val ts = System.currentTimeMillis()
                    val outPath = "$cacheDir/screenshot_crop_$ts.png"
                    val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
                    val exitCode = process.waitFor()
                    val file = java.io.File(outPath)
                    Log.i(TAG, "[CROP-DBG/Kt] screencap exit=$exitCode size=${file.length()}")
                    if (exitCode != 0 || !file.exists() || file.length() <= 500) {
                        Log.e(TAG, "[CROP-DBG/Kt] screencap failed")
                        return@thread
                    }
                    val dims = DocScreenshotService.getImageDimensions(outPath)
                    val imgW = dims?.first ?: 1920
                    val imgH = dims?.second ?: 2560

                    val activeSession = DocScreenshotService.loadSession()
                    if (activeSession != null && activeSession.images.isNotEmpty()) {
                        val updated = DocScreenshotService.addImage(outPath, imgW, imgH)
                        if (updated != null && updated.images.size >= 2) {
                            handler.post {
                                callClosePluginView()
                                openStitchPanel(updated)
                            }
                            return@thread
                        }
                    }

                    handler.post {
                        callClosePluginView()
                        openCropPanelForDoc(outPath, imgW, imgH)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[CROP-DBG/Kt] screencap error: ${e.message}", e)
                }
            }
        }
    }

    private fun restoreAfterCropFlow() {
        ScreenshotBubble.reshowIfPending()

        isInNoteApp = checkIsNoteAppForeground()
        if (isInNoteApp && tools.isNotEmpty() && rootView == null) restoreToolbar()
    }

    private fun openCropPanelForDoc(screenshotPath: String, imgW: Int, imgH: Int, fromStitch: Boolean = false) {
        val hasStitch = DocScreenshotService.hasActiveSession()
        CropPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
            .showWithFooter(
                path = screenshotPath,
                hasStitchSession = hasStitch,
                onConfirm = { crop, stayOpen ->
                    Log.i(TAG, "[CROP-DBG/Kt] crop confirm: ${crop.width}x${crop.height} multi=$stayOpen")
                    kotlin.concurrent.thread(isDaemon = true) {
                        DocScreenshotService.stageToQueue(screenshotPath, crop)
                        if (fromStitch) DocScreenshotService.clearSession()
                    }
                    if (!stayOpen) restoreAfterCropFlow()
                },
                onLongScreenshot = {
                    if (fromStitch) {
                        Log.i(TAG, "[CROP-DBG/Kt] long screenshot: session kept, returning to capture")
                    } else {
                        Log.i(TAG, "[CROP-DBG/Kt] long screenshot: saving to stitch session")
                        kotlin.concurrent.thread(isDaemon = true) {
                            val existing = DocScreenshotService.loadSession()
                            if (existing != null) {
                                DocScreenshotService.addImage(screenshotPath, imgW, imgH)
                            } else {
                                DocScreenshotService.startSession(screenshotPath, imgW, imgH)
                            }
                        }
                    }
                    CropPanel.currentInstance?.hide()
                    restoreAfterCropFlow()
                },
                onAddToHistory = { crop, stayOpen ->
                    Log.i(TAG, "[CROP-DBG/Kt] add to history: ${crop.width}x${crop.height} multi=$stayOpen")
                    kotlin.concurrent.thread(isDaemon = true) {
                        DocScreenshotService.saveToHistory(screenshotPath, crop)
                        if (fromStitch) DocScreenshotService.clearSession()
                    }
                    if (!stayOpen) restoreAfterCropFlow()
                },
                onScreenshotToNote = { crop ->
                    Log.i(TAG, "[CROP-DBG/Kt] screenshot→note: ${crop.width}x${crop.height}")

                    kotlin.concurrent.thread(isDaemon = true) {
                        DocScreenshotService.stageToQueue(screenshotPath, crop)
                        if (fromStitch) DocScreenshotService.clearSession()
                    }
                    CropPanel.currentInstance?.hide()
                    ScreenshotBubble.reshowIfPending()

                    isInNoteApp = true
                    if (tools.isNotEmpty() && rootView == null) restoreToolbar()
                    returnToNoteApp()
                },
                onCancel = {
                    Log.i(TAG, "[CROP-DBG/Kt] crop cancel")
                    if (fromStitch) kotlin.concurrent.thread(isDaemon = true) { DocScreenshotService.clearSession() }
                    restoreAfterCropFlow()
                }
            )
    }

    private fun returnToNoteApp() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName(NOTE_PACKAGE, NOTE_INSIDE_PAGES_ACTIVITY)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            reactApplicationContext.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "returnToNoteApp failed: ${e.message}", e)
        }
    }

    private fun openStitchPanel(session: DocScreenshotService.StitchSessionData) {
        StitchPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
            .show(
                session = session,
                onConfirm = { finalSession ->
                    Log.i(TAG, "[CROP-DBG/Kt] stitch confirm: compositing ${finalSession.images.size} images...")
                    kotlin.concurrent.thread(isDaemon = false) {
                        try {
                            val nativeParams = org.json.JSONObject().apply {
                                put("direction", finalSession.params.direction)
                                put("overlap", finalSession.params.overlap)
                                put("topLayerIndex", finalSession.params.topLayerIndex)
                                put("cols", finalSession.params.cols)
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
                            DocScreenshotService.updateSession(finalSession)
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
                                Log.e(TAG, "[CROP-DBG/Kt] composite returned null")
                                handler.post { StitchPanel.currentInstance?.hide(); restoreAfterCropFlow() }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[CROP-DBG/Kt] composite error: ${e.message}", e)
                            handler.post { StitchPanel.currentInstance?.hide(); restoreAfterCropFlow() }
                        }
                    }
                },
                onCancel = {
                    Log.i(TAG, "[CROP-DBG/Kt] stitch cancel: clearing session")
                    kotlin.concurrent.thread(isDaemon = true) {
                        DocScreenshotService.clearSession()
                    }
                    restoreAfterCropFlow()
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
            return compositeGrid(imagesArr, cols, overlap)
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

    private fun compositeGrid(imagesArr: org.json.JSONArray, cols: Int, overlap: Int): String? {
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

        val cellW = cells.maxOf { ((1f - it.cropLeft - it.cropRight) * it.width).toInt() }
        val cellH = cells.maxOf { ((1f - it.cropTop - it.cropBottom) * it.height).toInt() }

        val isVerticalStrip = cols == 1
        val isHorizontalStrip = cols >= n
        val ovl = if (isVerticalStrip || isHorizontalStrip) overlap else 0

        val canvasW: Int
        val canvasH: Int
        if (isVerticalStrip) {
            canvasW = cellW
            canvasH = cellH * rowCount - ovl * (rowCount - 1)
        } else if (isHorizontalStrip) {
            canvasW = cellW * cols - ovl * (cols - 1)
            canvasH = cellH
        } else {
            canvasW = cellW * cols
            canvasH = cellH * rowCount
        }
        if (canvasW <= 0 || canvasH <= 0) return null

        val result = android.graphics.Bitmap.createBitmap(canvasW, canvasH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

        for ((i, cell) in cells.withIndex()) {
            val col = i % cols
            val row = i / cols
            val bmp = android.graphics.BitmapFactory.decodeFile(cell.path) ?: continue
            val srcRect = android.graphics.Rect(
                (cell.width * cell.cropLeft).toInt(),
                (cell.height * cell.cropTop).toInt(),
                (cell.width * (1f - cell.cropRight)).toInt(),
                (cell.height * (1f - cell.cropBottom)).toInt()
            )
            val effW = srcRect.width().toFloat()
            val effH = srcRect.height().toFloat()
            val scaleToFit = kotlin.math.min(cellW / effW, cellH / effH)
            val drawW = effW * scaleToFit
            val drawH = effH * scaleToFit

            val ox: Float
            val oy: Float
            if (isVerticalStrip) {
                ox = (cellW - drawW) / 2f
                oy = row * (cellH - ovl) + (cellH - drawH) / 2f
            } else if (isHorizontalStrip) {
                ox = col * (cellW - ovl) + (cellW - drawW) / 2f
                oy = (cellH - drawH) / 2f
            } else {
                ox = col * cellW + (cellW - drawW) / 2f
                oy = row * cellH + (cellH - drawH) / 2f
            }

            canvas.drawBitmap(bmp, srcRect, android.graphics.RectF(ox, oy, ox + drawW, oy + drawH), paint)
            bmp.recycle()
        }

        val outPath = "${reactApplicationContext.cacheDir.absolutePath}/stitch_grid_${System.currentTimeMillis()}.png"
        java.io.FileOutputStream(outPath).use { fos -> result.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos) }
        result.recycle()
        return outPath
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
                    ImagePanel.saveToInsertCacheStatic(nextPath, lastNotePath, lastPageNum)
                }
                handler.postDelayed({
                    try { requestInsertImage(nextPath) }
                    catch (e: Exception) { Log.e(TAG, "requestInsertImage failed: ${e.message}"); restoreToolbar() }
                }, 500)
            } else {
                callClosePluginView()
                emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "screenshot") })
                DocScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show()
            }
        }
    }

    fun openDocScreenshotPanel() {
        handler.post {
            removeAll()
            callClosePluginView()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "screenshot") })
            DocScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show("queue")
        }
    }

    private fun startAppendPageCapture() {
        handler.post {
            removeAll()
            LassoScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                .captureAndShow(fromBubble = false, mode = "appendPage")
        }
    }

    @ReactMethod
    fun showSendPanelFromBubble() {
        handler.post {
            Log.i(TAG, "showSendPanelFromBubble")
            pendingScreen = "nativeSendHelper"
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "send") })
            SendPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule).show(fromBubble = true)
            handler.postDelayed({
                callShowPluginView()
                emitOpenMainWithRetries("nativeSendHelper")
            }, 150)
        }
    }

    @ReactMethod
    fun showLassoScreenshotPanelFromBubble() {
        handler.post {
            Log.i(TAG, "showLassoScreenshotPanelFromBubble")
            removeAll()
            FloatingBubbleModule.hideStatic()
            AiBubbleModule.hideStatic()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "lassoScreenshot") })
            LassoScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
                .captureAndShow(fromBubble = true, mode = "ai")
        }
    }

    @ReactMethod
    fun showLassoScreenshotPanelForSendFromBubble() {
        handler.post {
            Log.i(TAG, "showLassoScreenshotPanelForSendFromBubble")
            removeAll()
            FloatingBubbleModule.hideStatic()
            AiBubbleModule.hideStatic()
            emitEvent("onNativePanelOpen", Arguments.createMap().apply { putString("panel", "lassoScreenshot") })
            LassoScreenshotPanel.getInstance(reactApplicationContext, this@FloatingToolbarModule)
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
            Log.i(TAG, "destroyAll: removing all overlays + closing plugin")

            emitEvent("onToolbarDestroyAll", Arguments.createMap())

            stopForegroundMonitor()
            pendingScreen = ""
            pendingOpenMain = false
            activeModeIds.clear()

            removeAll()

            ToolRegistry.hideAll()
            ScreenshotBubble.pendingReshow = false
            ScreenshotBubble.hide()

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

    private fun forwardTapToClip(overlay: View, event: android.view.MotionEvent) {
        val loc = IntArray(2); overlay.getLocationOnScreen(loc)
        val touchY = event.rawY - loc[1]
        val iconH = dpToPx(CLIP_ICON_DP) + dpToPx(2)
        val idx = (touchY / iconH).toInt().coerceIn(0, 3)
        clipIconViews[idx]?.performClick()
    }

    private fun rebuildClipIcons() {
        val count = if (orientation == "vertical") 4 else 6
        val offset = if (orientation == "vertical") clipPage * 2 else 0
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
                    cornerRadius = dpToPx(3).toFloat()
                    setColor(Color.BLACK)
                }
            } else {
                null
            }
            tv.setOnClickListener {
                emitEvent("onTitleClipTap", Arguments.createMap().apply { putString("slot", slot.toString()) })
            }
            tv.setOnLongClickListener {
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
        val fgPkg = foregroundPackage()

        if (fgPkg == SETTINGS_PACKAGE) {
            if (ScreenshotBubble.isShowing) {
                Log.i(TAG, "foreground monitor: in ratta settings, hiding ScreenshotBubble")
                monitorHandler.post { ScreenshotBubble.hideForSettings() }
            }
        } else if (ScreenshotBubble.hiddenBySettings) {

            Log.i(TAG, "foreground monitor: left ratta settings, restoring ScreenshotBubble")
            monitorHandler.post { ScreenshotBubble.reshowIfHiddenBySettings() }
        }

        val inNote = if (fgPkg == null) true else (fgPkg == NOTE_PACKAGE || fgPkg == PLUGIN_PACKAGE)
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
                    val anyPanelOpen = ImagePanel.currentInstance != null
                        || DocLinkPanel.currentInstance != null
                        || SendPanel.currentInstance != null
                        || LassoScreenshotPanel.currentInstance != null
                        || DocScreenshotPanel.currentInstance != null
                    if (!anyPanelOpen) {
                        if ((wasVisibleBeforeBackground || ScreenshotBubble.isShowing || ScreenshotBubble.pendingReshow) && tools.isNotEmpty() && rootView == null) {
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

    private fun foregroundPackage(): String? {
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
                pkg
            } else {
                Log.w(TAG, "foregroundPackage: no mResumedActivity found")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "foregroundPackage: ${e.message}")
            null
        }
    }

    private fun checkIsNoteAppForeground(): Boolean {
        val pkg = foregroundPackage() ?: return true
        return pkg == NOTE_PACKAGE || pkg == PLUGIN_PACKAGE
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

    @ReactMethod
    fun showStrokeEraserOverlay() {
        handler.post {
            try {
                strokeEraserOverlay?.dismiss()
                strokeEraserOverlay = StrokeEraserOverlay(reactApplicationContext).apply {
                    show(
                        onPath = { pts ->
                            handler.post {
                                strokeEraserOverlay = null
                                val arr = Arguments.createArray()
                                for (p in pts) {
                                    arr.pushMap(Arguments.createMap().apply {
                                        putDouble("x", p.x.toDouble())
                                        putDouble("y", p.y.toDouble())
                                    })
                                }
                                emitEvent("onStrokeEraserPath", Arguments.createMap().apply {
                                    putArray("points", arr)
                                })
                            }
                        },
                        onCancel = {
                            handler.post {
                                strokeEraserOverlay = null
                                emitEvent("onStrokeEraserCancel", Arguments.createMap())
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "showStrokeEraserOverlay failed: ${e.message}", e)
                emitEvent("onStrokeEraserCancel", Arguments.createMap())
            }
        }
    }

    @ReactMethod
    fun dismissStrokeEraserOverlay() {
        handler.post {
            strokeEraserOverlay?.dismiss()
            strokeEraserOverlay = null
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
        Log.i(TAG, "startInsertTimer intervalMs=$insertTimerInterval")
    }

    @ReactMethod
    fun stopInsertTimer() {
        insertTimerRunning = false
        handler.removeCallbacks(insertTimerRunnable)
    }

    @ReactMethod fun addListener(eventName: String) {}
    @ReactMethod fun removeListeners(count: Int) {}
}
