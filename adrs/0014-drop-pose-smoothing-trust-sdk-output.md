# Drop pose smoothing; trust ARKit / ARCore output directly

## Context and Problem Statement

ADR 0005 introduced two Kalman filters on `GlassesRenderer` — one 3D
filter for the nose-bridge world position, one 4-component filter for
the face-rotation quaternion — with `processNoise = 0.1`,
`measurementNoise = 0.05` shared across iOS and Android. The
assumption was that ARKit / ARCore face-pose output jitters frame to
frame enough that glasses would visibly tremble on a still head
without smoothing.

That assumption was tested. We A/B'd by setting `measurementNoise = 0.0`
(algebraically exact pass-through: Kalman gain K = P/P = 1, so
`estimate = measurement` every frame, with `P` cycling between 0 and Q
on the predict step and never hitting NaN). Side-by-side on a
physical iPhone (ARKit, face tracking) and a Pixel running ARCore
augmented-faces, raw pass-through was indistinguishable from filtered
output on a still head, and *better* on fast head turns where the
filter had previously introduced visible lag.

The third rejected option in ADR 0005 ("no smoothing; trust the SDKs'
own filtering") turned out to be the correct one. The SDKs do their
own temporal stabilisation downstream of their face landmark
solvers — the per-frame raw output is already smooth at the
sub-millimeter / sub-degree scale that matters for glasses tracking.

## Considered Options

* **Remove the Kalman pipeline entirely**, feed raw nose-bridge
  position + face quaternion straight into the Filament transform.
* Keep the Kalman classes in place, gate them behind a runtime toggle
  for future re-enabling. Rejected: no validated use case to switch
  back on, and dead code is a maintenance tax against the next
  milestone (`project_next_milestone_maintainability`).
* Replace Kalman with a lighter EMA / one-pole filter. Rejected: same
  reason as above — A/B showed no smoothing is needed at all, so any
  filter is solving a non-problem.

## Decision Outcome

Chosen option: **remove the Kalman pipeline entirely**.

Deleted:
- `KalmanFilter.{h,mm,kt}` from `packages/vto-core-native/{ios,android/.../core}/`
  and the two wrappers' bundled copies.
- `kKalmanProcessNoise` / `kKalmanMeasurementNoise` constants and
  their Kotlin twins from `GlassesRenderer.{mm,kt}`.
- `_positionFilter` / `_rotationFilter` properties + their
  `KalmanFilter3D` / `KalmanFilterQuaternion` instantiations.
- `resetFilters` and its call sites in `hide()` and `switchModel()`.

`GlassesRenderer` now uses `noseBridgeWorld` and `faceRotationWorld`
(iOS) / `noseBridgeWorld` and `faceQuaternion` (Android) directly when
building the Filament transform matrix.

### Consequences

* Good: less code, fewer state transitions to remember (no more reset
  semantics on hide / model switch). The `resetFilters` call paths
  ADR 0005 flagged as needing care are gone.
* Good: no more empirical, untested tuning constants in the
  render-critical path.
* Good: glasses now track instantly on fast head turns — the perceived
  lag the filter introduced is recovered.
* Good: cross-platform parity simplified — both platforms now do
  literally the same thing (feed the SDK pose through), so the "same
  tuning" justification ADR 0005 leaned on becomes moot.
* Bad: if a future SDK update degrades raw pose quality, we'd need to
  reintroduce smoothing. The fix is straightforward (the deleted
  classes are recoverable from git history) but not zero-cost; we'd
  re-validate before re-adding.
* Revisit trigger: any visible jitter regression on glasses on a still
  head, or sub-millimeter drift on a tracked face becomes visible to
  users. Re-run the A/B from this ADR's context section before
  reintroducing any filter.
