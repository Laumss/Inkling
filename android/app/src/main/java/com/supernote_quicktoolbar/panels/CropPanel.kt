package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig

import com.supernote_quicktoolbar.*

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.PanelBar
import com.supernote_quicktoolbar.ui_common.PanelBase
import com.supernote_quicktoolbar.ui_common.ScreenScale
import com.supernote_quicktoolbar.ui_common.SelectField
import com.supernote_quicktoolbar.ui_common.UiUtils
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CropPanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "CropPanel"
    override val panelName = "crop"

    private enum class DragMode {
        MOVE, TOP, BOTTOM, LEFT, RIGHT,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }
    override val fullScreen = true
    override val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    companion object {
        @Volatile var currentInstance: CropPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): CropPanel {
            val inst = currentInstance ?: CropPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val IMAGE_PAD_DP = 48
        private const val EDGE_HIT_ZONE_DP = 40
        private const val MIN_CROP_DP = 50
        private const val CORNER_ARM_DP = 28
        private const val CORNER_WIDTH_DP = 10
        private const val CORNER_STROKE_DP = 2f
        private const val CORNER_OUTSET_DP = 4
        private const val MID_CAP_LONG_DP = 28
        private const val MID_CAP_SHORT_DP = 10
        private const val MID_CAP_STROKE_DP = 2f
        private const val FRAME_STROKE_DP = 2f
        private const val DASH_ON_DP = 16
        private const val DASH_OFF_DP = 10
        private const val FULL_AUTO_RELEASE_DELAY_MS = 300L

        private val DENSITY_STEPS = listOf(100, 80, 60, 40, 20)
    }

    private var imagePath: String? = null
    private var bitmap: Bitmap? = null
    private var onCropConfirm: ((CropResult) -> Unit)? = null
    private var showFooter = false
    private var multiMode = false
    private var hasStitchSession = false
    private var onLongScreenshot: (() -> Unit)? = null
    private var onSendToOtherDevices: ((CropResult) -> Unit)? = null
    private var onAddToHistory: ((CropResult) -> Unit)? = null
    private var onFooterCancel: (() -> Unit)? = null

    private var actionBar: PanelBar.Handle? = null

    private var filterMode: Int = ImageFilter.FILTER_NONE
    private var inkDensity: Int = 100
    private var filterField: LinearLayout? = null
    private var densityField: LinearLayout? = null

    private var origW = 0
    private var origH = 0
    private var filteredBitmap: Bitmap? = null
    private var previewGen = 0
    private val fullAutoHandler = Handler(Looper.getMainLooper())
    private var fullAutoEnabled = false
    private val releaseFullAutoRunnable = Runnable {
        setFullUiAuto(false, "release-delay")
    }

    private fun setFullUiAuto(enable: Boolean, reason: String) {
        fullAutoHandler.removeCallbacks(releaseFullAutoRunnable)
        if (fullAutoEnabled == enable) return
        try {
            val eink = reactContext.getSystemService("eink") ?: return
            val methods = eink.javaClass.methods
            val twoArg = methods.firstOrNull {
                it.name == "enableFullUiAuto" && it.parameterTypes.contentEquals(
                    arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                )
            }
            if (twoArg != null) {
                twoArg.invoke(eink, enable, true)
            } else {
                val oneArg = methods.firstOrNull {
                    it.name == "enableFullUiAuto" && it.parameterTypes.contentEquals(
                        arrayOf(Boolean::class.javaPrimitiveType)
                    )
                } ?: return
                oneArg.invoke(eink, enable)
            }
            fullAutoEnabled = enable
            if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "fullUiAuto=$enable reason=$reason")
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) {
                Log.w(tag, "fullUiAuto call exception reason=$reason: ${e.message}")
            }
        }
    }

    private fun scheduleFullUiAutoRelease(reason: String) {
        fullAutoHandler.removeCallbacks(releaseFullAutoRunnable)
        fullAutoHandler.postDelayed(releaseFullAutoRunnable, FULL_AUTO_RELEASE_DELAY_MS)
        if (BuildConfig.ENABLE_DEBUG) {
            Log.i(tag, "schedule fullUiAuto release reason=$reason delayMs=$FULL_AUTO_RELEASE_DELAY_MS")
        }
    }

    data class CropResult(
        val offsetX: Int, val offsetY: Int,
        val width: Int, val height: Int,
        val filter: Int = ImageFilter.FILTER_NONE,
        val density: Int = 100
    )

    private fun filterPrefs() =
        reactContext.getSharedPreferences("inkling_crop_filter", Context.MODE_PRIVATE)

    fun show(path: String, onConfirm: (CropResult) -> Unit) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "show() path=$path")
        currentInstance = this
        imagePath = path
        onCropConfirm = onConfirm
        showFooter = false
        filterPrefs().let {
            filterMode = it.getInt("filter", ImageFilter.FILTER_NONE)
                .coerceIn(ImageFilter.FILTER_NONE, ImageFilter.FILTER_TEXT_BW)
            inkDensity = it.getInt("density", 100)
        }
        showPanel()
    }

    fun showWithFooter(
        path: String,
        hasStitchSession: Boolean,
        onConfirm: (CropResult, Boolean) -> Unit,
        onLongScreenshot: () -> Unit,
        onSendToOtherDevices: (CropResult) -> Unit,
        onAddToHistory: (CropResult, Boolean) -> Unit,
        onCancel: () -> Unit,
        onShowResult: ((Boolean) -> Unit)? = null
    ) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "showWithFooter() path=$path stitch=$hasStitchSession")
        currentInstance = this
        imagePath = path
        this.hasStitchSession = hasStitchSession
        this.showFooter = true
        this.multiMode = false
        this.onCropConfirmMulti = onConfirm
        this.onLongScreenshot = onLongScreenshot
        this.onSendToOtherDevices = onSendToOtherDevices
        this.onAddToHistoryMulti = onAddToHistory
        this.onFooterCancel = onCancel
        showPanel(onShowResult)
    }

    private var onCropConfirmMulti: ((CropResult, Boolean) -> Unit)? = null
    private var onAddToHistoryMulti: ((CropResult, Boolean) -> Unit)? = null

    override fun onHide() {
        fullAutoHandler.removeCallbacks(releaseFullAutoRunnable)
        setFullUiAuto(false, "hide")
        SelectField.dismissAllPopups()
        previewGen++
        bitmap?.recycle()
        bitmap = null
        filteredBitmap?.recycle()
        filteredBitmap = null
        origW = 0
        origH = 0
        imagePath = null
        onCropConfirm = null
        onCropConfirmMulti = null
        onAddToHistoryMulti = null
        showFooter = false
        multiMode = false
        hasStitchSession = false
        onLongScreenshot = null
        onSendToOtherDevices = null
        onAddToHistory = null
        onFooterCancel = null
        actionBar = null
        filterMode = ImageFilter.FILTER_NONE
        inkDensity = 100
        filterField = null
        densityField = null
        currentInstance = null
    }

    override fun buildFullScreenContent(): View {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imagePath, bounds)
        origW = bounds.outWidth
        origH = bounds.outHeight
        val dm = reactContext.resources.displayMetrics
        val bmp = if (origW > 0 && origH > 0) {
            BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply {
                inSampleSize = calcPreviewSampleSize(origW, origH, dm.widthPixels, dm.heightPixels)
            })
        } else null
        bitmap = bmp

        val root = FrameLayout(reactContext).apply {
            setBackgroundColor(Color.parseColor("#E8E8E8"))
        }

        val center: List<PanelBar.Cell> = if (showFooter) {

            val lsLabel = if (hasStitchSession) NativeLocale.t("long_screenshot_active") else NativeLocale.t("long_screenshot")
            listOf(
                PanelBar.Action("icons/ic_edit_stitch.xml", lsLabel) {
                    onLongScreenshot?.invoke()
                    clearMultiAfterAction()
                },
                PanelBar.Action("icons/ic_edit_save.xml", NativeLocale.t("add_to_history")) {
                    val cv = cropView ?: return@Action
                    if (origW <= 0 || origH <= 0) return@Action
                    val wasMulti = multiMode
                    onAddToHistoryMulti?.invoke(cv.getCropResult(origW, origH), wasMulti)

                    if (wasMulti) clearMultiAfterAction() else hide()
                },
                PanelBar.Action("icons/ic_send_localsend.xml", NativeLocale.t("send_to_other_devices")) {
                    val cv = cropView ?: return@Action
                    if (origW <= 0 || origH <= 0) return@Action
                    onSendToOtherDevices?.invoke(cv.getCropResult(origW, origH))
                    hide()
                },
                PanelBar.Action("icons/ic_edit_to_note.xml", NativeLocale.t("insert_next")) {
                    doConfirm()
                }
            )
        } else {

            emptyList()
        }

        val right: List<PanelBar.Cell> = if (showFooter) {
            listOf(PanelBar.Check(NativeLocale.t("multi")) {
                multiMode = !multiMode
                actionBar?.setChecked(multiMode)
            })
        } else {
            listOf(PanelBar.TextBtn(NativeLocale.t("confirm")) { doConfirm() })
        }

        val left: List<PanelBar.Cell> =
            listOf(PanelBar.TextBtn(NativeLocale.t("cancel")) { closeAndRestore() })

        val centerCells: List<PanelBar.Cell> = if (showFooter) center else {
            listOf(PanelBar.Title(NativeLocale.t("cropper_title")))
        }

        val bar = PanelBar.build(
            ctx = reactContext,
            style = if (showFooter) PanelBar.Style.INBOX else PanelBar.Style.PAGE_HEADER_ALIGNED,
            left = left,
            center = centerCells,
            right = right
        )
        actionBar = bar
        val headerH = bar.heightPx
        bar.view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, headerH
        ).apply { gravity = Gravity.TOP }
        root.addView(bar.view)

        val bottomH = if (!showFooter) buildFilterBar(root) else 0

        if (bmp != null) {
            val cropView = CropView(reactContext, bmp, headerH)
            cropView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = headerH; bottomMargin = bottomH }
            root.addView(cropView)
            this.cropView = cropView
            applyPreviewFilter()
        }

        return root
    }

    private fun calcPreviewSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (outH > reqH || outW > reqW) {
            val halfH = outH / 2; val halfW = outW / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
        }
        return sample
    }

    private fun clearMultiAfterAction() {
        if (multiMode) {
            multiMode = false
            actionBar?.setChecked(false)
        }
    }

    private var cropView: CropView? = null

    private fun doConfirm() {
        val cv = cropView ?: return
        if (origW <= 0 || origH <= 0) return
        val result = cv.getCropResult(origW, origH)
        val hasFooter = showFooter
        val multi = multiMode
        if (hasFooter) {
            onCropConfirmMulti?.invoke(result, multi)
        } else {
            onCropConfirm?.invoke(result)
        }
        if (multi) {

            clearMultiAfterAction()
        } else {
            hide()
        }
        if (!hasFooter) toolbarModule.restoreToolbar()
    }

    private fun buildFilterBar(root: FrameLayout): Int {
        fun px(v: Int) = ScreenScale.px(reactContext, v)

        val wrapper = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }
        wrapper.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(1).coerceAtLeast(1)
            )
            setBackgroundColor(Color.BLACK)
        })

        val row = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(px(52), dp(14), px(52), dp(18))
        }

        val filterOptions = listOf(
            NativeLocale.t("filter_original"),
            NativeLocale.t("filter_enhance"),
            NativeLocale.t("filter_text_bw")
        )
        filterField = SelectField.create(
            reactContext, NativeLocale.t("filter"), filterOptions, filterMode
        ) { idx ->
            filterMode = idx
            filterPrefs().edit().putInt("filter", idx).apply()
            filterField?.let { SelectField.setValue(it, idx) }
            applyPreviewFilter()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(200), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(30) }
        }
        row.addView(filterField)

        val densityLabels = DENSITY_STEPS.map { "$it%" }
        val densityIdx = DENSITY_STEPS.indexOf(inkDensity).coerceAtLeast(0)
        inkDensity = DENSITY_STEPS[densityIdx]
        densityField = SelectField.create(
            reactContext, NativeLocale.t("density"), densityLabels, densityIdx
        ) { idx ->
            inkDensity = DENSITY_STEPS[idx]
            filterPrefs().edit().putInt("density", inkDensity).apply()
            densityField?.let { SelectField.setValue(it, idx) }
            applyPreviewFilter()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(200), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(densityField)

        wrapper.addView(row)
        root.addView(wrapper)

        val screenWpx = reactContext.resources.displayMetrics.widthPixels
        wrapper.measure(
            View.MeasureSpec.makeMeasureSpec(screenWpx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return wrapper.measuredHeight
    }

    private fun applyPreviewFilter() {
        val base = bitmap ?: return
        val gen = ++previewGen
        val f = filterMode
        val d = inkDensity
        if (f == ImageFilter.FILTER_NONE && d >= 100) {
            val old = filteredBitmap
            filteredBitmap = null
            cropView?.setBitmap(base)
            old?.recycle()
            return
        }
        kotlin.concurrent.thread(isDaemon = true) {
            val work = try {
                base.copy(Bitmap.Config.ARGB_8888, true)
            } catch (e: Exception) {
                null
            } ?: return@thread
            ImageFilter.process(work, f, d)
            handler.post {
                if (gen != previewGen || bitmap !== base) {
                    work.recycle()
                    return@post
                }
                val old = filteredBitmap
                filteredBitmap = work
                cropView?.setBitmap(work)
                old?.recycle()
            }
        }
    }

    private fun closeAndRestore() {
        val cancelCb = onFooterCancel
        hide()
        if (cancelCb != null) {
            cancelCb.invoke()
        } else {
            toolbarModule.restoreToolbar()
        }
    }

    private inner class CropView(
        ctx: Context,
        initialBmp: Bitmap,
        private val headerH: Int
    ) : View(ctx) {

        private var bmp: Bitmap = initialBmp

        fun setBitmap(b: Bitmap) {
            bmp = b
            invalidate()
        }

        private val imgPad = dp(IMAGE_PAD_DP)
        private val edgeHitZone = dp(EDGE_HIT_ZONE_DP)
        private val minCropSize = dp(MIN_CROP_DP)
        private val cornerArm = dp(CORNER_ARM_DP)
        private val cornerWidth = dp(CORNER_WIDTH_DP)
        private val cornerOutset = dp(CORNER_OUTSET_DP)
        private val midCapLong = dp(MID_CAP_LONG_DP)
        private val midCapShort = dp(MID_CAP_SHORT_DP)

        private var imgRect = RectF()

        private var cropBox = RectF()

        private var dragMode: DragMode? = null
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragStartBox = RectF()

        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = FRAME_STROKE_DP * density
            pathEffect = DashPathEffect(floatArrayOf(dp(DASH_ON_DP).toFloat(), dp(DASH_OFF_DP).toFloat()), 0f)
        }
        private val cornerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val cornerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = CORNER_STROKE_DP * density
            strokeJoin = Paint.Join.MITER
        }
        private val midCapStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = MID_CAP_STROKE_DP * density
        }
        private val midCapFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#999999"); style = Paint.Style.STROKE; strokeWidth = 1f * density
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            computeImageRect(w, h)
            cropBox.set(imgRect)
        }

        private fun computeImageRect(viewW: Int, viewH: Int) {
            val availW = viewW - imgPad * 2f
            val availH = viewH - imgPad * 2f
            val imgAspect = bmp.width.toFloat() / bmp.height
            val areaAspect = availW / availH

            val dispW: Float; val dispH: Float
            if (imgAspect > areaAspect) {
                dispW = availW; dispH = availW / imgAspect
            } else {
                dispH = availH; dispW = availH * imgAspect
            }

            val ox = (viewW - dispW) / 2f
            val oy = imgPad + (availH - dispH) / 2f
            imgRect.set(ox, oy, ox + dispW, oy + dispH)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            canvas.drawBitmap(bmp, null, imgRect, null)

            canvas.drawRect(imgRect, borderPaint)

            canvas.drawRect(cropBox, framePaint)

            drawCornerOutside(canvas, cropBox.left, cropBox.top, -1, -1)
            drawCornerOutside(canvas, cropBox.right, cropBox.top, 1, -1)
            drawCornerOutside(canvas, cropBox.left, cropBox.bottom, -1, 1)
            drawCornerOutside(canvas, cropBox.right, cropBox.bottom, 1, 1)

            val midX = cropBox.centerX()
            val midY = cropBox.centerY()

            drawMidCap(canvas, midX, cropBox.top, horizontal = true)
            drawMidCap(canvas, midX, cropBox.bottom, horizontal = true)

            drawMidCap(canvas, cropBox.left, midY, horizontal = false)
            drawMidCap(canvas, cropBox.right, midY, horizontal = false)
        }

        private fun drawCornerOutside(canvas: Canvas, cx: Float, cy: Float, dx: Int, dy: Int) {
            val o = cornerOutset.toFloat()
            val arm = cornerArm.toFloat()
            val w = cornerWidth.toFloat()

            val ox = cx + dx * o
            val oy = cy + dy * o

            val path = Path().apply {
                moveTo(ox, oy)
                lineTo(ox - dx * arm, oy)
                lineTo(ox - dx * arm, oy - dy * w)
                lineTo(ox - dx * w, oy - dy * w)
                lineTo(ox - dx * w, oy - dy * arm)
                lineTo(ox, oy - dy * arm)
                close()
            }
            canvas.drawPath(path, cornerFillPaint)
            canvas.drawPath(path, cornerStrokePaint)
        }

        private fun drawMidCap(canvas: Canvas, cx: Float, cy: Float, horizontal: Boolean) {
            val w = if (horizontal) midCapLong.toFloat() else midCapShort.toFloat()
            val h = if (horizontal) midCapShort.toFloat() else midCapLong.toFloat()
            val r = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            canvas.drawRect(r, midCapFillPaint)
            canvas.drawRect(r, midCapStrokePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragMode = detectDragMode(event.x, event.y)
                    dragStartX = event.x
                    dragStartY = event.y
                    dragStartBox = RectF(cropBox)
                    return dragMode != null
                }
                MotionEvent.ACTION_MOVE -> {
                    val mode = dragMode ?: return false
                    setFullUiAuto(true, "crop-move")
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY
                    val orig = dragStartBox
                    var nl = orig.left; var nt = orig.top
                    var nr = orig.right; var nb = orig.bottom
                    when (mode) {
                        DragMode.MOVE -> { nl += dx; nt += dy; nr += dx; nb += dy }
                        DragMode.TOP -> { nt += dy }
                        DragMode.BOTTOM -> { nb += dy }
                        DragMode.LEFT -> { nl += dx }
                        DragMode.RIGHT -> { nr += dx }
                        DragMode.TOP_LEFT -> { nl += dx; nt += dy }
                        DragMode.TOP_RIGHT -> { nr += dx; nt += dy }
                        DragMode.BOTTOM_LEFT -> { nl += dx; nb += dy }
                        DragMode.BOTTOM_RIGHT -> { nr += dx; nb += dy }
                    }
                    clampAndSet(nl, nt, nr, nb, mode == DragMode.MOVE)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragMode = null
                    scheduleFullUiAutoRelease(
                        if (event.action == MotionEvent.ACTION_UP) "crop-up" else "crop-cancel"
                    )
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun detectDragMode(x: Float, y: Float): DragMode? {
            val nearL = Math.abs(x - cropBox.left) < edgeHitZone
            val nearR = Math.abs(x - cropBox.right) < edgeHitZone
            val nearT = Math.abs(y - cropBox.top) < edgeHitZone
            val nearB = Math.abs(y - cropBox.bottom) < edgeHitZone

            if (nearT && nearL) return DragMode.TOP_LEFT
            if (nearT && nearR) return DragMode.TOP_RIGHT
            if (nearB && nearL) return DragMode.BOTTOM_LEFT
            if (nearB && nearR) return DragMode.BOTTOM_RIGHT
            if (nearT) return DragMode.TOP
            if (nearB) return DragMode.BOTTOM
            if (nearL) return DragMode.LEFT
            if (nearR) return DragMode.RIGHT
            if (x in cropBox.left..cropBox.right && y in cropBox.top..cropBox.bottom) return DragMode.MOVE
            return null
        }

        private fun clampAndSet(l: Float, t: Float, r: Float, b: Float, isMove: Boolean) {
            var w = r - l; var h = b - t
            if (isMove) {
                var nl = l; var nt = t
                nl = max(imgRect.left, min(nl, imgRect.right - w))
                nt = max(imgRect.top, min(nt, imgRect.bottom - h))
                cropBox.set(nl, nt, nl + w, nt + h)
            } else {
                w = max(minCropSize.toFloat(), w)
                h = max(minCropSize.toFloat(), h)
                var nl = min(l, r - minCropSize)
                var nt = min(t, b - minCropSize)
                nl = max(imgRect.left, nl)
                nt = max(imgRect.top, nt)
                var nr = max(nl + minCropSize, nl + w)
                var nb = max(nt + minCropSize, nt + h)
                nr = min(nr, imgRect.right)
                nb = min(nb, imgRect.bottom)
                cropBox.set(nl, nt, nr, nb)
            }
        }

        fun resetCropBox() {
            cropBox.set(imgRect)
            invalidate()
        }

        fun getCropResult(origW: Int, origH: Int): CropResult {
            val scaleX = origW / imgRect.width()
            val scaleY = origH / imgRect.height()
            val relX = cropBox.left - imgRect.left
            val relY = cropBox.top - imgRect.top
            val ox = max(0, (relX * scaleX).roundToInt())
            val oy = max(0, (relY * scaleY).roundToInt())
            val cw = min(origW - ox, (cropBox.width() * scaleX).roundToInt())
            val ch = min(origH - oy, (cropBox.height() * scaleY).roundToInt())
            return CropResult(ox, oy, max(1, cw), max(1, ch), filterMode, inkDensity)
        }
    }
}
