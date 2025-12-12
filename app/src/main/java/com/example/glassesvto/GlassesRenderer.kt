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
import com.google.ar.core.Pose
import kotlin.math.abs

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
     * @param face ARCore AugmentedFace with tracking data
     * @param frame Current ARCore frame for camera matrices
     */
    fun updateTransform(face: AugmentedFace, frame: Frame) {
        glassesAsset?.let { asset ->
            val instance = engine.transformManager.getInstance(asset.root)

            // Get camera matrices
            frame.camera.getViewMatrix(viewMatrix, 0)
            frame.camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100f)

            // Get nose bridge center in NDC coordinates for positioning
            val noseBridgeCenterNdc = getNoseBridgeCenterNdc(face)

            // Get PD measurements for scale calculation
            val pdMeters = getPupillaryDistance(face)
            val pdNdc = getPupillaryDistanceNdc(face)

            // Cross-multiplication to get glasses scale in NDC
            // If PD (pdMeters) maps to pdNdc, then glassesWidth maps to glassesNdc
            val currentModel = AVAILABLE_MODELS[currentModelIndex]
            val glassesWidthNdc = (currentModel.widthMeters / pdMeters) * pdNdc

            // Scale factor: glasses model is 1 unit wide, we want it to be glassesWidthNdc in NDC
            val scale = glassesWidthNdc / currentModel.widthMeters
            val scaleY = scale * aspectRatio

            // Build transform: rotation * scale, then set position
            val rotationMatrix = MatrixUtils.getGlassesRotationMatrix(face)

            val finalMatrix = FloatArray(16)
            Matrix.setIdentityM(finalMatrix, 0)
            Matrix.scaleM(finalMatrix, 0, scale, scaleY, scale)
            Matrix.multiplyMM(tempMatrix16, 0, rotationMatrix, 0, finalMatrix, 0)

            // Position in NDC space (flip X for mirrored camera)
            tempMatrix16[12] = -noseBridgeCenterNdc[0]
            tempMatrix16[13] = noseBridgeCenterNdc[1]
            tempMatrix16[14] = -0.5f

            engine.transformManager.setTransform(instance, tempMatrix16)
        }
    }

    private fun getPupillaryDistance(face: AugmentedFace): Float {
        // Eyes position from vertices
        val left = MatrixUtils.getPositionForVertice(374, face)
        val right = MatrixUtils.getPositionForVertice(145, face)
        return MatrixUtils.distance3d(left[0], left[1], left[2], right[0], right[1], right[2])
    }

    private fun getPupillaryDistanceNdc(face: AugmentedFace): Float {
        // Eyes position from vertices
        val left = MatrixUtils.getPositionForVertice(374, face)
        val right = MatrixUtils.getPositionForVertice(145, face)

        // Transform both points to world coordinates
        face.centerPose.toMatrix(tempMatrix16, 0)
        val leftWorld = MatrixUtils.transformToWorld(left[0], left[1], left[2], tempMatrix16, tempVec4)
        val rightWorld = MatrixUtils.transformToWorld(right[0], right[1], right[2], tempMatrix16, FloatArray(4))

        // Project both to NDC
        val leftNdc = MatrixUtils.projectToNdc(leftWorld, viewMatrix, projMatrix, tempVec4)
        val rightNdc = MatrixUtils.projectToNdc(rightWorld, viewMatrix, projMatrix, FloatArray(4))

        // Return horizontal distance in NDC
        return abs(rightNdc[0] - leftNdc[0])
    }

    private fun getNoseBridgeCenterNdc(face: AugmentedFace): FloatArray {
        // Nose bridge position from vertices
        val left = MatrixUtils.getPositionForVertice(351, face)
        val right = MatrixUtils.getPositionForVertice(122, face)

        // Nose bridge center in local coordinates
        val centerX = (left[0] + right[0]) / 2f
        val centerY = (left[1] + right[1]) / 2f
        val centerZ = (left[2] + right[2]) / 2f

        // Transform to world coordinates
        face.centerPose.toMatrix(tempMatrix16, 0)
        val worldPos = MatrixUtils.transformToWorld(centerX, centerY, centerZ, tempMatrix16, tempVec4)

        // Project to NDC
        return MatrixUtils.projectToNdc(worldPos, viewMatrix, projMatrix, tempVec4)
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
