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

    override var onArUnavailable: ((reason: ArUnavailableReason) -> Unit)? = null
        set(value) {
            field = value
            nitroVtoView.onArUnavailable = value?.let { cb ->
                { reason -> arUnavailableReason(reason)?.let(cb) }
            }
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

    override var isClipOn: Boolean = false
        set(value) {
            field = value
            nitroVtoView.setIsClipOn(value)
        }

    override var mode: String? = null
        set(value) {
            field = value
            nitroVtoView.setMode(value)
        }

    override var previewBackgroundColor: String? = null
        set(value) {
            field = value
            nitroVtoView.setPreviewBackgroundColor(value)
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

    override fun onDropView() {
        // The only place the engine is torn down. `VtoView.onDetachedFromWindow` deliberately
        // only pauses — calling `destroy()` there races Filament's `onDetachedFromSurface` —
        // so unmount teardown is the wrapper's job.
        nitroVtoView.destroy()
    }

    private fun arUnavailableReason(reason: String): ArUnavailableReason? = when (reason) {
        "device-not-capable" -> ArUnavailableReason.DEVICE_NOT_CAPABLE
        "arcore-not-installed" -> ArUnavailableReason.ARCORE_NOT_INSTALLED
        "arcore-outdated" -> ArUnavailableReason.ARCORE_OUTDATED
        "arcore-unavailable" -> ArUnavailableReason.ARCORE_UNAVAILABLE
        "face-tracking-unsupported" -> ArUnavailableReason.FACE_TRACKING_UNSUPPORTED
        else -> null
    }
}
