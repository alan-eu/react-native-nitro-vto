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
   * Enable face mesh occlusion (depth-only face mesh clips glasses behind the face).
   * Default: true.
   */
  faceMeshOcclusion?: boolean;

  /**
   * Enable back plane occlusion (depth planes behind the head clip temples that
   * extend too far back).
   * Default: true.
   */
  backPlaneOcclusion?: boolean;

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

  /** Fires once when the glTF model finishes loading. */
  onModelLoaded?: (modelUrl: string) => void;

  /**
   * Fires the first time face tracking enters the TRACKING state in the current
   * AR session. Does NOT fire again on face-lost-then-regained. Re-fires after
   * `resetSession()` or when the view is re-mounted.
   */
  onFaceTracked?: () => void;

  /**
   * Fires the first time the glasses model is rendered on the tracked face —
   * i.e. the first frame whose transform is driven by a valid face pose after
   * the model was loaded. Re-fires for each subsequent `switchModel`.
   */
  onGlassesDisplayed?: (modelUrl: string) => void;
}

export interface VtoCommonMethods {
  /** Switch to a different glasses model at runtime. */
  switchModel(modelUrl: string): void;

  /** Reset the AR session and face tracking. */
  resetSession(): void;
}
