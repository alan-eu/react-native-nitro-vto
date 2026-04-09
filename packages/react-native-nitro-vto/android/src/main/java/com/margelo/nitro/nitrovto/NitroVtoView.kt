package com.margelo.nitro.nitrovto

import android.content.Context
import android.util.Log
import android.view.Choreographer
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
 * - Filament rendering via ArCoreVtoAdapter
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
    private var arCoreVtoAdapter: ArCoreVtoAdapter? = null

    // Configuration
    private var modelUrl: String = ""
    private var isActive: Boolean = true
    private var faceMeshOcclusionState: Boolean = true
    private var backPlaneOcclusionState: Boolean = true
    private var forwardOffsetState: Float = 0.005f
    private var debugState: Boolean = false

    // Callbacks
    var onModelLoaded: ((modelUrl: String) -> Unit)? = null

    // State
    private var isInitialized = false
    private var isResumed = false

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isResumed || !isActive || !isInitialized) {
                return
            }
            arCoreVtoAdapter?.renderOnce()
            choreographer.postFrameCallback(this)
        }
    }

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
                arCoreVtoAdapter?.switchModel(modelUrl)
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
     * Set face mesh occlusion enabled
     */
    fun setFaceMeshOcclusion(enabled: Boolean?) {
        faceMeshOcclusionState = enabled ?: true
        arCoreVtoAdapter?.setFaceMeshOcclusion(faceMeshOcclusionState)
    }

    /**
     * Set back plane occlusion enabled
     */
    fun setBackPlaneOcclusion(enabled: Boolean?) {
        backPlaneOcclusionState = enabled ?: true
        arCoreVtoAdapter?.setBackPlaneOcclusion(backPlaneOcclusionState)
    }

    /**
     * Set forward offset for glasses positioning (in meters)
     */
    fun setForwardOffset(offset: Double?) {
        forwardOffsetState = (offset ?: 0.005).toFloat()
        arCoreVtoAdapter?.setForwardOffset(forwardOffsetState)
    }

    /**
     * Set debug mode enabled
     */
    fun setDebug(enabled: Boolean?) {
        debugState = enabled ?: false
        arCoreVtoAdapter?.setDebug(debugState)
    }

    /**
     * Switch to a different glasses model
     */
    fun switchModel(modelUrl: String) {
        this.modelUrl = modelUrl
        arCoreVtoAdapter?.switchModel(modelUrl)
    }

    /**
     * Reset the AR session
     */
    fun resetSession() {
        arCoreVtoAdapter?.resetSession()
        arSession?.pause()
        arSession?.resume()
    }

    /**
     * Initialize the view. Should be called after the view is attached.
     */
    private fun initialize() {
        if (isInitialized) return

        // Create and initialize renderer
        arCoreVtoAdapter = ArCoreVtoAdapter(context)
        arCoreVtoAdapter?.onModelLoaded = onModelLoaded
        arCoreVtoAdapter?.initialize(surfaceView, modelUrl)
        arCoreVtoAdapter?.setFaceMeshOcclusion(faceMeshOcclusionState)
        arCoreVtoAdapter?.setBackPlaneOcclusion(backPlaneOcclusionState)
        arCoreVtoAdapter?.setForwardOffset(forwardOffsetState)
        arCoreVtoAdapter?.setDebug(debugState)

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
        arCoreVtoAdapter?.resume()
        startFrameLoop()
    }

    /**
     * Pause the AR session and rendering
     */
    fun pause() {
        stopFrameLoop()
        arCoreVtoAdapter?.pause()
        arSession?.pause()
        isResumed = false
    }

    /**
     * Destroy and clean up resources
     */
    fun destroy() {
        stopFrameLoop()
        arSession?.close()
        arSession = null
        arCoreVtoAdapter?.destroy()
        arCoreVtoAdapter = null
        isInitialized = false
    }

    /**
     * Sets up the ARCore session with face tracking.
     * Assumes camera permission is already granted.
     */
    private fun setupArSession() {
        if (arSession != null) {
            arSession?.resume()
            arCoreVtoAdapter?.session = arSession
            return
        }

        try {
            // Check ARCore availability
            when (ArCoreApk.getInstance().requestInstall(getActivity(), true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return
                ArCoreApk.InstallStatus.INSTALLED -> { /* Continue */ }
            }

            // Create AR session with front camera + shared camera stream support.
            // Fallback to plain front camera if shared camera is unavailable on this device/ARCore build.
            arSession = try {
                Session(context, EnumSet.of(Session.Feature.FRONT_CAMERA, Session.Feature.SHARED_CAMERA))
            } catch (error: Throwable) {
                Log.w(TAG, "Shared camera feature unavailable, falling back to FRONT_CAMERA only: ${error.message}")
                Session(context, EnumSet.of(Session.Feature.FRONT_CAMERA))
            }

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
            arCoreVtoAdapter?.session = arSession

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

    private fun startFrameLoop() {
        choreographer.removeFrameCallback(frameCallback)
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        choreographer.removeFrameCallback(frameCallback)
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
