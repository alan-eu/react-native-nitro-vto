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

/**
 * Debug renderer for visualizing face mesh and back planes.
 * Renders colored overlays: red for face mesh, green for left plane, blue for right plane.
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
    private lateinit var backPlaneLeftMaterialInstance: MaterialInstance
    private lateinit var backPlaneRightMaterialInstance: MaterialInstance

    // Face mesh
    private var faceMeshVertexBuffer: VertexBuffer? = null
    private var faceMeshIndexBuffer: IndexBuffer? = null
    @Entity private var faceMeshEntity: Int = 0
    private var faceMeshInScene = false
    private var indexBufferInitialized = false

    // Back planes
    private var backPlaneLeftVertexBuffer: VertexBuffer? = null
    private var backPlaneRightVertexBuffer: VertexBuffer? = null
    private var backPlaneIndexBuffer: IndexBuffer? = null
    @Entity private var backPlaneLeftEntity: Int = 0
    @Entity private var backPlaneRightEntity: Int = 0
    private var backPlaneLeftInScene = false
    private var backPlaneRightInScene = false

    // State
    private var isEnabled = false

    // Reusable arrays
    private val vertexData = FloatArray(VERTEX_COUNT * 3)
    private val tempMatrix16 = FloatArray(16)
    private val backPlaneMatrix16 = FloatArray(16)

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

            // Green for left back plane (uses plane material)
            backPlaneLeftMaterialInstance = debugPlaneMaterial.createInstance()
            backPlaneLeftMaterialInstance.setParameter("debugColor", 0.0f, 1.0f, 0.0f, 0.4f)

            // Blue for right back plane (uses plane material)
            backPlaneRightMaterialInstance = debugPlaneMaterial.createInstance()
            backPlaneRightMaterialInstance.setParameter("debugColor", 0.0f, 0.0f, 1.0f, 0.4f)

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

        // Create back planes
        createBackPlanes()

        Log.d(TAG, "Debug renderer setup complete")
    }

    private fun createBackPlanes() {
        val planeSizeX = 0.12f
        val planeSizeY = 0.08f
        val gap = 0.01f

        // Left back plane vertices
        val leftVertices = floatArrayOf(
            -planeSizeX, -planeSizeY, 0f,
            -gap,        -planeSizeY, 0f,
            -planeSizeX,  planeSizeY, 0f,
            -gap,         planeSizeY, 0f
        )

        // Right back plane vertices
        val rightVertices = floatArrayOf(
            gap,        -planeSizeY, 0f,
            planeSizeX, -planeSizeY, 0f,
            gap,         planeSizeY, 0f,
            planeSizeX,  planeSizeY, 0f
        )

        // Create vertex buffers
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

        // Shared index buffer
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

        // Build left back plane renderable (priority 8, renders after face mesh, gets occluded)
        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                backPlaneLeftVertexBuffer!!,
                backPlaneIndexBuffer!!,
                0,
                6
            )
            .material(0, backPlaneLeftMaterialInstance)
            .boundingBox(boundingBox)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(8)
            .build(engine, backPlaneLeftEntity)

        // Build right back plane renderable (priority 8, renders after face mesh, gets occluded)
        RenderableManager.Builder(1)
            .geometry(
                0,
                RenderableManager.PrimitiveType.TRIANGLES,
                backPlaneRightVertexBuffer!!,
                backPlaneIndexBuffer!!,
                0,
                6
            )
            .material(0, backPlaneRightMaterialInstance)
            .boundingBox(boundingBox)
            .culling(false)
            .receiveShadows(false)
            .castShadows(false)
            .priority(8)
            .build(engine, backPlaneRightEntity)
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
    fun update(face: AugmentedFace, showLeftBackPlane: Boolean, showRightBackPlane: Boolean) {
        if (!isEnabled || faceMeshVertexBuffer == null || faceMeshIndexBuffer == null) return

        val meshVertices = face.meshVertices
        val meshIndices = face.meshTriangleIndices

        // Validate mesh data
        if (meshVertices.remaining() < VERTEX_COUNT * 3) return
        if (meshIndices.remaining() < INDEX_COUNT) return

        // Copy vertices
        for (i in 0 until VERTEX_COUNT) {
            vertexData[i * 3] = meshVertices.get(i * 3)
            vertexData[i * 3 + 1] = meshVertices.get(i * 3 + 1)
            vertexData[i * 3 + 2] = meshVertices.get(i * 3 + 2)
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

        // Update face mesh transform
        face.centerPose.toMatrix(tempMatrix16, 0)
        val faceInstance = engine.transformManager.getInstance(faceMeshEntity)
        engine.transformManager.setTransform(faceInstance, tempMatrix16)

        // Position back planes behind the face
        val zOffset = minZ + 0.03f
        tempMatrix16.copyInto(backPlaneMatrix16)
        val offsetX = backPlaneMatrix16[8] * zOffset
        val offsetY = backPlaneMatrix16[9] * zOffset
        val offsetZ = backPlaneMatrix16[10] * zOffset
        backPlaneMatrix16[12] += offsetX
        backPlaneMatrix16[13] += offsetY
        backPlaneMatrix16[14] += offsetZ

        val backPlaneLeftInstance = engine.transformManager.getInstance(backPlaneLeftEntity)
        val backPlaneRightInstance = engine.transformManager.getInstance(backPlaneRightEntity)
        engine.transformManager.setTransform(backPlaneLeftInstance, backPlaneMatrix16)
        engine.transformManager.setTransform(backPlaneRightInstance, backPlaneMatrix16)

        // Update left back plane visibility (matches occlusion renderer)
        if (showLeftBackPlane && !backPlaneLeftInScene) {
            scene.addEntity(backPlaneLeftEntity)
            backPlaneLeftInScene = true
        } else if (!showLeftBackPlane && backPlaneLeftInScene) {
            scene.removeEntity(backPlaneLeftEntity)
            backPlaneLeftInScene = false
        }

        // Update right back plane visibility based on yaw
        if (showRightBackPlane && !backPlaneRightInScene) {
            scene.addEntity(backPlaneRightEntity)
            backPlaneRightInScene = true
        } else if (!showRightBackPlane && backPlaneRightInScene) {
            scene.removeEntity(backPlaneRightEntity)
            backPlaneRightInScene = false
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
        hide()

        EntityManager.get().destroy(faceMeshEntity)
        EntityManager.get().destroy(backPlaneLeftEntity)
        EntityManager.get().destroy(backPlaneRightEntity)

        faceMeshVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        faceMeshIndexBuffer?.let { engine.destroyIndexBuffer(it) }
        backPlaneLeftVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        backPlaneRightVertexBuffer?.let { engine.destroyVertexBuffer(it) }
        backPlaneIndexBuffer?.let { engine.destroyIndexBuffer(it) }

        engine.destroyMaterialInstance(faceMeshMaterialInstance)
        engine.destroyMaterialInstance(backPlaneLeftMaterialInstance)
        engine.destroyMaterialInstance(backPlaneRightMaterialInstance)
        engine.destroyMaterial(debugFaceMaterial)
        engine.destroyMaterial(debugPlaneMaterial)
    }
}
