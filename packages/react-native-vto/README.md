# @alaneu/react-native-vto

React Native **old architecture** (Paper) wrapper for the glasses virtual try-on library. Same native rendering code as [`@alaneu/react-native-nitro-vto`](https://github.com/alan-eu/react-native-nitro-vto), no `react-native-nitro-modules` dependency — install this if your app runs with `newArchEnabled=false`.

The two packages are mutually exclusive. Pick the one that matches your RN architecture; they can't coexist in the same app.

## Install

```bash
npm install @alaneu/react-native-vto
cd ios && pod install
```

## Usage

```tsx
import { VtoView, type VtoRef } from "@alaneu/react-native-vto";
import { useRef } from "react";

function App() {
  const vtoRef = useRef<VtoRef>(null);

  return (
    <VtoView
      ref={vtoRef}
      style={{ flex: 1 }}
      modelUrl="https://example.com/glasses.glb"
      isActive={true}
      faceMeshOcclusion={true}
      backPlaneOcclusion={true}
      forwardOffset={0.005}
      onModelLoaded={(url) => console.log("loaded", url)}
      onFaceTracked={() => console.log("face tracked")}
      onGlassesDisplayed={(url) => console.log("displayed", url)}
    />
  );
}
```

Methods via `ref`:

```ts
vtoRef.current?.switchModel("https://example.com/other.glb");
vtoRef.current?.resetSession();
```

### Props

| Prop | Type | Default | Description |
| --- | --- | --- | --- |
| `modelUrl` | `string` | — | URL to the GLB model (meters, real-world size) |
| `isActive` | `boolean` | — | Whether the AR session is running |
| `faceMeshOcclusion` | `boolean` | `true` | Glasses appear behind face edges |
| `backPlaneOcclusion` | `boolean` | `true` | Clips glasses temples extending behind head |
| `forwardOffset` | `number` | `0.005` | Forward offset (meters) for fine-tuning glasses position |
| `debug` | `boolean` | `false` | Debug visualization (red=face mesh, green/blue=planes) |
| `onModelLoaded` | `(url: string) => void` | — | Fires once per model load |
| `onFaceTracked` | `() => void` | — | Fires on first face-tracked frame per session |
| `onGlassesDisplayed` | `(url: string) => void` | — | Fires once glasses are rendered on the tracked face |

### Methods

| Method | Description |
| --- | --- |
| `switchModel(url: string)` | Swap the glasses model at runtime |
| `resetSession()` | Reset AR session and face tracking |

## Differences from `@alaneu/react-native-nitro-vto`

- No `react-native-nitro-modules` dep — callbacks are plain JS functions (no `callback()` wrapper).
- Methods are exposed via `ref` / `useImperativeHandle`, not Nitro's `hybridRef`.
- Rendering / face tracking / materials are identical — both packages consume the same private `@alaneu/vto-core-native` core, bundled at install time.
