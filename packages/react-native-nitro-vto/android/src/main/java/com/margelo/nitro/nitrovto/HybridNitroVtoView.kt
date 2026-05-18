package com.margelo.nitro.nitrovto

import android.view.View
import com.facebook.react.uimanager.ThemedReactContext
import eu.alan.vto.core.VtoView

/**
 * HybridNitroVtoView - NitroModules HybridView implementation for NitroVto.
 *
 * This class extends the generated HybridNitroVtoViewSpec and provides
 * the actual implementation for the NitroVto view.
 */
class HybridNitroVtoView(private val reactContext: ThemedReactContext) : HybridNitroVtoViewSpec() {

    // The underlying native view (shared core)
    private val nitroVtoView: VtoView = VtoView(reactContext)

    /**
     * Returns the native view
     */
    override val view: View
        get() = nitroVtoView

    // Props implementation
    override var modelUrl: String = ""
        set(value) {
            field = value
            nitroVtoView.setModelUrl(value)
        }

    override var isActive: Boolean = true
        set(value) {
            field = value
            nitroVtoView.setIsActive(value)
        }

    override var onModelLoaded: ((modelUrl: String) -> Unit)? = null
        set(value) {
            field = value
            nitroVtoView.onModelLoaded = value
        }

    override var onFaceTracked: (() -> Unit)? = null
        set(value) {
            field = value
            nitroVtoView.onFaceTracked = value
        }

    override var onGlassesDisplayed: ((modelUrl: String) -> Unit)? = null
        set(value) {
            field = value
            nitroVtoView.onGlassesDisplayed = value
        }

    override var forwardOffset: Double? = null
        set(value) {
            field = value
            nitroVtoView.setForwardOffset(value)
        }

    override var debug: Boolean? = null
        set(value) {
            field = value
            nitroVtoView.setDebug(value)
        }

    override var showNativeFPS: Boolean? = null
        set(value) {
            field = value
            nitroVtoView.setShowNativeFPS(value)
        }

    // Methods implementation
    override fun hideGlasses() {
        nitroVtoView.hideGlasses()
    }

    override fun showGlasses() {
        nitroVtoView.showGlasses()
    }

    // Lifecycle callbacks from HybridView base class
    override fun beforeUpdate() {
        // Called before props are updated
    }

    override fun afterUpdate() {
        // Called after props are updated
        // Resume the view if active
        if (isActive) {
            nitroVtoView.resume()
        }
    }

    /**
     * Called when the view is attached to the window
     */
    fun onAttachedToWindow() {
        if (isActive) {
            nitroVtoView.resume()
        }
    }

    /**
     * Called when the view is detached from the window
     */
    fun onDetachedFromWindow() {
        nitroVtoView.pause()
    }

    /**
     * Cleanup resources when the view is destroyed
     */
    fun destroy() {
        nitroVtoView.destroy()
    }
}
