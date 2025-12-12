package com.example.glassesvto

import android.content.Context
import android.opengl.Matrix
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

data class GlassesModel(
    val path: String,
    val widthMeters: Float  // total frame width in meters
)

private val AVAILABLE_MODELS = listOf(
    GlassesModel("models/878082.glb", 0.135f),  // TODO: replace with actual width
    GlassesModel("models/680048.glb", 0.138f)   // TODO: replace with actual width
)

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
    private var currentModelIndex = 0

    // Reusable arrays to avoid per-frame allocations
    private val tempVec4 = FloatArray(4)
    private val tempMatrix16 = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)

    // Aspect ratio for scale correction
    private var aspectRatio = 1f

    /**
     * Setup the glasses renderer with Filament engine and scene.
     * @param engine Filament engine instance
     * @param scene Scene to add glasses entities to
     * @param modelIndex Index of the model to load (default: 0)
     */
    fun setup(engine: Engine, scene: Scene, modelIndex: Int = 0) {
        this.engine = engine
        this.scene = scene
        this.currentModelIndex = modelIndex

        // Setup GLTF loader
        val materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        // Load model
        loadModel(AVAILABLE_MODELS[currentModelIndex].path)
    }

    private fun loadModel(filename: String) {
        try {
            val modelBuffer = LoaderUtils.loadAsset(context, filename)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load glasses model: ${e.message}")
            e.printStackTrace()
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
            val noseBridgeNdc = projectToNdc(noseBridgeWorld)

            // Calculate depth (distance from camera) for scale calculation
            val depth = getDepthInViewSpace(noseBridgeWorld)

            // Scale: use depth-based projection to maintain consistent size regardless of head turn
            // Derived from: scale = pdNdc / pdMeters, where pdNdc = pdMeters * focalLength / depth
            // Simplifies to: scale = focalLength / depth
            val focalLength = kotlin.math.abs(projMatrix[0])
            val scale = focalLength / depth

            // Build transform matrix: rotation * uniform scale
            val rotationMatrix = MatrixUtils.quaternionToMatrix(face.centerPose.rotationQuaternion)
            Matrix.setIdentityM(tempMatrix16, 0)
            Matrix.scaleM(tempMatrix16, 0, scale, scale, scale)
            Matrix.multiplyMM(tempMatrix16, 0, rotationMatrix, 0, tempMatrix16.copyOf(), 0)

            // Apply aspect ratio correction in screen space (after rotation)
            // Multiplying Y components of each column stretches vertically
            tempMatrix16[1] *= aspectRatio
            tempMatrix16[5] *= aspectRatio
            tempMatrix16[9] *= aspectRatio

            // Set position (flip X for front camera mirror)
            tempMatrix16[12] = -noseBridgeNdc[0]
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
    }

    /**
     * Switch to the next available glasses model.
     */
    fun switchToNextModel() {
        // Remove current model from scene
        glassesAsset?.let { asset ->
            scene.removeEntities(asset.entities)
            assetLoader.destroyAsset(asset)
        }
        glassesAsset = null

        // Load next model
        currentModelIndex = (currentModelIndex + 1) % AVAILABLE_MODELS.size
        loadModel(AVAILABLE_MODELS[currentModelIndex].path)
        Log.d(TAG, "Switched to model: ${AVAILABLE_MODELS[currentModelIndex].path}")
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        glassesAsset?.let {
            scene.removeEntities(it.entities)
            assetLoader.destroyAsset(it)
        }
        resourceLoader.destroy()
        assetLoader.destroy()
    }
}
