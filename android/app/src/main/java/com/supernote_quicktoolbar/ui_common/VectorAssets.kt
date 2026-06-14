package com.supernote_quicktoolbar.ui_common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Xml
import com.facebook.react.bridge.ReactApplicationContext
import org.xmlpull.v1.XmlPullParser

object UiUtils {

    fun loadAssetIcon(
        ctx: ReactApplicationContext,
        assetName: String,
        sizePx: Int,
        tintColor: Int,
        strokeWidth: Float = 1.5f
    ): android.graphics.drawable.Drawable? {
        val bmp = VectorAssets.loadBitmapTinted(ctx, assetName, sizePx, tintColor) ?: return null
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
    }
}

object VectorAssets {

    private data class VPath(
        val data: String,
        val fillColor: Int?,
        val strokeColor: Int?,
        val strokeWidth: Float,
    )

    private val cache = mutableMapOf<String, Bitmap>()

    
    fun loadBitmap(ctx: Context, assetPath: String, targetWidthPx: Int): Bitmap? =
        loadBitmapTinted(ctx, assetPath, targetWidthPx, null)

    
    fun loadBitmapTinted(ctx: Context, assetPath: String, targetWidthPx: Int, tintColor: Int?): Bitmap? {
        val key = "$assetPath@${targetWidthPx}@${tintColor ?: "null"}"
        cache[key]?.let { return it }
        return try {
            var viewportW = 24f
            var viewportH = 24f
            val paths = mutableListOf<VPath>()
            ctx.assets.open(assetPath).use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(Xml.FEATURE_RELAXED, true)
                parser.setInput(stream, "UTF-8")
                var evt = parser.eventType
                while (evt != XmlPullParser.END_DOCUMENT) {
                    if (evt == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "vector" -> {
                                attr(parser, "viewportWidth")?.toFloatOrNull()?.let { viewportW = it }
                                attr(parser, "viewportHeight")?.toFloatOrNull()?.let { viewportH = it }
                            }
                            "path" -> {
                                val pd = attr(parser, "pathData") ?: ""
                                if (pd.isNotEmpty()) {
                                    paths.add(VPath(
                                        data = pd,
                                        fillColor = parseColor(attr(parser, "fillColor")),
                                        strokeColor = parseColor(attr(parser, "strokeColor")),
                                        strokeWidth = attr(parser, "strokeWidth")?.toFloatOrNull() ?: 0f,
                                    ))
                                }
                            }
                        }
                    }
                    evt = parser.next()
                }
            }
            if (paths.isEmpty() || viewportW <= 0 || viewportH <= 0) return null

            val scale = targetWidthPx / viewportW
            val heightPx = (viewportH * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(targetWidthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(scale, scale)
            for (vp in paths) {
                val p = Path()
                try {
                    val cls = Class.forName("android.util.PathParser")
                    val method = cls.getMethod("createPathFromPathData", String::class.java)
                    (method.invoke(null, vp.data) as? Path)?.let { p.set(it) }
                } catch (_: Exception) {}
                if (vp.fillColor != null) {
                    canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = tintColor ?: vp.fillColor
                    })
                }
                if (vp.strokeColor != null && vp.strokeWidth > 0f) {
                    canvas.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = tintColor ?: vp.strokeColor
                        strokeWidth = vp.strokeWidth
                    })
                }
            }
            cache[key] = bmp
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun attr(parser: XmlPullParser, name: String): String? =
        parser.getAttributeValue("http://schemas.android.com/apk/res/android", name)

    
    private fun parseColor(s: String?): Int? {
        if (s == null || !s.startsWith("#")) return null
        val hex = s.substring(1)
        val full = when (hex.length) {
            3, 4 -> hex.map { "$it$it" }.joinToString("")
            else -> hex
        }
        return try {
            val c = Color.parseColor("#$full")
            if (Color.alpha(c) == 0) null else c
        } catch (_: Exception) {
            null
        }
    }
}