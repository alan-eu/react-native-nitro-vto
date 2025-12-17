package com.margelo.nitro.glassesvto

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
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

    // Camera texture
    private var cameraTextureId: Int = 0
    private var cameraTexture: Texture? = null

    // Background quad
    private lateinit var cameraMaterial: Material
    private lateinit var cameraMaterialInstance: MaterialInstance
    @Entity private var backgroundQuadEntity: Int = 0
    private var backgroundQuadVertexBuffer: VertexBuffer? = null
    private var uvTransformSet = false

    // Reference to engine and scene (set during setup)
    private lateinit var engine: Engine
    private lateinit var scene: Scene

    /**
     * Returns the EGL context for sharing with Filament engine
     */
    fun getEglContext(): EGLContext = eglContext

    /**
     * Returns the camera texture ID for ARCore
     */
    fun getCameraTextureId(): Int = cameraTextureId

    /**
     * Initialize EGL context and create camera texture.
     * Must be called before creating Filament engine.
     */
    fun initializeEglContext() {
        createEglContext()
        makeEglContextCurrent()
        cameraTextureId = createExternalTextureId()
        Log.d(TAG, "Created camera texture ID: $cameraTextureId")
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

        // Import the external OES texture that ARCore writes to
        cameraTexture = Texture.Builder()
            .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
            .format(Texture.InternalFormat.RGB8)
            .importTexture(cameraTextureId.toLong())
            .build(engine)

        // Set texture on material
        cameraMaterialInstance.setParameter(
            "cameraTexture",
            cameraTexture!!,
            TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE
            )
        )

        Log.d(TAG, "Camera texture imported and set on material, ID: $cameraTextureId")

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
     * Update UV coordinates using ARCore's transformCoordinates2d
     */
    fun updateUvTransform(frame: Frame): Boolean {
        if (uvTransformSet) return true

        try {
            // NDC coordinates for the 4 quad vertices
            val ndcCoords = floatArrayOf(
                0f, 0f,  // bottom-left
                1f, 0f,  // bottom-right
                0f, 1f,  // top-left
                1f, 1f   // top-right
            )

            // Output buffer for transformed texture coordinates
            val textureCoords = FloatArray(8)

            // Transform NDC to texture coordinates using ARCore
            frame.transformCoordinates2d(
                Coordinates2d.VIEW_NORMALIZED,
                ndcCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                textureCoords
            )

            // Build new vertex data with positions and transformed UVs (flipped vertically)
            val vertices = floatArrayOf(
                -1f, -1f, textureCoords[4], textureCoords[5],
                 1f, -1f, textureCoords[6], textureCoords[7],
                -1f,  1f, textureCoords[0], textureCoords[1],
                 1f,  1f, textureCoords[2], textureCoords[3]
            )

            backgroundQuadVertexBuffer?.setBufferAt(engine, 0, MatrixUtils.createFloatBuffer(vertices))
            uvTransformSet = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update UV transform: ${e.message}")
            return false
        }
    }

    /**
     * Reset UV transform flag (call when session is reset)
     */
    fun resetUvTransform() {
        uvTransformSet = false
    }

    /**
     * Destroy all resources
     */
    fun destroy() {
        scene.removeEntity(backgroundQuadEntity)
        EntityManager.get().destroy(backgroundQuadEntity)

        cameraTexture?.let { engine.destroyTexture(it) }

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
        // Position (x,y) and UV (u,v) for each vertex - UVs set later by ARCore
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 0f, 0f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 0f, 0f
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

        backgroundQuadEntity = EntityManager.get().create()
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, backgroundQuadVertexBuffer!!, indexBuffer)
            .material(0, cameraMaterialInstance)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(7)
            .build(engine, backgroundQuadEntity)

        scene.addEntity(backgroundQuadEntity)
    }
}
