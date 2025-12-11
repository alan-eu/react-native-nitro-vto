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
import kotlin.math.abs

private const val DEFAULT_MODEL_PATH = "models/680048.glb"

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
     * @param modelPath Path to glasses GLB model (default: models/680048.glb)
     */
    fun setup(engine: Engine, scene: Scene, modelPath: String = DEFAULT_MODEL_PATH) {
        this.engine = engine
        this.scene = scene

        // Setup GLTF loader
        val materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        // Load model
        loadModel(modelPath)
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

            // Get eye center in NDC coordinates
            val eyeCenterNdc = getEyeCenterNdc(face)

            // Get forehead landmarks for scale calculation
            val foreheadLeft = face.getRegionPose(AugmentedFace.RegionType.FOREHEAD_LEFT)
            val foreheadRight = face.getRegionPose(AugmentedFace.RegionType.FOREHEAD_RIGHT)
            val foreheadRightNdc = MatrixUtils.projectToNdc(foreheadRight, viewMatrix, projMatrix, tempVec4)
            val foreheadLeftNdc = MatrixUtils.projectToNdc(foreheadLeft, viewMatrix, projMatrix, tempVec4)

            // Calculate scale: ratio of face width in NDC to face width in meters
            val faceWidthNdc = abs(foreheadRightNdc[0] - foreheadLeftNdc[0])
            val faceWidthMeters = MatrixUtils.distance3d(foreheadLeft, foreheadRight)
            val scale = faceWidthNdc / faceWidthMeters
            val scaleY = scale * aspectRatio

            // Build transform: rotation * scale, then set position
            val rotationMatrix = MatrixUtils.quaternionToMatrix(face.centerPose.rotationQuaternion)

            val finalMatrix = FloatArray(16)
            Matrix.setIdentityM(finalMatrix, 0)
            Matrix.scaleM(finalMatrix, 0, scale, scaleY, scale)
            Matrix.multiplyMM(tempMatrix16, 0, rotationMatrix, 0, finalMatrix, 0)

            // Position in NDC space (flip X for mirrored camera)
            tempMatrix16[12] = -eyeCenterNdc[0]
            tempMatrix16[13] = eyeCenterNdc[1]
            tempMatrix16[14] = -0.5f

            engine.transformManager.setTransform(instance, tempMatrix16)
        }
    }

    private fun getEyeCenterNdc(face: AugmentedFace): FloatArray {
        val meshBuffer = face.meshVertices
        val leftIdx = 374 * 3
        val rightIdx = 145 * 3

        // Read eye positions directly from buffer
        val leftX = meshBuffer.get(leftIdx)
        val leftY = meshBuffer.get(leftIdx + 1)
        val leftZ = meshBuffer.get(leftIdx + 2)
        val rightX = meshBuffer.get(rightIdx)
        val rightY = meshBuffer.get(rightIdx + 1)
        val rightZ = meshBuffer.get(rightIdx + 2)

        // Eye center in local coordinates
        val centerX = (leftX + rightX) / 2f
        val centerY = (leftY + rightY) / 2f
        val centerZ = (leftZ + rightZ) / 2f

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
