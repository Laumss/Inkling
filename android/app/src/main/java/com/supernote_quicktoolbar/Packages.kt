package com.supernote_quicktoolbar
import com.supernote_quicktoolbar.panels.*
import com.supernote_quicktoolbar.overlays.*
import com.supernote_quicktoolbar.bubbles.*

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class InklingPackages : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        val modules = mutableListOf<NativeModule>()
        fun tryAdd(name: String, factory: () -> NativeModule) {
            try {
                modules.add(factory())
                android.util.Log.i("InklingPackages", "✓ $name")
            } catch (e: Exception) {
                android.util.Log.e("InklingPackages", "✗ $name FAILED: ${e.message}", e)
            }
        }
        tryAdd("FloatingToolbar") { FloatingToolbarModule(reactContext) }
        tryAdd("LocalSendModule") { LocalSendModule(reactContext) }
        tryAdd("BroadcastBridge") { BroadcastBridge(reactContext) }
        tryAdd("FloatingBubble") { FloatingBubbleModule(reactContext) }
        tryAdd("ScreenshotModule") { ScreenshotModule(reactContext) }
        tryAdd("AiBubble") { AiBubbleModule(reactContext) }
        tryAdd("TextLayoutEngine") { TextLayoutEngine(reactContext) }
        return modules
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
    }
}
