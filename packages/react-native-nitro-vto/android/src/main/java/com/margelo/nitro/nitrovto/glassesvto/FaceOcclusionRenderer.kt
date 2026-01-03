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

    // Reusable arrays to avoid per-frame allocations
    private val vertexData = FloatArray(VERTEX_COUNT * 3)
    private val tempMatrix16 = FloatArray(16)

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

        Log.d(TAG, "Face occlusion renderer setup complete")
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

        // Create renderable if entity not in scene yet
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
                .priority(0)  // Render FIRST to write depth
                .build(engine, faceMeshEntity)

            scene.addEntity(faceMeshEntity)
            entityInScene = true
            Log.d(TAG, "Face mesh entity added to scene")
        }

        // Apply face pose transform to entity (transforms local vertices to world space)
        face.centerPose.toMatrix(tempMatrix16, 0)
        val instance = engine.transformManager.getInstance(faceMeshEntity)
        engine.transformManager.setTransform(instance, tempMatrix16)
    }

    /**
     * Hide face mesh (remove from scene).
     */
    fun hide() {
        if (entityInScene) {
            scene.removeEntity(faceMeshEntity)
            entityInScene = false
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        if (entityInScene) {
            scene.removeEntity(faceMeshEntity)
        }
        EntityManager.get().destroy(faceMeshEntity)

        vertexBuffer?.let { engine.destroyVertexBuffer(it) }
        indexBuffer?.let { engine.destroyIndexBuffer(it) }
        engine.destroyMaterialInstance(occlusionMaterialInstance)
        engine.destroyMaterial(occlusionMaterial)
    }
}
