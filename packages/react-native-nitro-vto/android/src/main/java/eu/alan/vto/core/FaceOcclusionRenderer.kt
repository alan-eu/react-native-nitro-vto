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
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Renders ARCore face mesh to depth buffer only for face occlusion.
 * Draw order: camera background (priority 0), this depth mask (priority 1),
 * then glasses (priority 4) which depth-test against it.
 */
class FaceOcclusionRenderer(private val context: Context) {

    companion object {
        private const val TAG = "FaceOcclusionRenderer"
        // ARCore face mesh has 468 vertices (fixed)
        private const val VERTEX_COUNT = 468
        // ARCore face mesh has 2694 triangle indices (898 triangles)
        private const val INDEX_COUNT = 2694
    }

    private lateinit var engine: Engine
    private lateinit var scene: Scene
    private lateinit var occlusionMaterial: Material
    private lateinit var occlusionMaterialInstance: MaterialInstance
    private var vertexBuffer: VertexBuffer? = null
    private var indexBuffer: IndexBuffer? = null
    @Entity private var faceMeshEntity: Int = 0
    private var entityInScene = false
    private var indexBufferInitialized = false

    // Single back clipping plane spanning the full ear-line width.
    private var backPlaneVertexBuffer: VertexBuffer? = null
    private var backPlaneIndexBuffer: IndexBuffer? = null
    @Entity private var backPlaneEntity: Int = 0
    private var backPlaneInScene = false

    /** Whether the back plane is currently visible. */
    val isBackPlaneVisible: Boolean get() = backPlaneInScene

    // Reusable arrays to avoid per-frame allocations
    private val vertexData = FloatArray(VERTEX_COUNT * 3)
    private val tempMatrix16 = FloatArray(16)
    private val backPlaneMatrix16 = FloatArray(16)
    private val faceMeshMatrix16 = FloatArray(16)
    private val backPlaneVertexData = FloatArray(4 * 3)

    /**
     * Half-width of the user's ears in face-local meters, derived from the
     * face mesh's lateral extent and the same earMargin constant used to size
     * the back plane. Updated each call to update(). 0 if no face has been
     * processed yet.
     */
    var earHalfWidth: Float = 0f
        private set

    /**
     * Setup the face occlusion renderer with Filament engine and scene.
     */
    fun setup(engine: Engine, scene: Scene) {
        this.engine = engine
        this.scene = scene

        // Load face occlusion material
        try {
            val materialBuffer = LoaderUtils.loadAsset(context, "materials/face_occlusion.filamat")
            Log.d(TAG, "Material buffer loaded, size: ${materialBuffer.remaining()}")
            occlusionMaterial = Material.Builder()
                .payload(materialBuffer, materialBuffer.remaining())
                .build(engine)
            occlusionMaterialInstance = occlusionMaterial.createInstance()
            Log.d(TAG, "Material created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load face occlusion material: ${e.message}")
            e.printStackTrace()
            throw e
        }

        // Create dynamic vertex buffer for face mesh positions
        vertexBuffer = VertexBuffer.Builder()
            .vertexCount(VERTEX_COUNT)
            .bufferCount(1)
            .attribute(
                VertexBuffer.VertexAttribute.POSITION,
                0,
                VertexBuffer.AttributeType.FLOAT3,
                0,
                12  // 3 floats * 4 bytes
            )
            .build(engine)

        // Create index buffer (fixed topology for ARCore face mesh)
        indexBuffer = IndexBuffer.Builder()
            .indexCount(INDEX_COUNT)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)

        // Create entity (but don't add to scene yet - wait for valid face data)
        faceMeshEntity = EntityManager.get().create()

        // Create back clipping plane
        createBackPlane()

