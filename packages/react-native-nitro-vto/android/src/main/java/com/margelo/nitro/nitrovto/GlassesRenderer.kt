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

    // Callbacks
    var onModelLoaded: ((modelUrl: String) -> Unit)? = null

    // Reusable arrays to avoid per-frame allocations
    private val tempVec4 = FloatArray(4)
    private val tempMatrix16 = FloatArray(16)

    // Kalman filters for smoothing (reduce jitter)
    // Higher processNoise = more responsive, higher measurementNoise = smoother
    private val positionFilter = KalmanFilter3D(processNoise = 0.1f, measurementNoise = 0.05f)
    private val rotationFilter = KalmanFilterQuaternion(processNoise = 0.1f, measurementNoise = 0.05f)

    // Forward offset for glasses positioning (in meters)
    private var forwardOffset = 0.005f  // Default: 5mm forward

    /**
     * Setup the glasses renderer with Filament engine and scene.
     * @param engine Filament engine instance
     * @param scene Scene to add glasses entities to
     * @param modelUrl URL to the glasses model (GLB format)
     */
    fun setup(engine: Engine, scene: Scene, modelUrl: String) {
        this.engine = engine
        this.scene = scene
        this.currentModelUrl = modelUrl

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
     * Update glasses transform based on detected face.
     */
    fun updateTransform(face: AugmentedFace, frame: Frame) {
        glassesAsset?.let { asset ->
            val instance = engine.transformManager.getInstance(asset.root)

            // Get nose bridge position in world space
            val noseBridgeWorld = getNoseBridgeWorldPos(face)

            // Get face rotation from pose (world space)
            val faceQuaternion = face.centerPose.rotationQuaternion

            // Apply Kalman filter smoothing
            val smoothedPosition = positionFilter.update(noseBridgeWorld)
            val smoothedRotation = rotationFilter.update(faceQuaternion)

            // Build world-space transform matrix (no scaling - models are in real-world meters)
            val rotationMatrix = MatrixUtils.quaternionToMatrix(smoothedRotation)
            System.arraycopy(rotationMatrix, 0, tempMatrix16, 0, 16)

            // Offset glasses along face's Z axis (forward/backward)
            val forwardX = rotationMatrix[8]   // Z axis X component (column 2, row 0)
            val forwardY = rotationMatrix[9]   // Z axis Y component (column 2, row 1)
            val forwardZ = rotationMatrix[10]  // Z axis Z component (column 2, row 2)

            // Set world-space position with forward offset
            tempMatrix16[12] = smoothedPosition[0] + forwardX * forwardOffset
            tempMatrix16[13] = smoothedPosition[1] + forwardY * forwardOffset
            tempMatrix16[14] = smoothedPosition[2] + forwardZ * forwardOffset

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
        rotationFilter.reset()
    }

    /**
     * Set forward offset for glasses positioning (in meters).
     */
    fun setForwardOffset(offset: Float) {
        forwardOffset = offset
    }

    /**
     * Switch to a different glasses model.
     * @param modelUrl URL to the new model (GLB format)
     */
    fun switchModel(modelUrl: String) {
        // Remove current model from scene
        glassesAsset?.let { asset ->
            scene.removeEntities(asset.entities)
            assetLoader.destroyAsset(asset)
        }
        glassesAsset = null
        resetFilters()

        // Update current model info
        currentModelUrl = modelUrl

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
