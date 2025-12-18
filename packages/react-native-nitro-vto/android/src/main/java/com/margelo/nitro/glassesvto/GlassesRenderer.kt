package com.margelo.nitro.nitrovto

import android.content.Context
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Renderer for glasses model with face tracking transform.
 * Handles GLTF loading and NDC-space positioning based on ARCore face mesh.
 */
class GlassesRenderer(private val context: Context) {

    companion object {
        private const val TAG = "GlassesRenderer"
    }

    private lateinit var engine: Engine
    private lateinit var scene: Scene
    private lateinit var assetLoader: AssetLoader
    private lateinit var resourceLoader: ResourceLoader
    private var glassesAsset: FilamentAsset? = null

    // Thread management for URL loading
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Loading state
    private var isLoading = false

    // Current model info
    private var currentModelUrl: String = ""
    private var currentWidthMeters: Float = 0f

    // Callbacks
    var onModelLoaded: ((modelUrl: String) -> Unit)? = null

    // Reusable arrays to avoid per-frame allocations
    private val tempVec4 = FloatArray(4)
    private val tempMatrix16 = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    // Aspect ratio for scale correction
    private var aspectRatio = 1f

    // Kalman filters for smoothing (reduce jitter)
    // Higher processNoise = more responsive, higher measurementNoise = smoother
    private val positionFilter = KalmanFilter2D(processNoise = 0.1f, measurementNoise = 0.05f)
    private val scaleFilter = KalmanFilter(processNoise = 0.1f, measurementNoise = 0.05f)
    private val rotationFilter = KalmanFilterQuaternion(processNoise = 0.1f, measurementNoise = 0.05f)

    /**
     * Setup the glasses renderer with Filament engine and scene.
     * @param engine Filament engine instance
     * @param scene Scene to add glasses entities to
     * @param modelUrl URL to the glasses model (GLB format)
     * @param widthMeters Width of the glasses in meters
     */
    fun setup(engine: Engine, scene: Scene, modelUrl: String, widthMeters: Float) {
        this.engine = engine
        this.scene = scene
        this.currentModelUrl = modelUrl
        this.currentWidthMeters = widthMeters

        // Setup GLTF loader
        val materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        // Load model
        loadModel(modelUrl)
    }

