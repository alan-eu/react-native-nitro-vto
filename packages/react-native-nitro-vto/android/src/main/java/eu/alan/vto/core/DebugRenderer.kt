package eu.alan.vto.core

import android.content.Context
import android.opengl.Matrix
import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.VertexBuffer
import com.google.ar.core.AugmentedFace

/**
 * Debug renderer for visualizing face mesh and the back plane.
 * Renders colored overlays: red for the face mesh, blue for the back plane.
 */
class DebugRenderer(private val context: Context) {

    companion object {
        private const val TAG = "DebugRenderer"
        // ARCore face mesh has 468 vertices (fixed)
        private const val VERTEX_COUNT = 468
        // ARCore face mesh has 2694 triangle indices (898 triangles)
        private const val INDEX_COUNT = 2694
    }

    private lateinit var engine: Engine
    private lateinit var scene: Scene

    // Materials
    private lateinit var debugFaceMaterial: Material
    private lateinit var debugPlaneMaterial: Material
    private lateinit var faceMeshMaterialInstance: MaterialInstance
    private lateinit var backPlaneMaterialInstance: MaterialInstance

    // Face mesh
    private var faceMeshVertexBuffer: VertexBuffer? = null
    private var faceMeshIndexBuffer: IndexBuffer? = null
    @Entity private var faceMeshEntity: Int = 0
    private var faceMeshInScene = false
    private var indexBufferInitialized = false

    // Back plane (single, spans full ear-line width)
    private var backPlaneVertexBuffer: VertexBuffer? = null
    private var backPlaneIndexBuffer: IndexBuffer? = null
    @Entity private var backPlaneEntity: Int = 0
    private var backPlaneInScene = false

    // State
    private var isEnabled = false

    // Reusable arrays
    private val vertexData = FloatArray(VERTEX_COUNT * 3)
    private val tempMatrix16 = FloatArray(16)
    private val backPlaneMatrix16 = FloatArray(16)
    private val faceMeshMatrix16 = FloatArray(16)
    private val backPlaneVertexData = FloatArray(4 * 3)

    /**
     * Setup the debug renderer with Filament engine and scene.
     */
    fun setup(engine: Engine, scene: Scene) {
        this.engine = engine
        this.scene = scene

        // Load debug materials
        try {
            // Face material (writes depth, renders first)
            val faceMaterialBuffer = LoaderUtils.loadAsset(context, "materials/debug_face_material.filamat")
            debugFaceMaterial = Material.Builder()
                .payload(faceMaterialBuffer, faceMaterialBuffer.remaining())
                .build(engine)

            // Plane material (reads depth, renders after, gets occluded)
            val planeMaterialBuffer = LoaderUtils.loadAsset(context, "materials/debug_plane_material.filamat")
            debugPlaneMaterial = Material.Builder()
                .payload(planeMaterialBuffer, planeMaterialBuffer.remaining())
                .build(engine)

            // Create material instances with different colors (40% opacity)
            // Red for face mesh (uses face material)
            faceMeshMaterialInstance = debugFaceMaterial.createInstance()
            faceMeshMaterialInstance.setParameter("debugColor", 1.0f, 0.0f, 0.0f, 0.4f)

            // Blue for the (single) back plane
            backPlaneMaterialInstance = debugPlaneMaterial.createInstance()
            backPlaneMaterialInstance.setParameter("debugColor", 0.0f, 0.0f, 1.0f, 0.4f)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load debug materials: ${e.message}")
            throw e
        }

        // Create face mesh buffers
        faceMeshVertexBuffer = VertexBuffer.Builder()
            .vertexCount(VERTEX_COUNT)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                12
            )
            .build(engine)

