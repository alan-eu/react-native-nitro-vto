package com.margelo.nitro.nitrovto

import android.view.View
import com.facebook.react.uimanager.ThemedReactContext

/**
 * HybridNitroVtoView - NitroModules HybridView implementation for NitroVto.
 *
 * This class extends the generated HybridNitroVtoViewSpec and provides
 * the actual implementation for the NitroVto view.
 */
class HybridNitroVtoView(private val reactContext: ThemedReactContext) : HybridNitroVtoViewSpec() {

    // The underlying native view
    private val nitroVtoView: NitroVtoView = NitroVtoView(reactContext)

    /**
     * Returns the native view
     */
    override val view: View
        get() = nitroVtoView

    // Props implementation
    override var modelPath: String = "models/878082.glb"
        set(value) {
            field = value
            nitroVtoView.setModelPath(value)
        }

    override var modelWidthMeters: Double = 0.135
        set(value) {
            field = value
            nitroVtoView.setModelWidthMeters(value.toFloat())
        }

    override var isActive: Boolean = true
        set(value) {
            field = value
            nitroVtoView.setIsActive(value)
        }

    // Methods implementation
    override fun switchModel(modelPath: String, widthMeters: Double) {
        nitroVtoView.switchModel(modelPath, widthMeters.toFloat())
    }

    override fun resetSession() {
        nitroVtoView.resetSession()
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
