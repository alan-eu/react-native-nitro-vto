package com.margelo.nitro.nitrovto

import android.content.Context
import android.util.Log
import android.view.SurfaceView
import android.widget.FrameLayout
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import java.util.EnumSet

/**
 * NitroVtoView - A FrameLayout containing the AR glasses try-on view.
 *
 * This view handles:
 * - ARCore session management
 * - Filament rendering via VTORenderer
 * - Face tracking and glasses overlay
 *
 * Note: Camera permissions must be handled by the consuming React Native app
 * before this view becomes active.
 */
class NitroVtoView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "NitroVtoView"
    }

    // ARCore session
    private var arSession: Session? = null

    // SurfaceView for rendering
    private val surfaceView: SurfaceView = SurfaceView(context)

    // Filament renderer
    private var vtoRenderer: VTORenderer? = null

    // Configuration
    private var modelUrl: String = ""
    private var modelWidthMeters: Float = 0f
    private var isActive: Boolean = true

    // State
    private var isInitialized = false
    private var isResumed = false

    init {
        // Add SurfaceView to fill the entire view
        addView(surfaceView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))
    }

    /**
     * Set the model URL for the glasses
     */
    fun setModelUrl(url: String) {
        if (modelUrl != url) {
            modelUrl = url
            if (isInitialized) {
                vtoRenderer?.switchModel(modelUrl, modelWidthMeters)
            }
        }
    }

    /**
     * Set the model width in meters
     */
    fun setModelWidthMeters(width: Float) {
        if (modelWidthMeters != width) {
            modelWidthMeters = width
            if (isInitialized) {
                vtoRenderer?.switchModel(modelUrl, modelWidthMeters)
            }
        }
    }

    /**
     * Set whether the AR session is active
     */
    fun setIsActive(active: Boolean) {
        if (isActive != active) {
            isActive = active
            if (active && isResumed) {
                resume()
            } else if (!active) {
                pause()
            }
        }
    }

    /**
     * Switch to a different glasses model
     */
    fun switchModel(modelUrl: String, widthMeters: Float) {
        this.modelUrl = modelUrl
        this.modelWidthMeters = widthMeters
        vtoRenderer?.switchModel(modelUrl, widthMeters)
    }

    /**
     * Take a snapshot of the current view
     * @return Base64-encoded image data
     */
    fun takeSnapshot(): String {
        // TODO: Implement snapshot functionality
        return ""
    }

    /**
     * Reset the AR session
     */
    fun resetSession() {
        vtoRenderer?.resetSession()
        arSession?.pause()
        arSession?.resume()
    }

    /**
     * Initialize the view. Should be called after the view is attached.
     */
    private fun initialize() {
        if (isInitialized) return

        // Create and initialize renderer
        vtoRenderer = VTORenderer(context)
        vtoRenderer?.initialize(surfaceView, modelUrl, modelWidthMeters)

        isInitialized = true
        Log.d(TAG, "NitroVtoView initialized")
    }

    /**
     * Resume the AR session and rendering
     */
    fun resume() {
        isResumed = true

        if (!isActive) return

        // Initialize if not already done
        if (!isInitialized) {
            initialize()
        }

        // Setup AR session if needed
        setupArSession()

        // Resume renderer
        vtoRenderer?.resume()
    }

    /**
     * Pause the AR session and rendering
     */
    fun pause() {
        vtoRenderer?.pause()
        arSession?.pause()
        isResumed = false
    }

    /**
     * Destroy and clean up resources
     */
    fun destroy() {
        arSession?.close()
        arSession = null
        vtoRenderer?.destroy()
        vtoRenderer = null
        isInitialized = false
    }

    /**
     * Sets up the ARCore session with face tracking.
     * Assumes camera permission is already granted.
     */
    private fun setupArSession() {
        if (arSession != null) {
            arSession?.resume()
            vtoRenderer?.session = arSession
            return
        }

        try {
            // Check ARCore availability
            when (ArCoreApk.getInstance().requestInstall(getActivity(), true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return
                ArCoreApk.InstallStatus.INSTALLED -> { /* Continue */ }
            }

            // Create AR session with front camera for face tracking
            arSession = Session(context, EnumSet.of(Session.Feature.FRONT_CAMERA))

            // Configure session for face tracking
            val config = Config(arSession).apply {
                augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                // Enable depth if supported by device
                depthMode = if (arSession!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
            }
            arSession?.configure(config)

            // Resume session
            arSession?.resume()

            // Connect session to renderer
            vtoRenderer?.session = arSession

            Log.d(TAG, "ARCore session created successfully")

        } catch (e: UnavailableArcoreNotInstalledException) {
            Log.e(TAG, "ARCore is not installed")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Log.e(TAG, "This device does not support AR")
        } catch (e: UnavailableSdkTooOldException) {
            Log.e(TAG, "Please update ARCore")
        } catch (e: UnavailableApkTooOldException) {
            Log.e(TAG, "Please update this app")
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AR session: ${e.message}")
        }
    }

    /**
     * Helper to get the activity from context
     */
    private fun getActivity(): android.app.Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is android.app.Activity) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "onAttachedToWindow")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "onDetachedFromWindow")
        destroy()
    }
}
