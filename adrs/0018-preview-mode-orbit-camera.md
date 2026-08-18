# Preview mode: show the glasses without AR, on an orbit camera

## Context and Problem Statement

The engine only knew how to render glasses on a tracked face. Where face
tracking is unavailable — the iOS simulator, an Android emulator, a device
without the required camera, or a user who has not granted camera permission —
there was nothing to show. iOS had a dev-only "harness" (a procedural test
pattern bound as the camera feed plus the glasses parked at a fixed pose) so
render order could be inspected in the simulator; Android had no equivalent at
all, because `VTORenderer.doFrame()` returns immediately without an ARCore
session.

The product need is the same shape as that harness but user-facing: show the
frame on its own, on a background the app chooses, and let the user turn it
around and look closer. That makes the component usable in a catalog/detail
screen, before (or instead of) asking for the camera.

## Considered Options

* Keep the harness dev-only and let apps render their own 3D preview.
* Clear the framebuffer to the app's color and hide the camera quad.
* A second background material (flat color) swapped onto the same fullscreen
  quad, with an orbit camera driven by native gesture recognizers.
* Rotate/scale the glasses model itself instead of moving the camera.

## Decision Outcome

Chosen option: **flat-color material on the existing background quad, plus an
orbit camera**, exposed as `mode="preview"` + `previewBackgroundColor`.

The background is a quad, not a clear color, because the view runs a Filmic
tonemap and an sRGB encoder (ADR 0012 / the camera-feed round-trip): a clear
color is written into the HDR target and comes out the other side as a
different color. `background_solid.mat` pre-inverts that chain the same way
`camera_background` does, so the pixel on screen is exactly the sRGB value the
app asked for. Reusing the quad also sidesteps Android's blocker — its camera
material samples a `samplerExternal` (OES) that a plain 2D texture cannot be
bound to, whereas a material with no sampler at all binds anywhere.

The camera orbits and the model stays put, because the static IBL (ADR 0011) is
world-fixed: spinning the model would drag its reflections across the frame,
and scaling it to zoom would break the real-world metric size the whole
pipeline assumes. Orbit state (azimuth, elevation clamped off the poles,
distance clamped to a framing computed from the model's bounding sphere) lives
in `PreviewCameraController`, mirrored per platform.

Gestures are native (`UIPanGestureRecognizer`/`UIPinchGestureRecognizer`,
`MotionEvent` + `ScaleGestureDetector`) rather than props driven from JS: they
run at display-link rate with no bridge round-trip, and consumers need no
gesture library.

Preview is also the automatic fallback when face tracking is unavailable, which
replaces the old harness path: a caller-chosen background beats a test pattern
for inspecting render order, and it is one code path on both platforms instead
of a dev-only one on iOS.

### Consequences

* Good, the component renders something useful without a camera, a camera
  permission, ARCore, or a TrueDepth camera — including on emulators, where the
  engine could not be exercised at all before.
* Good, the simulator harness is no longer a separate dev-only path that can rot
  unnoticed; it is the same code the product ships.
* Good, the background color is exact rather than approximately right, so app
  and engine backgrounds can sit flush.
* Bad, one more material to compile and ship (`background_solid.filamat`), and
  a `mode` prop consumers have to set correctly.
* Bad, orbit tuning (sensitivity, framing margin, zoom limits) is per-platform
  constants that must be kept in sync — `PreviewConstants.h` /
  `PreviewConstants.kt`, per ADR 0008.
