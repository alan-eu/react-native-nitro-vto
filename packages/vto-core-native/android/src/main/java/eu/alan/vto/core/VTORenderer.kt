package eu.alan.vto.core

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import android.opengl.Matrix
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.ar.core.Frame
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Session
import com.google.ar.core.TrackingState

/**
 * Filament-based renderer for glasses VTO.
 * Uses UiHelper to manage rendering surface and Choreographer for frame timing.
 */
class VTORenderer(private val context: Context) {

    companion object {
        private const val TAG = "VTORenderer"

        init {
            Utils.init()
            Gltfio.init()
        }
    }

    // Filament core components
    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private lateinit var filamentCamera: Camera
    @Entity private var cameraEntity: Int = 0

    // UI/Display helpers
    private lateinit var uiHelper: UiHelper
    private lateinit var displayHelper: DisplayHelper
    private var swapChain: SwapChain? = null
    private var colorGrading: ColorGrading? = null

    // Camera background renderer
    private lateinit var cameraTextureRenderer: CameraTextureRenderer

    // Environment lighting renderer
    private lateinit var environmentLightingRenderer: EnvironmentLightingRenderer

    // Glasses renderer
    private lateinit var glassesRenderer: GlassesRenderer

    // Face occlusion renderer
    private lateinit var faceOcclusionRenderer: FaceOcclusionRenderer

    // Debug renderer
    private lateinit var debugRenderer: DebugRenderer

    // ARCore
    var session: Session? = null

