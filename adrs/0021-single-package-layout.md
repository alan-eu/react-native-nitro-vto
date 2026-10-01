# Single package: native core lives in `react-native-nitro-vto`

## Context and Problem Statement

[ADR 0002](./0002-three-package-layout-with-bundled-core.md) kept the
native core in a private `vto-core-native` package, copied into each RN
wrapper by `scripts/bundle.ts`. Since
[ADR 0020](./0020-drop-old-arch-wrapper.md) there is only one wrapper,
so the copy step shares code with nobody.

## Considered Options

* Move the core into `react-native-nitro-vto` and drop the bundle step.
* Keep `vto-core-native` + `bundle.ts` with a single target.

## Decision Outcome

Chosen option: **move the core into `react-native-nitro-vto`**, at the
exact paths `bundle.ts` copied it to (`android/src/main/java/eu/alan/vto/core/`,
`android/src/main/assets/`, `ios/`, `ios/assets/`, `src/types.ts`,
`src/expo.ts`). The podspec, Gradle and CMake configs did not change.
The asset pipeline of [ADR 0003](./0003-offline-asset-pipeline.md) is
unchanged; its sources and scripts now live under
`packages/react-native-nitro-vto/{assets,scripts}/` and are not published.

### Consequences

* Good: what is in git is what ships; no stale copy if someone forgets
  to re-bundle, no `postinstall`, no gitignored copies to keep in sync.
* Good: native edits are picked up by the example app's next build,
  without a watcher.
* Bad: adding a second binding again would mean re-extracting the core.
