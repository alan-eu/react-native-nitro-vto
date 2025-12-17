package com.margelo.nitro.nitrovto

/**
 * Simple 1D Kalman filter for smoothing noisy measurements.
 *
 * @param processNoise How much we expect the value to change between measurements (Q).
 *                     Higher = more responsive, lower = smoother.
 * @param measurementNoise How noisy the measurements are (R).
 *                         Higher = trust measurements less, lower = trust more.
 * @param initialEstimate Starting value estimate.
 */
class KalmanFilter(
    private val processNoise: Float = 0.01f,
    private val measurementNoise: Float = 0.1f,
    initialEstimate: Float = 0f
) {
    private var estimate: Float = initialEstimate
    private var errorCovariance: Float = 1f

    /**
     * Update the filter with a new measurement and return the filtered estimate.
     */
    fun update(measurement: Float): Float {
        // Prediction step
        // estimate stays the same (assuming constant model)
        errorCovariance += processNoise

        // Update step
        val kalmanGain = errorCovariance / (errorCovariance + measurementNoise)
        estimate += kalmanGain * (measurement - estimate)
        errorCovariance *= (1 - kalmanGain)

        return estimate
    }

    /**
     * Reset the filter to a new initial state.
     */
    fun reset(initialEstimate: Float = 0f) {
        estimate = initialEstimate
        errorCovariance = 1f
    }

    /**
     * Get current estimate without updating.
     */
    fun getEstimate(): Float = estimate
}

/**
 * Kalman filter for 2D points (e.g., NDC coordinates).
 */
class KalmanFilter2D(
    processNoise: Float = 0.01f,
    measurementNoise: Float = 0.1f
) {
    private val filterX = KalmanFilter(processNoise, measurementNoise)
    private val filterY = KalmanFilter(processNoise, measurementNoise)

    fun update(x: Float, y: Float): FloatArray {
        return floatArrayOf(filterX.update(x), filterY.update(y))
    }

    fun reset() {
        filterX.reset()
        filterY.reset()
    }
}

/**
 * Kalman filter for 3D points (e.g., world coordinates).
 */
class KalmanFilter3D(
    processNoise: Float = 0.01f,
    measurementNoise: Float = 0.1f
) {
    private val filterX = KalmanFilter(processNoise, measurementNoise)
    private val filterY = KalmanFilter(processNoise, measurementNoise)
    private val filterZ = KalmanFilter(processNoise, measurementNoise)

    fun update(x: Float, y: Float, z: Float): FloatArray {
        return floatArrayOf(
            filterX.update(x),
            filterY.update(y),
            filterZ.update(z)
        )
    }

    fun update(pos: FloatArray): FloatArray {
        return update(pos[0], pos[1], pos[2])
    }

    fun reset() {
        filterX.reset()
        filterY.reset()
        filterZ.reset()
    }
}

/**
 * Kalman filter for quaternions (rotation smoothing).
 * Filters each component independently - works well for small rotational changes.
 */
class KalmanFilterQuaternion(
    processNoise: Float = 0.01f,
    measurementNoise: Float = 0.1f
) {
    private val filterX = KalmanFilter(processNoise, measurementNoise)
    private val filterY = KalmanFilter(processNoise, measurementNoise)
    private val filterZ = KalmanFilter(processNoise, measurementNoise)
    private val filterW = KalmanFilter(processNoise, measurementNoise, 1f)

    fun update(q: FloatArray): FloatArray {
        val filtered = floatArrayOf(
            filterX.update(q[0]),
            filterY.update(q[1]),
            filterZ.update(q[2]),
            filterW.update(q[3])
        )
        // Normalize to ensure valid quaternion
        val len = kotlin.math.sqrt(
            filtered[0] * filtered[0] +
            filtered[1] * filtered[1] +
            filtered[2] * filtered[2] +
            filtered[3] * filtered[3]
        )
        if (len > 0.0001f) {
            filtered[0] /= len
            filtered[1] /= len
            filtered[2] /= len
            filtered[3] /= len
        }
        return filtered
    }

    fun reset() {
        filterX.reset()
        filterY.reset()
        filterZ.reset()
        filterW.reset(1f)
    }
}
