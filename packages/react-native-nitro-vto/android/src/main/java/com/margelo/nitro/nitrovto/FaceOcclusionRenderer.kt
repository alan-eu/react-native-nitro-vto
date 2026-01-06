package com.margelo.nitro.nitrovto

import android.content.Context
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
 * Face mesh renders first (priority 0), writes depth, then camera background
 * overwrites color (ignoring depth), then glasses render with depth test.
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

    // Back clipping planes (split left/right for better occlusion based on head rotation)
    private var backPlaneLeftVertexBuffer: VertexBuffer? = null
    private var backPlaneRightVertexBuffer: VertexBuffer? = null
    private var backPlaneIndexBuffer: IndexBuffer? = null  // Shared between both planes
    @Entity private var backPlaneLeftEntity: Int = 0
    @Entity private var backPlaneRightEntity: Int = 0
    private var backPlaneLeftInScene = false
    private var backPlaneRightInScene = false

    /** Whether the left back plane is currently visible (based on head yaw) */
    val isLeftBackPlaneVisible: Boolean get() = backPlaneLeftInScene

    /** Whether the right back plane is currently visible (based on head yaw) */
    val isRightBackPlaneVisible: Boolean get() = backPlaneRightInScene

    // Reusable arrays to avoid per-frame allocations
    private val vertexData = FloatArray(VERTEX_COUNT * 3)
    private val tempMatrix16 = FloatArray(16)
    private val backPlaneMatrix16 = FloatArray(16)

    // Occlusion settings (both enabled by default)
    private var faceMeshEnabled = true
    private var backPlaneEnabled = true

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
     * Set face mesh occlusion enabled.
     */
    fun setFaceMeshOcclusion(enabled: Boolean) {
        // If face mesh is being disabled, remove from scene
        if (faceMeshEnabled && !enabled && entityInScene) {
            scene.removeEntity(faceMeshEntity)
            entityInScene = false
        }

        faceMeshEnabled = enabled
        Log.d(TAG, "Face mesh occlusion updated: $enabled")
    }

    /**
     * Set back plane occlusion enabled.
     */
    fun setBackPlaneOcclusion(enabled: Boolean) {
        // If back planes are being disabled, remove from scene
        if (backPlaneEnabled && !enabled) {
            if (backPlaneLeftInScene) {
                scene.removeEntity(backPlaneLeftEntity)
                backPlaneLeftInScene = false
            }
            if (backPlaneRightInScene) {
                scene.removeEntity(backPlaneRightEntity)
                backPlaneRightInScene = false
            }
        }

        backPlaneEnabled = enabled
        Log.d(TAG, "Back plane occlusion updated: $enabled")
    }

    /**
     * Create back clipping planes (split left/right) to occlude glasses behind the head.
     */
    private fun createBackPlane() {
        val planeSizeX = 0.12f  // 12cm half-width for each plane
        val planeSizeY = 0.08f  // 8cm half-height (16cm total)
        val gap = 0.01f  // Small gap between planes at center

        // Left back plane (user's left side, camera's right side)
        // X range: -planeSizeX to -gap
        val leftVertices = floatArrayOf(
            -planeSizeX, -planeSizeY, 0f,  // bottom-left
            -gap,        -planeSizeY, 0f,  // bottom-right
            -planeSizeX,  planeSizeY, 0f,  // top-left
            -gap,         planeSizeY, 0f   // top-right
        )

        // Right back plane (user's right side, camera's left side)
        // X range: gap to planeSizeX
        val rightVertices = floatArrayOf(
            gap,        -planeSizeY, 0f,  // bottom-left
            planeSizeX, -planeSizeY, 0f,  // bottom-right
            gap,         planeSizeY, 0f,  // top-left
            planeSizeX,  planeSizeY, 0f   // top-right
        )

        // Create vertex buffers for each plane
        backPlaneLeftVertexBuffer = VertexBuffer.Builder()
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
        backPlaneLeftVertexBuffer!!.setBufferAt(engine, 0, MatrixUtils.createFloatBuffer(leftVertices))

        backPlaneRightVertexBuffer = VertexBuffer.Builder()
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
        backPlaneRightVertexBuffer!!.setBufferAt(engine, 0, MatrixUtils.createFloatBuffer(rightVertices))

        // Shared index buffer (same topology for both planes)
        val indices = shortArrayOf(0, 1, 2, 2, 1, 3)
        backPlaneIndexBuffer = IndexBuffer.Builder()
            .indexCount(6)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        backPlaneIndexBuffer!!.setBuffer(engine, MatrixUtils.createShortBuffer(indices))

        // Create entities
        backPlaneLeftEntity = EntityManager.get().create()
        backPlaneRightEntity = EntityManager.get().create()

        val boundingBox = Box(0f, 0f, 0f, planeSizeX, planeSizeY, 0.1f)

        // Build left back plane renderable
        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                backPlaneLeftVertexBuffer!!,
                backPlaneIndexBuffer!!,
                0,
                6
            )
            .material(0, occlusionMaterialInstance)
            .boundingBox(boundingBox)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(0)
            .build(engine, backPlaneLeftEntity)

        // Build right back plane renderable
        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                backPlaneRightVertexBuffer!!,
                backPlaneIndexBuffer!!,
                0,
                6
            )
            .material(0, occlusionMaterialInstance)
            .boundingBox(boundingBox)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(0)
            .build(engine, backPlaneRightEntity)
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

        // Copy vertices directly (keep in face-local coordinates)
        for (i in 0 until VERTEX_COUNT) {
            vertexData[i * 3] = meshVertices.get(i * 3)
            vertexData[i * 3 + 1] = meshVertices.get(i * 3 + 1)
            vertexData[i * 3 + 2] = meshVertices.get(i * 3 + 2)
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

        // Create renderable if entity not in scene yet (only if face mesh is enabled)
        if (!entityInScene && faceMeshEnabled) {
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
                .priority(0)  // Render FIRST to write depth
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

        // Apply face pose transform to entity (transforms local vertices to world space)
        face.centerPose.toMatrix(tempMatrix16, 0)
        val faceInstance = engine.transformManager.getInstance(faceMeshEntity)
        engine.transformManager.setTransform(faceInstance, tempMatrix16)

        // Position back planes behind the face
        val zOffset = minZ + 0.03f  // 3cm behind the furthest face point
        // Copy face transform and add offset along local Z axis
        tempMatrix16.copyInto(backPlaneMatrix16)
        // Apply local Z offset (multiply by rotation part of matrix)
        val offsetX = backPlaneMatrix16[8] * zOffset   // column 2, row 0
        val offsetY = backPlaneMatrix16[9] * zOffset   // column 2, row 1
        val offsetZ = backPlaneMatrix16[10] * zOffset  // column 2, row 2
        backPlaneMatrix16[12] += offsetX  // translation X
        backPlaneMatrix16[13] += offsetY  // translation Y
        backPlaneMatrix16[14] += offsetZ  // translation Z

        // Position both back planes with the same transform
        val backPlaneLeftInstance = engine.transformManager.getInstance(backPlaneLeftEntity)
        val backPlaneRightInstance = engine.transformManager.getInstance(backPlaneRightEntity)
        engine.transformManager.setTransform(backPlaneLeftInstance, backPlaneMatrix16)
        engine.transformManager.setTransform(backPlaneRightInstance, backPlaneMatrix16)

        // Extract yaw (Y-axis rotation) from face transform to determine head rotation
        // For column-major matrix: yaw = atan2(m[8], m[0]) which is atan2(m[2][0], m[0][0])
        // Positive yaw = head turning left (user's perspective), negative = turning right
        val yaw = kotlin.math.atan2(tempMatrix16[8], tempMatrix16[0])

        // Threshold for when to hide a back plane (~7 degrees)
        val yawThreshold = 0.12f  // ~7 degrees in radians

        // Determine which back planes should be visible based on head rotation
        // When turning right (negative yaw): left temple visible, hide left back plane
        // When turning left (positive yaw): right temple visible, hide right back plane
        val showLeftBackPlane = backPlaneEnabled && (yaw < yawThreshold)
        val showRightBackPlane = backPlaneEnabled && (yaw > -yawThreshold)

        // Update left back plane visibility
        if (showLeftBackPlane && !backPlaneLeftInScene) {
            scene.addEntity(backPlaneLeftEntity)
            backPlaneLeftInScene = true
        } else if (!showLeftBackPlane && backPlaneLeftInScene) {
            scene.removeEntity(backPlaneLeftEntity)
            backPlaneLeftInScene = false
        }

        // Update right back plane visibility
        if (showRightBackPlane && !backPlaneRightInScene) {
            scene.addEntity(backPlaneRightEntity)
            backPlaneRightInScene = true
        } else if (!showRightBackPlane && backPlaneRightInScene) {
            scene.removeEntity(backPlaneRightEntity)
            backPlaneRightInScene = false
        }
    }

    /**
     * Hide face mesh and back planes (remove from scene).
     */
    fun hide() {
        if (entityInScene) {
            scene.removeEntity(faceMeshEntity)
            entityInScene = false
        }
        if (backPlaneLeftInScene) {
            scene.removeEntity(backPlaneLeftEntity)
            backPlaneLeftInScene = false
        }
        if (backPlaneRightInScene) {
            scene.removeEntity(backPlaneRightEntity)
            backPlaneRightInScene = false
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        if (entityInScene) {
            scene.removeEntity(faceMeshEntity)
        }
        if (backPlaneLeftInScene) {
            scene.removeEntity(backPlaneLeftEntity)
        }
        if (backPlaneRightInScene) {
            scene.removeEntity(backPlaneRightEntity)
        }
        EntityManager.get().destroy(faceMeshEntity)
        EntityManager.get().destroy(backPlaneLeftEntity)
        EntityManager.get().destroy(backPlaneRightEntity)

        vertexBuffer?.let { engine.destroyVertexBuffer(it) }
        indexBuffer?.let { engine.destroyIndexBuffer(it) }
        backPlaneLeftVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        backPlaneRightVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        backPlaneIndexBuffer?.let { engine.destroyIndexBuffer(it) }
        engine.destroyMaterialInstance(occlusionMaterialInstance)
        engine.destroyMaterial(occlusionMaterial)
    }
}
