# Layered face occlusion: depth-only mesh + back plane + hole closure

## Context and Problem Statement

Parts of the glasses must be occluded by the face: the bridge by the
nose, temples by the cheekbone and side of the head, and lenses /
temples passing behind the ears. The face mesh from ARKit/ARCore
covers the front of the face but has open holes (eyes, mouth) and
nothing behind the head. We need an occluder that handles all three
regions cheaply.

## Considered Options

* Render the AR face mesh as depth-only, plus a flat back plane sized
  from the face's lateral extent, plus self-discovered closure of the
  eye/mouth holes.
* Author a full head proxy mesh and animate it with the face anchor.
* Use only the AR face mesh and accept the leaks behind the head and
  through the eye/mouth holes.

## Decision Outcome

Chosen option: **layered occlusion**.
- The face mesh writes depth at priority 0 with no color.
- A single quad behind the face (sized from face mesh extents × 1.7
  ear margin, positioned at `minZ - kBackPlaneZOffset`) covers
  everything behind the head.
- The face mesh is shrunk by 0.95 in X only when writing depth, so the
  cheekbone edge pulls inward and stops clipping temple geometry; the
  nose / brow at X≈0 stay put and still occlude the bridge.
- `FaceMeshTopology` (iOS only — ARCore's mesh is already closed)
  detects boundary loops in the static triangle list and emits a
  centroid-fan triangulation per loop, dropping the largest (head
  perimeter) and keeping the eye/eye/mouth closures.

All tuning lives in `OcclusionConstants.{h,kt}`.

### Consequences

* Good: nose, cheekbone, ear, and behind-head occlusion all handled
  without authoring extra geometry per face shape.
* Good: hole closure self-discovers from the mesh — robust to future
  ARKit topology changes.
* Bad: the X-shrink is a global hack; reads as magic to a new
  contributor unless they read the comment chain.
* Bad: the back plane is a flat occluder, not a head-shaped proxy —
  it works because we only ever see it from roughly in front.
