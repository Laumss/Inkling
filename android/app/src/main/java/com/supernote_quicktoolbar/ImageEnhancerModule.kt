package com.supernote_quicktoolbar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.facebook.react.bridge.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

class ImageEnhancerModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName() = "ImageEnhancerModule"

    @ReactMethod
    fun enhance(inputPath: String, options: ReadableMap, promise: Promise) {
        try {
            val src = BitmapFactory.decodeFile(inputPath)
                ?: return promise.reject("ERR", "Cannot decode image: $inputPath")

            val gamma      = if (options.hasKey("gamma"))      options.getDouble("gamma")      else 1.0
            val contrast   = if (options.hasKey("contrast"))   options.getDouble("contrast")   else 1.0
            val brightness = if (options.hasKey("brightness")) options.getDouble("brightness") else 0.0
            val sharpen    = if (options.hasKey("sharpen"))    options.getDouble("sharpen")     else 0.0
            val bgNorm     = if (options.hasKey("bgNorm"))     options.getBoolean("bgNorm")     else false
            val bgBlur     = if (options.hasKey("bgBlur"))     options.getInt("bgBlur")         else 80

            val w = src.width
            val h = src.height
            val pixels = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)

            val lum = IntArray(w * h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                lum[i] = ((0.299 * r + 0.587 * g + 0.114 * b) + 0.5).toInt()
            }

            if (bgNorm) {
                applyBgNorm(lum, w, h, bgBlur)
            }

            val gammaLUT = IntArray(256)
            for (i in 0..255) {
                val gv = (i / 255.0).pow(gamma) * 255.0
                val cv = (gv - 128.0) * contrast + 128.0 + brightness
                gammaLUT[i] = clamp(cv)
            }
            for (i in lum.indices) {
                lum[i] = gammaLUT[clamp(lum[i].toDouble())]
            }

            if (sharpen > 0.0) {
                applySharpen(lum, w, h, sharpen)
            }

            for (i in pixels.indices) {
                val v = lum[i]
                pixels[i] = Color.argb(255, v, v, v)
            }

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(pixels, 0, w, 0, 0, w, h)

            val outFile = File(inputPath.replaceAfterLast('.', "enhanced.png"))
            FileOutputStream(outFile).use { fos ->
                out.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            src.recycle()
            out.recycle()

            promise.resolve(outFile.absolutePath)
        } catch (e: Exception) {
            promise.reject("ERR", e.message, e)
        }
    }

    private fun applyBgNorm(lum: IntArray, w: Int, h: Int, radius: Int) {
        val n = w * h
        val src = FloatArray(n) { lum[it].toFloat() }
        val tmp = FloatArray(n)

        for (y in 0 until h) {
            var sum = 0f; var count = 0
            for (x in 0 until min(radius, w)) { sum += src[y * w + x]; count++ }
            for (x in 0 until w) {
                if (x + radius < w)       { sum += src[y * w + x + radius]; count++ }
                if (x - radius - 1 >= 0)  { sum -= src[y * w + x - radius - 1]; count-- }
                tmp[y * w + x] = sum / count
            }
        }

        val bg = FloatArray(n)
        for (x in 0 until w) {
            var sum = 0f; var count = 0
            for (y in 0 until min(radius, h)) { sum += tmp[y * w + x]; count++ }
            for (y in 0 until h) {
                if (y + radius < h)       { sum += tmp[(y + radius) * w + x]; count++ }
                if (y - radius - 1 >= 0)  { sum -= tmp[(y - radius - 1) * w + x]; count-- }
                bg[y * w + x] = sum / count
            }
        }

        for (i in 0 until n) {
            val b = max(1f, bg[i])
            lum[i] = clamp((src[i] / b * 255f).toDouble())
        }
    }

    private fun applySharpen(lum: IntArray, w: Int, h: Int, amount: Double) {
        val copy = lum.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x

                val blur = (copy[idx - w - 1] + copy[idx - w] + copy[idx - w + 1] +
                            copy[idx - 1]     + copy[idx]     + copy[idx + 1] +
                            copy[idx + w - 1] + copy[idx + w] + copy[idx + w + 1]) / 9.0
                val sharp = copy[idx] + amount * (copy[idx] - blur)
                lum[idx] = clamp(sharp)
            }
        }
    }

    private fun clamp(v: Double): Int = max(0, min(255, round(v).toInt()))
}
