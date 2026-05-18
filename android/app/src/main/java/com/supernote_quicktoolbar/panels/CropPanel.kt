package com.supernote_quicktoolbar.panels

import com.supernote_quicktoolbar.*

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.ui_common.PanelBase
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

        private const val HEADER_H_DP = 56
        private const val IMAGE_PAD_DP = 16
        private const val EDGE_HIT_ZONE_DP = 40
        private const val MIN_CROP_DP = 50
        private const val DIM_ALPHA = 115
        private const val CORNER_SIZE_DP = 30
        private const val CORNER_THICK_DP = 12
        private const val HANDLE_LONG_DP = 32
        private const val HANDLE_SHORT_DP = 12
    }

    private var imagePath: String? = null
    private var bitmap: Bitmap? = null
    private var onCropConfirm: ((CropResult) -> Unit)? = null

    data class CropResult(
        val offsetX: Int, val offsetY: Int,
        val width: Int, val height: Int
    )

    fun show(path: String, onConfirm: (CropResult) -> Unit) {
        Log.i(tag, "show() path=$path")
        currentInstance = this
        imagePath = path
        onCropConfirm = onConfirm
        showPanel()
    }

    override fun onHide() {
        bitmap?.recycle()
        bitmap = null
        imagePath = null
        onCropConfirm = null
        currentInstance = null
    }

    override fun buildFullScreenContent(): View {
        val bmp = BitmapFactory.decodeFile(imagePath)
        bitmap = bmp

        val root = FrameLayout(reactContext).apply {
            setBackgroundColor(Color.parseColor("#E8E8E8"))
        }

        val headerH = dp(HEADER_H_DP)
        val header = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, headerH
            ).apply { gravity = Gravity.TOP }
            setPadding(dp(16), 0, dp(16), 0)
        }

        header.addView(makeHeaderBtn(NativeLocale.t("cancel")) { closeAndRestore() })
        header.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        header.addView(makeHeaderBtn(NativeLocale.t("confirm")) { doConfirm() })
        root.addView(header)

        if (bmp != null) {
            val cropView = CropView(reactContext, bmp, headerH)
            cropView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = headerH }
            root.addView(cropView)
            this.cropView = cropView
        }

        return root
    }

    private var cropView: CropView? = null

    private fun doConfirm() {
        val cv = cropView ?: return
        val bmp = bitmap ?: return
        val result = cv.getCropResult(bmp.width, bmp.height)
        onCropConfirm?.invoke(result)
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun closeAndRestore() {
        hide()
        toolbarModule.restoreToolbar()
    }

    private fun makeHeaderBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(reactContext).apply {
            text = label; textSize = 19f; setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
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
        private val cornerSize = dp(CORNER_SIZE_DP)
        private val cornerThick = dp(CORNER_THICK_DP)
        private val handleLong = dp(HANDLE_LONG_DP)
        private val handleShort = dp(HANDLE_SHORT_DP)

        private var imgRect = RectF()

        private var cropBox = RectF()

        private var dragMode: DragMode? = null
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragStartBox = RectF()

        private val dimPaint = Paint().apply { color = Color.argb(DIM_ALPHA, 0, 0, 0) }
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * density
            pathEffect = DashPathEffect(floatArrayOf(dp(4).toFloat(), dp(4).toFloat()), 0f)
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * density
        }
        private val cornerFillPaint = Paint().apply { color = Color.WHITE }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f * density
        }
        private val handleFillPaint = Paint().apply { color = Color.WHITE }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#999999"); style = Paint.Style.STROKE; strokeWidth = 1f * density
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            computeImageRect(w, h)

            cropBox.set(
                imgRect.left + imgRect.width() * 0.05f,
                imgRect.top + imgRect.height() * 0.05f,
                imgRect.right - imgRect.width() * 0.05f,
                imgRect.bottom - imgRect.height() * 0.05f
            )
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

            val cl = max(cropBox.left, imgRect.left)
            val ct = max(cropBox.top, imgRect.top)
            val cr = min(cropBox.right, imgRect.right)
            val cb = min(cropBox.bottom, imgRect.bottom)

            canvas.drawRect(imgRect.left, imgRect.top, imgRect.right, ct, dimPaint)

            canvas.drawRect(imgRect.left, cb, imgRect.right, imgRect.bottom, dimPaint)

            canvas.drawRect(imgRect.left, ct, cl, cb, dimPaint)

            canvas.drawRect(cr, ct, imgRect.right, cb, dimPaint)

            canvas.drawRect(cropBox, framePaint)

            val co = -(cornerThick - density) / 2f
            drawCorner(canvas, cropBox.left + co, cropBox.top + co, 1, 1)
            drawCorner(canvas, cropBox.right - cornerSize - co, cropBox.top + co, -1, 1)
            drawCorner(canvas, cropBox.left + co, cropBox.bottom - cornerSize - co, 1, -1)
            drawCorner(canvas, cropBox.right - cornerSize - co, cropBox.bottom - cornerSize - co, -1, -1)

            val midX = cropBox.centerX()
            val midY = cropBox.centerY()
            drawHandle(canvas, midX - handleLong / 2f, cropBox.top - handleShort / 2f, handleLong.toFloat(), handleShort.toFloat())
            drawHandle(canvas, midX - handleLong / 2f, cropBox.bottom - handleShort / 2f, handleLong.toFloat(), handleShort.toFloat())
            drawHandle(canvas, cropBox.left - handleShort / 2f, midY - handleLong / 2f, handleShort.toFloat(), handleLong.toFloat())
            drawHandle(canvas, cropBox.right - handleShort / 2f, midY - handleLong / 2f, handleShort.toFloat(), handleLong.toFloat())
        }

        private fun drawCorner(canvas: Canvas, x: Float, y: Float, @Suppress("UNUSED_PARAMETER") dx: Int, @Suppress("UNUSED_PARAMETER") dy: Int) {

            val hRect = RectF(x, y, x + cornerSize, y + cornerThick)
            canvas.drawRect(hRect, cornerFillPaint)
            canvas.drawRect(hRect, cornerPaint)

            val vRect = RectF(x, y, x + cornerThick, y + cornerSize)
            canvas.drawRect(vRect, cornerFillPaint)
            canvas.drawRect(vRect, cornerPaint)
        }

        private fun drawHandle(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
            val r = RectF(x, y, x + w, y + h)
            canvas.drawRect(r, handleFillPaint)
            canvas.drawRect(r, handlePaint)
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

        fun getCropResult(origW: Int, origH: Int): CropResult {
            val scaleX = origW / imgRect.width()
            val scaleY = origH / imgRect.height()
            val relX = cropBox.left - imgRect.left
            val relY = cropBox.top - imgRect.top
            val ox = max(0, (relX * scaleX).roundToInt())
            val oy = max(0, (relY * scaleY).roundToInt())
            val cw = min(origW - ox, (cropBox.width() * scaleX).roundToInt())
            val ch = min(origH - oy, (cropBox.height() * scaleY).roundToInt())
            return CropResult(ox, oy, max(1, cw), max(1, ch))
        }
    }
}
