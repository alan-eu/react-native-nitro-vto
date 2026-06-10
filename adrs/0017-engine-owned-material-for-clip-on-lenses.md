# Swap clip-on lenses to an engine-owned material instead of the glb's

## Context and Problem Statement

Clip-on / solar (tinted sunglass) frames rendered with a bright "chrome"
mirror across the lenses instead of a tinted, see-through tint. The lens
material lives in the glb (per ADR 0009, `KHR_materials_transmission`), and
the chrome is the static IBL (ADR 0011) reflecting off the smooth lens.

Investigation (comparing chrome vs. correctly-rendering clip-ons in the
catalog) showed the clip-on lens materials are **inconsistently authored**:
some are `alphaMode: BLEND` with full specular weight, some near-white
baseColor, some matte — the chrome ones reflect the IBL strongly. The
properties that distinguish them (`alphaMode`, `specularFactor`) are **not
settable at runtime** on the gltfio ubershader (only `baseColorFactor`,
`metallicFactor`, `roughnessFactor`, `specularColorFactor` are), and the
IBL specular's split-sum bias term reflects even at F0 = 0 — so we cannot
tame it through the ubershader. The frame must know which models are
clip-ons; the glb doesn't say, but the product catalog does, surfaced as
the `isClipOn` view prop.

## Considered Options

* Tune the glb lens material's exposed ubershader params at runtime
  (reflectance/specular/baseColor).
* Lower the global IBL intensity until the reflection stops reading as
  chrome.
* Edit / post-process the glbs to normalize the lens material at source.
* When `isClipOn`, swap the lens primitives' material to an engine-owned
  material (`clipon_lens.mat`) we fully control.

## Decision Outcome

Chosen option: **engine-owned material swap, gated on the `isClipOn`
prop**. `clipon_lens.mat` is a lit, screen-space-refraction material with a
baked `roughness` and an exposed `reflectance` uniform, so we control the
reflection (the knob the ubershader withheld) while keeping the realistic
refraction see-through. Its `tint` uniform is the glb's authored lens
baseColor — read per platform (iOS `MaterialInstance::getParameter`,
Android by parsing the glb JSON chunk, since its Filament binding has no
`getParameter`) and brightness-capped (hue-preserving) so near-white lenses
darken to a tint. Applied via `setMaterialInstanceAt` on `LensL/R_geometry`;
non-clip-on lenses keep the glb material. Tuning lives in
`LensConstants.{h,kt}` (ADR 0008).

The other options were rejected: runtime ubershader tuning can't reach the
properties that cause the chrome; lowering global IBL dims every model and
trades one bad look for another; editing the glbs touches external CDN
assets (see the "never edit the glb" rule) and, more importantly, hits the
same physics — the glossy lens still reflects the IBL.

### Consequences

* Good: chrome is gone with the see-through preserved, and the look is
  deterministic — being engine-owned and reflection-controlled, it does not
  depend on IBL/camera geometry (no sim-vs-device divergence).
* Good: per-SKU tint is preserved from the glb; no asset edits.
* Good: `reflectance` / tint-cap are tunable constants, not re-authored assets.
* Bad: clip-on lenses lose the glb's own material (textures, exact authored
  look); they render with our uniform-tint glass instead.
* Bad: adds an `isClipOn` prop the consumer must set correctly per SKU, and a
  glb-parsing path on Android to recover the tint.