        Log.d(TAG, "Face occlusion renderer setup complete")
    }

    /**
     * Create the single back clipping plane that occludes glasses behind the head.
     * Vertices are overwritten per-frame with ±halfW / ±halfH derived from the
     * face mesh.
     */
    private fun createBackPlane() {
        val planeSizeX = 0.12f  // initial 12cm half-width
        val planeSizeY = 0.08f  // initial 8cm half-height

        val initialVertices = floatArrayOf(
            -planeSizeX, -planeSizeY, 0f,  // bottom-left
             planeSizeX, -planeSizeY, 0f,  // bottom-right
            -planeSizeX,  planeSizeY, 0f,  // top-left
             planeSizeX,  planeSizeY, 0f   // top-right
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

        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                backPlaneVertexBuffer!!,
                backPlaneIndexBuffer!!,
                0,
                6
            )
            .material(0, occlusionMaterialInstance)
            .boundingBox(boundingBox)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(1)  // after camera background (0), before glasses (4)
            .build(engine, backPlaneEntity)
    }

    /**
     * Update face mesh geometry from ARCore face data.
     */
    fun update(face: AugmentedFace) {
        if (vertexBuffer == null || indexBuffer == null) return

        // Get face mesh vertices (in face-local coordinates)
        val meshVertices = face.meshVertices
        val meshIndices = face.meshTriangleIndices

        // Validate mesh data
        if (meshVertices.remaining() < VERTEX_COUNT * 3) {
            Log.w(TAG, "Invalid mesh vertices count: ${meshVertices.remaining()}")
            return
        }
        if (meshIndices.remaining() < INDEX_COUNT) {
            Log.w(TAG, "Invalid mesh indices count: ${meshIndices.remaining()}")
            return
        }

        // Copy vertices directly (keep in face-local coordinates) and track XY
        // extents in the same pass — they drive the per-frame back-plane size.
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
        vertexBuffer!!.setBufferAt(engine, 0, MatrixUtils.createFloatBuffer(vertexData))

        // Initialize index buffer only once (topology doesn't change)
        if (!indexBufferInitialized) {
            val indices = ShortArray(INDEX_COUNT)
            for (i in 0 until INDEX_COUNT) {
                indices[i] = meshIndices.get(i)
            }
            indexBuffer!!.setBuffer(engine, MatrixUtils.createShortBuffer(indices))
            indexBufferInitialized = true
        }

        if (!entityInScene) {
            // Create bounding box (approximate head size)
            val boundingBox = Box(0f, 0f, 0f, 0.15f, 0.15f, 0.15f)

            RenderableManager.Builder(1)
                .geometry(
                    0,
                    RenderableManager.PrimitiveType.TRIANGLES,
                    vertexBuffer!!,
                    indexBuffer!!,
                    0,
                    INDEX_COUNT
                )
                .material(0, occlusionMaterialInstance)
                .boundingBox(boundingBox)
                .culling(false)
                .receiveShadows(false)
                .castShadows(false)
                .priority(1)  // after camera background (0), before glasses (4); writes depth mask
                .build(engine, faceMeshEntity)

            scene.addEntity(faceMeshEntity)
            entityInScene = true
            Log.d(TAG, "Face mesh entity added to scene")
        }

        // Calculate min Z (furthest from camera in face local space)
        var minZ = Float.MAX_VALUE
        for (i in 0 until VERTEX_COUNT) {
            val z = vertexData[i * 3 + 2]
            if (z < minZ) minZ = z
        }

        // Resize back plane from face mesh extents. Tuning lives in
        // OcclusionConstants.
        val meshHalfW = kotlin.math.max(kotlin.math.abs(meshMinX), kotlin.math.abs(meshMaxX))
        val meshHalfH = kotlin.math.max(kotlin.math.abs(meshMinY), kotlin.math.abs(meshMaxY))
        val halfW = kotlin.math.max(meshHalfW * OcclusionConstants.EAR_MARGIN, OcclusionConstants.MIN_HALF_WIDTH)
        val halfH = meshHalfH * OcclusionConstants.HEIGHT_MARGIN

        // Publish the ear half-width for consumers that articulate around it
        // (GlassesRenderer's temple swing). Use the same factor the back-plane
        // sizing applies, so the temple tips track the plane.
        earHalfWidth = meshHalfW * OcclusionConstants.EAR_MARGIN

        // Single plane spanning the full ear-line width.
        backPlaneVertexData[0]  = -halfW; backPlaneVertexData[1]  = -halfH; backPlaneVertexData[2]  = 0f
        backPlaneVertexData[3]  =  halfW; backPlaneVertexData[4]  = -halfH; backPlaneVertexData[5]  = 0f
        backPlaneVertexData[6]  = -halfW; backPlaneVertexData[7]  =  halfH; backPlaneVertexData[8]  = 0f
        backPlaneVertexData[9]  =  halfW; backPlaneVertexData[10] =  halfH; backPlaneVertexData[11] = 0f

        backPlaneVertexBuffer!!.setBufferAt(engine, 0, MatrixUtils.createFloatBuffer(backPlaneVertexData))

        // Apply face pose transform to entity (transforms local vertices to world space)
        face.centerPose.toMatrix(tempMatrix16, 0)

        // Mirror X to match the glasses transform (see GlassesRenderer.updateTransform).
        // Required because VTORenderer uses setProjection(fov, aspect, …), which can't
        // express ARCore's front-camera m[0] < 0 mirror.
        tempMatrix16[0] = -tempMatrix16[0]
        tempMatrix16[4] = -tempMatrix16[4]
        tempMatrix16[8] = -tempMatrix16[8]
        tempMatrix16[12] = -tempMatrix16[12]

        // Shrink the face mesh in X only when writing depth. Back plane
        // derives from tempMatrix16 below without this scale, so behind-head
        // occlusion is unaffected.
        Matrix.scaleM(faceMeshMatrix16, 0, tempMatrix16, 0, OcclusionConstants.FACE_MESH_X_SHRINK, 1f, 1f)

        val faceInstance = engine.transformManager.getInstance(faceMeshEntity)
        engine.transformManager.setTransform(faceInstance, faceMeshMatrix16)

        // Position the back plane behind the face. minZ is the most-negative
        // (deepest) mesh vertex; the plane sits behind it.
        val zOffset = minZ - OcclusionConstants.BACK_PLANE_Z_OFFSET
        // Copy face transform and add offset along local Z axis
        tempMatrix16.copyInto(backPlaneMatrix16)
        // Apply local Z offset (multiply by rotation part of matrix)
        val offsetX = backPlaneMatrix16[8] * zOffset   // column 2, row 0
        val offsetY = backPlaneMatrix16[9] * zOffset   // column 2, row 1
        val offsetZ = backPlaneMatrix16[10] * zOffset  // column 2, row 2
        backPlaneMatrix16[12] += offsetX  // translation X
        backPlaneMatrix16[13] += offsetY  // translation Y
        backPlaneMatrix16[14] += offsetZ  // translation Z

        val backPlaneInstance = engine.transformManager.getInstance(backPlaneEntity)
        engine.transformManager.setTransform(backPlaneInstance, backPlaneMatrix16)

        if (!backPlaneInScene) {
            scene.addEntity(backPlaneEntity)
            backPlaneInScene = true
        }
    }

    /**
     * Hide face mesh and back plane (remove from scene).
     */
    fun hide() {
        if (entityInScene) {
            scene.removeEntity(faceMeshEntity)
            entityInScene = false
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
        if (entityInScene) {
            scene.removeEntity(faceMeshEntity)
        }
        if (backPlaneInScene) {
            scene.removeEntity(backPlaneEntity)
        }
        EntityManager.get().destroy(faceMeshEntity)
        EntityManager.get().destroy(backPlaneEntity)

        vertexBuffer?.let { engine.destroyVertexBuffer(it) }
        indexBuffer?.let { engine.destroyIndexBuffer(it) }
        backPlaneVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        backPlaneIndexBuffer?.let { engine.destroyIndexBuffer(it) }
        engine.destroyMaterialInstance(occlusionMaterialInstance)
        engine.destroyMaterial(occlusionMaterial)
    }
}
