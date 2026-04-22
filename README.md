# React Native VTO

A React Native library for glasses virtual try-on using ARCore (Android) and ARKit (iOS) face tracking with Filament 3D rendering.

## Features

- Real-time face tracking with ARCore (Android) and ARKit (iOS)
- High-quality 3D rendering with Filament
- GLB model loading from URLs with automatic caching
- Runtime model switching
- Callbacks for model-loaded, face-tracked, glasses-displayed
- World-space positioning with proper perspective projection
- Face occlusion support (glasses appear behind face when appropriate)

## Pick the right package

This repo publishes two wrappers that share the same native core but target different React Native architectures:

| Package | Architecture | Extra runtime dep | When to use |
| --- | --- | --- | --- |
| [`@alaneu/react-native-nitro-vto`](./packages/react-native-nitro-vto) | new (Fabric) | `react-native-nitro-modules` | RN ≥ 0.78 with `newArchEnabled=true` |
| [`@alaneu/react-native-vto`](./packages/react-native-vto) | old (Paper) | none | `newArchEnabled=false`, or apps that can't pull in Nitro |

They're mutually exclusive — install whichever matches your app's architecture. The API surface (props, methods, callbacks) is identical; only the import path, the callback wrapping convention (Nitro requires `callback()`), and the method-access pattern (Nitro's `hybridRef` vs. classic `ref`) differ.

See each package's README for install, usage, and full API:

- [`@alaneu/react-native-nitro-vto` — new-arch install + API](./packages/react-native-nitro-vto/README.md)
- [`@alaneu/react-native-vto` — old-arch install + API](./packages/react-native-vto/README.md)

## Requirements

- Android: device with ARCore support
- iOS: device with ARKit (TrueDepth camera — no simulator)

## How it works

Glasses positioning uses world-space coordinates driven by the platform's perspective camera:

1. **Camera** — Filament camera uses ARKit/ARCore view + projection matrices directly.
2. **Position** — world-space coordinates derived from face-mesh nose-bridge vertices
   - Android (ARCore): vertices 351 and 122
   - iOS (ARKit): vertices 818 and 366
3. **Rotation** — face-transform rotation quaternion, in world space.
4. **Smoothing** — Kalman filters applied to position and rotation for stability.

Models should be authored in meters at real-world size (e.g. a glasses frame width of ~0.135 m). World-space coordinates — rather than screen-space — ensure correct perspective projection and natural glasses behavior when the head moves.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for repo layout, dev setup, the Filament toolchain, material/IBL compilation, coding guidelines, and the publish flow.

## License

MIT