        faceMeshIndexBuffer = IndexBuffer.Builder()
            .indexCount(INDEX_COUNT)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)

        // Create face mesh entity
        faceMeshEntity = EntityManager.get().create()

        // Create back plane
        createBackPlane()

        Log.d(TAG, "Debug renderer setup complete")
    }

    private fun createBackPlane() {
        val planeSizeX = 0.12f
        val planeSizeY = 0.08f

        val initialVertices = floatArrayOf(
            -planeSizeX, -planeSizeY, 0f,
             planeSizeX, -planeSizeY, 0f,
            -planeSizeX,  planeSizeY, 0f,
             planeSizeX,  planeSizeY, 0f
        )

        backPlaneVertexBuffer = VertexBuffer.Builder()
            .vertexCount(4)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                12
            )
            .build(engine)
        backPlaneVertexBuffer!!.setBufferAt(engine, 0, MatrixUtils.createFloatBuffer(initialVertices))

        val indices = shortArrayOf(0, 1, 2, 2, 1, 3)
        backPlaneIndexBuffer = IndexBuffer.Builder()
            .indexCount(6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        backPlaneIndexBuffer!!.setBuffer(engine, MatrixUtils.createShortBuffer(indices))

        backPlaneEntity = EntityManager.get().create()

        val boundingBox = Box(0f, 0f, 0f, planeSizeX, planeSizeY, 0.1f)

        // Priority 8 — renders after the face mesh, gets occluded by it.
        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                backPlaneVertexBuffer!!,
                backPlaneIndexBuffer!!,
                0,
                6
            )
            .material(0, backPlaneMaterialInstance)
            .boundingBox(boundingBox)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(8)
            .build(engine, backPlaneEntity)
    }

    /**
     * Set debug mode enabled.
     */
    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return

        isEnabled = enabled

        if (!enabled) {
            hide()
        }

        Log.d(TAG, "Debug mode ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Update debug visualization with face data and back plane visibility from occlusion renderer.
     */
    fun update(face: AugmentedFace, showBackPlane: Boolean) {
        if (!isEnabled || faceMeshVertexBuffer == null || faceMeshIndexBuffer == null) return

        val meshVertices = face.meshVertices
        val meshIndices = face.meshTriangleIndices

        // Validate mesh data
        if (meshVertices.remaining() < VERTEX_COUNT * 3) return
        if (meshIndices.remaining() < INDEX_COUNT) return

        // Copy vertices and track XY extents — they drive the per-frame
        // back-plane size so the debug overlay matches the real occluder in
        // FaceOcclusionRenderer.
        var meshMinX = Float.MAX_VALUE
        var meshMaxX = -Float.MAX_VALUE
        var meshMinY = Float.MAX_VALUE
        var meshMaxY = -Float.MAX_VALUE
        for (i in 0 until VERTEX_COUNT) {
            val x = meshVertices.get(i * 3)
            val y = meshVertices.get(i * 3 + 1)
            val z = meshVertices.get(i * 3 + 2)
            vertexData[i * 3] = x
            vertexData[i * 3 + 1] = y
            vertexData[i * 3 + 2] = z
            if (x < meshMinX) meshMinX = x
            if (x > meshMaxX) meshMaxX = x
            if (y < meshMinY) meshMinY = y
            if (y > meshMaxY) meshMaxY = y
        }

        // Update vertex buffer
        faceMeshVertexBuffer!!.setBufferAt(engine, 0, MatrixUtils.createFloatBuffer(vertexData))

        // Initialize index buffer only once
        if (!indexBufferInitialized) {
            val indices = ShortArray(INDEX_COUNT)
            for (i in 0 until INDEX_COUNT) {
                indices[i] = meshIndices.get(i)
            }
            faceMeshIndexBuffer!!.setBuffer(engine, MatrixUtils.createShortBuffer(indices))
            indexBufferInitialized = true
        }

        // Create renderable if not in scene yet
        if (!faceMeshInScene) {
            val boundingBox = Box(0f, 0f, 0f, 0.15f, 0.15f, 0.15f)

            RenderableManager.Builder(1)
                .geometry(
                    0,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    faceMeshVertexBuffer!!,
                    faceMeshIndexBuffer!!,
                    0,
                    INDEX_COUNT
                )
                .material(0, faceMeshMaterialInstance)
                .boundingBox(boundingBox)
                .culling(false)
                .receiveShadows(false)
                .castShadows(false)
                .priority(7)
                .build(engine, faceMeshEntity)

            scene.addEntity(faceMeshEntity)
            faceMeshInScene = true
        }

        // Calculate min Z for back plane positioning
        var minZ = Float.MAX_VALUE
        for (i in 0 until VERTEX_COUNT) {
            val z = vertexData[i * 3 + 2]
            if (z < minZ) minZ = z
        }

        // Resize back plane from face mesh extents. Tuning lives in
        // OcclusionConstants — shared with FaceOcclusionRenderer.
        val meshHalfW = kotlin.math.max(kotlin.math.abs(meshMinX), kotlin.math.abs(meshMaxX))
        val meshHalfH = kotlin.math.max(kotlin.math.abs(meshMinY), kotlin.math.abs(meshMaxY))
        val halfW = kotlin.math.max(meshHalfW * OcclusionConstants.EAR_MARGIN, OcclusionConstants.MIN_HALF_WIDTH)
        val halfH = meshHalfH * OcclusionConstants.HEIGHT_MARGIN

        backPlaneVertexData[0]  = -halfW; backPlaneVertexData[1]  = -halfH; backPlaneVertexData[2]  = 0f
        backPlaneVertexData[3]  =  halfW; backPlaneVertexData[4]  = -halfH; backPlaneVertexData[5]  = 0f
        backPlaneVertexData[6]  = -halfW; backPlaneVertexData[7]  =  halfH; backPlaneVertexData[8]  = 0f
        backPlaneVertexData[9]  =  halfW; backPlaneVertexData[10] =  halfH; backPlaneVertexData[11] = 0f

        backPlaneVertexBuffer!!.setBufferAt(engine, 0, MatrixUtils.createFloatBuffer(backPlaneVertexData))

        // Update face mesh transform
        face.centerPose.toMatrix(tempMatrix16, 0)

        // Mirror X to match the glasses + occluder transforms (see
        // GlassesRenderer.updateTransform / FaceOcclusionRenderer.update).
        // Required because VTORenderer uses setProjection(fov, aspect, …),
        // which can't express ARCore's front-camera m[0] < 0 mirror.
        tempMatrix16[0] = -tempMatrix16[0]
        tempMatrix16[4] = -tempMatrix16[4]
        tempMatrix16[8] = -tempMatrix16[8]
        tempMatrix16[12] = -tempMatrix16[12]

        // Match FaceOcclusionRenderer's face-mesh X-shrink so the debug
        // overlay sits where the actual occluder sits.
        Matrix.scaleM(faceMeshMatrix16, 0, tempMatrix16, 0, OcclusionConstants.FACE_MESH_X_SHRINK, 1f, 1f)
        val faceInstance = engine.transformManager.getInstance(faceMeshEntity)
        engine.transformManager.setTransform(faceInstance, faceMeshMatrix16)

        // Position back plane behind the face — must match FaceOcclusionRenderer.
        val zOffset = minZ - OcclusionConstants.BACK_PLANE_Z_OFFSET
        tempMatrix16.copyInto(backPlaneMatrix16)
        val offsetX = backPlaneMatrix16[8] * zOffset
        val offsetY = backPlaneMatrix16[9] * zOffset
        val offsetZ = backPlaneMatrix16[10] * zOffset
        backPlaneMatrix16[12] += offsetX
        backPlaneMatrix16[13] += offsetY
        backPlaneMatrix16[14] += offsetZ

        val backPlaneInstance = engine.transformManager.getInstance(backPlaneEntity)
        engine.transformManager.setTransform(backPlaneInstance, backPlaneMatrix16)

        if (showBackPlane && !backPlaneInScene) {
            scene.addEntity(backPlaneEntity)
            backPlaneInScene = true
        } else if (!showBackPlane && backPlaneInScene) {
            scene.removeEntity(backPlaneEntity)
            backPlaneInScene = false
        }
    }

    /**
     * Hide debug visualization.
     */
    fun hide() {
        if (faceMeshInScene) {
            scene.removeEntity(faceMeshEntity)
            faceMeshInScene = false
        }
        if (backPlaneInScene) {
            scene.removeEntity(backPlaneEntity)
            backPlaneInScene = false
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        hide()

        EntityManager.get().destroy(faceMeshEntity)
        EntityManager.get().destroy(backPlaneEntity)

        faceMeshVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        faceMeshIndexBuffer?.let { engine.destroyIndexBuffer(it) }
        backPlaneVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        backPlaneIndexBuffer?.let { engine.destroyIndexBuffer(it) }

        engine.destroyMaterialInstance(faceMeshMaterialInstance)
        engine.destroyMaterialInstance(backPlaneMaterialInstance)
        engine.destroyMaterial(debugFaceMaterial)
        engine.destroyMaterial(debugPlaneMaterial)
    }
}
