# Three-package layout with bundled `vto-core-native`

## Context and Problem Statement

We ship two RN bindings — `react-native-nitro-vto` (Nitro modules) and
`react-native-vto` (classic bridge) — but the rendering code (Filament
+ ARKit/ARCore + materials) is identical between them. We need a way
to keep one source of truth without forcing consumers to install a
shared dependency.

## Considered Options

* `vto-core-native` private package, copied into both wrappers by a
  bundle script before publish.
* Symlink / npm workspace dependency from each wrapper to a shared
  package.
* One published package per binding, with the shared code duplicated.

## Decision Outcome

Chosen option: **private `vto-core-native` + bundle script**. Each
published wrapper is fully self-contained at install time (no peer
dependency, no link fragility), but day-to-day we edit one set of
files. `scripts/bundle.ts` runs on demand and on release.

### Consequences

* Good: single source of truth; consumers see a normal npm package.
* Good: each wrapper publishes independently with its own surface
  (Nitro spec vs classic ViewManager).
* Bad: bundled copies must be `.gitignore`d; new files in core need
  matching `.gitignore` patterns or they leak into commits.
* Bad: forgetting to run `npm run bundle` before commit/release ships
  stale code.
