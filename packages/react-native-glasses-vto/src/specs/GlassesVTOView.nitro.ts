import type {
  HybridView,
  HybridViewProps,
  HybridViewMethods,
} from 'react-native-nitro-modules'

/**
 * Props for the GlassesVTOView component.
 */
export interface GlassesVTOViewProps extends HybridViewProps {
  /**
   * The path to the glasses model file (GLB format).
   * This should be a path relative to the assets folder.
   */
  modelPath: string

  /**
   * The width of the glasses frame in meters.
   * Used for proper scaling on the face.
   */
  modelWidthMeters: number

  /**
   * Whether the AR session is active.
   * Set to false to pause face tracking and rendering.
   */
  isActive: boolean
}

/**
 * Methods available on the GlassesVTOView component.
 */
export interface GlassesVTOViewMethods extends HybridViewMethods {
  /**
   * Switch to a different glasses model at runtime.
   * @param modelPath - Path to the new model file
   * @param widthMeters - Width of the new model in meters
   */
  switchModel(modelPath: string, widthMeters: number): void

  /**
   * Reset the AR session and face tracking.
   */
  resetSession(): void
}

/**
 * GlassesVTOView is a native view component for glasses virtual try-on.
 * It uses ARCore for face tracking and Filament for 3D rendering.
 *
 * Android-only: Uses ARCore which is not available on iOS.
 */
export type GlassesVTOView = HybridView<
  GlassesVTOViewProps,
  GlassesVTOViewMethods,
  { android: 'kotlin' }
>
