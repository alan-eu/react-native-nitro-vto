# Drop the old-architecture wrapper

## Context and Problem Statement

[ADR 0002](./0002-three-package-layout-with-bundled-core.md) introduced
two RN bindings over one native core: `react-native-nitro-vto` (Nitro,
new arch) and `react-native-vto` (classic bridge, old arch). Our apps
now all run on the new architecture, so the classic wrapper and its
example app are maintenance cost with no consumer.

## Considered Options

* Remove `react-native-vto` and `example-old-arch`, keep Nitro only.
* Keep both wrappers in lockstep.

## Decision Outcome

Chosen option: **remove the old-arch wrapper**. Every surface change
had to be mirrored in a second ViewManager, README and example app,
and verified on a second build, for no user.

### Consequences

* Good: one binding to change, test and release.
* Good: opens the door to folding `vto-core-native` into the Nitro
  package, since the copy-into-each-wrapper step of ADR 0002 only
  existed to share code between two wrappers.
* Bad: apps still on the old architecture stay on
  `@alaneu/react-native-vto@0.15.5`, the last release.
