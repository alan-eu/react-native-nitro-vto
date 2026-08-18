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
   * Called once when the view gives up on AR and settles into preview mode.
   * Reason is one of `device-not-capable`, `arcore-not-installed`,
   * `arcore-outdated`, `arcore-unavailable`, `face-tracking-unsupported`. The view is showing the model in preview from
   * then on, whatever the `mode` prop says.
   * @param reason - Why AR is unavailable.
   */
  onArUnavailable?: (reason: string) => void;

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

  /**
   * `"ar"` (default) tries the glasses on a tracked face through the camera.
   * `"preview"` drops the camera and the AR session entirely and shows the
   * model on a flat background (`previewBackgroundColor`), which the user can
   * drag to orbit and pinch to zoom. Preview mode needs no camera permission.
   *
   * The view also falls back to preview on its own where face tracking is
   * unavailable (simulator, emulator, device without the required camera).
   */
  mode?: string;

  /**
   * Background behind the glasses in preview mode, as `#RGB`, `#RRGGBB` or
   * `#RRGGBBAA` (alpha ignored — the background is opaque). Ignored in AR mode.
   * Default: near-black.
   */
  previewBackgroundColor?: string;

  /**
   * Marks the model as a clip-on / solar (tinted sunglass) frame, so the engine
   * renders the lens as a tinted sunglass (its own glass material, no IBL chrome)
   * instead of a clear lens. Pass `false` for clear lenses. Required (not
   * optional): a nullable `is`-prefixed boolean mis-maps in the Nitro Android
   * codegen, so always pass an explicit boolean.
   */
  isClipOn: boolean;
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
