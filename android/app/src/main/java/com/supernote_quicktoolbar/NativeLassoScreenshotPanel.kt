package com.supernote_quicktoolbar

import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class NativeLassoScreenshotPanel(
    private val reactContext: ReactApplicationContext,
    private val toolbarModule: FloatingToolbarModule
) {
    companion object {
        private const val TAG = "NativeLassoScreenshotPanel"
        @Volatile var currentInstance: NativeLassoScreenshotPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): NativeLassoScreenshotPanel {
            val inst = currentInstance ?: NativeLassoScreenshotPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val STAGE_DIR = "/sdcard/EXPORT/lasso_ai"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null

    private var screenshotPath: String? = null
    private var bitmap: Bitmap? = null
    private var bitmapWidth = 0
    private var bitmapHeight = 0

    private var drawView: LassoDrawView? = null
    private var clearBtn: TextView? = null
    private var confirmBtn: TextView? = null

    private var cameFromBubble = false

    private var confirmMode = "ai"

    private val density get() = reactContext.resources.displayMetrics.density
    private val screenW get() = reactContext.resources.displayMetrics.widthPixels
    private val screenH get() = reactContext.resources.displayMetrics.heightPixels
    private fun dp(v: Int) = (v * density).roundToInt()
    private fun dp(v: Float) = (v * density).roundToInt()

    fun captureAndShow(fromBubble: Boolean, mode: String = "ai") {
        cameFromBubble = fromBubble
        confirmMode = mode
        toolbarModule.enablePenBlock()

        thread(isDaemon = false) {
            val path = runScreencap() ?: run {
                Log.e(TAG, "screencap failed")
                handler.post { emitCloseEvent() }
                return@thread
            }

            handler.post {
                val bmp = BitmapFactory.decodeFile(path)
                if (bmp == null) {
                    Log.e(TAG, "bitmap decode failed for $path")
                    emitCloseEvent()
                    return@post
                }
                screenshotPath = path
                bitmap = bmp
                bitmapWidth = bmp.width
                bitmapHeight = bmp.height
                createPanel()
            }
        }
    }

    private fun runScreencap(): String? {
        return try {
            val ts = System.currentTimeMillis()
            val outPath = "${reactContext.cacheDir.absolutePath}/lasso_screenshot_$ts.png"
            Log.i(TAG, "screencap -> $outPath")
            val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p", outPath))
            val exit = proc.waitFor()
            val f = File(outPath)
            Log.i(TAG, "screencap exit=$exit size=${f.length()}")
            if (exit == 0 && f.exists() && f.length() > 500) outPath else null
        } catch (e: Exception) {
            Log.e(TAG, "screencap EX: ${e.message}", e)
            null
        }
    }

    fun hide() {
        handler.post {
            try { windowManager?.removeView(rootView) } catch (_: Exception) {}
            rootView = null; windowManager = null
            drawView = null; clearBtn = null; confirmBtn = null
            bitmap?.recycle(); bitmap = null
            currentInstance = null
            toolbarModule.disablePenBlock()
        }
    }

    fun suspendVisibility() {
        handler.post { rootView?.visibility = View.GONE }
    }

    fun resumeVisibility() {
        handler.post { rootView?.visibility = View.VISIBLE }
    }

    private fun createPanel() {
        val ctx = reactContext
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
            Log.e(TAG, "no overlay permission")
            emitCloseEvent()
            return
        }

        windowManager = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager

        val imgAspect = bitmapWidth.toFloat() / bitmapHeight
        val screenAspect = screenW.toFloat() / screenH
        val dispW: Float; val dispH: Float; val offX: Float; val offY: Float
        if (imgAspect > screenAspect) {
            dispW = screenW.toFloat()
            dispH = screenW / imgAspect
            offX = 0f
            offY = (screenH - dispH) / 2f
        } else {
            dispH = screenH.toFloat()
            dispW = screenH * imgAspect
            offX = (screenW - dispW) / 2f
            offY = 0f
        }
        val imgRect = RectF(offX, offY, offX + dispW, offY + dispH)

        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
        }

        val iv = ImageView(ctx).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(iv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        drawView = LassoDrawView(ctx, imgRect) {
            updateButtonStates()
        }
        root.addView(drawView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val ctrlY = (screenH * 0.78f).roundToInt()
        val ctrlBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cancelBtn = makeOutlinedBtn(NativeLocale.t("cancel")) { onCancel() }
        clearBtn = makeOutlinedBtn(NativeLocale.t("lasso_clear")) { onClear() }
        confirmBtn = makeFilledBtn(NativeLocale.t("confirm")) { onConfirm() }

        ctrlBar.addView(cancelBtn, LinearLayout.LayoutParams(dp(130), dp(46)).apply { marginEnd = dp(12) })
        ctrlBar.addView(clearBtn, LinearLayout.LayoutParams(dp(130), dp(46)).apply { marginEnd = dp(12) })
        ctrlBar.addView(confirmBtn, LinearLayout.LayoutParams(dp(130), dp(46)))

        val ctrlLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = ctrlY
            gravity = Gravity.TOP
        }
        root.addView(ctrlBar, ctrlLp)

        val hint = TextView(ctx).apply {
            text = NativeLocale.t("lasso_hint")
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            gravity = Gravity.CENTER
        }
        val hintLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (screenH * 0.08f).roundToInt()
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        root.addView(hint, hintLp)
        drawView?.hintView = hint

        val wmType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            wmType,

            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        rootView = root
        try {
            windowManager?.addView(root, lp)
            Log.i(TAG, "panel shown, img=${bitmapWidth}x$bitmapHeight screen=${screenW}x$screenH")
            updateButtonStates()
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}", e)
            windowManager = null
            rootView = null
            emitCloseEvent()
        }
    }

    private fun updateButtonStates() {
        val dv = drawView ?: return
        val hasAny = dv.pointCount() > 0
        val hasValid = dv.hasValidLasso()
        clearBtn?.visibility = if (hasAny) View.VISIBLE else View.INVISIBLE
        confirmBtn?.apply {
            alpha = if (hasValid) 1f else 0.35f
            isEnabled = hasValid
        }
    }

    private fun onCancel() {
        Log.i(TAG, "cancel")
        hide()
        emitCloseEvent()
    }

    private fun onClear() {
        drawView?.clearPoints()
    }

    private fun onConfirm() {
        val dv = drawView ?: return
        val srcPath = screenshotPath ?: return
        if (!dv.hasValidLasso()) return
        val screenPoints = dv.getPoints()

        val imgRect = dv.imgRect
        val scaleX = bitmapWidth.toFloat() / imgRect.width()
        val scaleY = bitmapHeight.toFloat() / imgRect.height()
        val imgPoints = screenPoints.map {
            PointF(
                ((it.x - imgRect.left) * scaleX),
                ((it.y - imgRect.top) * scaleY)
            )
        }

        hide()
        thread(isDaemon = false) {
            if (confirmMode == "send") {
                val croppedPath = cropToBoundingBox(srcPath, imgPoints)
                Log.i(TAG, "confirm crop ok=${croppedPath != null}")
                handler.post {
                    if (croppedPath != null) {
                        val sendPanel = NativeSendPanel.getInstance(reactContext, toolbarModule)
                        sendPanel.show(cameFromBubble)
                        sendPanel.updateLassoData("", listOf(croppedPath))
                    } else {
                        emitCloseEvent()
                    }
                }
            } else {
                val ok = stageAndBroadcast(srcPath, imgPoints)
                Log.i(TAG, "confirm stage+broadcast ok=$ok")
                handler.post { emitCloseEvent() }
            }
        }
    }

    private fun stageAndBroadcast(srcPath: String, imgPoints: List<PointF>): Boolean {
        return try {
            val dir = File(STAGE_DIR)
            if (!dir.exists()) dir.mkdirs()

            val ts = System.currentTimeMillis()
            val destImg = "$STAGE_DIR/$ts.png"
            val destMask = "$STAGE_DIR/$ts.mask.json"

            File(srcPath).copyTo(File(destImg), overwrite = true)

            var minX = imgPoints[0].x; var maxX = minX
            var minY = imgPoints[0].y; var maxY = minY
            for (p in imgPoints) {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }

            val polyArr = JSONArray()
            for (p in imgPoints) {
                polyArr.put(JSONObject().apply {
                    put("x", p.x.roundToInt())
                    put("y", p.y.roundToInt())
                })
            }
            val boxObj = JSONObject().apply {
                put("x", minX.roundToInt()); put("y", minY.roundToInt())
                put("w", (maxX - minX).roundToInt()); put("h", (maxY - minY).roundToInt())
            }
            val mask = JSONObject().apply {
                put("imageWidth", bitmapWidth)
                put("imageHeight", bitmapHeight)
                put("boundingBox", boxObj)
                put("polygon", polyArr)
                put("ts", ts)
            }
            FileOutputStream(destMask).use { it.write(mask.toString().toByteArray(Charsets.UTF_8)) }

            val intent = Intent("com.dictation.IMAGE_QUERY_FROM_PLUGIN").apply {
                putExtra("imagePath", destImg)
                putExtra("maskPath", destMask)
                putExtra("prompt", "")
            }
            reactContext.sendBroadcast(intent)
            Log.i(TAG, "broadcast sent: img=$destImg mask=$destMask")
            true
        } catch (e: Exception) {
            Log.e(TAG, "stageAndBroadcast failed: ${e.message}", e)
            false
        }
    }

    private fun cropToBoundingBox(srcPath: String, imgPoints: List<PointF>): String? {
        return try {
            val srcBmp = BitmapFactory.decodeFile(srcPath) ?: return null

            var minX = imgPoints[0].x; var maxX = minX
            var minY = imgPoints[0].y; var maxY = minY
            for (p in imgPoints) {
                if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
            }

            val x = minX.roundToInt().coerceIn(0, srcBmp.width - 1)
            val y = minY.roundToInt().coerceIn(0, srcBmp.height - 1)
            val w = (maxX - minX).roundToInt().coerceAtLeast(1).coerceAtMost(srcBmp.width - x)
            val h = (maxY - minY).roundToInt().coerceAtLeast(1).coerceAtMost(srcBmp.height - y)

            val croppedBmp = Bitmap.createBitmap(srcBmp, x, y, w, h)
            srcBmp.recycle()

            val ts = System.currentTimeMillis()
            val outPath = "${reactContext.cacheDir.absolutePath}/lasso_send_$ts.png"
            FileOutputStream(outPath).use { out ->
                croppedBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            croppedBmp.recycle()
            Log.i(TAG, "cropToBoundingBox: ${w}x${h} → $outPath")
            outPath
        } catch (e: Exception) {
            Log.e(TAG, "cropToBoundingBox failed: ${e.message}", e)
            null
        }
    }

    private fun emitCloseEvent() {

        try {
            val params = Arguments.createMap().apply {
                putString("panel", "lassoScreenshot")
                putBoolean("cameFromBubble", cameFromBubble)
            }
            toolbarModule.emitEventPublic("onNativePanelClose", params)
        } catch (_: Exception) {}

        if (cameFromBubble) {
            handler.postDelayed({

                toolbarModule.restoreToolbar()

                FloatingBubbleModule.reshowLast(reactContext)

                AiBubbleModule.reshowLast(reactContext)
            }, 350)
        }
    }

    private fun makeOutlinedBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label
            textSize = 15f
            setTextColor(Color.BLACK)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.BLACK)
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun makeFilledBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(4).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }
}

