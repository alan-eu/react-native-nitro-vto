# React Native Nitro VTO

A React Native library for glasses virtual try-on using ARCore (Android) and ARKit (iOS) face tracking with Filament 3D rendering. Built with [Nitro Modules](https://nitro.margelo.com/) for high-performance native integration.

## Features

- Real-time face tracking with ARCore (Android) and ARKit (iOS)
- High-quality 3D rendering with Filament
- GLB model loading from URLs with automatic caching
- Runtime model switching
- Callback when model is loaded
- World-space positioning with proper perspective projection
- Face occlusion support (glasses appear behind face when appropriate)

## Requirements

- React Native >= 0.78.0
- `react-native-nitro-modules` >= 0.23.0
- Android: Device with ARCore support
- iOS: Device with ARKit support

## Installation

```bash
npm install @alaneu/react-native-nitro-vto react-native-nitro-modules
```

## Usage

```tsx
import React, { useState, useEffect } from "react";
import { View } from "react-native";
import { NitroVtoView } from "@alaneu/react-native-nitro-vto";
import { callback } from "react-native-nitro-modules";
import { Camera } from "react-native-vision-camera"; // or your preferred camera permission library

function App() {
  const [hasPermission, setHasPermission] = useState(false);

  useEffect(() => {
    async function requestPermission() {
      const status = await Camera.requestCameraPermission();
      setHasPermission(status === "granted");
    }
    requestPermission();
  }, []);

  if (!hasPermission) return null;

  return (
    <View style={{ flex: 1 }}>
      <NitroVtoView
        style={{ flex: 1 }}
        modelUrl="https://example.com/glasses.glb"
        isActive={true}
        faceMeshOcclusion={true}
        backPlaneOcclusion={true}
        forwardOffset={0.005}
        onModelLoaded={callback((url) => console.log("Model loaded:", url))}
      />
    </View>
  );
}
```

> **Note**: Callback props must be wrapped with `callback()` from `react-native-nitro-modules` due to React Native renderer limitations.

## API

### Props

| Prop                 | Type                         | Default | Description                                                                      |
| -------------------- | ---------------------------- | ------- | -------------------------------------------------------------------------------- |
| `modelUrl`           | `string`                     | -       | URL to the GLB model file. Models should be authored in meters at real-world size. |
| `isActive`           | `boolean`                    | -       | Whether the AR session is active                                                 |
| `faceMeshOcclusion`  | `boolean`                    | `true`  | Enable face mesh occlusion (glasses appear behind face edges)                    |
| `backPlaneOcclusion` | `boolean`                    | `true`  | Enable back plane occlusion (clips glasses temples extending behind head)        |
| `forwardOffset`      | `number`                     | `0.005` | Forward offset for glasses positioning in meters (positive = forward, negative = backward) |
| `debug`              | `boolean`                    | `false` | Enable debug visualization (red=face mesh, green=left plane, blue=right plane)  |
| `onModelLoaded`      | `(modelUrl: string) => void` | -       | Callback when model loading completes (wrap with `callback()`)                   |
| `style`              | `ViewStyle`                  | -       | Standard React Native view styles                                                |

### Methods

Access methods via `hybridRef`:

```tsx
import { useRef } from "react";
import {
  NitroVtoView,
  type NitroVtoViewMethods,
  type HybridRef,
} from "@alaneu/react-native-nitro-vto";

type VtoRef = HybridRef<NitroVtoViewProps, NitroVtoViewMethods>;

function App() {
  const vtoRef = useRef<VtoRef>(null);

  const switchGlasses = () => {
    vtoRef.current?.switchModel("https://example.com/other.glb");
  };

  return (
    <NitroVtoView
      modelUrl="https://example.com/glasses.glb"
      isActive={true}
      hybridRef={(ref) => {
        vtoRef.current = ref;
      }}
    />
  );
}
```

| Method                        | Description                                    |
| ----------------------------- | ---------------------------------------------- |
| `switchModel(modelUrl: string)` | Switch to a different glasses model at runtime |
| `resetSession()`              | Reset the AR session and face tracking         |

## Technical Details

### Filament Tools

Both platforms use Filament 1.69.3. Download from [Filament GitHub releases](https://github.com/google/filament/releases).

Create a `.env` file at the package root (see `.env.example`):

```bash
MATC_PATH=/path/to/filament/bin/matc
CMGEN_PATH=/path/to/filament/bin/cmgen
```

### Asset layout

Source materials (`.mat`) and environment maps (`.hdr`) live in a single shared folder:

```
packages/react-native-nitro-vto/
├── assets/
│   ├── materials/*.mat   ← source materials (one copy, shared across platforms)
│   └── envs/*.hdr        ← source HDRis
├── ios/assets/           ← compiled artifacts only (.filamat, .ktx, .txt)
└── android/src/main/assets/   ← compiled artifacts only (.filamat, .ktx, .txt)
```

Most materials are identical across platforms and live as a single `.mat`. Platform-specific materials use a `.ios.mat` / `.android.mat` suffix — the `matc` script compiles those only for the matching platform and strips the suffix from the output filename (e.g. `camera_background.ios.mat` → `ios/assets/materials/camera_background.filamat`). The only platform-specific material today is `camera_background`, because the iOS (Metal, `sampler2d` + `flipUV: false`) and Android (OpenGL/Vulkan, `samplerExternal`) sampler conventions can't be expressed in a single `.mat`.

### Compile Materials

Compiles every `.mat` in `assets/materials/` in one pass. Shared sources compile for both platforms; `*.ios.mat` / `*.android.mat` files compile only for the matching platform. Metal output lands in `ios/assets/materials/`, OpenGL+Vulkan output in `android/src/main/assets/materials/`:

```bash
npm run matc
```

### Generate IBL from HDR

Processes every `.hdr` in `assets/envs/` once per file — `.ktx` is platform‑agnostic so the outputs (`_ibl.ktx`, `_skybox.ktx`, `_sh.txt`) are copied to both `ios/assets/envs/` and `android/src/main/assets/envs/`:

```bash
npm run cmgen
```

### Transform Pipeline

The glasses positioning uses world-space coordinates with ARKit/ARCore perspective camera:

1. **Camera**: Filament camera uses ARKit/ARCore view and projection matrices directly
2. **Position**: World-space coordinates from face mesh nose bridge vertices
   - Android (ARCore): vertices 351 and 122
   - iOS (ARKit): vertices 818 and 366
3. **Rotation**: Face transform rotation quaternion in world space
4. **Smoothing**: Kalman filters applied to position and rotation for stability

Models should be authored in meters at real-world size (e.g., a glasses frame width of ~0.135m). This world-space approach ensures correct perspective projection and natural glasses behavior when moving the head.

## License

MIT
