package eu.alan.vto.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
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

        // Reasons handed to onArUnavailable — keep in sync with types.ts.
        private const val REASON_DEVICE_NOT_CAPABLE = "device-not-capable"
        private const val REASON_NOT_INSTALLED = "arcore-not-installed"
        private const val REASON_OUTDATED = "arcore-outdated"
        private const val REASON_UNAVAILABLE = "arcore-unavailable"
        private const val REASON_FACE_TRACKING = "face-tracking-unsupported"

        // Availability is queried asynchronously; poll this many times, 200ms
        // apart, before giving up on an answer.
        private const val MAX_AVAILABILITY_CHECKS = 5

        // Whether ARCore reported this device as incapable. The verdict can't
        // change while the process lives, so it is cached here: a remounted view
        // must not re-check, and above all must not send the user to the Play
        // Store again.
        private var deviceNotCapable = false
    }

    // ARCore session
    private var arSession: Session? = null

    // SurfaceView for rendering
    private val surfaceView: SurfaceView = SurfaceView(context)

    // FPS overlay (visible when debug=true).
    private val fpsLabel: TextView = TextView(context).apply {
        text = "—"
        setTextColor(Color.YELLOW)
        setBackgroundColor(Color.argb(102, 0, 0, 0))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = android.graphics.Typeface.MONOSPACE
        gravity = Gravity.END
        visibility = View.GONE
        val pad = (context.resources.displayMetrics.density * 4f).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }
    private val fpsHandler = Handler(Looper.getMainLooper())
    private val fpsRunnable = object : Runnable {
        override fun run() {
            val fps = vtoRenderer?.lastFps ?: 0f
            fpsLabel.text = "%.0f fps · %.1f ms".format(
                fps,
                if (fps > 0f) 1000f / fps else 0f
            )
            fpsHandler.postDelayed(this, 500)
        }
    }

    private fun resolveActivity(): Activity? {
        // 1. Direct cast (works when consumer passes a plain Activity context).
        (context as? Activity)?.let { return it }
        // 2. Walk the ContextWrapper chain (works for some wrappers).
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        // 3. React Native's ThemedReactContext.getCurrentActivity() —
        //    accessed via reflection so vto-core-native doesn't take a
        //    hard dependency on the RN host classes.
        return try {
            val method = context.javaClass.methods.firstOrNull { it.name == "getCurrentActivity" }
            method?.invoke(context) as? Activity
        } catch (e: Exception) {
            null
        }
    }

    // Filament renderer
    private var vtoRenderer: VTORenderer? = null

    // Configuration
    private var modelUrl: String = ""
    private var isActive: Boolean = true
    private var isClipOnState: Boolean = false
    private var backgroundColorState: FloatArray? = null

    // Preview mode: no AR session — the glasses sit on a flat background and the
    // user orbits/zooms them. Either asked for through the `mode` prop, or
    // forced when ARCore can't run here (emulator, unsupported device), which
    // would otherwise render nothing.
    private var previewRequested = false
    private var arUnavailable = false

    // AR might still happen: the availability query hasn't resolved yet, or the
    // user is in the ARCore install flow. Preview renders meanwhile so the view
    // is never blank, and clears once the answer arrives.
    private var arPending = false

    // We spend at most one Play Store redirect per view. If we come back and
    // ARCore still isn't there, the user declined and we stop asking.
    private var installRequested = false

    private val isPreviewMode: Boolean get() = previewRequested || arUnavailable || arPending

    // Orbit/zoom touch state, live only while preview mode is on.
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val previewScaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                vtoRenderer?.zoomPreviewCamera(detector.scaleFactor)
                return true
            }
        }
    )

    // Callbacks
    var onModelLoaded: ((modelUrl: String) -> Unit)? = null
    var onFaceTracked: (() -> Unit)? = null
    var onGlassesDisplayed: ((modelUrl: String) -> Unit)? = null
    var onArUnavailable: ((reason: String) -> Unit)? = null

    // State
    private var isInitialized = false
    private var isResumed = false

    // App lifecycle. ARCore requires session.pause()/resume() around the host
    // Activity's onPause/onResume — without it the camera is released by the
    // system on background and the feed stays frozen on return. Callbacks are
    // registered per-attached-window and filtered to the host activity, so
    // they also fire when another activity covers the camera.
    private var hostActivity: Activity? = null
    private var lifecycleCallbacksRegistered = false
    private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity === hostActivity) resume()
        }

        override fun onActivityPaused(activity: Activity) {
            if (activity === hostActivity) pause()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // Bounded retry for ARCore's camera-handback race: on fast
    // background→foreground cycles, Session.resume() can throw
    // CameraNotAvailableException because the system hasn't released the
    // camera back to us yet.
    private val resumeRetryHandler = Handler(Looper.getMainLooper())
    private var resumeRetryCount = 0

    // ArCoreApk.checkAvailability() answers asynchronously the first time
    // (UNKNOWN_CHECKING); poll it a few times rather than guessing.
    private var availabilityCheckCount = 0
    private val availabilityRunnable = Runnable {
        if (isResumed && isActive && isAttachedToWindow) {
            arPending = false
            applyPreviewMode()
        }
    }
    private val resumeRetryRunnable = Runnable {
        if (isActive && isResumed && isAttachedToWindow) {
            resume()
        }
    }

    init {
        // Add SurfaceView to fill the entire view
        addView(surfaceView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))
        // The fpsLabel is intentionally NOT added here — Filament's
        // SurfaceView surface composites above sibling views on some
        // devices, so the label gets parented to the activity's decor
        // view (outside the React Native subtree) lazily on first
        // setDebug(true). See attachFpsLabelIfNeeded().
    }

    private fun attachFpsLabelIfNeeded() {
        if (fpsLabel.parent != null) return
        val activity = resolveActivity() ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val density = context.resources.displayMetrics.density
        // Top-right of the activity window. Parented to the decor view
        // rather than this FrameLayout so the SurfaceView's separately-
        // composited GL surface can't occlude it.
        val lp = FrameLayout.LayoutParams(
            (110 * density).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = (30 * density).toInt()
            rightMargin = (8 * density).toInt()
        }
        activity.runOnUiThread { decor.addView(fpsLabel, lp) }
    }

    private fun detachFpsLabel() {
        (fpsLabel.parent as? ViewGroup)?.removeView(fpsLabel)
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
     * Set forward offset for glasses positioning (in meters)
     */
    fun setForwardOffset(offset: Double?) {
        vtoRenderer?.setForwardOffset(offset?.toFloat() ?: OcclusionConstants.FORWARD_OFFSET)
    }

    /**
     * Set debug visualization (colored face-mesh + back-plane overlays).
     */
    fun setDebug(enabled: Boolean?) {
        vtoRenderer?.setDebug(enabled ?: false)
    }

    /**
     * Mark the model as a clip-on / solar frame (tinted-lens treatment).
     */
    fun setIsClipOn(enabled: Boolean) {
        isClipOnState = enabled
        vtoRenderer?.setIsClipOn(enabled)
    }

    /**
     * Show/hide the native FPS counter overlay (top-right of the activity).
     */
    fun setShowNativeFPS(enabled: Boolean?) {
        val on = enabled ?: false
        if (on) {
            attachFpsLabelIfNeeded()
            fpsLabel.visibility = View.VISIBLE
        } else {
            fpsLabel.visibility = View.GONE
        }
        fpsHandler.removeCallbacks(fpsRunnable)
        if (on) fpsHandler.post(fpsRunnable)
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
     * Render mode: `"preview"` for the no-AR orbit viewer, anything else (the
     * default) for AR try-on.
     */
    fun setMode(mode: String?) {
        val requested = mode == "preview"
        if (requested == previewRequested) return
        previewRequested = requested
        applyPreviewMode()
    }

    /**
     * Preview background, as `#RGB`, `#RRGGBB` or `#RRGGBBAA` (alpha ignored —
     * the background is opaque), with or without the leading `#`. An unparseable
     * value leaves the engine's default in place.
     */
    fun setPreviewBackgroundColor(hex: String?) {
        backgroundColorState = hex?.let { parseColor(it) }
        val color = backgroundColorState ?: return
        vtoRenderer?.setPreviewBackgroundColor(color[0], color[1], color[2])
    }

    private fun parseColor(hex: String): FloatArray? {
        var digits = hex.trim().removePrefix("#")
        if (digits.length == 3) {
            digits = digits.map { "$it$it" }.joinToString("")
        }
        if (digits.length != 6 && digits.length != 8) return null
        val value = digits.substring(0, 6).toLongOrNull(16) ?: return null
        return floatArrayOf(
            ((value shr 16) and 0xFF) / 255f,
            ((value shr 8) and 0xFF) / 255f,
            (value and 0xFF) / 255f
        )
    }

    /**
     * Keeps the renderer, the AR session and touch handling in step with the
     * current mode. Idempotent — called on prop changes and on resume.
     */
    private fun applyPreviewMode() {
        if (!isInitialized) return
        val preview = isPreviewMode

        vtoRenderer?.setPreviewMode(preview)

        if (preview) {
            // Preview needs no camera, so stop the capture rather than leaving
            // it running behind an opaque background.
            arSession?.pause()
        } else if (isResumed && isActive) {
            setupArSession()
        }
    }

    /**
     * ARCore can't run here (as opposed to a transient camera hand-back):
     * render the glasses in preview mode rather than nothing.
     */
    private fun fallBackToPreview(reason: String) {
        if (arUnavailable) return
        arUnavailable = true
        arPending = false
        Log.w(TAG, "AR unavailable ($reason) — falling back to preview mode")
        applyPreviewMode()
        onArUnavailable?.invoke(reason)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isPreviewMode) return super.onTouchEvent(event)

        previewScaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Don't let a parent scroll container steal the orbit drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                // One finger orbits; a second finger means a pinch, which the
                // scale detector owns.
                if (event.pointerCount == 1 && !previewScaleDetector.isInProgress) {
                    val density = resources.displayMetrics.density
                    vtoRenderer?.orbitPreviewCamera(
                        (event.x - lastTouchX) / density,
                        (event.y - lastTouchY) / density
                    )
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // event.x/y average the active pointers, so they jump when the
                // pointer count changes — re-baseline instead of orbiting.
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
        return true
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

        // Re-apply stored configuration. `isClipOn` is read at model-load time
        // (configureLensMaterial), so the prop can arrive before the renderer
        // exists and be dropped — set it here so the first model loads with the
        // correct lens treatment. Mirrors VtoView.swift's post-init apply.
        vtoRenderer?.setIsClipOn(isClipOnState)
        backgroundColorState?.let { vtoRenderer?.setPreviewBackgroundColor(it[0], it[1], it[2]) }

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

        // A resume is the moment to re-evaluate an in-flight verdict: this is
        // where we land coming back from the ARCore installer.
        arPending = false

        // Starts the AR session, or tears it down for preview — which runs
        // without one, and without the camera permission it would require.
        applyPreviewMode()

        // Resume renderer
        vtoRenderer?.resume()
    }

    /**
     * Pause the AR session and rendering
     */
    fun pause() {
        cancelResumeRetry()
        vtoRenderer?.pause()
        arSession?.pause()
        isResumed = false
    }

    /**
     * Destroy and clean up resources
     */
    fun destroy() {
        cancelResumeRetry()
        unregisterLifecycleCallbacks()
        fpsHandler.removeCallbacks(fpsRunnable)
        detachFpsLabel()
        arSession?.close()
        arSession = null
        vtoRenderer?.destroy()
        vtoRenderer = null
        isInitialized = false
    }

    private fun registerLifecycleCallbacks() {
        if (lifecycleCallbacksRegistered) return
        // Without a resolved host activity the callbacks could never match —
        // skip registration entirely (same behavior as before this hook).
        val activity = resolveActivity() ?: return
        val app = activity.application
            ?: context.applicationContext as? Application
            ?: return
        hostActivity = activity
        app.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        lifecycleCallbacksRegistered = true
    }

    private fun unregisterLifecycleCallbacks() {
        if (!lifecycleCallbacksRegistered) return
        val app = hostActivity?.application
            ?: context.applicationContext as? Application
        app?.unregisterActivityLifecycleCallbacks(activityLifecycleCallbacks)
        lifecycleCallbacksRegistered = false
        hostActivity = null
    }

    private fun scheduleResumeRetry() {
        if (resumeRetryCount >= 3) {
            Log.e(TAG, "Camera still unavailable after $resumeRetryCount resume retries — giving up")
            return
        }
        resumeRetryCount++
        resumeRetryHandler.postDelayed(resumeRetryRunnable, 300)
    }

    private fun cancelResumeRetry() {
        resumeRetryHandler.removeCallbacks(resumeRetryRunnable)
        resumeRetryCount = 0
        resumeRetryHandler.removeCallbacks(availabilityRunnable)
    }

    /**
     * Sets up the ARCore session with face tracking.
     * Assumes camera permission is already granted.
     */
    private fun setupArSession() {
        if (arSession != null) {
            try {
                arSession?.resume()
                resumeRetryCount = 0
                vtoRenderer?.session = arSession
            } catch (e: CameraNotAvailableException) {
                // System hasn't handed the camera back yet (fast
                // background→foreground cycle) — retry shortly.
                Log.w(TAG, "Camera not yet available on resume — scheduling retry")
                scheduleResumeRetry()
            }
            return
        }

        // Decide whether AR can run here *before* anything can send the user to
        // the Play Store. Returns false when we should stay in preview — either
        // for good, or until a pending check/install resolves.
        if (!ensureArAvailable()) return

        try {
            // Create AR session with front camera for face tracking
            arSession = Session(context, EnumSet.of(Session.Feature.FRONT_CAMERA))

            // Configure session for face tracking
            val config = Config(arSession).apply {
                augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
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
            fallBackToPreview(REASON_NOT_INSTALLED)
        } catch (e: UnavailableDeviceNotCompatibleException) {
            deviceNotCapable = true
            fallBackToPreview(REASON_DEVICE_NOT_CAPABLE)
        } catch (e: UnavailableSdkTooOldException) {
            fallBackToPreview(REASON_OUTDATED)
        } catch (e: UnavailableApkTooOldException) {
            fallBackToPreview(REASON_OUTDATED)
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available")
        } catch (e: Exception) {
            // Session creation failed outright — front-camera AR is the part
            // checkAvailability() can't speak for, so an unsupported device
            // surfaces here rather than above. Not the transient camera
            // hand-back handled earlier, so there is nothing to retry.
            Log.e(TAG, "Failed to create AR session: ${e.message}")
            fallBackToPreview(REASON_FACE_TRACKING)
        }
    }

    /**
     * Whether an ARCore session may be created right now.
     *
     * `checkAvailability()` is the only call that answers "can this device run
     * ARCore at all", and it costs nothing — asking it first is what keeps an
     * incapable device from being sent to the Play Store to install something it
     * can never run. `requestInstall()` is reached only for a device that ARCore
     * says it supports, and at most once per view.
     *
     * Returns false when the caller should stay in preview: either permanently
     * (fallBackToPreview has fired) or until a pending check or install
     * resolves, in which case `arPending` keeps the view in preview meanwhile.
     */
    private fun ensureArAvailable(): Boolean {
        if (deviceNotCapable) {
            fallBackToPreview(REASON_DEVICE_NOT_CAPABLE)
            return false
        }

        return when (ArCoreApk.getInstance().checkAvailability(context)) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                arPending = false
                true
            }

            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> requestArCoreInstall()

            ArCoreApk.Availability.UNKNOWN_CHECKING -> {
                scheduleAvailabilityCheck()
                false
            }

            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                deviceNotCapable = true
                fallBackToPreview(REASON_DEVICE_NOT_CAPABLE)
                false
            }

            // UNKNOWN_ERROR / UNKNOWN_TIMED_OUT — no verdict is coming.
            else -> {
                fallBackToPreview(REASON_UNAVAILABLE)
                false
            }
        }
    }

    /**
     * One Play Store redirect, ever. A second pass through here means the user
     * came back without ARCore. Why is not knowable — declined, cancelled, or
     * the Play install failed all look identical from here — so we report it as
     * "not installed" and settle into preview instead of bouncing them out
     * again.
     */
    private fun requestArCoreInstall(): Boolean {
        if (installRequested) {
            fallBackToPreview(REASON_NOT_INSTALLED)
            return false
        }

        val activity = getActivity()
        if (activity == null) {
            // requestInstall needs an Activity to launch the installer.
            fallBackToPreview(REASON_NOT_INSTALLED)
            return false
        }

        return try {
            installRequested = true
            when (ArCoreApk.getInstance().requestInstall(activity, true)) {
                ArCoreApk.InstallStatus.INSTALLED -> {
                    arPending = false
                    true
                }
                // The installer is up; we resume again when the user returns.
                else -> {
                    enterPendingPreview()
                    false
                }
            }
        } catch (e: UnavailableUserDeclinedInstallationException) {
            fallBackToPreview(REASON_NOT_INSTALLED)
            false
        } catch (e: UnavailableDeviceNotCompatibleException) {
            deviceNotCapable = true
            fallBackToPreview(REASON_DEVICE_NOT_CAPABLE)
            false
        } catch (e: Exception) {
            Log.e(TAG, "ARCore install request failed: ${e.message}")
            fallBackToPreview(REASON_UNAVAILABLE)
            false
        }
    }

    /**
     * Render preview while an availability check or an install is in flight, so
     * the view is never blank waiting on an answer. Re-entrant by design: the
     * applyPreviewMode() below re-enters with isPreviewMode true, which takes
     * the preview branch and stops there.
     */
    private fun enterPendingPreview() {
        if (arPending) return
        arPending = true
        applyPreviewMode()
    }

    private fun scheduleAvailabilityCheck() {
        if (availabilityCheckCount >= MAX_AVAILABILITY_CHECKS) {
            fallBackToPreview(REASON_UNAVAILABLE)
            return
        }
        availabilityCheckCount++
        enterPendingPreview()
        resumeRetryHandler.removeCallbacks(availabilityRunnable)
        resumeRetryHandler.postDelayed(availabilityRunnable, 200)
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
        registerLifecycleCallbacks()
        // Auto-resume on attach so old-arch wrappers that don't have an
        // `afterUpdate`-style hook get the same behavior as Nitro. `resume()`
        // is idempotent; the Nitro path still calls it via `afterUpdate`,
        // which is a harmless no-op after the first time.
        resume()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.d(TAG, "onDetachedFromWindow")
        unregisterLifecycleCallbacks()
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
