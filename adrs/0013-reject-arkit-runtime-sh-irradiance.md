# Reject ARKit-driven runtime IBL SH on iOS; keep static SH + primary direction/intensity

## Context and Problem Statement

ADR 0011 settled on static IBL + a per-frame directional ("sun") light
driven by ARKit's `ARDirectionalLightEstimate.primaryLightDirection` /
`primaryLightIntensity`. The IBL's diffuse irradiance is baked from
`assets/envs/studio_small_02_2k_sh.txt` and never modulated.

ARKit also exposes 3-band ambient spherical harmonics on the same
estimate (`sphericalHarmonicsCoefficients`, 27 floats). Profiling on a
physical iPhone (`xctrace` Time Profiler, 30 s steady-state face
tracking) shows ARKit's `ARFaceLightEstimationTechnique`
(`FacialLightEstimation::estimateLight` → `FaceLightOptimizer::compute`
→ `solveQuadratic` → `quadprogpp`) consuming ~5.2% of total CPU
regardless of whether we read the SH output. Today we read only the
primary direction + intensity; the SH coefficients are computed by the
SDK and discarded.

The question this ADR closes: do we ship that?

## Considered Options

* **Adopt ARKit SH as the IBL's diffuse irradiance on iOS**
  Rebuild `IndirectLight` per frame with ARKit's SH. 
  Specular cubemap stays static. ~30 lines.
* **Disable `isLightEstimationEnabled` on the `ARFaceTrackingConfiguration`.**
  Saves the ~5% CPU but also kills `primaryLightDirection` and
  `primaryLightIntensity`, which we actively use (ADR 0011). Not
  separable; rejected.
* **Keep status quo** — static IBL SH + per-frame directional from
  ARKit primary direction/intensity. CPU cost of unused ARKit SH
  computation is paid but unavoidable so long as we keep the
  directional path.

## Decision Outcome

Chosen option: **keep status quo**. Static SH on iOS (ADR 0011
unchanged for diffuse); keep `primaryLightDirection` +
`primaryLightIntensity` driving the per-frame directional light.

The runtime-SH path was considered, prototyped on a branch, and
rejected before merging.

### Why reject

- **Win not validated.** The claimed benefit ("warm lamp on the left
  warms the left temple") is plausible but was never confirmed on a
  device with controlled lighting. Shipping a rendering change of this
  shape without a measured A/B is the same shape of mistake that led to
  ADR 0011 in the first place — that ADR was the retreat from a
  too-eager lighting integration. Repeating the pattern at smaller
  scale is still the pattern.
- **Specular-asymmetry footgun remembered.** ADR 0011 exists because a
  prior SDK-driven lighting integration produced visible cross-platform
  divergence. Diffuse-only SH on iOS is *probably* safe — the
  attempted spec carefully avoids touching specular — but "probably
  safe" wasn't worth the additional code path when the diffuse win
  itself isn't verified.
- **The ~5% CPU is not recoverable separately.** It's paid because we
  keep `primaryLightDirection`/`primaryLightIntensity`, not because we
  read the SH. Adopting SH would consume an output we already pay for,
  but the cost is fixed either way — so "no free CPU" cuts both
  directions: there's nothing to gain by adopting either.

### Consequences

* Good: iOS and Android diffuse pipelines stay structurally identical.
  One mental model for the next maintainer; ADR 0011 remains the single
  source of truth for the diffuse path.
* Bad: glasses diffuse does not track room color/direction on iOS. The
  win is forfeited. But wasn't verified.
* Bad: ARKit still spends ~5% CPU computing SH we discard. Not
  recoverable without also losing the directional light we use.
