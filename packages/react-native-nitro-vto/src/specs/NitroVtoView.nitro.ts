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
   * Called exactly once per AR session, the first time face tracking enters
   * the TRACKING state. Does NOT fire again on face-lost-then-regained, and
   * is unaffected by `hideGlasses()` / `showGlasses()`. Re-fires only when
   * the view is re-mounted.
   */
  onFaceTracked?: () => void;

  /**
   * Called the first time the glasses model is rendered on the tracked face
   * — i.e. the first frame whose transform is driven by a valid face pose
   * after the model was loaded. Re-fires whenever `modelUrl` changes to a
   * different model.
   * @param modelUrl - The URL of the glasses model that became visible.
   */
  onGlassesDisplayed?: (modelUrl: string) => void;

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

  /**
   * Show a small native FPS counter in the top-right corner of the view.
   * Default: false.
   */
  showNativeFPS?: boolean;
}

/** Methods available on the NitroVtoView component. */
export interface NitroVtoViewMethods extends HybridViewMethods {
  /**
   * Hide the glasses and face occlusion meshes. Sticky: stays hidden across
   * frames until `showGlasses()` is called. The AR session keeps running and
   * face tracking state is untouched.
   *
   * To switch models, update the `modelUrl` prop instead.
   */
  hideGlasses(): void;

  /**
   * Show the glasses and face occlusion meshes again after `hideGlasses()`.
   * No-op if they weren't hidden.
   */
  showGlasses(): void;
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
