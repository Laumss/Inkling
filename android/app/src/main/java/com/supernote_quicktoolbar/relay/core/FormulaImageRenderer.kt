package com.supernote_quicktoolbar.relay.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.ArrayDeque

/**
 * LaTeX → transparent-background PNG, via a hidden WebView + MathJax.
 *
 * assets/formula/index.html does the real work: MathJax typesets the LaTeX to
 * SVG, the page rasterizes it and scanline-fills the raster into zigzag
 * polylines (solid black glyphs) plus a fallback PNG, returning JSON
 * {width, height, em, png, strokes?}. The plugin
 * inserts the polylines as real ink elements (createElement/insertElements) at
 * absolute page coordinates; the PNG is only used if stroke insertion fails.
 *
 * AIRelay now runs in the PluginHost process. This path is intentionally kept
 * unchanged and must be verified on device before formula insertion is enabled.
 *
 * Rendering knobs (font package, ink color, resolution) would be configured
 * here / in index.html — the plugin side is agnostic to them.
 */
class FormulaImageRenderer(private val context: Context) {

    companion object {
        private const val TAG = "FormulaImage"
        private const val RENDER_TIMEOUT_MS = 25_000L
        private const val ENGINE_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var engineReady = false
    private var engineFailed: String? = null

    private class Job(
        val latex: String,
        val onResult: (String) -> Unit,
        val onError: (String) -> Unit,
    )

    private val queue = ArrayDeque<Job>()
    private var active: Job? = null
    private var timeoutRunnable: Runnable? = null

    /** Renders [latex]; callbacks fire on the main thread. Serialized internally. */
    fun render(latex: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (latex.isBlank()) { handler.post { onError("empty latex") }; return }
        handler.post {
            queue.add(Job(latex, onResult, onError))
            ensureWebView()
            pump()
        }
    }

    /** Pre-warms the engine so the first render doesn't pay MathJax startup. */
    fun warmup() { handler.post { ensureWebView() } }

    fun release() {
        handler.post {
            failAll("released")
            webView?.destroy()
            webView = null
            engineReady = false
            engineFailed = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView() {
        if (webView != null) return
        engineFailed = null
        try {
            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            // file:///android_asset page + relative tex-svg.js need file access.
            wv.settings.allowFileAccess = true
            wv.webViewClient = WebViewClient()
            // Surface index.html's console.log/flog diagnostics into Logcat so
            // the hollow-glyph investigation is observable on-device.
            wv.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                    Log.i(TAG, "JS[${cm.lineNumber()}] ${cm.message()}")
                    return true
                }
            }
            wv.addJavascriptInterface(JsBridge(), "AndroidFormula")
            wv.loadUrl("file:///android_asset/formula/index.html")
            webView = wv
            handler.postDelayed({
                if (!engineReady && webView === wv) {
                    engineFailed = "engine timeout (assets/formula/tex-svg.js missing or broken?)"
                    failAll(engineFailed!!)
                }
            }, ENGINE_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "WebView init failed: ${e.message}", e)
            engineFailed = "webview-init: ${e.message}"
            failAll(engineFailed!!)
        }
    }

    private fun pump() {
        if (active != null || queue.isEmpty()) return
        engineFailed?.let { failAll(it); return }
        if (!engineReady) return // pumped again from onEngineReady
        val job = queue.poll() ?: return
        active = job
        val b64 = Base64.encodeToString(job.latex.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        webView?.evaluateJavascript("renderFormulaB64('$b64')", null)
        timeoutRunnable = Runnable {
            if (active === job) {
                active = null
                job.onError("formula render timeout")
                pump()
            }
        }.also { handler.postDelayed(it, RENDER_TIMEOUT_MS) }
    }

    private fun finishActive(block: (Job) -> Unit) {
        handler.post {
            val job = active ?: return@post
            active = null
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = null
            block(job)
            pump()
        }
    }

    private fun failAll(msg: String) {
        handler.post {
            active?.onError?.invoke(msg)
            active = null
            while (queue.isNotEmpty()) queue.poll()?.onError?.invoke(msg)
        }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onPageLoaded() { Log.i(TAG, "formula page loaded") }

        @JavascriptInterface
        fun onLog(msg: String) { Log.i(TAG, "flog: $msg") }

        @JavascriptInterface
        fun onEngineReady() {
            Log.i(TAG, "MathJax engine ready")
            handler.post {
                engineReady = true
                pump()
            }
        }

        @JavascriptInterface
        fun onResult(json: String) {
            Log.i(TAG, "render ok, ${json.length} bytes")
            finishActive { it.onResult(json) }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            Log.e(TAG, "render error: $msg")
            if (msg == "mathjax-load-failed") {
                handler.post {
                    engineFailed = "MathJax load failed (bundle assets/formula/tex-svg.js)"
                    failAll(engineFailed!!)
                }
            } else {
                finishActive { it.onError(msg) }
            }
        }
    }
}
