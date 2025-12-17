import { getHostComponent } from "react-native-nitro-modules";
import type {
  GlassesVTOViewProps,
  GlassesVTOViewMethods,
} from "./specs/GlassesVTOView.nitro";
import GlassesVTOViewConfig from "../nitrogen/generated/shared/json/GlassesVTOViewConfig.json";
// Re-export types
export type { GlassesVTOViewProps, GlassesVTOViewMethods };

// Export the HybridRef type for use with hybridRef prop
export type { HybridRef } from "react-native-nitro-modules";

/**
 * GlassesVTOView is a React Native component for virtual try-on of glasses.
 * It uses ARCore for face tracking and Filament for 3D rendering.
 *
 * **Important**: Camera permissions must be granted before using this component.
 * The consuming app is responsible for requesting camera permissions.
 *
 * **Android-only**: This component uses ARCore which is not available on iOS.
 *
 * @example
 * ```tsx
 * import { GlassesVTOView, type GlassesVTOViewProps, type GlassesVTOViewMethods, type HybridRef } from 'react-native-glasses-vto'
 * import { useRef } from 'react'
 *
 * type GlassesVTORef = HybridRef<GlassesVTOViewProps, GlassesVTOViewMethods>
 *
 * function App() {
 *   const vtoRef = useRef<GlassesVTORef>(null)
 *
 *   const switchGlasses = () => {
 *     vtoRef.current?.switchModel('models/680048.glb', 0.138)
 *   }
 *
 *   return (
 *     <GlassesVTOView
 *       modelPath="models/878082.glb"
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
export const GlassesVTOView = getHostComponent<
  GlassesVTOViewProps,
  GlassesVTOViewMethods
>("GlassesVTOView", () => GlassesVTOViewConfig);
