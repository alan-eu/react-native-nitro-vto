#ifndef PreviewConstants_h
#define PreviewConstants_h

#include <math.h>

// Tuning constants for preview mode's orbit camera. Mirrored on Android in
// PreviewConstants.kt — keep the two in sync.
//
// Preview mode has no AR session: the glasses sit at the world origin on a
// solid background and the camera orbits them (drag) and dollies (pinch).
// Angles are radians, distances are meters unless stated otherwise.

// Vertical field of view of the preview camera.
static const double kPreviewFovDeg = 60.0;

// Opening pose: straight on, facing the front of the frame — which the glb
// authoring convention (ADR 0006) puts on +Z, so azimuth 0.
static const float kPreviewDefaultAzimuth = 0.0f;
static const float kPreviewDefaultElevation = 0.0f;

// Elevation stops short of the poles so the camera's up vector never flips.
static const float kPreviewMaxElevation = 1.4f;

// Drag sensitivity, radians per point of finger travel.
static const float kPreviewOrbitRadiansPerPoint = 0.008f;

// Framing distance: how much of the viewport the model's bounding sphere fills.
// 1.0 fits the whole sphere edge to edge; below that the camera moves in and
// crops into it — which the opening view does, because the sphere is drawn
// around the bounding box's diagonal and a frame is far wider than it is deep,
// so it leaves a lot of empty room at 1.0.
static const float kPreviewFramingMargin = 0.88f;

// Zoom limits, as multiples of the framed distance.
static const float kPreviewMinZoomFactor = 0.35f;
static const float kPreviewMaxZoomFactor = 3.0f;

// Ear half-width (face-local meters) fed to temple articulation, so the temples
// read as worn instead of folded. Roughly an average adult head.
static const float kPreviewEarHalfWidth = 0.07f;

// Background used until the app sets one, as an sRGB grey level.
static const float kPreviewDefaultBackground = 0.06f;

#endif /* PreviewConstants_h */
