package com.supernote_quicktoolbar.panels

import com.supernote_quicktoolbar.*

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.*
import com.facebook.react.bridge.ReactApplicationContext
import com.supernote_quicktoolbar.panels.DocScreenshotService.StitchSessionData
import com.supernote_quicktoolbar.panels.DocScreenshotService.StitchImage
import com.supernote_quicktoolbar.panels.DocScreenshotService.StitchParams
import com.supernote_quicktoolbar.ui_common.PanelBar
import com.supernote_quicktoolbar.ui_common.PanelBase
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class StitchPanel(
    ctx: ReactApplicationContext,
    toolbar: FloatingToolbarModule
) : PanelBase(ctx, toolbar) {

    override val tag = "StitchPanel"
    override val panelName = "stitch"
    override val fullScreen = true
    override val windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    override val forcedOrientation: Int? get() = desiredOrientation()

    companion object {
        @Volatile var currentInstance: StitchPanel? = null

        fun getInstance(ctx: ReactApplicationContext, module: FloatingToolbarModule): StitchPanel {
            val inst = currentInstance ?: StitchPanel(ctx, module)
            currentInstance = inst
            return inst
        }

        private const val CTRL_H_DP = 120
        private const val CTRL_H_LAND_DP = 150
        private const val CTRL_H_LAND_GRID_DP = 200
        private const val PAD_DP = 12
        private const val HANDLE_LEN_DP = 40
        private const val HANDLE_THICK_DP = 6
        private const val HIT_RADIUS_DP = 40
        private const val MIN_VISIBLE = 0.05f

        private const val DRAG_EDGE = 0
        private const val DRAG_BOTH = 1
        private const val DRAG_OVERLAP = 2
    }

    private var session: StitchSessionData? = null
    private var onConfirm: ((StitchSessionData) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var stitchView: StitchView? = null
    private var gridView: GridStitchView? = null
    private var bitmaps = mutableListOf<Bitmap?>()
    private var isCompositing = false
    private var configCallback: ComponentCallbacks2? = null
    private var lastBuiltLandscape: Boolean? = null
    private var colBtnList = mutableListOf<Pair<Int, TextView>>()
    private var gridOrderRow: LinearLayout? = null
    private var gridOrderBtn: TextView? = null
    private var preCompositeBitmap: Bitmap? = null
    private var preCompositePath: String? = null
    private var stripVirtualSession: StitchSessionData? = null

    private val isGridMode: Boolean get() = (session?.images?.size ?: 0) > 2

    private fun desiredOrientation(): Int {
        val s = session ?: return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val horizontal: Boolean = if (isGridMode) {
            when {
                s.params.cols == 1 -> false
                s.params.cols >= s.images.size -> true
                else -> {
                    val cols = s.params.cols
                    val rows = (s.images.size + cols - 1) / cols
                    val maxEffW = s.images.maxOf { it.width * (1f - it.cropLeft - it.cropRight) }
                    val maxEffH = s.images.maxOf { it.height * (1f - it.cropTop - it.cropBottom) }
                    maxEffW * cols > maxEffH * rows
                }
            }
        } else {
            s.params.direction == "horizontal"
        }
        return if (horizontal) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
               else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun show(session: StitchSessionData, onConfirm: (StitchSessionData) -> Unit, onCancel: () -> Unit) {
        Log.i(tag, "show() images=${session.images.size} cols=${session.params.cols}")
        currentInstance = this
        this.session = session
        this.onConfirm = onConfirm
        this.onCancel = onCancel
        isCompositing = false
        applyDefaultDirection(session)
        registerConfigCallback()
        showPanel()
    }

    private fun applyDefaultDirection(session: StitchSessionData) {
        toolbarModule.refreshScreenDimensions()
        if (session.images.size > 2) {
            session.params.cols = if (session.params.direction == "vertical") 1 else session.images.size
        } else {
            val wantHorizontal = !isLandscape
            session.params.direction = if (wantHorizontal) "horizontal" else "vertical"
        }
    }

    override fun onHide() {
        unregisterConfigCallback()
        bitmaps.forEach { it?.recycle() }
        bitmaps.clear()
        session = null
        onConfirm = null
        onCancel = null
        stitchView = null
        gridView = null
        lastBuiltLandscape = null
        colBtnList.clear()
        gridOrderRow = null
        gridOrderBtn = null
        preCompositeBitmap?.recycle()
        preCompositeBitmap = null
        preCompositePath?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
        preCompositePath = null
        stripVirtualSession = null
        currentInstance = null
    }

    private fun registerConfigCallback() {
        if (configCallback != null) return
        val cb = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {
                handler.post { rebuildIfOrientationChanged() }
            }
            override fun onLowMemory() {}
            override fun onTrimMemory(level: Int) {}
        }
        configCallback = cb
        try { reactContext.applicationContext.registerComponentCallbacks(cb) } catch (_: Exception) {}
    }

    private fun unregisterConfigCallback() {
        val cb = configCallback ?: return
        try { reactContext.applicationContext.unregisterComponentCallbacks(cb) } catch (_: Exception) {}
        configCallback = null
    }

    private fun rebuildContent() {
        val root = rootView as? FrameLayout ?: return
        stitchView = null
        gridView = null
        root.removeAllViews()
        populateContent(root)
    }

    private fun preCompositeStrip(sess: StitchSessionData): Bitmap? {
        val imgs = sess.images.subList(0, sess.images.size - 1)
        if (imgs.isEmpty()) return null
        if (imgs.size == 1) return BitmapFactory.decodeFile(imgs[0].path)

        val isVert = sess.params.direction == "vertical"
        val effWs = imgs.map { ((1f - it.cropLeft - it.cropRight) * it.width).roundToInt() }
        val effHs = imgs.map { ((1f - it.cropTop - it.cropBottom) * it.height).roundToInt() }
        val ovl = sess.params.overlap

        val canvasW: Int; val canvasH: Int
        if (isVert) {
            canvasW = effWs.max(); canvasH = effHs.sum() - ovl * (imgs.size - 1)
        } else {
            canvasW = effWs.sum() - ovl * (imgs.size - 1); canvasH = effHs.max()
        }
        if (canvasW <= 0 || canvasH <= 0) return null

        val result = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (isVert) {
            var yOff = 0f
            for ((i, img) in imgs.withIndex()) {
                val bmp = BitmapFactory.decodeFile(img.path) ?: continue
                val src = Rect(
                    (img.width * img.cropLeft).toInt(), (img.height * img.cropTop).toInt(),
                    (img.width * (1f - img.cropRight)).toInt(), (img.height * (1f - img.cropBottom)).toInt()
                )
                val ox = (canvasW - effWs[i]) / 2f
                canvas.drawBitmap(bmp, src, RectF(ox, yOff, ox + effWs[i], yOff + effHs[i]), paint)
                bmp.recycle()
                yOff += effHs[i] - ovl
            }
        } else {
            var xOff = 0f
            for ((i, img) in imgs.withIndex()) {
                val bmp = BitmapFactory.decodeFile(img.path) ?: continue
                val src = Rect(
                    (img.width * img.cropLeft).toInt(), (img.height * img.cropTop).toInt(),
                    (img.width * (1f - img.cropRight)).toInt(), (img.height * (1f - img.cropBottom)).toInt()
                )
                val oy = (canvasH - effHs[i]) / 2f
                canvas.drawBitmap(bmp, src, RectF(xOff, oy, xOff + effWs[i], oy + effHs[i]), paint)
                bmp.recycle()
                xOff += effWs[i] - ovl
            }
        }
        return result
    }

    private fun rebuildIfOrientationChanged() {
        val root = rootView as? FrameLayout ?: return
        toolbarModule.refreshScreenDimensions()
        if (lastBuiltLandscape == isLandscape) return
        Log.i(tag, "orientation flip → rebuild content (landscape=$isLandscape)")
        stitchView = null
        gridView = null
        root.removeAllViews()
        populateContent(root)
    }

    override fun buildFullScreenContent(): View {
        val root = FrameLayout(reactContext).apply {
            setBackgroundColor(Color.parseColor("#E8E8E8"))
        }
        populateContent(root)
        return root
    }

    private fun populateContent(root: FrameLayout) {
        val sess = session ?: return

        val ctrlH = dp(when {
            !isLandscape -> CTRL_H_DP
            isGridMode -> CTRL_H_LAND_GRID_DP
            else -> CTRL_H_LAND_DP
        })
        lastBuiltLandscape = isLandscape

        if (bitmaps.isEmpty()) {
            for (img in sess.images) {
                bitmaps.add(BitmapFactory.decodeFile(img.path))
            }
        }

        val bar = PanelBar.build(
            ctx = reactContext,

            style = PanelBar.Style.INBOX.copy(rightAlign = true),
            left = listOf(PanelBar.TextBtn(NativeLocale.t("cancel")) { doCancel() }),
            right = listOf(PanelBar.TextBtn(NativeLocale.t("confirm")) { doConfirm() })
        )
        val headerH = bar.heightPx
        bar.view.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, headerH
        ).apply { gravity = Gravity.TOP }
        root.addView(bar.view)

        if (isGridMode && isLinearMode(sess)) {
            val preComp = preCompositeStrip(sess)
            val lastBmp = bitmaps.lastOrNull()
            if (preComp != null && lastBmp != null) {
                preCompositeBitmap?.recycle()
                preCompositeBitmap = preComp
                val tmpPath = "${reactContext.cacheDir.absolutePath}/pre_composite_${System.currentTimeMillis()}.png"
                java.io.FileOutputStream(tmpPath).use { preComp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                preCompositePath?.let { try { java.io.File(it).delete() } catch (_: Exception) {} }
                preCompositePath = tmpPath

                val lastImg = sess.images.last()
                val vSess = StitchSessionData(
                    images = mutableListOf(
                        StitchImage(tmpPath, preComp.width, preComp.height),
                        StitchImage(lastImg.path, lastImg.width, lastImg.height,
                            lastImg.cropTop, lastImg.cropBottom, lastImg.cropLeft, lastImg.cropRight)
                    ),
                    params = StitchParams(
                        direction = sess.params.direction,
                        overlap = 0,
                        topLayerIndex = 1,
                        cols = 0
                    ),
                    createdAt = sess.createdAt
                )
                stripVirtualSession = vSess

                val sv = StitchView(reactContext, vSess, preComp, lastBmp, headerH, ctrlH)
                sv.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { topMargin = headerH; bottomMargin = ctrlH }
                root.addView(sv)
                stitchView = sv

                val ctrl = buildStripModeControlPanel(vSess, ctrlH)
                ctrl.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, ctrlH
                ).apply { gravity = Gravity.BOTTOM }
                root.addView(ctrl)
            }
        } else if (isGridMode) {
            stripVirtualSession = null

            val gv = GridStitchView(reactContext, sess, bitmaps, headerH, ctrlH)
            gv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = headerH; bottomMargin = ctrlH }
            root.addView(gv)
            gridView = gv

            val ctrl = buildGridControlPanel(sess, ctrlH)
            ctrl.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, ctrlH
            ).apply { gravity = Gravity.BOTTOM }
            root.addView(ctrl)
        } else if (bitmaps.size >= 2 && bitmaps[0] != null && bitmaps[1] != null) {
            stripVirtualSession = null

            val sv = StitchView(reactContext, sess, bitmaps[0]!!, bitmaps[1]!!, headerH, ctrlH)
            sv.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = headerH; bottomMargin = ctrlH }
            root.addView(sv)
            stitchView = sv

            val ctrl = buildControlPanel(sess, ctrlH)
            ctrl.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, ctrlH
            ).apply { gravity = Gravity.BOTTOM }
            root.addView(ctrl)
        } else {
            val waitView = LinearLayout(reactContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { topMargin = headerH }
            }
            waitView.addView(TextView(reactContext).apply {
                text = NativeLocale.t("stitch_waiting")
                textSize = sp(23f); setTextColor(Color.BLACK); gravity = Gravity.CENTER
            })
            waitView.addView(TextView(reactContext).apply {
                text = NativeLocale.t("stitch_waiting_hint")
                textSize = sp(16f); setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.CENTER; setPadding(dp(40), dp(12), dp(40), 0)
            })
            root.addView(waitView)

            val ctrl = buildControlPanel(sess, ctrlH)
            ctrl.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, ctrlH
            ).apply { gravity = Gravity.BOTTOM }
            root.addView(ctrl)
        }
    }

    private fun buildStripModeControlPanel(vSess: StitchSessionData, ctrlH: Int): LinearLayout {
        val ctrl = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (isLandscape) setPadding(dp(12), dp(14), dp(12), dp(22))
            else setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        ctrl.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(Color.parseColor("#CCCCCC"))
        })

        val modeRow = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        val dirLabel = if (session?.params?.direction == "vertical") "↕ Strip" else "↔ Strip"
        val stripBtn = makeCtrlBtn(dirLabel) {}
        stripBtn.background = GradientDrawable().apply { setColor(Color.BLACK); setStroke(dp(1), Color.BLACK) }
        stripBtn.setTextColor(Color.WHITE)
        modeRow.addView(stripBtn)
        modeRow.addView(makeCtrlBtn("⊞ Grid") {
            val s = session ?: return@makeCtrlBtn
            s.params.cols = 2
            applyOrientation(desiredOrientation())
            rebuildContent()
        })
        ctrl.addView(modeRow)

        val row2 = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        row2.addView(makeSmallBtn("⇅ Swap") {
            stitchView?.swapBitmaps()
            val tmp = vSess.images[0]
            vSess.images[0] = vSess.images[1]
            vSess.images[1] = tmp
            rebuildStitchView()
        })
        row2.addView(makeSmallBtn("☰ Top: ${vSess.params.topLayerIndex + 1}") {
            vSess.params.topLayerIndex = if (vSess.params.topLayerIndex == 0) 1 else 0
            rebuildStitchView()
        })
        ctrl.addView(row2)

        val row3 = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        overlapLabel = TextView(reactContext).apply {
            text = "Overlap: ${vSess.params.overlap}px"
            textSize = sp(15f); setTextColor(Color.BLACK)
            setPadding(0, 0, dp(8), 0)
        }
        row3.addView(overlapLabel)
        for (delta in listOf(-50, -10, 10, 50)) {
            row3.addView(makeSmallBtn(if (delta > 0) "+$delta" else "$delta") {
                adjustOverlap(delta)
            })
        }
        ctrl.addView(row3)
        return ctrl
    }

    private var colsLabel: TextView? = null
    private var gridCtrlContainer: LinearLayout? = null
    private var gridOverlapRow: LinearLayout? = null
    private var gridOverlapLabel: TextView? = null

    private fun isLinearMode(sess: StitchSessionData): Boolean {
        return sess.params.cols == 1 || sess.params.cols >= sess.images.size
    }

    private fun buildGridControlPanel(sess: StitchSessionData, ctrlH: Int): LinearLayout {
        val ctrl = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (isLandscape) setPadding(dp(12), dp(14), dp(12), dp(22))
            else setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        gridCtrlContainer = ctrl

        ctrl.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(Color.parseColor("#CCCCCC"))
        })

        val row1 = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }

        val dirLabel = if (sess.params.direction == "vertical") "↕ Strip" else "↔ Strip"
        row1.addView(makeCtrlBtn(dirLabel) {
            val s = session ?: return@makeCtrlBtn
            s.params.cols = if (s.params.direction == "vertical") 1 else s.images.size
            applyOrientation(desiredOrientation())
            rebuildContent()
        })

        colsLabel = TextView(reactContext).apply {
            text = "⊞ Grid"
            textSize = sp(15f); setTextColor(Color.BLACK)
            setPadding(0, 0, dp(12), 0)
        }
        row1.addView(colsLabel)

        colBtnList.clear()
        for (c in 1..minOf(4, sess.images.size)) {
            val label = if (c == 1) "1 col" else "$c cols"
            val btn = makeCtrlBtn(label) {
                val s = session ?: return@makeCtrlBtn
                s.params.cols = c
                updateColHighlights(c)
                updateOverlapRowVisibility(s)
                updateGridOrderVisibility(s)
                applyOrientation(desiredOrientation())
                gridView?.updateSession(s)
            }
            colBtnList.add(c to btn)
            row1.addView(btn)
        }
        updateColHighlights(sess.params.cols)
        ctrl.addView(row1)

        gridOrderRow = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            visibility = if (!isLinearMode(sess)) View.VISIBLE else View.GONE
        }
        gridOrderBtn = makeSmallBtn(gridOrderLabel(sess)) {
            val s = session ?: return@makeSmallBtn
            s.params.gridOrder = if (s.params.gridOrder == "row") "col" else "row"
            gridOrderBtn?.text = gridOrderLabel(s)
            gridView?.updateSession(s)
        }
        gridOrderRow!!.addView(gridOrderBtn)
        ctrl.addView(gridOrderRow!!)

        gridOverlapRow = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
            visibility = if (isLinearMode(sess)) View.VISIBLE else View.GONE
        }
        gridOverlapLabel = TextView(reactContext).apply {
            text = "Overlap: ${sess.params.overlap}px"
            textSize = sp(15f); setTextColor(Color.BLACK)
            setPadding(0, 0, dp(8), 0)
        }
        gridOverlapRow!!.addView(gridOverlapLabel)
        for (delta in listOf(-50, -10, 10, 50)) {
            gridOverlapRow!!.addView(makeSmallBtn(if (delta > 0) "+$delta" else "$delta") {
                adjustGridOverlap(delta)
            })
        }
        ctrl.addView(gridOverlapRow!!)

        val row3 = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row3.addView(makeSmallBtn("⇅ Swap last") {
            val s = session ?: return@makeSmallBtn
            val n = s.images.size
            if (n >= 2) {
                val tmp = s.images[n - 2]
                s.images[n - 2] = s.images[n - 1]
                s.images[n - 1] = tmp
                val tmpB = bitmaps[n - 2]
                bitmaps[n - 2] = bitmaps[n - 1]
                bitmaps[n - 1] = tmpB
                gridView?.updateSession(s)
            }
        })
        row3.addView(makeSmallBtn("× Remove last") {
            val s = session ?: return@makeSmallBtn
            if (s.images.size > 2) {
                val last = s.images.removeAt(s.images.size - 1)
                try { java.io.File(last.path).delete() } catch (_: Exception) {}
                bitmaps.removeAt(bitmaps.size - 1)?.recycle()
                if (s.images.size <= 2) s.params.cols = 0
                colsLabel?.text = "${s.images.size} images"
                applyOrientation(desiredOrientation())
                gridView?.updateSession(s)
            }
        })
        ctrl.addView(row3)

        return ctrl
    }

    private fun adjustGridOverlap(delta: Int) {
        val s = session ?: return
        if (s.images.size < 2) return
        val isVert = s.params.cols == 1
        val dim = if (isVert) {
            s.images.minOf { it.height * (1 - it.cropTop - it.cropBottom) }
        } else {
            s.images.minOf { it.width * (1 - it.cropLeft - it.cropRight) }
        }
        val maxOvl = (dim * 0.8f).toInt()
        s.params.overlap = max(0, min(maxOvl, s.params.overlap + delta))
        gridOverlapLabel?.text = "Overlap: ${s.params.overlap}px"
        gridView?.updateSession(s)
    }

    private fun updateColHighlights(activeCols: Int) {
        for ((c, btn) in colBtnList) {
            if (c == activeCols) {
                btn.background = GradientDrawable().apply {
                    setColor(Color.BLACK); setStroke(dp(1), Color.BLACK)
                }
                btn.setTextColor(Color.WHITE)
            } else {
                btn.background = GradientDrawable().apply {
                    setColor(Color.WHITE); setStroke(dp(1), Color.BLACK)
                }
                btn.setTextColor(Color.BLACK)
            }
        }
    }

    private fun updateOverlapRowVisibility(sess: StitchSessionData) {
        gridOverlapRow?.visibility = if (isLinearMode(sess)) View.VISIBLE else View.GONE
    }

    private fun updateGridOrderVisibility(sess: StitchSessionData) {
        gridOrderRow?.visibility = if (!isLinearMode(sess)) View.VISIBLE else View.GONE
    }

    private fun gridOrderLabel(sess: StitchSessionData): String {
        return if (sess.params.gridOrder == "row") "⊞ 12|34" else "⊞ 13|24"
    }

    private fun buildControlPanel(sess: StitchSessionData, ctrlH: Int): LinearLayout {
        val ctrl = LinearLayout(reactContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER
            if (isLandscape) setPadding(dp(12), dp(14), dp(12), dp(22))
            else setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        ctrl.addView(View(reactContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
            setBackgroundColor(Color.parseColor("#CCCCCC"))
        })

        val row1 = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        val dirBtn = makeCtrlBtn(if (sess.params.direction == "vertical") "↕ Vertical" else "↔ Horizontal") {
            val s = session ?: return@makeCtrlBtn
            s.params.direction = if (s.params.direction == "vertical") "horizontal" else "vertical"
            s.params.overlap = (s.params.overlap * 0.5).toInt()
            applyOrientation(desiredOrientation())
            rebuildStitchView()
        }
        val swapBtn = makeCtrlBtn("⇅ Swap") {
            val s = session ?: return@makeCtrlBtn
            if (s.images.size >= 2) {
                val tmp = s.images[0]
                s.images[0] = s.images[1]
                s.images[1] = tmp
                val tmpBmp = bitmaps[0]
                bitmaps[0] = bitmaps[1]
                bitmaps[1] = tmpBmp
                stitchView?.swapBitmaps()
                rebuildStitchView()
            }
        }
        val topBtn = makeCtrlBtn("☰ Top: ${sess.params.topLayerIndex + 1}") {
            val s = session ?: return@makeCtrlBtn
            s.params.topLayerIndex = if (s.params.topLayerIndex == 0) 1 else 0
            rebuildStitchView()
        }
        row1.addView(dirBtn); row1.addView(swapBtn); row1.addView(topBtn)
        ctrl.addView(row1)

        val row2 = LinearLayout(reactContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        overlapLabel = TextView(reactContext).apply {
            text = "Overlap: ${sess.params.overlap}px"
            textSize = sp(15f); setTextColor(Color.BLACK)
            setPadding(0, 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row2.addView(overlapLabel)
        for (delta in listOf(-50, -10, 10, 50)) {
            row2.addView(makeSmallBtn(if (delta > 0) "+$delta" else "$delta") {
                adjustOverlap(delta)
            })
        }
        ctrl.addView(row2)

        return ctrl
    }

    private var overlapLabel: TextView? = null

    private fun adjustOverlap(delta: Int) {
        val s = stripVirtualSession ?: session ?: return
        val imgs = s.images
        if (imgs.size < 2) return
        val isVert = s.params.direction == "vertical"
        val dim0 = if (isVert) imgs[0].height * (1 - imgs[0].cropTop - imgs[0].cropBottom)
                   else imgs[0].width * (1 - imgs[0].cropLeft - imgs[0].cropRight)
        val dim1 = if (isVert) imgs[1].height * (1 - imgs[1].cropTop - imgs[1].cropBottom)
                   else imgs[1].width * (1 - imgs[1].cropLeft - imgs[1].cropRight)
        val maxOvl = (min(dim0, dim1) * 0.8f).toInt()
        s.params.overlap = max(0, min(maxOvl, s.params.overlap + delta))
        overlapLabel?.text = "Overlap: ${s.params.overlap}px"
        stitchView?.updateSession(s)
    }

    private fun rebuildStitchView() {
        val s = stripVirtualSession ?: session ?: return
        stitchView?.updateSession(s)
        overlapLabel?.text = "Overlap: ${s.params.overlap}px"
    }

    private fun doConfirm() {
        if (isCompositing) return
        isCompositing = true
        val s = stripVirtualSession ?: session ?: return
        onConfirm?.invoke(s)
    }

    private fun doCancel() {
        onCancel?.invoke()
        hide()
    }

    private fun makeCtrlBtn(label: String, onClick: () -> Unit): TextView {
        val land = isLandscape
        return TextView(reactContext).apply {
            text = label; textSize = sp(if (land) 18f else 15f); setTextColor(Color.BLACK)
            if (land) setPadding(dp(20), dp(11), dp(20), dp(11))
            else setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.BLACK)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(if (land) 16 else 10) }
            setOnClickListener { onClick() }
        }
    }

    private fun makeSmallBtn(label: String, onClick: () -> Unit): TextView {
        val land = isLandscape
        return TextView(reactContext).apply {
            text = label; textSize = sp(if (land) 16f else 14f); setTextColor(Color.BLACK)
            if (land) setPadding(dp(16), dp(8), dp(16), dp(8))
            else setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#666666"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(if (land) 10 else 6) }
            setOnClickListener { onClick() }
        }
    }

    private inner class GridStitchView(
        ctx: Context,
        private var sess: StitchSessionData,
        private val bmps: MutableList<Bitmap?>,
        private val headerH: Int,
        private val ctrlH: Int
    ) : View(ctx) {

        private val pad = dp(PAD_DP)
        private val imgBorderPaint = Paint().apply {
            color = Color.parseColor("#888888"); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        private val labelBgPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * resources.displayMetrics.density * com.supernote_quicktoolbar.ui_common.ScreenScale.factor(context)
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT
        }

        private var cellRects = listOf<RectF>()

        fun updateSession(s: StitchSessionData) {
            sess = s
            computeLayout(width, height)
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            computeLayout(w, h)
        }

        private fun computeLayout(viewW: Int, viewH: Int) {
            if (viewW == 0 || viewH == 0 || sess.images.isEmpty()) return
            val cols = max(1, sess.params.cols)
            val n = sess.images.size
            val rows = (n + cols - 1) / cols

            val effWs = sess.images.map { it.width * (1f - it.cropLeft - it.cropRight) }
            val effHs = sess.images.map { it.height * (1f - it.cropTop - it.cropBottom) }
            val maxEffW = effWs.max()
            val maxEffH = effHs.max()

            val isVertStrip = cols == 1
            val isHorizStrip = cols >= n
            val ovl = if (isVertStrip || isHorizStrip) sess.params.overlap.toFloat() else 0f

            val totalW: Float
            val totalH: Float
            if (isVertStrip) {
                totalW = maxEffW
                totalH = effHs.sum() - ovl * (n - 1)
            } else if (isHorizStrip) {
                totalW = effWs.sum() - ovl * (n - 1)
                totalH = maxEffH
            } else {
                totalW = maxEffW * cols
                totalH = maxEffH * rows
            }

            val availW = viewW - pad * 2f
            val availH = viewH - pad * 2f
            val scale = min(availW / max(totalW, 1f), min(availH / max(totalH, 1f), 1f))
            val ovlScaled = ovl * scale
            val gridW = totalW * scale
            val gridH = totalH * scale
            val originX = (viewW - gridW) / 2f
            val originY = (viewH - gridH) / 2f

            cellRects = if (isVertStrip) {
                var yOff = originY
                (0 until n).map { i ->
                    val cw = effWs[i] * scale
                    val ch = effHs[i] * scale
                    val x = originX + (maxEffW * scale - cw) / 2f
                    val rect = RectF(x, yOff, x + cw, yOff + ch)
                    yOff += ch - ovlScaled
                    rect
                }
            } else if (isHorizStrip) {
                var xOff = originX
                (0 until n).map { i ->
                    val cw = effWs[i] * scale
                    val ch = effHs[i] * scale
                    val y = originY + (maxEffH * scale - ch) / 2f
                    val rect = RectF(xOff, y, xOff + cw, y + ch)
                    xOff += cw - ovlScaled
                    rect
                }
            } else {
                val cellW = maxEffW * scale
                val cellH = maxEffH * scale
                val isColOrder = sess.params.gridOrder == "col"
                (0 until n).map { i ->
                    val col = if (isColOrder) i / rows else i % cols
                    val row = if (isColOrder) i % rows else i / cols
                    RectF(originX + col * cellW, originY + row * cellH,
                          originX + col * cellW + cellW, originY + row * cellH + cellH)
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (i in cellRects.indices) {
                if (i >= bmps.size) break
                val bmp = bmps[i] ?: continue
                val r = cellRects[i]
                val img = sess.images[i]

                val srcRect = Rect(
                    (img.width * img.cropLeft).toInt(),
                    (img.height * img.cropTop).toInt(),
                    (img.width * (1f - img.cropRight)).toInt(),
                    (img.height * (1f - img.cropBottom)).toInt()
                )
                val effW = srcRect.width().toFloat()
                val effH = srcRect.height().toFloat()
                val scaleToFit = min((r.width()) / effW, (r.height()) / effH)
                val drawW = effW * scaleToFit
                val drawH = effH * scaleToFit
                val ox = r.left + (r.width() - drawW) / 2f
                val oy = r.top + (r.height() - drawH) / 2f
                val dstRect = RectF(ox, oy, ox + drawW, oy + drawH)

                canvas.drawBitmap(bmp, srcRect, dstRect, null)
                canvas.drawRect(dstRect, imgBorderPaint)

                val lx = dstRect.left + dp(4)
                val ly = dstRect.top + dp(4)
                val labelW = dp(20).toFloat()
                val labelH = dp(20).toFloat()
                canvas.drawRect(lx, ly, lx + labelW, ly + labelH, labelBgPaint)
                canvas.drawText("${i + 1}", lx + dp(5).toFloat(), ly + dp(15).toFloat(), labelTextPaint)
            }
        }
    }

    private inner class StitchView(
        ctx: Context,
        private var sess: StitchSessionData,
        private var bmp0: Bitmap,
        private var bmp1: Bitmap,
        private val headerH: Int,
        private val ctrlH: Int
    ) : View(ctx) {

        private val pad = dp(PAD_DP)
        private val handleLen = dp(HANDLE_LEN_DP).toFloat()
        private val handleThick = dp(HANDLE_THICK_DP).toFloat()
        private val hitRadius = dp(HIT_RADIUS_DP).toFloat()

        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.FILL
        }
        private val handleOverlapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#666666"); style = Paint.Style.FILL
        }
        private val imgBorderPaint = Paint().apply {
            color = Color.parseColor("#888888"); style = Paint.Style.STROKE; strokeWidth = 2f
        }
        private val overlapZonePaint = Paint().apply {
            color = Color.parseColor("#1F000000"); style = Paint.Style.FILL
        }
        private val labelBgPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
        private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 13f * resources.displayMetrics.density * com.supernote_quicktoolbar.ui_common.ScreenScale.factor(context)
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT
        }

        private var scale = 1f
        private var dispW = 0f; private var dispH = 0f
        private var originX = 0f; private var originY = 0f
        private var rect0 = RectF(); private var rect1 = RectF()
        private var eff0W = 0f; private var eff0H = 0f
        private var eff1W = 0f; private var eff1H = 0f

        private var dragKind: Int? = null
        private var dragImgIdx = 0
        private var dragCropKey = ""
        private var dragOppKey = ""
        private var dragSign = 1f
        private var dragPxPerUnit = 1f
        private var dragStartVal = 0f
        private var dragPxPerUnit1 = 1f
        private var dragStartVal1 = 0f
        private var dragOverlapStart = 0
        private var dragStartX = 0f; private var dragStartY = 0f

        fun updateSession(s: StitchSessionData) {
            sess = s
            computeLayout(width, height)
            invalidate()
        }

        fun swapBitmaps() {
            val tmp = bmp0; bmp0 = bmp1; bmp1 = tmp
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            computeLayout(w, h)
        }

        private fun computeLayout(viewW: Int, viewH: Int) {
            if (viewW == 0 || viewH == 0) return
            val imgs = sess.images
            if (imgs.size < 2) return
            val isVert = sess.params.direction == "vertical"

            eff0W = imgs[0].width * (1 - imgs[0].cropLeft - imgs[0].cropRight)
            eff0H = imgs[0].height * (1 - imgs[0].cropTop - imgs[0].cropBottom)
            eff1W = imgs[1].width * (1 - imgs[1].cropLeft - imgs[1].cropRight)
            eff1H = imgs[1].height * (1 - imgs[1].cropTop - imgs[1].cropBottom)

            val totalW: Float; val totalH: Float
            if (isVert) {
                totalW = max(eff0W, eff1W)
                totalH = eff0H + eff1H - sess.params.overlap
            } else {
                totalW = eff0W + eff1W - sess.params.overlap
                totalH = max(eff0H, eff1H)
            }

            val availW = viewW - pad * 2f
            val availH = viewH - pad * 2f
            scale = min(availW / max(totalW, 1f), min(availH / max(totalH, 1f), 1f))
            dispW = totalW * scale
            dispH = totalH * scale
            originX = (viewW - dispW) / 2f
            originY = (viewH - dispH) / 2f

            if (isVert) {
                rect0.set(originX, originY, originX + eff0W * scale, originY + eff0H * scale)
                rect1.set(originX, originY + eff0H * scale - sess.params.overlap * scale,
                          originX + eff1W * scale, originY + eff0H * scale - sess.params.overlap * scale + eff1H * scale)
            } else {
                rect0.set(originX, originY, originX + eff0W * scale, originY + eff0H * scale)
                rect1.set(originX + eff0W * scale - sess.params.overlap * scale, originY,
                          originX + eff0W * scale - sess.params.overlap * scale + eff1W * scale, originY + eff1H * scale)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val imgs = sess.images
            if (imgs.size < 2) return
            val isVert = sess.params.direction == "vertical"

            val drawOrder = if (sess.params.topLayerIndex == 0) intArrayOf(1, 0) else intArrayOf(0, 1)
            for (idx in drawOrder) {
                val r = if (idx == 0) rect0 else rect1
                val bmp = if (idx == 0) bmp0 else bmp1
                val img = imgs[idx]
                val fullW = img.width * scale
                val fullH = img.height * scale
                val offsetL = img.cropLeft * img.width * scale
                val offsetT = img.cropTop * img.height * scale
                canvas.save()
                canvas.clipRect(r)
                canvas.drawBitmap(bmp, null,
                    RectF(r.left - offsetL, r.top - offsetT,
                          r.left - offsetL + fullW, r.top - offsetT + fullH), null)
                canvas.restore()
            }

            if (sess.params.overlap > 0) {
                if (isVert) {
                    val h = min(sess.params.overlap * scale, rect0.height())
                    canvas.drawRect(originX, rect1.top, originX + dispW, rect1.top + h, overlapZonePaint)
                } else {
                    val w = min(sess.params.overlap * scale, rect0.width())
                    canvas.drawRect(rect1.left, originY, rect1.left + w, originY + dispH, overlapZonePaint)
                }
            }

            for (idx in 0..1) {
                val r = if (idx == 0) rect0 else rect1
                imgBorderPaint.color = if (idx == 0) Color.parseColor("#888888") else Color.parseColor("#444444")
                canvas.drawRect(r, imgBorderPaint)
                val lx = r.left + dp(4)
                val ly = r.top + dp(4)
                val labelW = dp(20).toFloat()
                val labelH = dp(20).toFloat()
                canvas.drawRect(lx, ly, lx + labelW, ly + labelH, labelBgPaint)
                canvas.drawText("${idx + 1}", lx + dp(5).toFloat(), ly + dp(15).toFloat(), labelTextPaint)
            }

            val juncY = if (isVert) (rect0.bottom + rect1.top) / 2f else rect0.centerY()
            val juncX = if (isVert) rect0.centerX() else (rect0.right + rect1.left) / 2f

            if (isVert) {
                drawHandle(canvas, rect0.centerX(), rect0.top, true, false)
                drawHandle(canvas, rect1.centerX(), rect1.bottom, true, false)
                drawHandle(canvas, juncX, juncY, true, true)
                drawHandle(canvas, originX, juncY, false, false)
                drawHandle(canvas, originX + dispW, juncY, false, false)
            } else {
                drawHandle(canvas, rect0.left, rect0.centerY(), false, false)
                drawHandle(canvas, rect1.right, rect1.centerY(), false, false)
                drawHandle(canvas, juncX, juncY, false, true)
                drawHandle(canvas, juncX, originY, true, false)
                drawHandle(canvas, juncX, originY + dispH, true, false)
            }
        }

        private fun drawHandle(canvas: Canvas, cx: Float, cy: Float, horizontal: Boolean, isOverlap: Boolean) {
            val w = if (horizontal) handleLen else handleThick
            val h = if (horizontal) handleThick else handleLen
            val p = if (isOverlap) handleOverlapPaint else handlePaint
            canvas.drawRoundRect(RectF(cx - w/2, cy - h/2, cx + w/2, cy + h/2), 3f, 3f, p)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val imgs = sess.images
            if (imgs.size < 2) return false
            val isVert = sess.params.direction == "vertical"

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = event.x; dragStartY = event.y
                    if (!hitTestHandle(event.x, event.y, isVert)) return false
                    dragKind = hitKind
                    when (hitKind) {
                        DRAG_OVERLAP -> {
                            dragOverlapStart = sess.params.overlap
                        }
                        DRAG_EDGE -> {
                            dragImgIdx = hitImgIdx
                            dragCropKey = hitCropKey
                            dragOppKey = hitOppKey
                            dragSign = hitSign
                            dragPxPerUnit = hitPxPerUnit
                            dragStartVal = getCropVal(imgs[hitImgIdx], hitCropKey)
                        }
                        DRAG_BOTH -> {
                            dragCropKey = hitCropKey
                            dragOppKey = hitOppKey
                            dragSign = hitSign
                            dragPxPerUnit = hitPxPerUnit
                            dragPxPerUnit1 = hitPxPerUnit1
                            dragStartVal = getCropVal(imgs[0], hitCropKey)
                            dragStartVal1 = getCropVal(imgs[1], hitCropKey)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val kind = dragKind ?: return false
                    val dx = event.x - dragStartX
                    val dy = event.y - dragStartY

                    when (kind) {
                        DRAG_OVERLAP -> {
                            val screenDelta = if (isVert) -dy else -dx
                            val imgDelta = screenDelta / scale
                            val dim0 = if (isVert) eff0H else eff0W
                            val dim1 = if (isVert) eff1H else eff1W
                            val maxOvl = (min(dim0, dim1) * 0.8f).toInt()
                            sess.params.overlap = max(0, min(maxOvl, dragOverlapStart + imgDelta.roundToInt()))
                            overlapLabel?.text = "Overlap: ${sess.params.overlap}px"
                        }
                        DRAG_EDGE -> {
                            val isVertAxis = dragCropKey == "cropTop" || dragCropKey == "cropBottom"
                            val sd = if (isVertAxis) dy else dx
                            val delta = (dragSign * sd) / dragPxPerUnit
                            val maxVal = 1f - MIN_VISIBLE - getCropVal(imgs[dragImgIdx], dragOppKey)
                            val newVal = max(0f, min(maxVal, dragStartVal + delta))
                            setCropVal(imgs[dragImgIdx], dragCropKey, newVal)
                        }
                        DRAG_BOTH -> {
                            val isVertAxis = dragCropKey == "cropTop" || dragCropKey == "cropBottom"
                            val sd = if (isVertAxis) dy else dx
                            val d0 = (dragSign * sd) / dragPxPerUnit
                            val d1 = (dragSign * sd) / dragPxPerUnit1
                            val max0 = 1f - MIN_VISIBLE - getCropVal(imgs[0], dragOppKey)
                            val max1 = 1f - MIN_VISIBLE - getCropVal(imgs[1], dragOppKey)
                            setCropVal(imgs[0], dragCropKey, max(0f, min(max0, dragStartVal + d0)))
                            setCropVal(imgs[1], dragCropKey, max(0f, min(max1, dragStartVal1 + d1)))
                        }
                    }
                    computeLayout(width, height)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragKind = null
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private var hitKind = DRAG_OVERLAP
        private var hitImgIdx = 0
        private var hitCropKey = ""
        private var hitOppKey = ""
        private var hitSign = 1f
        private var hitPxPerUnit = 1f
        private var hitPxPerUnit1 = 1f

        private fun hitTestHandle(x: Float, y: Float, isVert: Boolean): Boolean {
            val imgs = sess.images
            val juncY = if (isVert) (rect0.bottom + rect1.top) / 2f else rect0.centerY()
            val juncX = if (isVert) rect0.centerX() else (rect0.right + rect1.left) / 2f

            val hxs: FloatArray; val hys: FloatArray
            val types: IntArray; val idxs: IntArray; val edges: Array<String>
            if (isVert) {
                hxs = floatArrayOf(rect0.centerX(), rect1.centerX(), juncX, originX, originX + dispW)
                hys = floatArrayOf(rect0.top, rect1.bottom, juncY, juncY, juncY)
                types = intArrayOf(0, 0, 1, 2, 2)
                idxs = intArrayOf(0, 1, 0, 0, 0)
                edges = arrayOf("top", "bottom", "", "left", "right")
            } else {
                hxs = floatArrayOf(rect0.left, rect1.right, juncX, juncX, juncX)
                hys = floatArrayOf(rect0.centerY(), rect1.centerY(), juncY, originY, originY + dispH)
                types = intArrayOf(0, 0, 1, 2, 2)
                idxs = intArrayOf(0, 1, 0, 0, 0)
                edges = arrayOf("left", "right", "", "top", "bottom")
            }

            var bestDist = hitRadius; var bestIdx = -1
            for (i in hxs.indices) {
                val d = sqrt((x - hxs[i]) * (x - hxs[i]) + (y - hys[i]) * (y - hys[i]))
                if (d < bestDist) { bestDist = d; bestIdx = i }
            }

            if (bestIdx == -1 || types[bestIdx] == 1) {
                hitKind = DRAG_OVERLAP; return true
            }

            if (types[bestIdx] == 0) {
                val hitEdge = edges[bestIdx]
                val targetEdge = when (hitEdge) {
                    "top" -> "bottom"; "bottom" -> "top"; "left" -> "right"; else -> "left"
                }
                hitKind = DRAG_EDGE
                hitImgIdx = idxs[bestIdx]
                hitCropKey = "crop${targetEdge.replaceFirstChar { it.uppercase() }}"
                hitOppKey = "crop${hitEdge.replaceFirstChar { it.uppercase() }}"
                val isVertAxis = hitEdge == "top" || hitEdge == "bottom"
                hitSign = if (hitEdge == "top" || hitEdge == "left") 1f else -1f
                hitPxPerUnit = (if (isVertAxis) imgs[hitImgIdx].height.toFloat() else imgs[hitImgIdx].width.toFloat()) * scale
                return true
            }

            val edge = edges[bestIdx]
            val oppEdge = when (edge) {
                "top" -> "bottom"; "bottom" -> "top"; "left" -> "right"; else -> "left"
            }
            hitKind = DRAG_BOTH
            hitCropKey = "crop${edge.replaceFirstChar { it.uppercase() }}"
            hitOppKey = "crop${oppEdge.replaceFirstChar { it.uppercase() }}"
            val isVertAxis = edge == "top" || edge == "bottom"
            hitSign = if (edge == "top" || edge == "left") 1f else -1f
            hitPxPerUnit = (if (isVertAxis) imgs[0].height.toFloat() else imgs[0].width.toFloat()) * scale
            hitPxPerUnit1 = (if (isVertAxis) imgs[1].height.toFloat() else imgs[1].width.toFloat()) * scale
            return true
        }

        private fun getCropVal(img: StitchImage, key: String): Float = when (key) {
            "cropTop" -> img.cropTop; "cropBottom" -> img.cropBottom
            "cropLeft" -> img.cropLeft; "cropRight" -> img.cropRight
            else -> 0f
        }

        private fun setCropVal(img: StitchImage, key: String, v: Float) {
            when (key) {
                "cropTop" -> img.cropTop = v; "cropBottom" -> img.cropBottom = v
                "cropLeft" -> img.cropLeft = v; "cropRight" -> img.cropRight = v
            }
        }
    }
}
