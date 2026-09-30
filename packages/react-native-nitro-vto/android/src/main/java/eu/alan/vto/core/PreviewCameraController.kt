package eu.alan.vto.core

import com.google.android.filament.Camera
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Orbit camera for preview mode (no AR session, no face).
 *
 * The model stays where it is and the camera moves around it: state is spherical
 * around [target] — azimuth about Y, elevation clamped short of the poles, and a
 * distance clamped to the framing computed from the model's bounds. Mirrored on
 * iOS in PreviewCameraController.mm.
 */
internal class PreviewCameraController {

    private val target = floatArrayOf(0f, 0f, 0f)
    private var azimuth = PreviewConstants.DEFAULT_AZIMUTH
    private var elevation = PreviewConstants.DEFAULT_ELEVATION

    // Bounding radius of the framed model, and the zoom as a multiple of the
    // distance that frames it. The distance itself is derived per frame, because
    // what fits depends on the viewport aspect. Until a model is framed, a
    // glasses-sized default keeps the first frames sane rather than putting the
    // camera inside the model.
    private var radius = 0.1f
    private var zoom = 1f

    /**
     * Frame a model: [centerX]/[centerY]/[centerZ] and [radius] are its
     * world-space bounding sphere. Sets the orbit target, the distance that fits
     * it in view, and the zoom limits (which are relative to that distance).
     * Also restores the opening angles.
     */
    fun frameBounds(centerX: Float, centerY: Float, centerZ: Float, radius: Float) {
        target[0] = centerX
        target[1] = centerY
        target[2] = centerZ
        this.radius = max(radius, 0.001f)
        zoom = 1f

        azimuth = PreviewConstants.DEFAULT_AZIMUTH
        elevation = PreviewConstants.DEFAULT_ELEVATION
    }

    /**
     * Distance at which the bounding sphere fits the *narrower* of the two
     * fields of view — on a portrait viewport that's the horizontal one, and
     * fitting the vertical would run the model off the sides.
     */
    private fun framedDistance(aspect: Double): Float {
        val halfFovV = (PreviewConstants.FOV_DEG * PI / 180.0).toFloat() * 0.5f
        val tanV = tan(halfFovV)
        val tanH = tanV * (if (aspect > 0.0) aspect else 1.0).toFloat()
        return (radius / minOf(tanV, tanH)) * PreviewConstants.FRAMING_MARGIN
    }

    /**
     * Drag: screen-space finger delta in dp. Right/down drags orbit the camera
     * left/up around the model, the way dragging the object itself would.
     */
    fun orbitBy(dx: Float, dy: Float) {
        azimuth -= dx * PreviewConstants.ORBIT_RADIANS_PER_DP
        elevation = (elevation + dy * PreviewConstants.ORBIT_RADIANS_PER_DP)
            .coerceIn(-PreviewConstants.MAX_ELEVATION, PreviewConstants.MAX_ELEVATION)
    }

    /** Pinch: [scale] > 1 (fingers apart) moves the camera closer. */
    fun zoomBy(scale: Float) {
        if (scale <= 0f) return
        zoom = (zoom / scale).coerceIn(
            PreviewConstants.MIN_ZOOM_FACTOR,
            PreviewConstants.MAX_ZOOM_FACTOR
        )
    }

    /**
     * Point [camera] at the target from the current orbit pose and apply the
     * preview projection for [aspect] (width / height).
     */
    fun applyTo(camera: Camera, aspect: Double) {
        val distance = framedDistance(aspect) * zoom
        val cosElevation = cos(elevation)
        val eyeX = target[0] + distance * cosElevation * sin(azimuth)
        val eyeY = target[1] + distance * sin(elevation)
        val eyeZ = target[2] + distance * cosElevation * cos(azimuth)

        // Near/far track the orbit distance: a fixed near plane would z-fight on
        // a close zoom, and a fixed far plane would clip when zoomed out.
        val near = max(0.001, distance * 0.01)
        val far = distance * 10.0

        camera.setProjection(
            PreviewConstants.FOV_DEG,
            if (aspect > 0.0) aspect else 1.0,
            near,
            far,
            Camera.Fov.VERTICAL
        )
        camera.lookAt(
            eyeX.toDouble(), eyeY.toDouble(), eyeZ.toDouble(),
            target[0].toDouble(), target[1].toDouble(), target[2].toDouble(),
            0.0, 1.0, 0.0
        )
    }
}
