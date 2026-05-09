package eu.alan.vto.core

/**
 * Lighting tuning constants. Mirrored on iOS in LightingConstants.h.
 *
 * The static IBL intensity stays constant across the session — the
 * scalar IBL sweep we used to do was structurally asymmetric across
 * platforms (ARKit lumens vs ARCore pixelIntensity) — see ADR 0011.
 *
 * The directional ("sun") light is driven per-frame from each AR
 * SDK's light estimate. Intensity is in lux; both platforms
 * calibrate to a common peak (DIRECTIONAL_INTENSITY_MAX) so
 * specular highlights match.
 */
internal object LightingConstants {
    /** Static IBL intensity (lux). */
    const val STATIC_IBL_INTENSITY = 60_000f

    /**
     * Peak intensity (lux) the directional light reaches when the SDK
     * reports a fully-lit scene. Tunable.
     */
    const val DIRECTIONAL_INTENSITY_MAX = 20_000f

    /**
     * Empirical factor sceneview applies to ARCore's unit-less
     * colorCorrection intensity to bring it into Filament's lux range.
     * Source:
     *   https://github.com/sceneview/sceneview/blob/main/arsceneview/src/main/java/io/github/sceneview/ar/light/LightEstimator.kt
     * In our pipeline this multiplies a [0, 1] signal that's already
     * further scaled by DIRECTIONAL_INTENSITY_MAX — see
     * EnvironmentLightingRenderer.kt.
     */
    const val ARCORE_INTENSITY_FACTOR = 1.8f

    /**
     * ARKit's ARDirectionalLightEstimate.primaryLightIntensity is in
     * lumens where 1000 = "neutral indoor" per Apple's documentation.
     * We normalize against that to get a [0, ~2] factor. Used on iOS
     * only.
     */
    const val ARKIT_NEUTRAL_INTENSITY = 1000f
}