class LassoDrawView(
    context: android.content.Context,
    val imgRect: RectF,
    private val onChanged: () -> Unit
) : View(context) {

    companion object {
        private const val MIN_POINT_DIST_PX = 8f
        private const val LINE_WIDTH_PX = 3f
    }

    private val points = mutableListOf<PointF>()
    private var drawing = false

    var hintView: View? = null

    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = LINE_WIDTH_PX
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dimPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val startDotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val startDotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init {

        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun pointCount(): Int = points.size
    fun hasValidLasso(): Boolean = points.size >= 3
    fun getPoints(): List<PointF> = points.toList()

    fun clearPoints() {
        points.clear()
        drawing = false
        hintView?.visibility = View.VISIBLE
        invalidate()
        onChanged()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        val x = event.x.coerceIn(imgRect.left, imgRect.right)
        val y = event.y.coerceIn(imgRect.top, imgRect.bottom)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                points.clear()
                points.add(PointF(x, y))
                drawing = true
                hintView?.visibility = View.GONE
                invalidate()
                onChanged()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val last = points.lastOrNull() ?: return true
                val dx = x - last.x; val dy = y - last.y
                if (dx * dx + dy * dy < MIN_POINT_DIST_PX * MIN_POINT_DIST_PX) return true
                points.add(PointF(x, y))
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drawing = false
                invalidate()
                onChanged()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        if (!drawing && points.size >= 3) {
            val sc = canvas.saveLayer(0f, 0f, w, h, null)
            canvas.drawRect(0f, 0f, w, h, dimPaint)
            val cutPath = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                close()
            }
            canvas.drawPath(cutPath, cutPaint)
            canvas.restoreToCount(sc)
        } else {

            canvas.drawRect(0f, 0f, w, h, dimPaint)
        }

        if (points.size >= 2) {
            val linePath = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
                if (!drawing && points.size >= 3) lineTo(points[0].x, points[0].y)
            }
            canvas.drawPath(linePath, pathPaint)
        }

        if (points.isNotEmpty()) {
            val p = points[0]
            val r = 6f
            canvas.drawCircle(p.x, p.y, r, startDotFill)
            canvas.drawCircle(p.x, p.y, r, startDotStroke)
        }
    }
}
