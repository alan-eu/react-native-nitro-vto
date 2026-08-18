/**
 * Shared TypeScript surface for both the Nitro (new-arch) and classic (old-arch)
 * VTO React Native wrappers. Edit here; `scripts/bundle.ts` copies this file into
 * each wrapper so their types stay in lockstep.
 */

/**
 * Why the view gave up on AR and settled into preview mode.
 *
 * - `"device-not-capable"` — ARCore says this device can't run AR at all.
 * - `"arcore-not-installed"` — ARCore is missing and still missing after we sent
 *   the user to install it once. Declined, cancelled and "the Play install
 *   failed" are indistinguishable from the app's side, so they all report this.
 *   We don't ask a second time.
 * - `"arcore-outdated"` — ARCore or this app is too old for the other.
 * - `"arcore-unavailable"` — ARCore couldn't give a verdict.
 * - `"face-tracking-unsupported"` — the device runs AR but not front-camera face
 *   tracking (also what the iOS simulator reports).
 */
export type ArUnavailableReason =
  | "device-not-capable"
  | "arcore-not-installed"
  | "arcore-outdated"
  | "arcore-unavailable"
  | "face-tracking-unsupported";

export interface VtoCommonProps {
  /**
   * URL to the glasses model file (GLB format). Models should be authored in
   * meters at real-world size.
   */
  modelUrl: string;

  /**
   * Whether the AR session is active. Set to `false` to pause face tracking
   * and rendering.
   */
  isActive: boolean;

  /**
   * Forward offset for glasses positioning in meters.
   * Default: 0.005 (5mm in front of the nose bridge).
   */
  forwardOffset?: number;

  /**
   * Debug visualization: renders colored overlays for face mesh (red) and
   * back planes (green/blue).
   * Default: false.
   */
  debug?: boolean;

  /**
   * Show a small native FPS counter in the top-right corner of the view.
   * Reads frames-per-second + frame-time-in-ms directly from the render
   * loop on each platform. Intended for performance profiling.
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
  mode?: "ar" | "preview";

  /**
   * Background behind the glasses in preview mode, as `#RGB`, `#RRGGBB` or
   * `#RRGGBBAA` (alpha ignored — the background is opaque). Ignored in AR mode,
   * where the camera feed is the background.
   * Default: near-black.
   */
  previewBackgroundColor?: string;

  /**
   * Marks the model as a clip-on / solar (tinted sunglass) frame. When true, the
   * engine renders the lens as a tinted sunglass (its own glass material, no IBL
   * chrome) instead of the glb's clear lens. Pass `false` for clear prescription
   * lenses. Required (not optional) on purpose — a nullable `is`-prefixed boolean
   * mis-maps in the Nitro Android codegen — so always pass an explicit boolean.
   */
  isClipOn: boolean;

  /** Fires once when the glTF model finishes loading. */
  onModelLoaded?: (modelUrl: string) => void;

  /**
   * Fires exactly once per AR session, the first time face tracking enters the
   * TRACKING state. Does NOT fire again on face-lost-then-regained, and is
   * unaffected by `hideGlasses()` / `showGlasses()`. Re-fires only when the
   * view is re-mounted.
   */
  onFaceTracked?: () => void;

  /**
   * Fires once when the view gives up on AR and settles into preview mode. Use
   * it to stop offering try-on, or to label the screen: the view is showing the
   * model in preview from then on, regardless of the `mode` prop.
   *
   * See {@link ArUnavailableReason} for what each reason means.
   */
  onArUnavailable?: (reason: ArUnavailableReason) => void;

  /**
   * Fires the first time the glasses model is rendered on the tracked face —
   * i.e. the first frame whose transform is driven by a valid face pose after
   * the model was loaded. In preview mode there is no face, so it fires on the
   * first frame the model is rendered. Re-fires whenever `modelUrl` changes to
   * a different model.
   */
  onGlassesDisplayed?: (modelUrl: string) => void;
}

export interface VtoCommonMethods {
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
