# Tuning constants in narrow per-domain shared files

## Context and Problem Statement

Several tuning numbers (ear margin, X shrink, ear-tip scale, IBL
intensities) appear in multiple renderers and have to stay in lockstep
between iOS and Android. Inline literals invite drift; one big "all
constants here" file invites the wrong numbers to land in the wrong
places.

## Considered Options

* One shared file per domain, mirrored on each platform
  (`OcclusionConstants.{h,kt}`, `LightingConstants.{h,kt}`).
* One catch-all `Constants` file per platform.
* Inline literals everywhere with peer-review as the only guard.

## Decision Outcome

Chosen option: **narrow per-domain files**. Constants used in only
one renderer stay as named locals there (e.g. Kalman tuning in
`GlassesRenderer`). Constants shared across renderers move into a
domain file. New domain → new file rather than appending to an
existing one.

### Consequences

* Good: edits are scoped — touching occlusion never makes you read
  lighting numbers.
* Good: cross-platform parity is easy to audit (diff the iOS header
  against the Kotlin object).
* Bad: drift between iOS and Android is still possible; only
  enforcement is human eyes.
* Bad: deciding "domain or local?" is a judgement call that has to
  be revisited each time a constant moves.
