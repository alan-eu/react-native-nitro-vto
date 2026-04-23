package eu.alan.vto.core

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
 * VtoView - A FrameLayout containing the AR glasses try-on view.
 *
 * This view handles:
 * - ARCore session management
 * - Filament rendering via VTORenderer
 * - Face tracking and glasses overlay
 *
 * Note: Camera permissions must be handled by the consuming React Native app
 * before this view becomes active.
 */
class VtoView(context: Context) : FrameLayout(context) {

    companion object {
        private const val TAG = "VtoView"
    }

    // ARCore session
    private var arSession: Session? = null

    // SurfaceView for rendering
    private val surfaceView: SurfaceView = SurfaceView(context)

    // Filament renderer
    private var vtoRenderer: VTORenderer? = null

    // Configuration
    private var modelUrl: String = ""
    private var isActive: Boolean = true

    // Callbacks
    var onModelLoaded: ((modelUrl: String) -> Unit)? = null
    var onFaceTracked: (() -> Unit)? = null
    var onGlassesDisplayed: ((modelUrl: String) -> Unit)? = null

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
                vtoRenderer?.switchModel(modelUrl)
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
        vtoRenderer?.setFaceMeshOcclusion(enabled ?: true)
    }

    /**
     * Set back plane occlusion enabled
     */
    fun setBackPlaneOcclusion(enabled: Boolean?) {
        vtoRenderer?.setBackPlaneOcclusion(enabled ?: true)
    }

    /**
     * Set forward offset for glasses positioning (in meters)
     */
    fun setForwardOffset(offset: Double?) {
        vtoRenderer?.setForwardOffset((offset ?: 0.005).toFloat())
    }

    /**
     * Set debug mode enabled
     */
    fun setDebug(enabled: Boolean?) {
        vtoRenderer?.setDebug(enabled ?: false)
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
     * Hide the glasses and face occlusion meshes. Sticky: stays hidden across
     * frames until showGlasses() is called. The AR session keeps running and
     * face tracking state is untouched.
     */
    fun hideGlasses() {
        vtoRenderer?.hideGlasses()
    }

    /**
     * Show the glasses and face occlusion meshes again after hideGlasses().
     * No-op if they weren't hidden.
     */
    fun showGlasses() {
        vtoRenderer?.showGlasses()
    }

    /**
     * Initialize the view. Should be called after the view is attached.
     */
    private fun initialize() {
        if (isInitialized) return

        // Create and initialize renderer
        vtoRenderer = VTORenderer(context)
        vtoRenderer?.onModelLoaded = onModelLoaded
        vtoRenderer?.onFaceTracked = onFaceTracked
        vtoRenderer?.onGlassesDisplayed = onGlassesDisplayed
        vtoRenderer?.initialize(surfaceView, modelUrl)

        isInitialized = true
        Log.d(TAG, "VtoView initialized")
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
        // Auto-resume on attach so old-arch wrappers that don't have an
        // `afterUpdate`-style hook get the same behavior as Nitro. `resume()`
        // is idempotent; the Nitro path still calls it via `afterUpdate`,
        // which is a harmless no-op after the first time.
        resume()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "onDetachedFromWindow")
        // Only pause here — full teardown (`destroy()`) races with Filament's
        // `UiHelper.onDetachedFromSurface` and the Android `SurfaceHolder`
        // surfaceDestroyed callback, which can fire against an already-freed
        // engine and trip Filament's `TPanic<PreconditionPanic>` → SIGABRT.
        // The wrappers own the true teardown: `VtoViewManager.onDropViewInstance`
        // (old-arch) and nitrogen's HybridView cleanup (Nitro) both call
        // `destroy()` at unmount time.
        pause()
    }
}
