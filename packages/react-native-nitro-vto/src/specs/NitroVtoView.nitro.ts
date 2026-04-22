import type {
  HybridView,
  HybridViewProps,
  HybridViewMethods,
} from "react-native-nitro-modules";

/**
 * Props for the NitroVtoView component.
 *
 * NOTE: This shape is kept in lockstep with `packages/vto-core-native/src/types.ts`
 * (the single source of truth for both the Nitro and classic wrappers).
 * When editing the prop surface, update both files. The `scripts/bundle.ts` in
 * core copies `types.ts` into the classic wrapper verbatim; Nitro keeps its own
 * copy inline here because it has to be resolvable by nitrogen at build time
 * (before workspace `postinstall` runs).
 */
export interface NitroVtoViewProps extends HybridViewProps {
  /**
   * The URL to the glasses model file (GLB format).
   * Models should be authored in meters at real-world size.
   */
  modelUrl: string;

  /**
   * Whether the AR session is active. Set to `false` to pause face tracking
   * and rendering.
   */
  isActive: boolean;

  /** Callback invoked when model loading completes. */
  onModelLoaded?: (modelUrl: string) => void;

  /**
   * Called the first time face tracking enters the TRACKING state in the
   * current AR session. Does NOT fire again on face-lost-then-regained.
   * Re-fires after `resetSession()` or when the view is re-mounted.
   */
  onFaceTracked?: () => void;

  /**
   * Called the first time the glasses model is rendered on the tracked face
   * — i.e. the first frame whose transform is driven by a valid face pose
   * after the model was loaded. Re-fires for each subsequent `switchModel`.
   * @param modelUrl - The URL of the glasses model that became visible.
   */
  onGlassesDisplayed?: (modelUrl: string) => void;

  /**
   * Enable face mesh occlusion (glasses appear behind face edges).
   * Default: true.
   */
  faceMeshOcclusion?: boolean;

  /**
   * Enable back plane occlusion (clips glasses temples behind the head).
   * Default: true.
   */
  backPlaneOcclusion?: boolean;

  /**
   * Forward offset for glasses positioning in meters.
   * Default: 0.005 (5mm forward).
   */
  forwardOffset?: number;

  /**
   * Debug visualization: face mesh (red), back planes (green/blue).
   * Default: false.
   */
  debug?: boolean;
}

/** Methods available on the NitroVtoView component. */
export interface NitroVtoViewMethods extends HybridViewMethods {
  /** Switch to a different glasses model at runtime. */
  switchModel(modelUrl: string): void;

  /** Reset the AR session and face tracking. */
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
