# @alaneu/vto-core-native

**Internal package — not published.** Do not install directly.

Contains the shared native code (Kotlin + Swift/ObjC++) and assets (Filament materials, IBL environments) used by the public VTO React Native libraries:

- [`@alaneu/react-native-nitro-vto`](../react-native-nitro-vto) — React Native **new architecture** (Nitro)
- [`@alaneu/react-native-vto`](../react-native-vto) — React Native **old architecture** (legacy View Manager)

Each public wrapper bundles a copy of this package's sources into its own `android/` and `ios/` folders at publish time via [`scripts/bundle.ts`](./scripts/bundle.ts). This gives end users a single self-contained install and avoids publishing this package separately.

## Layout

```
vto-core-native/
├── android/src/main/java/eu/alan/vto/core/  # Kotlin renderers (VtoView + Filament + ARCore)
├── android/src/main/assets/                  # compiled .filamat / .ktx / .txt
├── ios/                                      # Swift/ObjC++ renderers (VtoView + Filament + ARKit)
├── ios/assets/                               # compiled .filamat / .ktx / .txt
├── assets/materials/                         # .mat sources (incl. .ios.mat / .android.mat variants)
├── assets/envs/                              # .hdr sources
├── scripts/
│   ├── matc.ts                               # compile .mat → .filamat for both platforms
│   ├── cmgen.ts                              # process .hdr → IBL .ktx + _sh.txt
│   └── bundle.ts                             # copy core sources + assets into each wrapper
└── src/
    ├── expo.ts                               # Expo config plugin (ARCore manifest entries)
    └── types.ts                              # shared prop/method typedefs
```

## Dev workflow

```bash
# Once, from the repo root:
npm install               # runs the workspace postinstall → bundle

# When editing core files during development:
npm run watch             # re-bundles copies into the wrappers on save
```
