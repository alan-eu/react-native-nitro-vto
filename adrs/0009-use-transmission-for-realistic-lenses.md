# Use `KHR_materials_transmission` for realistic glass lenses

## Context and Problem Statement

Glasses lenses are transparent and refract the world behind them. The
champagne / tinted-glass look on the sample model `878082.glb` depends
on actual refraction, not just alpha. We need to pick a transparency
model for lens materials.

## Considered Options

* `KHR_materials_transmission` — Filament implements this as a screen-
  space refraction pass that re-samples the framebuffer.
* Standard alpha blending with a tinted color.
* Opaque material with a baked tinted texture.

## Decision Outcome

Chosen option: **`KHR_materials_transmission`**. Lenses sample the
framebuffer at the refracted UV, giving real distortion of the face
behind the lens — visually distinguishable from any alpha-blend
fake.

### Consequences

* Good: lenses look like glass, not a colored film.
* Good: authored entirely in the `.glb` material — no engine-side
  shader work.
* Bad: tightly couples us to Filament's screen-space refraction
  pass; broken refraction means broken lenses.
* Bad: drives several downstream constraints — see ADR 0010 for the
  Android projection / mirror workaround that exists only to keep
  refraction working.
