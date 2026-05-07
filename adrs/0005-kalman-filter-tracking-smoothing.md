# Kalman-filter the tracked face pose

## Context and Problem Statement

ARKit and ARCore face poses jitter frame to frame. Without smoothing,
the glasses visibly tremble even when the user is still. We need a
filter that's responsive enough not to lag a real head turn but
smooths microscopic noise.

## Considered Options

* Two Kalman filters — one for position (3D), one for rotation
  (quaternion) — with shared tuning across platforms.
* Exponential moving average (EMA) on position + slerp on rotation.
* No smoothing; trust the SDKs' own filtering.

## Decision Outcome

Chosen option: **two Kalman filters**, `processNoise = 0.1`,
`measurementNoise = 0.05`, identical on iOS and Android. State lives
on `GlassesRenderer`; reset on hide / model switch so the next show
doesn't drift in from the off-screen pose.

### Consequences

* Good: visibly steadier glasses without perceptible lag.
* Good: same tuning both platforms — one set of numbers to think
  about, easy to audit.
* Bad: more code than EMA; reset semantics have to be remembered at
  every state transition.
* Bad: tuning is empirical; no automated test catches a regression
  from changing the constants.
* Bad: not trusting SDK's own filtering
