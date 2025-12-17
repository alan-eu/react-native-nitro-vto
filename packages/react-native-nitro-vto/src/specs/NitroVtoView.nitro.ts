import type {
  HybridView,
  HybridViewProps,
  HybridViewMethods,
} from "react-native-nitro-modules";

/**
 * Props for the NitroVtoView component.
 */
export interface NitroVtoViewProps extends HybridViewProps {
  /**
   * The URL to the glasses model file (GLB format).
   * Example: "https://example.com/glasses.glb"
   */
  modelUrl: string;

  /**
   * The width of the glasses frame in meters.
   * Used for proper scaling on the face.
   */
  modelWidthMeters: number;

  /**
   * Whether the AR session is active.
   * Set to false to pause face tracking and rendering.
   */
  isActive: boolean;
}

/**
 * Methods available on the NitroVtoView component.
 */
export interface NitroVtoViewMethods extends HybridViewMethods {
  /**
   * Switch to a different glasses model at runtime.
   * @param modelUrl - URL to the new model file (GLB format)
   * @param widthMeters - Width of the new model in meters
   */
  switchModel(modelUrl: string, widthMeters: number): void;

  /**
   * Reset the AR session and face tracking.
   */
  resetSession(): void;
}

/**
 * NitroVtoView is a native view component for glasses virtual try-on.
 * It uses ARCore for face tracking and Filament for 3D rendering.
 *
 * Android-only: Uses ARCore which is not available on iOS.
 */
export type NitroVtoView = HybridView<
  NitroVtoViewProps,
  NitroVtoViewMethods,
  { android: "kotlin" }
>;
