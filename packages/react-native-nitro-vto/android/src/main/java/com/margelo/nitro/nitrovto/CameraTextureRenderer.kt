package com.margelo.nitro.nitrovto

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.MaterialInstance.FloatElement
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame

/**
 * Handles camera texture rendering for AR background.
 * Creates and manages the EGL context, external texture, and fullscreen quad.
 */
class CameraTextureRenderer(private val context: Context) {

    companion object {
        private const val TAG = "CameraTextureRenderer"
    }

    // EGL context for ARCore
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Camera textures (multiple to avoid read/write conflicts with ARCore)
    // @see https://github.com/google/filament/issues/5498
    private var cameraTextureIds: IntArray = IntArray(4)
    private var cameraTextures: Array<Texture?> = arrayOfNulls(4)

    // Background quad
    private lateinit var cameraMaterial: Material
    private lateinit var cameraMaterialInstance: MaterialInstance
    @Entity private var backgroundQuadEntity: Int = 0
    private var backgroundQuadVertexBuffer: VertexBuffer? = null

    // Reusable 9-float buffer for the textureTransform MAT3 uniform (column-major)
    private val textureTransformMatrix = FloatArray(9)

    // Reference to engine and scene (set during setup)
    private lateinit var engine: Engine
    private lateinit var scene: Scene

    /**
     * Returns the EGL context for sharing with Filament engine
     */
    fun getEglContext(): EGLContext = eglContext

    /**
     * Returns the camera texture IDs for ARCore (multiple textures to avoid sync issues)
     */
    fun getCameraTextureIds(): IntArray = cameraTextureIds

    /**
     * Initialize EGL context and create camera texture.
     * Must be called before creating Filament engine.
     */
    fun initializeEglContext() {
        createEglContext()
        makeEglContextCurrent()
        // Create multiple textures to avoid read/write sync issues with ARCore
        for (i in cameraTextureIds.indices) {
            cameraTextureIds[i] = createExternalTextureId()
        }
        Log.d(TAG, "Created camera texture IDs: ${cameraTextureIds.contentToString()}")
    }

    /**
     * Setup the camera background rendering.
     * Must be called after Filament engine is created.
     */
    fun setup(engine: Engine, scene: Scene) {
        this.engine = engine
        this.scene = scene

        // Load camera background material
        val materialBuffer = LoaderUtils.loadAsset(context, "materials/camera_background.filamat")
        cameraMaterial = Material.Builder()
            .payload(materialBuffer, materialBuffer.remaining())
            .build(engine)
        cameraMaterialInstance = cameraMaterial.createInstance()

        // Import all external OES textures that ARCore cycles through
        for (i in cameraTextureIds.indices) {
            cameraTextures[i] = Texture.Builder()
                .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
                .format(Texture.InternalFormat.RGB8)
                .importTexture(cameraTextureIds[i].toLong())
                .build(engine)
        }

        // Set initial texture on material (will be updated each frame)
        cameraMaterialInstance.setParameter(
            "cameraFeed",
            cameraTextures[0]!!,
            TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE
            )
        )

        Log.d(TAG, "Camera textures imported, IDs: ${cameraTextureIds.contentToString()}")

