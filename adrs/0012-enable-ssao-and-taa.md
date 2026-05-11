# SSAO + TAA, no FXAA

## Context and Problem Statement

With post-processing now on (ADR 0011 baseline), the view-quality
toolbox in Filament is open. Two knobs are worth a look for the VTO
use case where glasses geometry sits on a face mesh in front of a
live camera feed: screen-space ambient occlusion (SSAO) for
contact-shadow grounding, and an anti-aliasing pass for silhouette
stability against the moving face.

The tension: every post-process pass costs frame time on mobile GPUs;
the VTO loop already runs ARKit/ARCore face tracking, an IBL pass,
the camera-feed quad, the glasses mesh with transmission, and a face
occlusion pass on top.

## Considered Options

* SSAO on, TAA on — pick TAA for AA.
* SSAO on, FXAA on — pick FXAA for AA.
* SSAO on, TAA on, FXAA on — full set.
* All off — match the pre-ADR-0011 baseline.

## Decision Outcome

Chosen option: **SSAO on, TAA on, no FXAA**.

* **SSAO** — contact shadow at the temple/skin boundary and around
  the nose-pad noticeably grounds the glasses to the face. Without
  it, the glasses read as a floating overlay. Config: 0.3m radius
  (face-scale geometry), intensity 1.0, `View.QualityLevel.MEDIUM` —
  the cheap-but-visible knee. Both platforms.
* **TAA** — temporal accumulation smooths the metallic frame
  silhouette as the head moves and damps sub-pixel jitter FXAA
  can't reach. Default `TemporalAntiAliasingOptions` (feedback 0.12,
  filterWidth 1.0). Both platforms.
* **No FXAA** — TAA covers spatial AA on its own; running both is
  redundant cost. TAA's history-based path is the higher-quality
  choice for our slow-motion head-tracking case.

## Consequences

* Good: glasses look planted on the face rather than floating; edges
  read clean across frames.
* Good: TAA damps the sub-pixel temporal noise that FXAA-only leaves
  on the frame silhouette.
* Bad: extra GPU time per frame (SSAO + TAA history pass). Native
  FPS counter (PR #57) is in place to track it.
* Bad: TAA history state can ghost on fast head turns or scene cuts.
  Watch for it; if it shows up, the next step is tweaking
  `taa.feedback` or, worst case, falling back to FXAA.
