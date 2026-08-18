package eu.alan.vto.core

/**
 * Tuning constants for preview mode's orbit camera. Mirrored on iOS in
 * PreviewConstants.h — keep the two in sync.
 *
 * Preview mode has no AR session: the glasses sit at the world origin on a solid
 * background and the camera orbits them (drag) and dollies (pinch). Angles are
 * radians, distances are meters unless stated otherwise.
 */
internal object PreviewConstants {
    /** Vertical field of view of the preview camera. */
    const val FOV_DEG = 60.0

    /**
     * Opening pose: straight on, facing the front of the frame — which the glb
     * authoring convention (ADR 0006) puts on +Z, so azimuth 0.
     */
    const val DEFAULT_AZIMUTH = 0.0f
    const val DEFAULT_ELEVATION = 0.0f

    /** Elevation stops short of the poles so the camera's up vector never flips. */
    const val MAX_ELEVATION = 1.4f

    /** Drag sensitivity, radians per dp of finger travel. */
    const val ORBIT_RADIANS_PER_DP = 0.008f

    /**
     * Framing distance: how much of the viewport the model's bounding sphere
     * fills. 1.0 fits the whole sphere edge to edge; below that the camera moves
     * in and crops into it — which the opening view does, because the sphere is
     * drawn around the bounding box's diagonal and a frame is far wider than it
     * is deep, so it leaves a lot of empty room at 1.0.
     */
    const val FRAMING_MARGIN = 0.88f

    /** Zoom limits, as multiples of the framed distance. */
    const val MIN_ZOOM_FACTOR = 0.35f
    const val MAX_ZOOM_FACTOR = 3.0f

    /**
     * Ear half-width (face-local meters) fed to temple articulation, so the
     * temples read as worn instead of folded. Roughly an average adult head.
     */
    const val EAR_HALF_WIDTH = 0.07f

    /** Background used until the app sets one, as an sRGB grey level. */
    const val DEFAULT_BACKGROUND = 0.06f
}
