package com.supernote_quicktoolbar

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.facebook.react.bridge.ReactApplicationContext
import java.lang.reflect.Proxy

class WindowStateMonitor(private val reactContext: ReactApplicationContext) {

    companion object {
        private const val TAG = "WindowStateMonitor"
        private const val ATTACH_RETRY_MS = 1000L
        private const val MAX_ATTACH_RETRIES = 15

        @Volatile
        private var isPluginHostShowing = false

        private val handler = Handler(Looper.getMainLooper())
    }

    private val startRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    private var hooked = false
    private var retries = 0
    private var container: ViewGroup? = null
    private var listenerField: java.lang.reflect.Field? = null
    private var originalListener: Any? = null
    private var proxyListener: Any? = null

    private val hookRunnable = Runnable { tryHook() }

    fun start() {
        startRequested.set(true)
        handler.post { sync() }
    }

    fun stop() {
        startRequested.set(false)
        handler.post { sync() }
    }

    private fun sync() {
        if (startRequested.get()) {
            if (!hooked) {
                retries = 0
                handler.removeCallbacks(hookRunnable)
                tryHook()
            }
        } else {
            handler.removeCallbacks(hookRunnable)
            unhook()
        }
    }

    private fun tryHook() {
        if (!startRequested.get() || hooked) return
        val ok = hookInternal()
        if (!ok) {
            retries++
            if (retries <= MAX_ATTACH_RETRIES) {
                handler.postDelayed(hookRunnable, ATTACH_RETRY_MS)
            } else {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "give up hooking after $retries retries")
            }
        }
    }

    private fun hookInternal(): Boolean {
        try {
            if (!reactContext.hasActiveCatalystInstance()) {
                if (BuildConfig.ENABLE_DEBUG) {
                    Log.w(TAG, "React bridge not ready (retry $retries)")
                }
                return false
            }
            val c = findPluginContainer() ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "PluginContainer not ready (retry $retries)")
                return false
            }
            val field = c.javaClass.declaredFieldOrNull("visibilityListener") ?: run {
                if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "visibilityListener field not found on ${c.javaClass.name}")
                return false
            }
            field.isAccessible = true
            val original = field.get(c)
            if (original != null && Proxy.isProxyClass(original.javaClass)) {
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "listener already wrapped, skip")
                hooked = true
                container = c
                listenerField = field
                return true
            }
            val iface = field.type // PluginContainer.VisibilityListener
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxyObj, method, args ->
                if (method.declaringClass == Any::class.java) {
                    return@newProxyInstance when (method.name) {
                        "hashCode" -> System.identityHashCode(proxyObj)
                        "equals" -> proxyObj === args?.getOrNull(0)
                        "toString" -> "WindowStateMonitor.VisibilityListenerProxy"
                        else -> null
                    }
                }
                var result: Any? = null
                if (original != null) {
                    try {
                        result = method.invoke(original, *(args ?: emptyArray()))
                    } catch (e: Exception) {
                        if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "forward to host listener failed: ${e.message}")
                    }
                }
                if (method.name == "onVisibilityChange") {
                    val vis = (args?.getOrNull(0) as? Int) ?: View.GONE
                    onContainerVisibility(vis == View.VISIBLE)
                }
                result
            }
            field.set(c, proxy)
            container = c
            listenerField = field
            originalListener = original
            proxyListener = proxy
            hooked = true

            onContainerVisibility(c.visibility == View.VISIBLE)
            if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "hooked PluginContainer.visibilityListener (original=${original?.javaClass?.name})")
            return true
        } catch (e: AssertionError) {
            // BridgeReactContext.getCatalystInstance() throws AssertionError (not
            // Exception) while a newly installed plugin is still constructing its
            // Catalyst instance. Treat that transition exactly like any other
            // not-ready state and let tryHook() retry instead of crashing PluginHost.
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "React bridge transition: ${e.message}")
            return false
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "hook error: ${e.message}", e)
            return false
        }
    }

    private fun unhook() {
        try {
            val c = container ?: return
            val field = listenerField ?: return
            val current = field.get(c)
            if (current != null && current === proxyListener) {
                field.set(c, originalListener)
                if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "unhooked, original listener restored")
            }
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "unhook error: ${e.message}")
        } finally {
            container = null
            listenerField = null
            originalListener = null
            proxyListener = null
            hooked = false
        }
    }

    private fun onContainerVisibility(visible: Boolean) {
        val showing = visible
        if (showing == isPluginHostShowing) return
        isPluginHostShowing = showing
        if (BuildConfig.ENABLE_DEBUG) Log.i(TAG, "PluginHost window state changed: showing=$showing")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            FloatingToolbarModule.onPluginHostStateChanged(showing)
        } else {
            handler.post { FloatingToolbarModule.onPluginHostStateChanged(showing) }
        }
    }

    private fun findPluginContainer(): ViewGroup? {
        return try {
            val pm = reactContext.catalystInstance?.getNativeModule("NativePluginManager") ?: return null
            val paField = pm.javaClass.declaredFieldOrNull("pluginApp") ?: return null
            paField.isAccessible = true
            val pa = paField.get(pm) ?: return null
            val pvField = pa.javaClass.declaredFieldOrNull("pluginView") ?: return null
            pvField.isAccessible = true
            pvField.get(pa) as? ViewGroup
        } catch (e: AssertionError) {
            if (BuildConfig.ENABLE_DEBUG) Log.w(TAG, "findPluginContainer: React bridge not ready")
            null
        } catch (e: Exception) {
            if (BuildConfig.ENABLE_DEBUG) Log.e(TAG, "findPluginContainer error: ${e.message}")
            null
        }
    }

    private fun Class<*>.declaredFieldOrNull(name: String): java.lang.reflect.Field? {
        var cls: Class<*>? = this
        while (cls != null) {
            cls.declaredFields.firstOrNull { it.name == name }?.let { return it }
            cls = cls.superclass
        }
        return null
    }
}
