# Decouple the Android render loop from ARCore camera arrival

## Context and Problem Statement

ARCore's default `Config.UpdateMode` is `BLOCKING`: `session.update()`
waits for the next camera image. The Android render loop calls it
synchronously, so the render thread is **camera-paced**. The front
camera's AE (Auto Exposure) controller extends per-frame exposure in low light, so
capture drops below the configured 30 fps. Result on both tested
devices (Pixel 5, Samsung A54): fps is light-dependent (18–24 fps
indoors, ~30 fps in bright light) and cadence is bimodal — frames
alternate between 33 ms and 67 ms intervals as render work spills
past the next camera deadline. The render thread is sleep-bound;
no GPU optimisation lifts the ceiling because the GPU is idle.

## Considered Options

* Keep `BLOCKING`. Lowest power; jittery, light-dependent fps.
* Switch to `LATEST_CAMERA_IMAGE`. `session.update()` returns
  immediately with the latest frame; render loop free-runs at vsync.
* `LATEST_CAMERA_IMAGE` + face-pose interpolation between camera
  frames. Rejected — reintroduces the smoothing layer ADR 0014
  removed, for a benefit the cheaper option already delivers.

## Decision Outcome

Chosen: **`Config.UpdateMode.LATEST_CAMERA_IMAGE` on Android**. One
line in `setupArSession()`. iOS is unaffected (ARKit is already
non-blocking).

| metric | Pixel 5 BLOCKING | Pixel 5 LATEST | A54 BLOCKING | A54 LATEST |
|---|--:|--:|--:|--:|
| fps mean | 23.6 | **31.5** | 18.4 | **29.9** |
| cadence mean (ms) | 42 | **32** | 54 | **33** |
| cadence max (ms) | 108 | **58** | 97 | **56** |

Both devices land at a steady, vsync-locked 30 fps. We stop at 30
(not 60) because Filament's per-frame work exceeds one vsync on
these GPUs, so double-buffered FIFO presents every other refresh.

### Consequences

* Good: cadence stability is unconditional — independent of lighting
  / AE response. The bimodal jitter is gone on both tested devices.
* Good: fps floor under poor light rises from camera-rate to
  vsync-rate.
* Good: cross-platform symmetry — iOS already non-blocking.
* Bad: render-thread Running time goes from near-zero to ~14–20% of
  wall clock; CPU + GPU work roughly doubles, with proportional
  power and thermal load. Not visible in a 30 s trace; will matter
  in 10+ minute sessions.
* Bad: glasses **motion** is unchanged — pose only updates per
  camera frame. Rendering at vsync makes the *cadence* steady, not
  the motion faster. Perceived smoothness is jitter elimination,
  not higher motion rate.
* Revisit triggers: sustained-use power/thermal regression → add a
  "skip render when input state unchanged" guard; or product wants
  real 60 fps motion → reopen face-pose interpolation, which
  conflicts with ADR 0014.