        // Create fullscreen quad geometry
        createBackgroundQuad()
    }

    /**
     * Make EGL context current for OpenGL operations
     */
    fun makeEglContextCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("Unable to make EGL context current")
        }
    }

    /**
     * Update the material to use the correct texture for the current frame.
     * ARCore cycles through the texture array, so we need to bind the right one.
     */
    fun updateCameraTexture(frame: Frame) {
        val currentTextureId = frame.cameraTextureName
        val index = cameraTextureIds.indexOf(currentTextureId)
        if (index >= 0 && cameraTextures[index] != null) {
            cameraMaterialInstance.setParameter(
                "cameraFeed",
                cameraTextures[index]!!,
                TextureSampler(
                    TextureSampler.MinFilter.LINEAR,
                    TextureSampler.MagFilter.LINEAR,
                    TextureSampler.WrapMode.CLAMP_TO_EDGE
                )
            )
        }
    }

    /**
     * Update the camera_background material's `textureTransform` uniform from ARCore.
     *
     * The camera_background material uses identity UVs in the vertex buffer and applies
     * this 3x3 transform in its vertex shader to map (u, v) → texture UV. We derive the
     * matrix by asking ARCore for the texture coordinates of 3 NDC corners and fitting
     * a 2D affine — an exact fit, since the mapping ARCore returns is affine.
     *
     * Corner pairings deliberately create a 180° rotation (combined horizontal + vertical
     * flip) so the display matches the iOS side and the previous vertex-buffer-rebuild
     * behaviour on Android:
     *   vertex_uv (0, 0) → ARCore's texture coord for (1, 1)
     *   vertex_uv (1, 0) → ARCore's texture coord for (0, 1)
     *   vertex_uv (0, 1) → ARCore's texture coord for (1, 0)
     */
    fun updateUvTransform(frame: Frame): Boolean {
        try {
            // 3 NDC corners — enough to solve a 2D affine (6 unknowns).
            // Intentionally "flipped" so we bake the front-camera mirror + vertical flip
            // into the resulting matrix.
            val ndcCoords = floatArrayOf(
                1f, 1f,  // pair 0 → vertex_uv (0, 0)
                0f, 1f,  // pair 1 → vertex_uv (1, 0)
                1f, 0f   // pair 2 → vertex_uv (0, 1)
            )
            val textureCoords = FloatArray(6)

            frame.transformCoordinates2d(
                Coordinates2d.VIEW_NORMALIZED,
                ndcCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                textureCoords
            )

            // Affine fit:
            //   transform(0, 0) = (tx, ty)          = tc[0..1]
            //   transform(1, 0) = (a + tx, c + ty)  = tc[2..3]
            //   transform(0, 1) = (b + tx, d + ty)  = tc[4..5]
            val tx = textureCoords[0]
            val ty = textureCoords[1]
            val a = textureCoords[2] - tx
            val c = textureCoords[3] - ty
            val b = textureCoords[4] - tx
            val d = textureCoords[5] - ty

            // Filament expects a column-major 3x3 packed as 9 floats:
            //   col0 = (a, c, 0), col1 = (b, d, 0), col2 = (tx, ty, 1)
            textureTransformMatrix[0] = a;  textureTransformMatrix[1] = c;  textureTransformMatrix[2] = 0f
            textureTransformMatrix[3] = b;  textureTransformMatrix[4] = d;  textureTransformMatrix[5] = 0f
            textureTransformMatrix[6] = tx; textureTransformMatrix[7] = ty; textureTransformMatrix[8] = 1f

            cameraMaterialInstance.setParameter(
                "textureTransform",
                FloatElement.MAT3,
                textureTransformMatrix,
                0,
                1
            )
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update UV transform: ${e.message}")
            return false
        }
    }

    /**
     * No-op: the textureTransform matrix is rebuilt every frame in updateUvTransform.
     * Kept for API compatibility with callers that previously reset vertex-baked UVs.
     */
    fun resetUvTransform() {
    }

    /**
     * Destroy all resources
     */
    fun destroy() {
        scene.removeEntity(backgroundQuadEntity)
        EntityManager.get().destroy(backgroundQuadEntity)

        // Destroy all camera textures
        for (texture in cameraTextures) {
            texture?.let { engine.destroyTexture(it) }
        }

        // Destroy EGL context
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)
    }

    private fun createEglContext() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL")
        }

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        if (numConfigs[0] == 0) {
            throw RuntimeException("Unable to find suitable EGL config")
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Unable to create EGL context")
        }

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Unable to create EGL surface")
        }
    }

    private fun createExternalTextureId(): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        return textures[0]
    }

    private fun createBackgroundQuad() {
        // Fullscreen quad in device coordinates with identity UVs. The material's
        // textureTransform uniform (set per-frame in updateUvTransform) does the real
        // mapping to the camera-feed external texture.
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,  // bottom-left  → uv (0, 0)
             1f, -1f, 1f, 0f,  // bottom-right → uv (1, 0)
            -1f,  1f, 0f, 1f,  // top-left     → uv (0, 1)
             1f,  1f, 1f, 1f   // top-right    → uv (1, 1)
        )

        backgroundQuadVertexBuffer = VertexBuffer.Builder()
            .vertexCount(4)
            .bufferCount(1)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT2, 0, 16)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 8, 16)
            .build(engine)
        backgroundQuadVertexBuffer!!.setBufferAt(engine, 0, MatrixUtils.createFloatBuffer(vertices))

        val indices = shortArrayOf(0, 1, 2, 2, 1, 3)
        val indexBuffer = IndexBuffer.Builder()
            .indexCount(6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, MatrixUtils.createShortBuffer(indices))

        // Create entity
        backgroundQuadEntity = EntityManager.get().create()

        // Bounding box for the fullscreen quad (prevents frustum culling)
        val boundingBox = Box(-1f, -1f, 0f, 1f, 1f, 0f)

        RenderableManager.Builder(1)
            .material(0, cameraMaterialInstance)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, backgroundQuadVertexBuffer!!, indexBuffer)
            .boundingBox(boundingBox)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(7)
            .build(engine, backgroundQuadEntity)

        scene.addEntity(backgroundQuadEntity)
    }
}
