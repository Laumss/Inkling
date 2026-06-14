package com.supernote_quicktoolbar.panels
import com.supernote_quicktoolbar.BuildConfig

import com.supernote_quicktoolbar.*

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.PanelBar
import com.supernote_quicktoolbar.ui_common.PanelBase
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
    }

    private var imagePath: String? = null
    private var bitmap: Bitmap? = null
    private var onCropConfirm: ((CropResult) -> Unit)? = null
    private var showFooter = false
    private var multiMode = false
    private var hasStitchSession = false
    private var onLongScreenshot: (() -> Unit)? = null
    private var onAddToHistory: ((CropResult) -> Unit)? = null
    private var onFooterCancel: (() -> Unit)? = null

    private var actionBar: PanelBar.Handle? = null
    private var opacity: Int = 100
    private var opacityLabel: TextView? = null

    data class CropResult(
        val offsetX: Int, val offsetY: Int,
        val width: Int, val height: Int,
        val opacity: Int = 100
    )

    fun show(path: String, onConfirm: (CropResult) -> Unit) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "show() path=$path")
        currentInstance = this
        imagePath = path
        onCropConfirm = onConfirm
        showFooter = false
        showPanel()
    }

    fun showWithFooter(
        path: String,
        hasStitchSession: Boolean,
        onConfirm: (CropResult, Boolean) -> Unit,
        onLongScreenshot: () -> Unit,
        onAddToHistory: (CropResult, Boolean) -> Unit,
        onScreenshotToNote: (CropResult) -> Unit,
        onCancel: () -> Unit
    ) {
        if (BuildConfig.ENABLE_DEBUG) Log.i(tag, "showWithFooter() path=$path stitch=$hasStitchSession")
        currentInstance = this
        imagePath = path
        this.hasStitchSession = hasStitchSession
        this.showFooter = true
        this.multiMode = false
        this.onCropConfirmMulti = onConfirm
        this.onLongScreenshot = onLongScreenshot
        this.onAddToHistoryMulti = onAddToHistory
        this.onScreenshotToNote = onScreenshotToNote
        this.onFooterCancel = onCancel
        showPanel()
    }

    private var onCropConfirmMulti: ((CropResult, Boolean) -> Unit)? = null
    private var onAddToHistoryMulti: ((CropResult, Boolean) -> Unit)? = null
    private var onScreenshotToNote: ((CropResult) -> Unit)? = null

    override fun onHide() {
        bitmap?.recycle()
        bitmap = null
        imagePath = null
        onCropConfirm = null
        onCropConfirmMulti = null
        onAddToHistoryMulti = null
        onScreenshotToNote = null
        showFooter = false
        multiMode = false
        hasStitchSession = false
        onLongScreenshot = null
        onAddToHistory = null
        onFooterCancel = null
        actionBar = null
        opacity = 100
        opacityLabel = null
        currentInstance = null
    }

    override fun buildFullScreenContent(): View {
        val bmp = BitmapFactory.decodeFile(imagePath)
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
                    val bmpW = bitmap?.width ?: return@Action
                    val bmpH = bitmap?.height ?: return@Action
                    val wasMulti = multiMode
                    onAddToHistoryMulti?.invoke(cv.getCropResult(bmpW, bmpH), wasMulti)

                    if (wasMulti) clearMultiAfterAction() else hide()
                },
                PanelBar.Action("icons/ic_edit_confirm.xml", NativeLocale.t("insert_next")) {
                    doConfirm()
                },

                PanelBar.Action("icons/ic_edit_to_note.xml", NativeLocale.t("screenshot_to_note")) {
                    val cv = cropView ?: return@Action
                    val bmpW = bitmap?.width ?: return@Action
                    val bmpH = bitmap?.height ?: return@Action
                    onScreenshotToNote?.invoke(cv.getCropResult(bmpW, bmpH))
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

        val left: List<PanelBar.Cell> = if (showFooter) {
            listOf(PanelBar.TextBtn(NativeLocale.t("cancel")) { closeAndRestore() })
        } else {
            listOf(PanelBar.IconBtn("icons/ic_arrow_left.xml") { closeAndRestore() })
        }

        val centerCells: List<PanelBar.Cell> = if (showFooter) center else {
            listOf(PanelBar.Title(NativeLocale.t("cropper_title")))
        }

        val bar = PanelBar.build(
            ctx = reactContext,
            style = if (showFooter) PanelBar.Style.INBOX else PanelBar.Style.PAGE_HEADER,
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

        val bottomH = if (!showFooter) buildOpacityBar(root) else 0

        if (bmp != null) {
            val cropView = CropView(reactContext, bmp, headerH)
            cropView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = headerH; bottomMargin = bottomH }
            root.addView(cropView)
            this.cropView = cropView
        }

        return root
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
        val bmp = bitmap ?: return
        val result = cv.getCropResult(bmp.width, bmp.height)
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

    private fun buildOpacityBar(root: FrameLayout): Int {
        val contentH = dp(60)
        val dividerH = dp(1)
        val totalH = contentH + dividerH

        val wrapper = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, totalH
            ).apply { gravity = Gravity.BOTTOM }
        }
        wrapper.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dividerH)
            setBackgroundColor(Color.BLACK)
        })

        val bar = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, contentH
            )
        }

        bar.addView(TextView(reactContext).apply {
            text = NativeLocale.t("opacity"); textSize = sp(15f); setTextColor(Color.BLACK)
            setPadding(0, 0, dp(12), 0)
        })

        opacityLabel = TextView(reactContext).apply {
            text = "100%"; textSize = sp(15f); setTextColor(Color.BLACK)
            setPadding(0, 0, dp(16), 0)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        bar.addView(opacityLabel)

        for (pct in listOf(100, 75, 50, 25)) {
            bar.addView(TextView(reactContext).apply {
                text = "$pct%"; textSize = sp(14f)
                setTextColor(if (pct == 100) Color.WHITE else Color.BLACK)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                background = GradientDrawable().apply {
                    setColor(if (pct == 100) Color.BLACK else Color.WHITE)
                    setStroke(dp(1), Color.BLACK)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    opacity = pct
                    opacityLabel?.text = "$pct%"
                    updateOpacityBtnHighlights(bar, pct)
                }
            })
        }

        wrapper.addView(bar)
        root.addView(wrapper)
        return totalH
    }

    private fun updateOpacityBtnHighlights(bar: LinearLayout, activePct: Int) {
        for (pct in listOf(100, 75, 50, 25)) {
            val idx = listOf(100, 75, 50, 25).indexOf(pct) + 2
            val btn = bar.getChildAt(idx) as? TextView ?: continue
            val isActive = pct == activePct
            btn.setTextColor(if (isActive) Color.WHITE else Color.BLACK)
            btn.background = GradientDrawable().apply {
                setColor(if (isActive) Color.BLACK else Color.WHITE)
                setStroke(dp(1), Color.BLACK)
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
        private val bmp: Bitmap,
        private val headerH: Int
    ) : View(ctx) {

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
            return CropResult(ox, oy, max(1, cw), max(1, ch), opacity)
        }
    }
}
