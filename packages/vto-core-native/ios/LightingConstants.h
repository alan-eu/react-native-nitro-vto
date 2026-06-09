#ifndef LightingConstants_h
#define LightingConstants_h

// Static IBL intensity (lux). Set once at setup, never modulated. The
// scalar IBL sweep we used to do was structurally asymmetric across
// platforms (ARKit lumens vs ARCore pixelIntensity) — see ADR 0011.
// Mirrored on Android in LightingConstants.kt.
static const float kStaticIblIntensity = 30000.0f;

// Directional ("sun") light driven per-frame from each AR SDK's light
// estimate. Intensity is in lux; both platforms calibrate to a common
// peak (kDirectionalIntensityMax) so specular highlights match.

// Peak intensity (lux) the directional light reaches when the SDK
// reports a fully-lit scene. Tunable.
static const float kDirectionalIntensityMax = 20000.0f;

// Empirical factor sceneview applies to ARCore's unit-less colorCorrection
// intensity to bring it into Filament's lux range. Source:
//   https://github.com/sceneview/sceneview/blob/main/arsceneview/src/main/java/io/github/sceneview/ar/light/LightEstimator.kt
// In our pipeline this multiplies a [0, 1] signal that's already further
// scaled by kDirectionalIntensityMax — see EnvironmentLightingRenderer.mm.
static const float kArcoreIntensityFactor = 1.8f;

// ARKit's ARDirectionalLightEstimate.primaryLightIntensity is in lumens
// where 1000 = "neutral indoor" per Apple's documentation. We normalize
// against that to get a [0, ~2] factor.
static const float kArkitNeutralIntensity = 1000.0f;

#endif /* LightingConstants_h */
