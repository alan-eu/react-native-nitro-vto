# Take the bridge X from the face anchor's symmetry axis

## Context and Problem Statement

ADR 0004 anchored the glasses to the nose bridge by averaging two
"L"/"R" mesh vertices: ARKit `818` / `366` and ARCore `351` / `122`.
The midpoint was used as the glasses entity's translation, in face-local
coordinates, every frame.

Multiple testers — across faces and across models — reported the
glasses sitting consistently to the user's left of where they should
be. Light, but real. Sampling the bridge-vertex pair across 10 frames
on both platforms revealed the pair is not a true mirror pair:

| Platform | L.x      | R.x      | sumX = L.x + R.x | midX     |
|----------|----------|----------|------------------|----------|
| iOS      | +0.00487 | -0.00792 | -0.00305         | -0.00153 |
| Android  | +0.00488 | -0.00589 | -0.00104         | -0.00052 |

`midX` is negative on every sampled frame, on both platforms. Face-local
+X is the user's right, so a negative `midX` translates the glasses to
the user's left by ~1.5mm (iOS) / ~0.5mm (Android). The shift is
purely mesh asymmetry: neither ARKit's nor ARCore's canonical face mesh
is perfectly mirror-symmetric around their face anchor's +X axis.

## Considered Options

* **Lock bridge X to the face anchor's symmetry axis** (`centerX = 0`),
  take only Y (bridge height) and Z (bridge depth) from the vertex
  average.
* Hunt for a better symmetric pair in each canonical mesh. Rejected:
  unbounded scope per platform, with no guarantee any pair is exactly
  symmetric — the meshes simply aren't.
* Synthesise symmetry: e.g. `centerX = (|L.x| - |R.x|) / 2 * sign(...)`.
  Rejected: opaque, still depends on which pair you picked, and the
  face anchor already provides a load-bearing symmetric axis for free.
* Leave it; the offset is sub-2mm. Rejected: testers and engineers both
  see it, and the fix is one line.

## Decision Outcome

Chosen option: **lock bridge X to the face anchor's symmetry axis**.

`GlassesRenderer.{mm,kt}::getNoseBridgeWorldPos*` now does:

```text
centerX = 0
centerY = (a.y + b.y) / 2
centerZ = (a.z + b.z) / 2
```

then transforms `(centerX, centerY, centerZ)` to world space via
`face.transform` (iOS) / `face.centerPose` (Android), as before. The
two vertices stay — they're still the right source for the bridge's
height and depth — but their X component is dropped because it was
encoding mesh asymmetry, not bridge position.

### Consequences

* Good: glasses centering is now driven by the canonical face axis,
  which is symmetric by construction (ARKit's and ARCore's anchor
  definition). No dependency on any pair of vertices being
  well-chosen.
* Good: robust to future ARKit / ARCore mesh template revisions — only
  the per-anchor symmetry contract matters, not vertex indices.
* Good: removes one source of cross-platform difference; the X axis is
  now treated identically.
* Bad: discards information from one X component, but the measurement
  above shows that information was a fixed systematic bias, not signal.
* Revisit trigger: any visible centering regression on either platform
  — e.g. if the face anchor's own symmetry contract loosens in a
  future SDK update — would warrant re-measuring and reopening this.

### Relationship to ADR 0004

ADR 0004 — "Anchor glasses to the nose bridge via hardcoded vertex
indices" — stands. The decision to anchor *via vertex indices* is
unchanged; this ADR narrows how the indices' X component is used.
