package com.example.glassesvto

import android.opengl.Matrix
import com.google.ar.core.AugmentedFace
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.sqrt

/**
 * Utility functions for matrix operations and buffer creation.
 */
object MatrixUtils {

    /**
     * Convert quaternion to 4x4 rotation matrix (column-major).
     */
    fun quaternionToMatrix(q: FloatArray): FloatArray {
        val qx = q[0]
        val qy = q[1]
        val qz = q[2]
        val qw = q[3]
        return floatArrayOf(
            1 - 2*qy*qy - 2*qz*qz,  2*qx*qy + 2*qz*qw,      2*qx*qz - 2*qy*qw,      0f,
            2*qx*qy - 2*qz*qw,      1 - 2*qx*qx - 2*qz*qz,  2*qy*qz + 2*qx*qw,      0f,
            2*qx*qz + 2*qy*qw,      2*qy*qz - 2*qx*qw,      1 - 2*qx*qx - 2*qy*qy,  0f,
            0f,                     0f,                     0f,                     1f
        )
    }

    /**
     * Calculate 3D distance between two 3D points.
     */
    fun distance3d(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Project a world position (FloatArray) to NDC coordinates.
     */
    fun projectToNdc(worldPos: FloatArray, viewMatrix: FloatArray, projMatrix: FloatArray, tempVec4: FloatArray): FloatArray {
        tempVec4[0] = worldPos[0]
        tempVec4[1] = worldPos[1]
        tempVec4[2] = worldPos[2]
        tempVec4[3] = 1f
        return projectVec4ToNdc(tempVec4, viewMatrix, projMatrix)
    }

    private fun projectVec4ToNdc(vec4: FloatArray, viewMatrix: FloatArray, projMatrix: FloatArray): FloatArray {
        val viewPos = FloatArray(4)
        val clipPos = FloatArray(4)
        Matrix.multiplyMV(viewPos, 0, viewMatrix, 0, vec4, 0)
        Matrix.multiplyMV(clipPos, 0, projMatrix, 0, viewPos, 0)
        return floatArrayOf(clipPos[0] / clipPos[3], clipPos[1] / clipPos[3])
    }

    /**
     * Transform a local position to world coordinates using a pose matrix.
     */
    fun transformToWorld(
        localX: Float, localY: Float, localZ: Float,
        poseMatrix: FloatArray,
        tempVec4: FloatArray
    ): FloatArray {
        tempVec4[0] = localX
        tempVec4[1] = localY
        tempVec4[2] = localZ
        tempVec4[3] = 1f
        val worldPos = FloatArray(4)
        Matrix.multiplyMV(worldPos, 0, poseMatrix, 0, tempVec4, 0)
        return worldPos
    }

    /**
     * Create a hide matrix (translates far on Z axis).
     */
    fun createHideMatrix(): FloatArray {
        val hideMatrix = FloatArray(16)
        Matrix.setIdentityM(hideMatrix, 0)
        Matrix.translateM(hideMatrix, 0, 0f, 0f, -1000f)
        return hideMatrix
    }

    /**
     * Create a direct FloatBuffer from a float array.
     */
    fun createFloatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .apply { flip() }

    /**
     * Create a direct ShortBuffer from a short array.
     */
    fun createShortBuffer(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(data)
            .apply { flip() }

    /**
     * Form the ARCore canonical_face_mesh get x,y,z position of a given vertice.
     * See https://github.com/google-ar/arcore-android-sdk/blob/main/assets/canonical_face_mesh.fbx
     */
    fun getPositionForVertice(index: Int, face: AugmentedFace): FloatArray {
        val meshBuffer = face.meshVertices

        val x = meshBuffer.get(index * 3)
        val y = meshBuffer.get(index * 3 + 1)
        val z = meshBuffer.get(index * 3 + 2)

        return floatArrayOf(x, y, z)
    }

}
