# React Native Nitro VTO

A React Native library for glasses virtual try-on using ARCore face tracking and Filament 3D rendering. Built with [Nitro Modules](https://nitro.margelo.com/) for high-performance native integration.

## Features

- Real-time face tracking with ARCore
- High-quality 3D rendering with Filament
- GLB model support
- Runtime model switching
- Proper depth-based scaling for accurate sizing

## Requirements

- React Native >= 0.78.0
- Android device with ARCore support
- `react-native-nitro-modules` >= 0.23.0

**Note**: This library is Android-only as it uses ARCore which is not available on iOS.

## Installation

```bash
npm install @alaneu/react-native-nitro-vto react-native-nitro-modules
```

## Usage

```tsx
import React, { useState, useEffect } from "react";
import { View, PermissionsAndroid, Platform } from "react-native";
import { NitroVtoView } from "@alaneu/react-native-nitro-vto";

function App() {
  const [hasPermission, setHasPermission] = useState(false);

  useEffect(() => {
    async function requestPermission() {
      if (Platform.OS === "android") {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA
        );
        setHasPermission(granted === PermissionsAndroid.RESULTS.GRANTED);
      }
    }
    requestPermission();
  }, []);

  if (!hasPermission) return null;

  return (
    <View style={{ flex: 1 }}>
      <NitroVtoView
        style={{ flex: 1 }}
        modelPath="models/glasses.glb"
        modelWidthMeters={0.135}
        isActive={true}
      />
    </View>
  );
}
```

## API

### Props

| Prop               | Type        | Description                                              |
| ------------------ | ----------- | -------------------------------------------------------- |
| `modelPath`        | `string`    | Path to the GLB model file relative to the assets folder |
| `modelWidthMeters` | `number`    | Width of the glasses frame in meters for proper scaling  |
| `isActive`         | `boolean`   | Whether the AR session is active                         |
| `style`            | `ViewStyle` | Standard React Native view styles                        |

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
    vtoRef.current?.switchModel("models/other.glb", 0.138);
  };

  return (
    <NitroVtoView
      modelPath="models/glasses.glb"
      modelWidthMeters={0.135}
      isActive={true}
      hybridRef={(ref) => {
        vtoRef.current = ref;
      }}
    />
  );
}
```

| Method                                                | Description                                    |
| ----------------------------------------------------- | ---------------------------------------------- |
| `switchModel(modelPath: string, widthMeters: number)` | Switch to a different glasses model at runtime |
| `resetSession()`                                      | Reset the AR session and face tracking         |

## Assets Setup

Place your GLB model files in your Android assets folder:

```
android/app/src/main/assets/models/glasses.glb
```

## Technical Details

### Compile a Filament Material

Download the Filament tools and compile the camera background material:

```bash
 matc --api opengl --api vulkan --platform mobile -o output.filamat input.mat
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

The glasses positioning uses a depth-based scaling approach:

1. **Position**: Computed from ARCore face mesh nose bridge vertices (351, 122)
2. **Scale**: `focalLength / depth` - ensures consistent size regardless of head orientation
3. **Rotation**: Uses ARCore's `centerPose.rotationQuaternion` directly
4. **Aspect Ratio**: Applied after rotation to prevent skewing during head tilt

This approach solves common issues like glasses shrinking when turning the head (perspective foreshortening) and skewing during roll rotation.

## License

MIT
