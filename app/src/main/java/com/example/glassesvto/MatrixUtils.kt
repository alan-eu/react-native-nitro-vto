package com.example.glassesvto

import android.opengl.Matrix
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Pose
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

    /**
     * Compute a rotation matrix for glasses placement based on eye region vertices.
     * Builds a coordinate frame from left eye, right eye, and forehead vertices.
     *
     * Assumes GLB model convention:
     * - X axis: along temples (right to left)
     * - Y axis: up
     * - Z axis: forward (out from lenses toward viewer)
     */
    fun getGlassesRotationMatrix(face: AugmentedFace): FloatArray {
        // Get key points for building coordinate frame
        val leftEye = getPositionForVertice(374, face)
        val rightEye = getPositionForVertice(145, face)
        val forehead = getPositionForVertice(10, face)  // center forehead

        // Eye center
        val eyeCenter = floatArrayOf(
            (leftEye[0] + rightEye[0]) / 2f,
            (leftEye[1] + rightEye[1]) / 2f,
            (leftEye[2] + rightEye[2]) / 2f
        )

        // X axis: right eye to left eye (along temples)
        val xAxis = floatArrayOf(
            leftEye[0] - rightEye[0],
            leftEye[1] - rightEye[1],
            leftEye[2] - rightEye[2]
        )
        normalize(xAxis)

        // Temp Y axis: from eye center toward forehead (pointing up)
        val tempYAxis = floatArrayOf(
            forehead[0] - eyeCenter[0],
            forehead[1] - eyeCenter[1],
            forehead[2] - eyeCenter[2]
        )
        normalize(tempYAxis)

        // Z axis: cross product of X and tempY (pointing out from face)
        val zAxis = cross(xAxis, tempYAxis)
        normalize(zAxis)

        // Recompute Y to ensure orthogonality
        val yAxis = cross(zAxis, xAxis)
        normalize(yAxis)

        // Build rotation matrix (column-major order for OpenGL)
        // Negate all axes for mirrored front camera
        return floatArrayOf(
            -xAxis[0], -xAxis[1], -xAxis[2], 0f,
            yAxis[0], yAxis[1], yAxis[2], 0f,
            zAxis[0], zAxis[1], zAxis[2], 0f,
            0f, 0f, 0f, 1f
        )
    }

    private fun normalize(v: FloatArray) {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len > 0.0001f) {
            v[0] /= len
            v[1] /= len
            v[2] /= len
        }
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )
    }
}
