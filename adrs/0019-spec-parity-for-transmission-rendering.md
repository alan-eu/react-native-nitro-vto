# Neutral tone pipeline and spec-parity transmission rendering

## Context and Problem Statement

Translucent frames (`KHR_materials_transmission`, per ADR 0009) rendered
wrong on both platforms: milky/frosted, too dark, and dominated by whatever
sat behind them (the wearer's skin turned grey frames pink). The same glbs
render correctly, with zero per-material tweaks, in reference glTF viewers
(three-gltf-viewer / three.js).

Comparing the two pipelines found one legitimate engine difference and one
self-inflicted distortion:

* **The refraction source was distorted by our own tone pipeline.** The view
  used a Filmic tone mapper, and the camera-feed materials pre-inverted that
  curve so the directly-visible feed round-tripped unchanged. But screen-space
  refraction samples the color buffer — which therefore held the
  **inverse-Filmic-boosted** feed (~1.4× brighter than scene-linear in the
  skin range). Every transmissive frame showed too much of the face, reading
  "too transparent".

## Considered Options

* Keep Filmic + inverse-tonemapped feed, and compensate at the material
  level (cap/derive `transmissionFactor` from baked signals, lift
  `baseColorFactor`).
* Fix the bakes at source (re-author the catalog).
* Switch the view to a neutral (identity-in-the-mids) tone transform,
  write the feed as plain scene-linear, and render transmission as authored
  (spec parity).

## Decision Outcome

Chosen option: **neutral tone mapping + scene-linear feed + spec-pure
transmission**, matching what reference viewers do.

* `ColorGrading` uses `PBRNeutralToneMapper` (Khronos PBR Neutral, designed
  for commerce color fidelity) on both platforms (`VTORendererBridge.mm`,
  `VTORenderer.kt`). It is identity below its ~0.76 knee and rolls off only
  the top highlights: the camera feed (mostly below the knee) round-trips
  essentially unchanged through the sRGB decode/encode pair, the refraction
  pass samples an undistorted feed, and specular highlights compress
  gracefully instead of clipping — which is what lets the lighting
  (`LightingConstants`: IBL 45 000 lux, directional peak 25 000 lux) be
  pushed hard enough to give frames their shine after losing Filmic's
  mid-tone lift. (Pure `LinearToneMapper` was tried first: exact feed
  round-trip, but highlights clipped harshly and dark patterned acetates —
  tortoise — read flat and dim; a catalog-wide A/B confirmed PBR Neutral
  wins on every model.) Only feed-safe operators are viable here — a curved
  one (Filmic/ACES) would need the feed pre-inversion back and the
  refraction pass would resample the distorted result.
* `camera_background.ios.mat`, `camera_background.android.mat`,
  `background_solid.mat` drop their `inverseTonemap()` step (Android keeps
  its 0.90 feed-brightness match).
* No per-material remap remains: the glb materials render fully as
  authored.

Compensation stacks tried and rejected during calibration: capping
transmission globally (frosted crystal frames milky), deriving per-material
transmission from baked roughness/albedo signals ("frost model" — two texture
decodes and five constants to counteract a distortion we could simply
remove), lifting `baseColorFactor` (brightened the boosted feed further and
skewed metal F0), and a global roughness scale on frame materials —
motivated by Filament's refraction-blur LOD (`(2·log2(r) + offset)·0.86`)
being far more aggressive than three.js's (`log2(size)·r`) at the same
roughness, but an A/B with the scale on/off after the feed fix showed no
visible difference worth a constant: the "milky" read had been the
brightness-boosted feed being blurred, not the blur itself. Editing the
glbs is forbidden (see the "never edit the glb" rule); they are
demonstrably fine — reference viewers render them correctly as-is.

### Consequences

* Good: transmissive materials render as authored — same math as three.js
  (transmitted light × baseColor × (1−Fresnel), roughness-blurred). No
  per-material tuning constants remain.
* Good: the camera round-trip guarantee is simpler (pure sRGB decode/encode,
  no analytic curve inversion to keep in sync with the tone mapper).
* Trade-off: a translucent frame legitimately shows what's behind it —
  reference viewers only look "perfectly clean" because their background is
  neutral like a packshot's. Over a face, some skin shows through, as it
  does on the physical product.
* Watch: Filmic was originally chosen to tame IBL shine on metallic frames;
  PBR Neutral rolls highlights off more gently. If chrome-glare returns on
  metal SKUs, address it at the source (exposure/IBL calibration), not by
  re-adding curves the refraction pass will resample.
* Watch: feed pixels above the ~0.76-linear knee (near-white lamps/windows)
  are very slightly compressed on screen — imperceptible in practice, but it
  is the one deviation from an exact feed round-trip.
