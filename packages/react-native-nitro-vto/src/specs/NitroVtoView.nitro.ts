import type {
  HybridView,
  HybridViewProps,
  HybridViewMethods,
} from "react-native-nitro-modules";

/**
 * Occlusion settings for controlling face mesh and back plane occlusion.
 */
export interface OcclusionSettings {
  /**
   * Whether to enable face mesh occlusion.
   * When enabled, the face mesh writes to depth buffer to occlude glasses behind the face.
   */
  faceMesh: boolean;

  /**
   * Whether to enable back plane occlusion.
   * When enabled, a plane behind the face clips glasses temples that extend too far back.
   */
  backPlane: boolean;
}

/**
 * Props for the NitroVtoView component.
 */
export interface NitroVtoViewProps extends HybridViewProps {
  /**
   * The URL to the glasses model file (GLB format).
   * Models should be authored in meters at real-world size.
   * Example: "https://example.com/glasses.glb"
   */
  modelUrl: string;

  /**
   * Whether the AR session is active.
   * Set to false to pause face tracking and rendering.
   */
  isActive: boolean;

  /**
   * Callback invoked when model loading completes.
   * @param modelUrl - The URL of the model that was loaded
   */
  onModelLoaded?: (modelUrl: string) => void;

  /**
   * Occlusion settings for controlling face mesh and back plane occlusion.
   * Default: { faceMesh: true, backPlane: true }
   */
  occlusion?: OcclusionSettings;
}

/**
 * Methods available on the NitroVtoView component.
 */
export interface NitroVtoViewMethods extends HybridViewMethods {
  /**
   * Switch to a different glasses model at runtime.
   * @param modelUrl - URL to the new model file (GLB format)
   */
  switchModel(modelUrl: string): void;

  /**
   * Reset the AR session and face tracking.
   */
  resetSession(): void;
}

/**
 * NitroVtoView is a native view component for glasses virtual try-on.
 * It uses ARCore/ARKit for face tracking and Filament for 3D rendering.
 */
export type NitroVtoView = HybridView<
  NitroVtoViewProps,
  NitroVtoViewMethods,
  { android: "kotlin"; ios: "swift" }
>;
