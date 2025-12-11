package com.example.glassesvto

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport
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

    // Camera background renderer
    private lateinit var cameraTextureRenderer: CameraTextureRenderer

    // Environment lighting renderer
    private lateinit var environmentLightingRenderer: EnvironmentLightingRenderer

    // Glasses renderer
    private lateinit var glassesRenderer: GlassesRenderer

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

    /**
     * Initialize Filament and attach to surface view
     */
    fun initialize(surfaceView: SurfaceView) {
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
        view.camera = filamentCamera
        view.scene = scene

        // Configure view
        view.isPostProcessingEnabled = false

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
                    updateCameraProjection()
                    glassesRenderer.setViewportSize(width, height)
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

        // Setup glasses renderer
        glassesRenderer = GlassesRenderer(context)
        glassesRenderer.setup(engine, scene)

        initialized = true
    }

    private fun updateCameraProjection() {
        if (width == 0 || height == 0) return
        filamentCamera.setProjection(
            Camera.Projection.ORTHO,
            -1.0, 1.0, -1.0, 1.0, -1.0, 1.0
        )
    }

    fun resume() {
        choreographer.postFrameCallback(frameCallback)
    }

    fun pause() {
        choreographer.removeFrameCallback(frameCallback)
    }

    private fun doFrame() {
        if (!initialized) return

        val session = session ?: return
        val swap = swapChain ?: return

        if (!uiHelper.isReadyToRender) return

        try {
            // Make EGL context current for ARCore texture operations
            cameraTextureRenderer.makeEglContextCurrent()

            // Set camera texture name on ARCore session (only once)
            if (!cameraTextureNameSet) {
                session.setCameraTextureName(cameraTextureRenderer.getCameraTextureId())
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

            // Update lighting from ARCore light estimation
            environmentLightingRenderer.updateFromARCore(frame)

            // Update UV transform for proper aspect ratio
            if (width > 0 && height > 0) {
                cameraTextureRenderer.updateUvTransform(frame)
            }

            // Get tracked faces
            val faces = session.getAllTrackables(AugmentedFace::class.java)
                .filter { it.trackingState == TrackingState.TRACKING }

            // Update glasses transform if face detected
            if (faces.isNotEmpty()) {
                glassesRenderer.updateTransform(faces.first(), frame)
            } else {
                glassesRenderer.hide()
            }

            // Render frame with Filament
            if (renderer.beginFrame(swap, frame.timestamp)) {
                renderer.render(view)
                renderer.endFrame()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Render error: ${e.message}")
        }
    }

    fun destroy() {
        choreographer.removeFrameCallback(frameCallback)

        if (!initialized) return

        glassesRenderer.destroy()
        cameraTextureRenderer.destroy()
        environmentLightingRenderer.destroy()

        engine.destroyCameraComponent(cameraEntity)
        EntityManager.get().destroy(cameraEntity)

        uiHelper.detach()

        swapChain?.let { engine.destroySwapChain(it) }
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.destroy()
    }
}
