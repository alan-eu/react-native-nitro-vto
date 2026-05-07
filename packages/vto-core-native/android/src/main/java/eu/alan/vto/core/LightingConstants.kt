package eu.alan.vto.core

/**
 * Tuning constants for the IBL (image-based lighting) intensity sweep
 * driven by ARCore light estimation. Mirrored on iOS in
 * LightingConstants.h.
 */
internal object LightingConstants {
    /** Floor intensity (lux) applied even when the scene is dark. */
    const val BASE_INTENSITY = 30_000f

    /**
     * Range added on top of BASE_INTENSITY scaled by normalized pixel
     * intensity in [0, 1]. So the final intensity sweeps from
     * BASE_INTENSITY to (BASE_INTENSITY + INTENSITY_FACTOR) as the
     * scene brightens.
     */
    const val INTENSITY_FACTOR = 60_000f
}
