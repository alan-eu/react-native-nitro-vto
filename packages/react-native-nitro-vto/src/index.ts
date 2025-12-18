import { getHostComponent } from "react-native-nitro-modules";
import type {
  NitroVtoViewProps,
  NitroVtoViewMethods,
} from "./specs/NitroVtoView.nitro";
import NitroVtoViewConfig from "../nitrogen/generated/shared/json/NitroVtoViewConfig.json";
// Re-export types
export type { NitroVtoViewProps, NitroVtoViewMethods };

// Export the HybridRef type for use with hybridRef prop
export type { HybridRef } from "react-native-nitro-modules";

/**
 * NitroVtoView is a React Native component for virtual try-on of glasses.
 * It uses ARCore/ARKit for face tracking and Filament for 3D rendering.
 *
 * **Important**: Camera permissions must be granted before using this component.
 * The consuming app is responsible for requesting camera permissions.
 *
 * @example
 * ```tsx
 * import { NitroVtoView, type NitroVtoViewProps, type NitroVtoViewMethods, type HybridRef } from '@alaneu/react-native-nitro-vto'
 * import { useRef } from 'react'
 *
 * type NitroVtoRef = HybridRef<NitroVtoViewProps, NitroVtoViewMethods>
 *
 * function App() {
 *   const vtoRef = useRef<NitroVtoRef>(null)
 *
 *   const switchGlasses = () => {
 *     vtoRef.current?.switchModel('https://example.com/glasses.glb', 0.138)
 *   }
 *
 *   return (
 *     <NitroVtoView
 *       modelUrl="https://example.com/glasses.glb"
 *       modelWidthMeters={0.135}
 *       isActive={true}
 *       style={{ flex: 1 }}
 *       hybridRef={(ref) => {
 *         vtoRef.current = ref
 *       }}
 *     />
 *   )
 * }
 * ```
 */
export const NitroVtoView = getHostComponent<
  NitroVtoViewProps,
  NitroVtoViewMethods
>("NitroVtoView", () => NitroVtoViewConfig);
