# React Native Nitro VTO

A React Native library for glasses virtual try-on using ARCore (Android) and ARKit (iOS) face tracking with Filament 3D rendering. Built with [Nitro Modules](https://nitro.margelo.com/) for high-performance native integration.

## Features

- Real-time face tracking with ARCore (Android) and ARKit (iOS)
- High-quality 3D rendering with Filament
- GLB model loading from URLs with automatic caching
- Runtime model switching
- Callback when model is loaded
- World-space positioning with proper perspective projection

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
        modelWidthMeters={0.135}
        isActive={true}
        onModelLoaded={callback((url) => console.log("Model loaded:", url))}
      />
    </View>
  );
}
```

> **Note**: Callback props must be wrapped with `callback()` from `react-native-nitro-modules` due to React Native renderer limitations.

## API

### Props

| Prop               | Type                         | Description                                                    |
| ------------------ | ---------------------------- | -------------------------------------------------------------- |
| `modelUrl`         | `string`                     | URL to the GLB model file                                      |
| `modelWidthMeters` | `number`                     | Width of the glasses frame in meters for proper scaling        |
| `isActive`         | `boolean`                    | Whether the AR session is active                               |
| `onModelLoaded`    | `(modelUrl: string) => void` | Callback when model loading completes (wrap with `callback()`) |
| `style`            | `ViewStyle`                  | Standard React Native view styles                              |

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
    vtoRef.current?.switchModel("https://example.com/other.glb", 0.138);
  };

  return (
    <NitroVtoView
      modelUrl="https://example.com/glasses.glb"
      modelWidthMeters={0.135}
      isActive={true}
      hybridRef={(ref) => {
        vtoRef.current = ref;
      }}
    />
  );
}
```

| Method                                               | Description                                    |
| ---------------------------------------------------- | ---------------------------------------------- |
| `switchModel(modelUrl: string, widthMeters: number)` | Switch to a different glasses model at runtime |
| `resetSession()`                                     | Reset the AR session and face tracking         |

## Technical Details

### Filament `matc` and `cmgen` tools

Version 1.67.1 for Android and 1.56.6 for iOS.

### Compile Materials for Android

For Android, compile materials with OpenGL and Vulkan backends:

```bash
matc --api opengl --api vulkan --platform mobile -o output.filamat input.mat
```

### Compile Materials for iOS

For iOS, compile materials with Metal backend:

```bash
matc --api metal --platform mobile -o output.filamat input.mat
```

### Generate IBL from HDR env

Download the Filament tools and generate the IBL from the HDR env:

```bash
cmgen --format=ktx --size=256 --deploy=./output/path/ ./input/path/your_env.hdr
```

### Head Rotation Axes

- X axis (Roll): Tilt head left/right
- Y axis (Pitch): Look up/down
- Z axis (Yaw): Turn left/right

### Transform Pipeline

The glasses positioning uses world-space coordinates with ARKit/ARCore perspective camera:

1. **Camera**: Filament camera uses ARKit/ARCore view and projection matrices directly
2. **Position**: World-space coordinates from face mesh nose bridge vertices
   - Android (ARCore): vertices 351 and 122
   - iOS (ARKit): vertices 818 and 366
3. **Scale**: Computed from model bounding box to match target width in meters (`targetWidth / modelBoundingBoxWidth`)
4. **Rotation**: Face transform rotation quaternion in world space
5. **Smoothing**: Kalman filters applied to position and rotation for stability

This world-space approach ensures correct perspective projection and natural glasses behavior when moving the head.

## License

MIT
