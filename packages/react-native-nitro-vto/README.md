# @alaneu/react-native-nitro-vto

React Native **new architecture** (Fabric) wrapper for the glasses virtual try-on library. Built on [Nitro Modules](https://nitro.margelo.com/) for high-performance native integration.

If your app runs with `newArchEnabled=false`, install [`@alaneu/react-native-vto`](https://github.com/alan-eu/react-native-nitro-vto/tree/main/packages/react-native-vto) instead. The two packages are mutually exclusive; they share identical API surfaces but cannot coexist in the same app.

## Requirements

- React Native ≥ 0.78 with `newArchEnabled=true`
- `react-native-nitro-modules` ≥ 0.23
- Android: device with ARCore support
- iOS: device with ARKit (TrueDepth camera — no simulator)

## Install

```bash
npm install @alaneu/react-native-nitro-vto react-native-nitro-modules
cd ios && pod install
```

## Usage

```tsx
import { NitroVtoView } from "@alaneu/react-native-nitro-vto";
import { callback } from "react-native-nitro-modules";
import { useEffect, useState } from "react";
import { View } from "react-native";

function App() {
  const [hasPermission, setHasPermission] = useState(false);

  useEffect(() => {
    // Request camera permission with your library of choice
    // (PermissionsAndroid on Android, AVCaptureDevice on iOS, or expo-camera).
    // Set `hasPermission` once granted.
  }, []);

  if (!hasPermission) return null;

  return (
    <View style={{ flex: 1 }}>
      <NitroVtoView
        style={{ flex: 1 }}
        modelUrl="https://example.com/glasses.glb"
        isActive={true}
        forwardOffset={0.005}
        onModelLoaded={callback((url) => console.log("loaded", url))}
        onFaceTracked={callback(() => console.log("face tracked"))}
        onGlassesDisplayed={callback((url) => console.log("displayed", url))}
      />
    </View>
  );
}
```

> **Callbacks must be wrapped with `callback()`** from `react-native-nitro-modules`. This is a Nitro renderer requirement and is specific to the new-arch wrapper — the old-arch wrapper uses plain JS functions.

To switch glasses, update the `modelUrl` prop — setting it to a new URL swaps the model.

Methods via `hybridRef`:

```tsx
import { useRef } from "react";
import type { HybridRef } from "react-native-nitro-modules";
import {
  NitroVtoView,
  type NitroVtoViewProps,
  type NitroVtoViewMethods,
} from "@alaneu/react-native-nitro-vto";

type VtoRef = HybridRef<NitroVtoViewProps, NitroVtoViewMethods>;

const vtoRef = useRef<VtoRef>(null);

vtoRef.current?.hideGlasses();
vtoRef.current?.showGlasses();

// Attach via the `hybridRef` prop on <NitroVtoView ... />:
// hybridRef={(ref) => { vtoRef.current = ref; }}
```

## Props

| Prop | Type | Default | Description |
| --- | --- | --- | --- |
| `modelUrl` | `string` | — | URL to the GLB model (meters, real-world size) |
| `isActive` | `boolean` | — | Whether the AR session is running |
| `forwardOffset` | `number` | `0.005` | Forward offset (meters) for fine-tuning glasses position |
| `debug` | `boolean` | `false` | Debug visualization (red=face mesh, green/blue=planes) |
| `onModelLoaded` | `(url: string) => void` | — | Fires once per model load (wrap with `callback()`) |
| `onFaceTracked` | `() => void` | — | Fires on first face-tracked frame per session (wrap with `callback()`) |
| `onGlassesDisplayed` | `(url: string) => void` | — | Fires once glasses are rendered on the tracked face (wrap with `callback()`) |
| `style` | `ViewStyle` | — | Standard React Native view styles |

## Methods

| Method | Description |
| --- | --- |
| `hideGlasses()` | Hide the glasses + face occlusion meshes. Sticky across frames. AR session and face tracking state are untouched. |
| `showGlasses()` | Show them again after `hideGlasses()`. No-op if they weren't hidden. |

## Differences from `@alaneu/react-native-vto`

- Requires `react-native-nitro-modules` at runtime; the classic wrapper doesn't.
- Callbacks **must** be wrapped in `callback()`; the classic wrapper accepts plain JS functions.
- Methods exposed via `hybridRef` (Nitro pattern); the classic wrapper uses `ref` / `useImperativeHandle`.
- Rendering / face tracking / materials are identical — both packages consume the same private `@alaneu/vto-core-native` core, bundled at install time.

## License

MIT
