# Contributing

## Development Setup

### Repo layout

This is an npm-workspace monorepo:

```
packages/
  vto-core-native/           private — shared native code + assets (single source of truth)
  react-native-nitro-vto/    published — new-arch (Fabric) wrapper
  react-native-vto/          published — old-arch (Paper) wrapper
examples/
  example-new-arch/          new-arch Expo demo app
  example-old-arch/          old-arch Expo demo app
```

Native sources live in `vto-core-native/`. The two wrapper packages each hold only their arch-specific bridge code (Nitro HybridView vs. RCTViewManager). The shared core is **copied** into each wrapper by `scripts/bundle.ts` — those copied paths are gitignored; do not edit them.

### First-time setup

```bash
git clone git@github.com:alan-eu/react-native-nitro-vto.git
cd react-native-nitro-vto
npm install
```

`npm install` runs a `postinstall` hook that bundles core into both wrappers, so the repo is immediately buildable.

### Filament toolchain (only needed to edit materials or IBL)

Download Filament 1.71.4 binaries from [filament releases](https://github.com/google/filament/releases), then at the **repo root** copy `.env.example` to `.env` and set:

```
MATC_PATH=/path/to/filament/bin/matc
CMGEN_PATH=/path/to/filament/bin/cmgen
```

Recompile when you touch a `.mat` or `.hdr`:

```bash
npm run matc    --workspace=@alaneu/vto-core-native   # .mat → .filamat
npm run cmgen   --workspace=@alaneu/vto-core-native   # .hdr → .ktx + _sh.txt
```

### Running the examples

New-arch (Nitro):

```bash
cd examples/example-new-arch
npm run ios           # or npm run android
```

Old-arch (classic):

```bash
cd examples/example-old-arch
npm run ios           # or npm run android
```

Both apps install on the same device side-by-side (distinct bundle IDs).

### Dev loop for core edits

Run the watcher once in a terminal:

```bash
npm run watch:core
```

Edits under `packages/vto-core-native/` (Swift / Kotlin / Obj-C++ / headers / compiled `.filamat` / `.ktx` / `src/types.ts` / `src/expo.ts`) re-bundle into both wrappers within ~150 ms. You still need to rebuild on the consumer side — Gradle and Xcode don't notice file replacements on their own. On iOS, a Metro reload is not enough for native changes; reinstall via `npm run ios`.

## Coding Guidelines

### Source of truth

- **All shared native code belongs in `packages/vto-core-native/`.** Never edit `.kt` / `.swift` / `.mm` / `.h` inside a wrapper's bundled paths (`packages/react-native-*-vto/ios/*` for the core renderer files, `packages/react-native-*-vto/android/src/main/java/eu/alan/vto/core/`, or the `android/src/main/assets/` dirs) — those are git-ignored and overwritten by every bundle run.
- Each wrapper keeps only its arch-specific bridge code:
  - `react-native-nitro-vto`: `HybridNitroVtoView.{kt,swift}`, the `NitroVto.h` umbrella, `nitrogen/`, `src/specs/`.
  - `react-native-vto`: `VtoViewManager.{kt,mm,h}`, `VtoPackage.kt`, `VtoBridgeView.swift`, `src/VtoView.tsx`.

### API parity

The two wrappers expose the **same** props / methods / callbacks. Any surface change (new prop, renamed method, new callback signature) must land in **all** of:

- `packages/vto-core-native/src/types.ts` — shared TS typedefs (bundled into each wrapper as `src/types.ts`)
- `packages/react-native-nitro-vto/src/specs/NitroVtoView.nitro.ts` — Nitro spec (re-run `npm run specs` from the Nitro package)
- `packages/react-native-vto/src/VtoView.tsx` — old-arch wrapper (requireNativeComponent + `useImperativeHandle`)
- Both native view managers: `HybridNitroVtoView.{kt,swift}` and `VtoViewManager.kt` / `VtoBridgeView.swift` / `VtoViewManager.mm`
- `examples/example-new-arch/app/index.tsx` and `examples/example-old-arch/app/index.tsx` — exercise the new surface
- Both READMEs (`packages/react-native-nitro-vto/README.md` and `packages/react-native-vto/README.md`)

### Platform conventions

- **iOS**: core renderers are Objective-C++ (`.mm` / `.h`) using Filament's C++ API directly; the `VtoView` facade is Swift. Keep the public Swift API `public` so it reaches each wrapper's auto-generated `<Module>-Swift.h` — that's what the Nitro HybridView and the old-arch RCTViewManager both import.
- **Android**: core is Kotlin, package `eu.alan.vto.core`. Do not put anything in `com.margelo.nitro.nitrovto` — that namespace is reserved for Nitro-specific bridge code.
- **Filament**: version is pinned at `1.71.4` in both podspecs and both `android/build.gradle` files; don't bump one without the other.
- **Assets**: source `.mat` / `.hdr` live in `packages/vto-core-native/assets/`; compiled `.filamat` / `.ktx` / `.txt` are checked in under `packages/vto-core-native/android/src/main/assets/` and `packages/vto-core-native/ios/assets/`. Always recompile and commit both source and compiled forms together.
- **Resource bundle lookup on iOS**: `LoaderUtils.loadAssetNamed:` tries both `NitroVtoAssets.bundle` and `ReactNativeVtoAssets.bundle` (each wrapper podspec names its `resource_bundles` differently). If you add a third wrapper, extend that list.

### What to test before opening a PR

- Rebuild both example apps on a physical device (ARKit face tracking needs a TrueDepth camera; simulator doesn't count).
- Confirm glasses render, face occlusion works, and model switching works on both arches.
- `npm pack --dry-run --workspace=packages/react-native-nitro-vto` and `--workspace=packages/react-native-vto`: every bundled file should appear in the listing. If a core file is missing, the bundle step in `prepublishOnly` isn't picking it up.

## Publish Steps

The two wrappers are released in lockstep at the same version. `vto-core-native` is private and never published — its code ships embedded inside each wrapper's tarball via `prepublishOnly`.

### Pre-release checklist

1. Both example apps build + run on a physical device for both platforms.
2. Working tree is clean (or only expected changes).
3. You are logged into npm: `npm whoami` returns the account with publish rights to the `@alaneu` scope.
4. Dry-run both tarballs and verify bundled native sources are present:
   ```bash
   cd packages/react-native-nitro-vto && npm pack --dry-run
   cd ../react-native-vto         && npm pack --dry-run
   ```

### Release

From the repo root:

```bash
npm run release
```

This runs, in order:

1. `release-it` inside `packages/react-native-nitro-vto` — builds via `bob` (the `prepack` hook re-runs `bundle` first), publishes to npm.
2. `release-it` inside `packages/react-native-vto` — same flow for the old-arch wrapper.
3. `release-it` at the root — bumps the version across every relevant `package.json` (root, both wrappers, `vto-core-native`, both examples), creates a signed git tag `vX.Y.Z`, and opens a GitHub release with the conventional-commits changelog.

Each step prompts for the version bump type (patch / minor / major). Use the same answer for all three so the wrappers and the git tag stay aligned.

### After release

- `git push --follow-tags origin main`
- Verify both packages on npm: `npm view @alaneu/react-native-nitro-vto version` and `npm view @alaneu/react-native-vto version`.
- Smoke-test a fresh install in a scratch RN project for at least one arch (install the wrapper, `pod install` on iOS, run on a device — catches packaging regressions that `npm pack --dry-run` misses).

### Hotfix / single-wrapper release

If only one wrapper needs a patch and you want to skip the lockstep, run `npm run release` from that wrapper's directory directly. Remember to manually bump the other `package.json` files afterward if you want versions to stay aligned — the root release-it is what normally handles that.
