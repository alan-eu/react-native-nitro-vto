/**
 * Shared TypeScript surface for both the Nitro (new-arch) and classic (old-arch)
 * VTO React Native wrappers. Edit here; `scripts/bundle.ts` copies this file into
 * each wrapper so their types stay in lockstep.
 */

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
   * Fires the first time the glasses model is rendered on the tracked face —
   * i.e. the first frame whose transform is driven by a valid face pose after
   * the model was loaded. Re-fires whenever `modelUrl` changes to a different
   * model.
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
