#ifndef LightingConstants_h
#define LightingConstants_h

// Tuning constants for the IBL (image-based lighting) intensity sweep
// driven by ARKit/ARCore light estimation. Mirrored on Android in
// LightingConstants.kt.

// Floor intensity (lux) applied even when the scene is dark.
static const float kBaseIntensity = 20000.0f;

// Range added on top of kBaseIntensity scaled by normalized pixel
// intensity in [0, 1]. So the final intensity sweeps from kBaseIntensity
// to (kBaseIntensity + kIntensityFactor) as the scene brightens.
static const float kIntensityFactor = 40000.0f;

#endif /* LightingConstants_h */
