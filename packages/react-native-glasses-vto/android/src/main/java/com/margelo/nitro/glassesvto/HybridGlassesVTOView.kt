package com.margelo.nitro.glassesvto

import android.view.View
import com.facebook.react.uimanager.ThemedReactContext

/**
 * HybridGlassesVTOView - NitroModules HybridView implementation for glasses VTO.
 *
 * This class extends the generated HybridGlassesVTOViewSpec and provides
 * the actual implementation for the glasses virtual try-on view.
 */
class HybridGlassesVTOView(private val reactContext: ThemedReactContext) : HybridGlassesVTOViewSpec() {

    // The underlying native view
    private val glassesVTOView: GlassesVTOView = GlassesVTOView(reactContext)

    /**
     * Returns the native view
     */
    override val view: View
        get() = glassesVTOView

    // Props implementation
    override var modelPath: String = "models/878082.glb"
        set(value) {
            field = value
            glassesVTOView.setModelPath(value)
        }

    override var modelWidthMeters: Double = 0.135
        set(value) {
            field = value
            glassesVTOView.setModelWidthMeters(value.toFloat())
        }

    override var isActive: Boolean = true
        set(value) {
            field = value
            glassesVTOView.setIsActive(value)
        }

    // Methods implementation
    override fun switchModel(modelPath: String, widthMeters: Double) {
        glassesVTOView.switchModel(modelPath, widthMeters.toFloat())
    }

    override fun resetSession() {
        glassesVTOView.resetSession()
    }

    // Lifecycle callbacks from HybridView base class
    override fun beforeUpdate() {
        // Called before props are updated
    }

    override fun afterUpdate() {
        // Called after props are updated
        // Resume the view if active
        if (isActive) {
            glassesVTOView.resume()
        }
    }

    /**
     * Called when the view is attached to the window
     */
    fun onAttachedToWindow() {
        if (isActive) {
            glassesVTOView.resume()
        }
    }

    /**
     * Called when the view is detached from the window
     */
    fun onDetachedFromWindow() {
        glassesVTOView.pause()
    }

    /**
     * Cleanup resources when the view is destroyed
     */
    fun destroy() {
        glassesVTOView.destroy()
    }
}
