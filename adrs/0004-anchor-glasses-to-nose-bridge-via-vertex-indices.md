# Anchor glasses to the nose bridge via hardcoded vertex indices

## Context and Problem Statement

The glasses root entity's world transform has to track a stable point
on the face. ARKit and ARCore both expose face meshes whose topology
is documented as static, but neither SDK exposes a "nose bridge"
landmark directly.

## Considered Options

* Hardcode vertex indices for two points symmetric around the nose
  bridge and average them: ARKit `818` / `366`, ARCore `351` / `122`.
* Use blendshapes / face anchor `lookAtPoint` and derive the bridge.
* Use a separate face-landmark detector on top of the AR feed.

## Decision Outcome

Chosen option: **hardcoded vertex indices**, averaged to give the
bridge midpoint, then transformed to world space by the face anchor's
pose. `GlassesRenderer.{mm,kt}` does this every frame.

### Consequences

* Good: trivial, exact, no extra subsystems.
* Good: stable with the documented mesh topology — no drift between
  frames.
* Bad: brittle to topology changes. If Apple or Google ever revs the
  face mesh layout, glasses positioning silently breaks.
* Bad: no programmatic check that the indices still map to the nose
  bridge — relies on community references.