    private fun loadModel(url: String) {
        if (url.isEmpty()) {
            Log.d(TAG, "Empty URL, skipping model load")
            return
        }

        if (isLoading) {
            Log.d(TAG, "Already loading a model, skipping request for: $url")
            return
        }

        isLoading = true
        Log.d(TAG, "Starting download from URL: $url")

        executor.execute {
            try {
                val modelBuffer = LoaderUtils.loadFromUrl(context, url)

                mainHandler.post {
                    try {
                        loadModelBuffer(modelBuffer)
                        onModelLoaded?.invoke(url)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load model buffer on main thread: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        isLoading = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download GLB from URL: ${e.message}")
                e.printStackTrace()
                mainHandler.post {
                    isLoading = false
                }
            }
        }
    }

    private fun loadModelBuffer(modelBuffer: ByteBuffer) {
        glassesAsset = assetLoader.createAsset(modelBuffer)

        glassesAsset?.let { asset ->
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
            Log.d(TAG, "Glasses model loaded: ${asset.entities.size} entities")
            hide()
        } ?: run {
            Log.e(TAG, "Failed to create glasses asset")
        }
    }

    /**
     * Set viewport dimensions for aspect ratio correction.
     */
    fun setViewportSize(width: Int, height: Int) {
        if (height > 0) {
            aspectRatio = width.toFloat() / height.toFloat()
        }
    }

    /**
     * Update glasses transform based on detected face.
     */
    fun updateTransform(face: AugmentedFace, frame: Frame) {
        glassesAsset?.let { asset ->
            val instance = engine.transformManager.getInstance(asset.root)

            frame.camera.getViewMatrix(viewMatrix, 0)
            frame.camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

            // Get nose bridge in world and NDC space
            val noseBridgeWorld = getNoseBridgeWorldPos(face)
            val noseBridgeNdcRaw = projectToNdc(noseBridgeWorld)

            // Calculate depth (distance from camera) for scale calculation
            val depth = getDepthInViewSpace(noseBridgeWorld)

            // Scale: use depth-based projection to maintain consistent size regardless of head turn
            // Derived from: scale = pdNdc / pdMeters, where pdNdc = pdMeters * focalLength / depth
            // Simplifies to: scale = focalLength / depth
            val focalLength = kotlin.math.abs(projMatrix[0])
            val scaleRaw = focalLength / depth

            // Apply Kalman filters to smooth position, scale, and rotation
            val noseBridgeNdc = positionFilter.update(noseBridgeNdcRaw[0], noseBridgeNdcRaw[1])
            val scale = scaleFilter.update(scaleRaw)

            // Negate Y and Z rotation components to compensate for horizontal camera mirror
            val faceQuaternion = face.centerPose.rotationQuaternion
            val mirroredQuaternion = floatArrayOf(
                faceQuaternion[0],   // X unchanged
                -faceQuaternion[1],  // Y negated
                -faceQuaternion[2],  // Z negated
                faceQuaternion[3]    // W unchanged
            )
            val smoothedQuaternion = rotationFilter.update(mirroredQuaternion)

            // Build transform matrix: rotation * uniform scale
            val rotationMatrix = MatrixUtils.quaternionToMatrix(smoothedQuaternion)
            Matrix.setIdentityM(tempMatrix16, 0)
            Matrix.scaleM(tempMatrix16, 0, scale, scale, scale)
            Matrix.multiplyMM(tempMatrix16, 0, rotationMatrix, 0, tempMatrix16.copyOf(), 0)

            // Apply aspect ratio correction in screen space (after rotation)
            // Multiplying Y components of each column stretches vertically
            tempMatrix16[1] *= aspectRatio
            tempMatrix16[5] *= aspectRatio
            tempMatrix16[9] *= aspectRatio

            // Set position
            tempMatrix16[12] = noseBridgeNdc[0]
            tempMatrix16[13] = noseBridgeNdc[1]
            tempMatrix16[14] = -0.5f

            engine.transformManager.setTransform(instance, tempMatrix16)
        }
    }

    /**
     * Get nose bridge center position in world coordinates.
     * Uses vertices 351 (left) and 122 (right) from ARCore face mesh.
     */
    private fun getNoseBridgeWorldPos(face: AugmentedFace): FloatArray {
        val left = MatrixUtils.getPositionForVertice(351, face)
        val right = MatrixUtils.getPositionForVertice(122, face)

        val centerX = (left[0] + right[0]) / 2f
        val centerY = (left[1] + right[1]) / 2f
        val centerZ = (left[2] + right[2]) / 2f

        face.centerPose.toMatrix(tempMatrix16, 0)
        return MatrixUtils.transformToWorld(centerX, centerY, centerZ, tempMatrix16, tempVec4)
    }

    /**
     * Project world position to NDC (Normalized Device Coordinates).
     */
    private fun projectToNdc(worldPos: FloatArray): FloatArray {
        return MatrixUtils.projectToNdc(worldPos, viewMatrix, projMatrix, tempVec4)
    }

    /**
     * Get depth (Z distance) from camera in view space.
     */
    private fun getDepthInViewSpace(worldPos: FloatArray): Float {
        tempVec4[0] = worldPos[0]
        tempVec4[1] = worldPos[1]
        tempVec4[2] = worldPos[2]
        tempVec4[3] = 1f
        val viewPos = FloatArray(4)
        Matrix.multiplyMV(viewPos, 0, viewMatrix, 0, tempVec4, 0)
        return kotlin.math.abs(viewPos[2])
    }

    /**
     * Hide glasses by moving off-screen.
     */
    fun hide() {
        glassesAsset?.let { asset ->
            val instance = engine.transformManager.getInstance(asset.root)
            engine.transformManager.setTransform(instance, MatrixUtils.createHideMatrix())
        }
        resetFilters()
    }

    private fun resetFilters() {
        positionFilter.reset()
        scaleFilter.reset()
        rotationFilter.reset()
    }

    /**
     * Switch to a different glasses model.
     * @param modelUrl URL to the new model (GLB format)
     * @param widthMeters Width of the new model in meters
     */
    fun switchModel(modelUrl: String, widthMeters: Float) {
        // Remove current model from scene
        glassesAsset?.let { asset ->
            scene.removeEntities(asset.entities)
            assetLoader.destroyAsset(asset)
        }
        glassesAsset = null
        resetFilters()

        // Update current model info
        currentModelUrl = modelUrl
        currentWidthMeters = widthMeters

        // Load new model
        loadModel(modelUrl)
        Log.d(TAG, "Switched to model: $modelUrl")
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        executor.shutdown()
        glassesAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        resourceLoader.destroy()
        assetLoader.destroy()
    }
}