    // Frame callback
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            choreographer.postFrameCallback(this)
            doFrame()
        }
    }

    // Track initialization
    private var initialized = false
    private var width = 0
    private var height = 0
    private var surfaceViewRef: SurfaceView? = null
    private var cameraTextureNameSet = false
    private var lastDisplayRotation = -1
    private var lastDisplayWidth = 0
    private var lastDisplayHeight = 0

    // Model configuration
    private var modelUrl: String = ""

    // Reusable matrices for camera update (avoid per-frame allocations)
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val cameraModelMatrix = FloatArray(16)

    // Callbacks
    var onModelLoaded: ((modelUrl: String) -> Unit)? = null
    var onFaceTracked: (() -> Unit)? = null
    var onGlassesDisplayed: ((modelUrl: String) -> Unit)? = null

    // Perf-callback state
    private var hasFiredFaceTracked = false

    // Whether the glasses + face occlusion meshes are explicitly hidden. Sticky
    // across frames — cleared only by showGlasses().
    private var isHidden = false

    // FPS tracking — VtoView reads `lastFps` periodically to update the
    // overlay label.
    private var fpsFrameCount = 0
    private var fpsLastUpdateNs = 0L
    @Volatile var lastFps: Float = 0f
        private set

    /**
     * Initialize Filament and attach to surface view
     */
    fun initialize(surfaceView: SurfaceView, modelUrl: String) {
        this.modelUrl = modelUrl
        surfaceViewRef = surfaceView

        // Initialize camera texture renderer (creates EGL context)
        cameraTextureRenderer = CameraTextureRenderer(context)
        cameraTextureRenderer.initializeEglContext()

        // Initialize Filament engine with shared EGL context
        engine = Engine.Builder()
            .sharedContext(cameraTextureRenderer.getEglContext())
            .build()
        renderer = engine.createRenderer()
        scene = engine.createScene()
        view = engine.createView()

        // Create camera
        cameraEntity = EntityManager.get().create()
        filamentCamera = engine.createCamera(cameraEntity)

        // Pin Filament's photographic exposure model to a known reference
        // (sceneview's defaults: f/16, 1/125s, ISO 100). Locking the EV
        // here means our directional-light intensity calibration in
        // EnvironmentLightingRenderer reads the same lux values
        // regardless of any scene-driven exposure changes.
        filamentCamera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)

        view.camera = filamentCamera
        view.scene = scene

        // Configure view
        view.isPostProcessingEnabled = true

        // Filmic tone mapper — compresses bright highlights more than
        // the default ACES, taming the shine on the metallic glasses
        // frame off the IBL. The camera material inverts this exact
        // curve + the piecewise sRGB encoder so the feed round-trips
        // unchanged.
        colorGrading = ColorGrading.Builder()
            .toneMapper(ToneMapper.Filmic())
            .build(engine)
        view.colorGrading = colorGrading

        // SSAO — grounds the glasses to the face (contact shadow at the
        // temple-skin boundary, around the nose-pad). 0.3m radius is
        // right for face-scale geometry; MEDIUM is the cheap-but-visible
        // knee. See ADR 0012.
        view.ambientOcclusionOptions = view.ambientOcclusionOptions.apply {
            enabled = true
            radius = 0.3f
            intensity = 1.0f
            quality = View.QualityLevel.MEDIUM
        }

        // TAA for edge AA — temporal accumulation smooths the metallic
        // frame silhouette as the head moves and damps sub-pixel jitter
        // FXAA can't reach. See ADR 0012.
        view.temporalAntiAliasingOptions = view.temporalAntiAliasingOptions.apply {
            enabled = true
        }

        // Setup UiHelper for surface management
        uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
            renderCallback = object : UiHelper.RendererCallback {
                override fun onNativeWindowChanged(surface: Surface) {
                    swapChain?.let { engine.destroySwapChain(it) }
                    swapChain = engine.createSwapChain(surface)
                    displayHelper.attach(renderer, surfaceView.display)
                }

                override fun onDetachedFromSurface() {
                    displayHelper.detach()
                    swapChain?.let {
                        engine.destroySwapChain(it)
                        engine.flushAndWait()
                        swapChain = null
                    }
                }

                override fun onResized(width: Int, height: Int) {
                    this@VTORenderer.width = width
                    this@VTORenderer.height = height
                    view.viewport = Viewport(0, 0, width, height)
                }
            }
            attachTo(surfaceView)
        }

        displayHelper = DisplayHelper(context)

        // Setup environment lighting
        environmentLightingRenderer = EnvironmentLightingRenderer(context)
        environmentLightingRenderer.setup(engine, scene)

        // Setup camera background
        cameraTextureRenderer.setup(engine, scene)

        // Setup face occlusion renderer
        faceOcclusionRenderer = FaceOcclusionRenderer(context)
        faceOcclusionRenderer.setup(engine, scene)

        // Setup glasses renderer
        glassesRenderer = GlassesRenderer(context)
        glassesRenderer.onModelLoaded = onModelLoaded
        glassesRenderer.onGlassesDisplayed = onGlassesDisplayed
        glassesRenderer.setup(engine, scene, modelUrl)

        // Setup debug renderer
        debugRenderer = DebugRenderer(context)
        debugRenderer.setup(engine, scene)

        initialized = true
    }

    private fun updateCameraProjection(frame: Frame) {
        if (width == 0 || height == 0) return

        val near = 0.1
        val far = 10.0

        // Get ARCore camera matrices
        frame.camera.getViewMatrix(viewMatrix, 0)
        frame.camera.getProjectionMatrix(projMatrix, 0, near.toFloat(), far.toFloat())

        // ARCore viewMatrix transforms world -> camera space
        // Filament camera needs model matrix (camera -> world), which is inverse(viewMatrix)
        Matrix.invertM(cameraModelMatrix, 0, viewMatrix, 0)

        // Filament's screen-space refraction (used by KHR_materials_transmission) fails to
        // populate the refraction source on the Android GL backend when the projection is
        // set via setCustomProjection — the frame ends up sampling black. The
        // setProjection(fov, aspect, near, far) overload (Filament builds the matrix
        // itself) is the only one where refraction works. Derive the vertical fov from
        // ARCore's projection matrix so the glasses still align with the tracked face;
        // the horizontal flip ARCore bakes into m[0] (front camera mirror) is applied at
        // the model-matrix level on each ARCore-tracked entity (see GlassesRenderer and
        // FaceOcclusionRenderer), so the view matrix stays determinant-positive and the
        // refraction pass keeps working.
        val fovYRadians = 2.0 * kotlin.math.atan(1.0 / projMatrix[5])
        val fovYDegrees = Math.toDegrees(fovYRadians)
        val aspect = width.toDouble() / height.toDouble()

        filamentCamera.setProjection(
            fovYDegrees, aspect, near, far, Camera.Fov.VERTICAL
        )
        filamentCamera.setModelMatrix(cameraModelMatrix)
    }

    fun resume() {
        choreographer.postFrameCallback(frameCallback)
    }

    fun pause() {
        choreographer.removeFrameCallback(frameCallback)
    }

    /**
     * Switch to a different glasses model
     */
    fun switchModel(modelUrl: String) {
        this.modelUrl = modelUrl
        glassesRenderer.switchModel(modelUrl)
    }

    /**
     * Hide the glasses and face occlusion meshes. Sticky: stays hidden across
     * frames until showGlasses() is called. The AR session keeps running and
     * face tracking state is untouched.
     */
    fun hideGlasses() {
        isHidden = true
        faceOcclusionRenderer.hide()
        glassesRenderer.hide()
    }

    /**
     * Show the glasses and face occlusion meshes again after hideGlasses().
     * No-op if they weren't hidden. Clearing the flag is enough — the next
     * frame with a tracked face runs the updates, which overwrite the
     * hide-transform set by hideGlasses().
     */
    fun showGlasses() {
        isHidden = false
    }

    /**
     * Set forward offset for glasses positioning (in meters)
     */
    fun setForwardOffset(offset: Float) {
        if (initialized) {
            glassesRenderer.setForwardOffset(offset)
        }
    }

    /**
     * Set debug mode enabled
     */
    fun setDebug(enabled: Boolean) {
        if (initialized) {
            debugRenderer.setEnabled(enabled)
        }
    }

    /**
     * Mark the loaded model as a clip-on / solar frame (tinted-lens treatment).
     */
    fun setIsClipOn(isClipOn: Boolean) {
        if (initialized) {
            glassesRenderer.setIsClipOn(isClipOn)
        }
    }

    private fun doFrame() {
        if (!initialized) return

        val session = session ?: return
        val swap = swapChain ?: return

        if (!uiHelper.isReadyToRender) return

        try {
            // Make EGL context current for ARCore texture operations
            cameraTextureRenderer.makeEglContextCurrent()

            // Set camera texture names on ARCore session (only once)
            // Using multiple textures avoids read/write sync issues (green flashes)
            if (!cameraTextureNameSet) {
                session.setCameraTextureNames(cameraTextureRenderer.getCameraTextureIds())
                cameraTextureNameSet = true
            }

            // Set display geometry for ARCore (only when changed)
            if (width > 0 && height > 0) {
                val display = surfaceViewRef?.display
                val rotation = display?.rotation ?: 0
                if (rotation != lastDisplayRotation || width != lastDisplayWidth || height != lastDisplayHeight) {
                    session.setDisplayGeometry(rotation, width, height)
                    lastDisplayRotation = rotation
                    lastDisplayWidth = width
                    lastDisplayHeight = height
                }
            }

            // Update ARCore and get frame
            val frame = session.update()

            // Update Filament camera with ARCore camera matrices
            updateCameraProjection(frame)

            // Update material to use the correct texture for this frame
            cameraTextureRenderer.updateCameraTexture(frame)

            // Per-frame directional ("sun") light tracks the AR light
            // estimate. The IBL itself stays static — see ADR 0011 — so
            // specular reflections are platform-equivalent; the directional
            // light adds scene-tracking shading on top.
            environmentLightingRenderer.updateDirectionalFromARCore(frame)

            // Update UV transform for proper aspect ratio
            if (width > 0 && height > 0) {
                cameraTextureRenderer.updateUvTransform(frame)
            }

            // Get tracked faces
            val faces = session.getAllTrackables(AugmentedFace::class.java)
                .filter { it.trackingState == TrackingState.TRACKING }

            // Update face occlusion and glasses transform if face detected
            if (faces.isNotEmpty()) {
                if (!hasFiredFaceTracked) {
                    hasFiredFaceTracked = true
                    onFaceTracked?.invoke()
                }
                val face = faces.first()
                if (!isHidden) {
                    faceOcclusionRenderer.update(face)
                    glassesRenderer.updateTransform(face, frame)
                    glassesRenderer.updateTempleArticulation(faceOcclusionRenderer.earHalfWidth)
                }
                debugRenderer.update(face, faceOcclusionRenderer.isBackPlaneVisible)
            } else {
                faceOcclusionRenderer.hide()
                glassesRenderer.hide()
                debugRenderer.hide()
            }

            // Render frame with Filament
            if (renderer.beginFrame(swap, frame.timestamp)) {
                renderer.render(view)
                renderer.endFrame()
            }

            // FPS bookkeeping — refresh the public lastFps every 500ms.
            fpsFrameCount++
            val now = System.nanoTime()
            if (fpsLastUpdateNs == 0L) fpsLastUpdateNs = now
            val dtNs = now - fpsLastUpdateNs
            if (dtNs >= 500_000_000L) {
                lastFps = (fpsFrameCount * 1_000_000_000f) / dtNs
                fpsFrameCount = 0
                fpsLastUpdateNs = now
            }

        } catch (e: Exception) {
            Log.e(TAG, "Render error: ${e.message}")
        }
    }

    fun destroy() {
        choreographer.removeFrameCallback(frameCallback)

        if (!initialized) return

        debugRenderer.destroy()
        glassesRenderer.destroy()
        faceOcclusionRenderer.destroy()
        cameraTextureRenderer.destroy()
        environmentLightingRenderer.destroy()

        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)

        uiHelper.detach()

        swapChain?.let { engine.destroySwapChain(it) }
        engine.destroyView(view)
        colorGrading?.let { engine.destroyColorGrading(it) }
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.destroy()

        // Flip so any subsequent destroy() call is a true no-op (the guard
        // above short-circuits on `!initialized`). Defense-in-depth against
        // racy teardown paths re-entering this method.
        initialized = false
    }
}
