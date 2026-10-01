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

## Package

This repo publishes [`@alaneu/react-native-nitro-vto`](./packages/react-native-nitro-vto), built on [Nitro Modules](https://nitro.margelo.com/) for the React Native new architecture (RN ≥ 0.78 with `newArchEnabled=true`).

See its [README](./packages/react-native-nitro-vto/README.md) for install, usage, and full API.

The old-architecture wrapper `@alaneu/react-native-vto` is no longer maintained; its last release is 0.15.5.

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

Models should be authored in meters at real-world size (e.g. a glasses frame width of ~0.135 m). World-space coordinates — rather than screen-space — ensure correct perspective projection and natural glasses behavior when the head moves.

## Contributing

See [CONTRIBUTING.md](./CONTRIBUTING.md) for repo layout, dev setup, the Filament toolchain, material/IBL compilation, coding guidelines, and the publish flow.

## License

MIT
