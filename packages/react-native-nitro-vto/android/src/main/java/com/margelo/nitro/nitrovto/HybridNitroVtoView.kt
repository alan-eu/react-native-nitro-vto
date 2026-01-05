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

    override var faceMeshOcclusion: Boolean? = null
        set(value) {
            field = value
            nitroVtoView.setFaceMeshOcclusion(value)
        }

    override var backPlaneOcclusion: Boolean? = null
        set(value) {
            field = value
            nitroVtoView.setBackPlaneOcclusion(value)
        }

    // Methods implementation
    override fun switchModel(modelUrl: String) {
        nitroVtoView.switchModel(modelUrl)
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
