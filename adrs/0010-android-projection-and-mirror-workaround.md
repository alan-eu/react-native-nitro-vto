# Android: derive FOV from ARCore + mirror X per-entity to preserve refraction

## Context and Problem Statement

ARCore's projection matrix for the front camera bakes the camera
mirror as `m[0] < 0`. On the Filament GL backend, calling
`setCustomProjection` with that matrix silently breaks the
screen-space refraction pass — lenses sample black instead of the
framebuffer. We need refraction to keep working (ADR 0009) without
flipping the camera or losing tracking accuracy.

## Considered Options

* Pass ARCore's projection directly via `setCustomProjection` —
  refraction breaks.
* Derive the vertical FOV from ARCore's matrix and use Filament's
  `setProjection(fov, aspect, near, far)`; mirror X on every
  ARCore-tracked transform (face mesh, glasses, debug overlay) at the
  *model-matrix* level.
* Drop refraction on Android.

## Decision Outcome

Chosen option: **derive FOV + per-entity X mirror**. Concretely:
- `VTORenderer.kt` reads `frame.camera.getProjectionMatrix(...)`,
  extracts vertical FOV from `m[5]`, calls `setProjection(fovY,
  aspect, near, far, VERTICAL)`.
- Every ARCore-tracked transform negates row 0 of its 4×4 model
  matrix before binding (`m[0]/m[4]/m[8]/m[12] *= -1`). This keeps
  the *view* matrix determinant-positive — Filament's refraction
  pass relies on that invariant.

### Consequences

* Good: refraction works on the GL backend.
* Good: iOS Metal needs none of this; iOS uses
  `setCustomProjection` directly.
* Bad: every new ARCore-tracked entity has to remember the mirror —
  silent breakage if forgotten.
* Bad: the workaround is non-obvious; without ADR 0009 + this one
  the next contributor will "fix" it back to direct projection and
  re-break refraction.
