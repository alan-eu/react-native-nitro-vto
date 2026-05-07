# Authoring contract for temple articulation in `.glb` models

## Context and Problem Statement

Glasses temples have to swing inward to land on the user's ears, which
vary in width per face. The runtime needs a reliable way to find the
two hinge pivots and the temple geometry inside any loaded `.glb`.

## Considered Options

* Require named nodes in the `.glb` and look them up at load time.
* Require an authored skeleton + skinned mesh with per-vertex weights.
* Detect temples heuristically from mesh geometry (e.g. by lateral
  extent).

## Decision Outcome

Chosen option: **named-node contract**. Every glasses model must
expose `HingeL_temple`, `HingeR_temple`, `TempleL_geometry`,
`TempleR_geometry` as nodes in the scene graph, with the temple
geometry extending along glb +Y from the hinge. At load time
`GlassesRenderer` looks them up by name; missing names → articulation
disabled, model still renders unarticulated.

### Consequences

* Good: trivial runtime; pure node-name lookup, no skinning math.
* Good: graceful fallback — assets without the contract still display.
* Bad: hard dependency on the authoring side; new vendor models
  without the convention need rework before they can articulate.
* Bad: no compile-time check; mistakes (typo in node name) only show
  up at runtime as un-articulated temples.
