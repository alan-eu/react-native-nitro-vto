# Static IBL + per-frame directional light, no IBL-scalar sweep

## Context and Problem Statement

ARKit and ARCore expose face-tracking light estimates that aren't
equivalent: ARKit's `ambientIntensity` (lumens, scene-tracking) vs
ARCore's `pixelIntensity` (post-AE pixel luminance, ~0.5 always).
Feeding both into the same scalar IBL `intensity()` sweep produced
~15-20% brighter specular shine on Android in the same room.

## Considered Options

* Static IBL on both platforms; drive a per-frame Filament directional
  light from each SDK (ARSceneView pattern).
* Drive lighting from per-frame SDK SH on both platforms — blocked on
  Android: `ENVIRONMENTAL_HDR` is rear-camera only.
* Renormalize ARCore's `pixelIntensity` to match ARKit's range.
* Drop dynamic light estimation entirely; ship a static IBL only.

## Decision Outcome

Chosen option: **static IBL + per-frame directional ("sun") light**.

The IBL stays at one neutral intensity for the whole session. Specular
reflections come from it and are platform-equivalent. A separate
Filament directional light tracks the AR signal:

- iOS reads `ARDirectionalLightEstimate.primaryLightDirection` and
  `primaryLightIntensity` (lumens; normalized vs 1000 = neutral).
- Android reads `LightEstimate.getColorCorrection(...)` (4-tuple of
  RGB color correction + intensity), normalizes RGB by max channel,
  converts sRGB → linear, applies sceneview's empirical 1.8× factor.
  Direction is fixed (camera-forward) since `AMBIENT_INTENSITY` doesn't
  expose one.

Both platforms map their AR signal into Filament lux units against the
same `kDirectionalIntensityMax` ceiling, so peak shine is platform-
equivalent.

### Consequences

* Good: specular highlights match across platforms (driven by static
  IBL); diffuse + sun shading still tracks the room.
* Good: cross-platform tuning lives in a single pair of constants.
* Good: iOS gets a real light direction; Android falls back to fixed.
  The asymmetry is in *direction*, not *intensity*, so the
  "shinier on Android" bug is solved.
* Bad: Android still can't track the room's light direction —
  `AMBIENT_INTENSITY` is the only mode available on the front camera.
* Bad: empirical calibration constants (1.8 on Android, the lux
  ceiling) are tuned by eye, no automated regression catches drift.
