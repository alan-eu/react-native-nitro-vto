package eu.alan.vto.core

import android.content.Context
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.utils.KTX1Loader
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import kotlin.math.pow

/**
 * Handles environment-based lighting for AR rendering.
 *
 * - Static studio IBL (specular cubemap + baked SH) loaded once at
 *   setup; never modulated per frame (see ADR 0011).
 * - Per-frame directional ("sun") light driven by ARCore's
 *   AMBIENT_INTENSITY mode color-correction signal (the only mode
 *   ARCore exposes for front-camera face tracking).
 */
class EnvironmentLightingRenderer(private val context: Context) {

    companion object {
        private const val TAG = "EnvironmentLighting"
    }

    private var indirectLight: IndirectLight? = null
    private var skybox: Skybox? = null
    private lateinit var engine: Engine
    private lateinit var scene: Scene

    @Entity private var directionalLightEntity: Int = 0
    private var directionalLightAddedToScene = false

    // Reusable scratch for getColorCorrection (RGB + intensity).
    private val colorCorrection = FloatArray(4)

    /**
     * Setup environment lighting + create the directional light entity.
     */
    fun setup(
        engine: Engine,
        scene: Scene,
        iblPath: String = "envs/studio_small_02_2k_ibl.ktx",
        skyboxPath: String = "envs/studio_small_02_2k_skybox.ktx",
        shPath: String = "envs/studio_small_02_2k_sh.txt"
    ) {
        this.engine = engine
        this.scene = scene

        // Load IBL texture (reflections only — matches iOS approach)
        val iblBuffer = LoaderUtils.loadAsset(context, iblPath)
        val iblTexture = KTX1Loader.createTexture(engine, iblBuffer)

        val builder = IndirectLight.Builder()
            .reflections(iblTexture)
            .intensity(LightingConstants.STATIC_IBL_INTENSITY)

        // Load spherical harmonics for irradiance (diffuse IBL) from sh.txt
        val sh = loadSphericalHarmonics(shPath)
        if (sh != null) {
            builder.irradiance(3, sh)
            Log.d(TAG, "Loaded SH irradiance (3 bands)")
        }

        indirectLight = builder.build(engine)
        scene.indirectLight = indirectLight

        // Load skybox from ktx file
        val skyBuffer = LoaderUtils.loadAsset(context, skyboxPath)
        val skyboxBundle = KTX1Loader.createSkybox(engine, skyBuffer)
        skybox = skyboxBundle.skybox
        scene.skybox = skybox

        // Create the directional ("sun") light. ARCore's
        // AMBIENT_INTENSITY mode doesn't expose a direction on the front
        // camera, so we pick a plausible top-front studio direction
        // (~45° down from above the user). This keeps the lens specular
        // highlight off-center — pointing the light straight into the
        // face (e.g. (0, 0, -1)) produces an obvious centered hotspot
        // when the user faces the camera, which iOS doesn't have (its
        // direction tracks ARKit's primaryLightDirection). Intensity +
        // color are overridden each frame by updateDirectionalFromARCore.
        directionalLightEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(0.0f)                       // overridden per-frame
            .direction(0.196f, -0.819f, -0.539f)   // fixed top-front, 55° down, 20° toward +X (highlight ≈ 25% from top, 75% from left)
            .castShadows(false)
            .build(engine, directionalLightEntity)
        scene.addEntity(directionalLightEntity)
        directionalLightAddedToScene = true
    }

    private fun loadSphericalHarmonics(shPath: String): FloatArray? {
        return try {
            val text = context.assets.open(shPath).bufferedReader().readText()
            val coeffs = FloatArray(27) // 9 coefficients * 3 components (RGB)
            var idx = 0
            for (line in text.lines()) {
                if (idx >= 9) break
                val trimmed = line.trim()
                if (trimmed.isEmpty() || !trimmed.startsWith("(")) continue
                val values = trimmed
                    .substringAfter("(")
                    .substringBefore(")")
                    .split(",")
                    .map { it.trim().toFloat() }
                if (values.size == 3) {
                    coeffs[idx * 3] = values[0]
                    coeffs[idx * 3 + 1] = values[1]
                    coeffs[idx * 3 + 2] = values[2]
                    idx++
                }
            }
            if (idx == 9) coeffs else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load SH file: ${e.message}")
            null
        }
    }

    /**
     * Update the directional light from ARCore's AMBIENT_INTENSITY light
     * estimate. Recipe ported from sceneview's LightEstimator: pull
     * colorCorrection (RGB + intensity), normalize RGB by max channel,
     * convert sRGB → linear, scale intensity by the empirical 1.8×.
     */
    fun updateDirectionalFromARCore(frame: Frame) {
        val estimate = frame.lightEstimate
        if (estimate.state != LightEstimate.State.VALID) return

        estimate.getColorCorrection(colorCorrection, 0)
        var r = colorCorrection[0]
        var g = colorCorrection[1]
        var b = colorCorrection[2]
        val rawIntensity = colorCorrection[3]

        // Normalize RGB by the max channel so hue is preserved at any
        // intensity (sceneview pattern).
        val maxChannel = kotlin.math.max(kotlin.math.max(r, g), kotlin.math.max(b, 1e-4f))
        r /= maxChannel; g /= maxChannel; b /= maxChannel

        // sRGB → linear (piecewise curve, not gamma-2.2 approximation).
        r = srgbToLinear(r); g = srgbToLinear(g); b = srgbToLinear(b)

        val factor = (rawIntensity * LightingConstants.ARCORE_INTENSITY_FACTOR)
            .coerceIn(0.0f, 2.0f)
        val intensityLux = factor * LightingConstants.DIRECTIONAL_INTENSITY_MAX

        val lm = engine.lightManager
        val instance = lm.getInstance(directionalLightEntity)
        lm.setColor(instance, r, g, b)
        lm.setIntensity(instance, intensityLux)
    }

    private fun srgbToLinear(c: Float): Float {
        return if (c <= 0.04045f) c / 12.92f
        else (((c + 0.055f) / 1.055f).toDouble().pow(2.4)).toFloat()
    }

    /**
     * Destroy all lighting resources.
     */
    fun destroy() {
        if (directionalLightAddedToScene) {
            scene.removeEntity(directionalLightEntity)
            directionalLightAddedToScene = false
        }
        if (directionalLightEntity != 0) {
            engine.lightManager.destroy(directionalLightEntity)
            EntityManager.get().destroy(directionalLightEntity)
        }
        indirectLight?.let { engine.destroyIndirectLight(it) }
        skybox?.let { engine.destroySkybox(it) }
    }
}
