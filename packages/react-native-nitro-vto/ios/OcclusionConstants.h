#ifndef OcclusionConstants_h
#define OcclusionConstants_h

// Tuning constants shared between FaceOcclusionRenderer, DebugRenderer, and
// GlassesRenderer. Single source of truth — change a value here and the
// occluder, the debug overlay, and the temple articulation all stay in
// agreement. Mirrored on Android in OcclusionConstants.kt.

// Cheekbone -> ear-line factor. The ARKit face mesh stops at the cheekbones,
// so we scale its half-width by this to estimate the ear's lateral position.
static const float kEarMargin = 1.7f;

// Vertical-padding factor applied to the back plane (multiplies the face
// mesh's Y half-extent).
static const float kHeightMargin = 1.1f;

// Floor on the back plane half-width (meters). Keeps the plane sensible if
// face tracking momentarily collapses.
static const float kMinHalfWidth = 0.07f;

// X-only shrink applied to the face mesh when it writes depth. Pulls the
// cheek edge inward away from where the temples pass; the nose/eyes/brow at
// X≈0 stay put, so nose-bridge occlusion of the glasses bridge is preserved.
static const float kFaceMeshXShrink = 0.95f;

// Distance behind the deepest face-mesh vertex (face-local meters) at which
// the back plane sits.
static const float kBackPlaneZOffset = 0.03f;

// Scale applied to ear half-width when computing the temple-tip lateral
// target. The back planes sit visually on the ears thanks to perspective
// foreshortening (they live behind the head); the temple tips sit at the
// ear's actual depth, so the same lateral target would overshoot.
static const float kTempleTipScale = 0.6f;

// Default forward offset (face-local meters) of the glasses from the nose
// bridge. Exposed as the `forwardOffset` JS prop; this is the fallback
// value when nothing is provided. Visible to Swift via the pod's module
// map (this header is listed in the wrappers' s.public_header_files).
static const float kForwardOffset = 0.005f;

#endif /* OcclusionConstants_h */
