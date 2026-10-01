# Contributing

## Development Setup

### Repo layout

This is an npm-workspace monorepo:

```
packages/
  react-native-nitro-vto/    published — native core + Nitro bridge
example/                     Expo demo app
```

Inside `packages/react-native-nitro-vto/`:

```
android/src/main/java/eu/alan/vto/core/   Kotlin renderers (VtoView + Filament + ARCore)
android/src/main/java/com/margelo/...     Nitro bridge (HybridNitroVtoView)
android/src/main/assets/                  compiled .filamat / .ktx / .txt
ios/                                      Swift/ObjC++ renderers + Nitro bridge
ios/assets/                               compiled .filamat / .ktx / .txt
assets/                                   .mat / .hdr sources (not published)
scripts/                                  matc.ts / cmgen.ts (not published)
src/                                      TS entry, Nitro spec, types, Expo plugin
nitrogen/generated/                       nitrogen output — do not edit
```

### First-time setup

```bash
git clone git@github.com:alan-eu/react-native-nitro-vto.git
cd react-native-nitro-vto
npm install
```

### Filament toolchain (only needed to edit materials or IBL)

Download Filament 1.71.4 binaries from [filament releases](https://github.com/google/filament/releases), then at the **repo root** copy `.env.example` to `.env` and set:

```
MATC_PATH=/path/to/filament/bin/matc
CMGEN_PATH=/path/to/filament/bin/cmgen
```

Recompile when you touch a `.mat` or `.hdr`:

```bash
npm run matc    --workspace=@alaneu/react-native-nitro-vto   # .mat → .filamat
npm run cmgen   --workspace=@alaneu/react-native-nitro-vto   # .hdr → .ktx + _sh.txt
```

### Running the example

```bash
cd example
npm run ios           # or npm run android
```

### Dev loop for native edits

The example app consumes the package through the npm workspace, so native edits under `packages/react-native-nitro-vto/` are picked up by the next native build. A Metro reload is not enough for native changes; rebuild via `npm run ios` / `npm run android`.

## Coding Guidelines

### API surface changes

Any surface change (new prop, renamed method, new callback signature) must land in **all** of:

- `packages/react-native-nitro-vto/src/specs/NitroVtoView.nitro.ts` — Nitro spec (re-run `npm run specs` from the Nitro package)
- `HybridNitroVtoView.{kt,swift}`
- `example/app/index.tsx` — exercise the new surface
- `packages/react-native-nitro-vto/README.md`

### Platform conventions

- **iOS**: core renderers are Objective-C++ (`.mm` / `.h`) using Filament's C++ API directly; the `VtoView` facade is Swift. Keep the public Swift API `public` so it reaches the wrapper's auto-generated `<Module>-Swift.h` — that's what the Nitro HybridView imports.
- **Android**: core is Kotlin, package `eu.alan.vto.core`. Do not put anything in `com.margelo.nitro.nitrovto` — that namespace is reserved for Nitro-specific bridge code.
- **Filament**: version is pinned at `1.71.4` in the podspec and `android/build.gradle`; don't bump one without the other.
- **Assets**: source `.mat` / `.hdr` live in `packages/react-native-nitro-vto/assets/`; compiled `.filamat` / `.ktx` / `.txt` are checked in under `packages/react-native-nitro-vto/android/src/main/assets/` and `packages/react-native-nitro-vto/ios/assets/`. Always recompile and commit both source and compiled forms together.
- **Resource bundle lookup on iOS**: `LoaderUtils.loadAssetNamed:` looks up `NitroVtoAssets.bundle`, the `resource_bundles` name declared in the podspec. Rename both together.

### What to test before opening a PR

- Rebuild the example app on a physical device (ARKit face tracking needs a TrueDepth camera; simulator doesn't count).
- Confirm glasses render, face occlusion works, and model switching works.
- `npm pack --dry-run --workspace=packages/react-native-nitro-vto`: every native source and compiled asset should appear in the listing. If one is missing, check the `files` field in the package's `package.json`.

## Publish Steps

### Pre-release checklist

1. The example app builds + runs on a physical device for both platforms.
2. Working tree is clean (or only expected changes).
3. You are logged into npm: `npm whoami` returns the account with publish rights to the `@alaneu` scope.
4. Dry-run the tarball and verify native sources and assets are present:
   ```bash
   cd packages/react-native-nitro-vto && npm pack --dry-run
   ```

### Release

From the repo root:

```bash
npm run release
```

This runs, in order:

1. `release-it` inside `packages/react-native-nitro-vto` — builds via `bob` (`prepack` hook), publishes to npm.
2. `release-it` at the root — bumps the version across every relevant `package.json` (root, the package, the example), creates a signed git tag `vX.Y.Z`, and opens a GitHub release with the conventional-commits changelog.

Each step prompts for the version bump type (patch / minor / major). Use the same answer for both so the package and the git tag stay aligned.

### After release

- `git push --follow-tags origin main`
- Verify the package on npm: `npm view @alaneu/react-native-nitro-vto version`.
- Smoke-test a fresh install in a scratch RN project (install the wrapper, `pod install` on iOS, run on a device — catches packaging regressions that `npm pack --dry-run` misses).
