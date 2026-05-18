package com.supernote_quicktoolbar.ui_common

import com.facebook.react.bridge.ReactApplicationContext

object UiUtils {

    fun loadAssetIcon(
        ctx: ReactApplicationContext,
        assetName: String,
        sizePx: Int,
        tintColor: Int
    ): android.graphics.drawable.Drawable? {
        return try {
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
                    paint.strokeWidth = 1.5f
                    paint.strokeCap = android.graphics.Paint.Cap.ROUND
                    paint.strokeJoin = android.graphics.Paint.Join.ROUND
                }
                canvas.drawPath(p, paint)
            }
            android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
        } catch (_: Exception) {
            null
        }
    }
}
