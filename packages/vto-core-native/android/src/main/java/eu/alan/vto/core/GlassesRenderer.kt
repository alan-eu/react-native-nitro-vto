package eu.alan.vto.core

import android.content.Context
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.RenderableManager
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
    var onGlassesDisplayed: ((modelUrl: String) -> Unit)? = null

    // Fires onGlassesDisplayed once per loaded model — reset on every successful load.
    private var hasDisplayedCurrentModel = false

    // Reusable arrays to avoid per-frame allocations
    private val tempVec4 = FloatArray(4)
    private val tempMatrix16 = FloatArray(16)

    // Forward offset for glasses positioning (in meters)
    private var forwardOffset = OcclusionConstants.FORWARD_OFFSET

    // Temple articulation state. articulationEnabled stays false if any of
    // the four expected hinge/temple node names is missing from the asset —
    // articulation becomes a no-op for that asset.
    private var articulationEnabled = false
    @Entity private var hingeLEntity: Int = 0
    @Entity private var hingeREntity: Int = 0
    private val hingeLRest = FloatArray(16)
    private val hingeRRest = FloatArray(16)
    private var restTipXL = 0f       // glb-cm (AABB center.x of TempleL_geometry)
    private var restTipXR = 0f       // glb-cm
    private var templeLLength = 0f   // glb-cm, hinge-to-tip distance along glb +Y
    private var templeRLength = 0f   // glb-cm
    private val articulationRotMatrix = FloatArray(16)
    private val articulationOutMatrix = FloatArray(16)
    private val articulationBbox = Box(0f, 0f, 0f, 0f, 0f, 0f)

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
            // Reset the "already displayed" flag so onGlassesDisplayed fires again
            // the next time updateTransform runs for this freshly-loaded model.
            hasDisplayedCurrentModel = false
            cacheTempleArticulationState(asset)
            hide()
        } ?: run {
            Log.e(TAG, "Failed to create glasses asset")
        }
    }

    private fun cacheTempleArticulationState(asset: FilamentAsset) {
        articulationEnabled = false

        val hingeL = asset.getFirstEntityByName("HingeL_temple")
        val hingeR = asset.getFirstEntityByName("HingeR_temple")
        val templeL = asset.getFirstEntityByName("TempleL_geometry")
        val templeR = asset.getFirstEntityByName("TempleR_geometry")

        if (hingeL == 0 || hingeR == 0 || templeL == 0 || templeR == 0) {
            Log.d(TAG, "Hinge/temple nodes not found — articulation disabled for this asset")
            return
        }

        val tm = engine.transformManager
        val rm = engine.renderableManager

        tm.getTransform(tm.getInstance(hingeL), hingeLRest)
        tm.getTransform(tm.getInstance(hingeR), hingeRRest)

        hingeLEntity = hingeL
        hingeREntity = hingeR
        // Translation Y is at index 13 in column-major 4x4.
        val hingeLY = hingeLRest[13]
        val hingeRY = hingeRRest[13]

        // Temples are authored extending along glb +Y in Z-up cm: the hinge
        // sits at the front of the frame (smaller Y) and the geometry runs
        // back to a larger +Y for the tip. Approximate the rest tip with
        // the AABB:
        //   restTipX  ≈ AABB.center.x   (X is roughly constant along the temple)
        //   templeLen = AABB.maxY - hingeY  (distance from hinge to the +Y end)
        rm.getAxisAlignedBoundingBox(rm.getInstance(templeL), articulationBbox)
        restTipXL = articulationBbox.center[0]
        templeLLength = (articulationBbox.center[1] + articulationBbox.halfExtent[1]) - hingeLY
        rm.getAxisAlignedBoundingBox(rm.getInstance(templeR), articulationBbox)
        restTipXR = articulationBbox.center[0]
        templeRLength = (articulationBbox.center[1] + articulationBbox.halfExtent[1]) - hingeRY

        if (templeLLength <= 0f || templeRLength <= 0f) {
            Log.d(TAG, "Degenerate temple AABB — articulation disabled")
            return
        }

        articulationEnabled = true
        Log.d(
            TAG,
            "Temple articulation enabled (L tipX=$restTipXL cm, len=$templeLLength cm; R tipX=$restTipXR cm, len=$templeRLength cm)"
        )
    }

    /**
     * Update glasses transform based on detected face.
     */
    fun updateTransform(face: AugmentedFace, frame: Frame) {
        glassesAsset?.let { asset ->
            if (!hasDisplayedCurrentModel) {
                hasDisplayedCurrentModel = true
                onGlassesDisplayed?.invoke(currentModelUrl)
            }
            val instance = engine.transformManager.getInstance(asset.root)

            // Get nose bridge position in world space
            val noseBridgeWorld = getNoseBridgeWorldPos(face)

            // Get face rotation from pose (world space)
            val faceQuaternion = face.centerPose.rotationQuaternion

            // Build world-space transform matrix (no scaling - models are in real-world meters)
            val rotationMatrix = MatrixUtils.quaternionToMatrix(faceQuaternion)
            System.arraycopy(rotationMatrix, 0, tempMatrix16, 0, 16)

            // Offset glasses along face's Z axis (forward/backward)
            val forwardX = rotationMatrix[8]   // Z axis X component (column 2, row 0)
            val forwardY = rotationMatrix[9]   // Z axis Y component (column 2, row 1)
            val forwardZ = rotationMatrix[10]  // Z axis Z component (column 2, row 2)

            // Set world-space position with forward offset
            tempMatrix16[12] = noseBridgeWorld[0] + forwardX * forwardOffset
            tempMatrix16[13] = noseBridgeWorld[1] + forwardY * forwardOffset
            tempMatrix16[14] = noseBridgeWorld[2] + forwardZ * forwardOffset

            // Mirror X for the front-facing camera: ARCore's projection encodes the mirror
            // in m[0] < 0, but VTORenderer uses Filament's setProjection(fov, aspect, …) which
            // can't express it — and setCustomProjection with ARCore's raw matrix silently
            // breaks screen-space refraction. Instead we mirror every ARCore-tracked
            // transform here (pre-multiply by scale(-1, 1, 1) → negate row 0 of this 4x4).
            // Keeps the Filament view matrix determinant-positive → refraction keeps working.
            tempMatrix16[0] = -tempMatrix16[0]
            tempMatrix16[4] = -tempMatrix16[4]
            tempMatrix16[8] = -tempMatrix16[8]
            tempMatrix16[12] = -tempMatrix16[12]

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
    }

    /**
     * Set forward offset for glasses positioning (in meters).
     */
    fun setForwardOffset(offset: Float) {
        forwardOffset = offset
    }

    /**
     * Articulate the temples (swing left/right hinge nodes) so the temple
     * tips land at ±earHalfWidth (face-local meters). No-op if the loaded
     * glb does not expose the expected hinge node names
     * (HingeL_temple / HingeR_temple).
     */
    fun updateTempleArticulation(earHalfWidth: Float) {
        if (!articulationEnabled || earHalfWidth <= 0f) return
        glassesAsset ?: return

        // Scale ear half-width to the temple tip's depth (see
        // TEMPLE_TIP_SCALE doc in OcclusionConstants). Convert face-local
        // meters → glb-cm.
        val desiredXcm = earHalfWidth * OcclusionConstants.TEMPLE_TIP_SCALE * 100f

        // Temples extend along glb +Y from the hinge. Rotating around the
        // hinge's local Z axis by θ moves the tip's parent X by
        // approximately -L·sin(θ). Solve for θ to land the tip at
        // ±desiredXcm.
        val sinL = ((restTipXL - desiredXcm) / templeLLength).coerceIn(-1f, 1f)
        val sinR = ((restTipXR - (-desiredXcm)) / templeRLength).coerceIn(-1f, 1f)
        val thetaLDeg = Math.toDegrees(kotlin.math.asin(sinL).toDouble()).toFloat()
        val thetaRDeg = Math.toDegrees(kotlin.math.asin(sinR).toDouble()).toFloat()

        val tm = engine.transformManager

        Matrix.setRotateM(articulationRotMatrix, 0, thetaLDeg, 0f, 0f, 1f)
        Matrix.multiplyMM(articulationOutMatrix, 0, hingeLRest, 0, articulationRotMatrix, 0)
        tm.setTransform(tm.getInstance(hingeLEntity), articulationOutMatrix)

        Matrix.setRotateM(articulationRotMatrix, 0, thetaRDeg, 0f, 0f, 1f)
        Matrix.multiplyMM(articulationOutMatrix, 0, hingeRRest, 0, articulationRotMatrix, 0)
        tm.setTransform(tm.getInstance(hingeREntity), articulationOutMatrix)
    }

    /**
     * Switch to a different glasses model.
     * @param modelUrl URL to the new model (GLB format)
     */
    fun switchModel(modelUrl: String) {
        // Disable articulation immediately — the cached hinge entities belong
        // to the asset we're about to destroy. cacheTempleArticulationState
        // reruns when the new asset finishes loading.
        articulationEnabled = false

        // Remove current model from scene
        glassesAsset?.let { asset ->
            scene.removeEntities(asset.entities)
            assetLoader.destroyAsset(asset)
        }
        glassesAsset = null

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
