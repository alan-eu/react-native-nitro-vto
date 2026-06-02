package eu.alan.vto.core

/**
 * Tuning constants shared between FaceOcclusionRenderer, DebugRenderer, and
 * GlassesRenderer. Single source of truth — change a value here and the
 * occluder, the debug overlay, and the temple articulation all stay in
 * agreement. Mirrored on iOS in OcclusionConstants.h.
 */
internal object OcclusionConstants {
    /**
     * Cheekbone -> ear-line factor. The ARCore face mesh stops at the
     * cheekbones, so we scale its half-width by this to estimate the ear's
     * lateral position.
     */
    const val EAR_MARGIN = 1.7f

    /** Vertical-padding factor applied to the back plane. */
    const val HEIGHT_MARGIN = 1.1f

    /**
     * Floor on the back plane half-width (meters). Keeps the plane sensible
     * if face tracking momentarily collapses.
     */
    const val MIN_HALF_WIDTH = 0.07f

    /**
     * X-only shrink applied to the face mesh when it writes depth. Pulls
     * the cheek edge inward away from where the temples pass; the
     * nose/eyes/brow at X≈0 stay put, so nose-bridge occlusion of the
     * glasses bridge is preserved.
     */
    const val FACE_MESH_X_SHRINK = 0.975f

    /**
     * Distance behind the deepest face-mesh vertex (face-local meters) at
     * which the back plane sits.
     */
    const val BACK_PLANE_Z_OFFSET = 0.03f

    /**
     * Scale applied to ear half-width when computing the temple-tip lateral
     * target. The back planes sit visually on the ears thanks to
     * perspective foreshortening (they live behind the head); the temple
     * tips sit at the ear's actual depth, so the same lateral target would
     * overshoot.
     */
    const val TEMPLE_TIP_SCALE = 0.6f

    /**
     * Downward pitch (degrees) of each temple about the hinge's lateral axis,
     * so the tips sit at ear height instead of riding slightly high. The rest
     * temple points back and a touch up; this drops the tip with negligible
     * depth change.
     */
    const val TEMPLE_DOWN_PITCH_DEG = 1.5f

    /**
     * Extra outward yaw (degrees) of each temple about the vertical axis,
     * applied on top of the lateral swing so the temple lifts off the facemesh
     * occluder and is not clipped by it. Complements FACE_MESH_X_SHRINK (which
     * pulls the cheek mesh inward); this angles the temple itself outward.
     */
    const val TEMPLE_OUTWARD_YAW_DEG = 2.0f

    /**
     * Default forward offset (face-local meters) of the glasses from the
     * nose bridge. Exposed as the `forwardOffset` JS prop; this is the
     * fallback when nothing is provided.
     */
    const val FORWARD_OFFSET = 0.005f
}
