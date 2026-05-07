package eu.alan.vto.core

import android.content.Context
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.utils.KTX1Loader
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate

/**
 * Handles environment-based lighting (IBL) for AR rendering.
 * Loads skybox and indirect light from KTX files and updates
 * intensity based on ARCore light estimation.
 */
class EnvironmentLightingRenderer(private val context: Context) {

    companion object {
        private const val TAG = "EnvironmentLighting"
        // IBL intensity tuning lives in LightingConstants.
    }

    private var indirectLight: IndirectLight? = null
    private var skybox: Skybox? = null
    private lateinit var engine: Engine

    /**
     * Setup environment lighting with IBL from KTX files.
     * @param engine Filament engine
     * @param scene Filament scene to apply lighting to
     * @param iblPath Path to IBL KTX file in assets
     * @param skyboxPath Path to skybox KTX file in assets
     */
    fun setup(
        engine: Engine,
        scene: Scene,
        iblPath: String = "envs/studio_small_02_2k_ibl.ktx",
        skyboxPath: String = "envs/studio_small_02_2k_skybox.ktx",
        shPath: String = "envs/studio_small_02_2k_sh.txt"
    ) {
        this.engine = engine

        // Load IBL texture (reflections only — matches iOS approach)
        val iblBuffer = LoaderUtils.loadAsset(context, iblPath)
        val iblTexture = KTX1Loader.createTexture(engine, iblBuffer)

        val builder = IndirectLight.Builder()
            .reflections(iblTexture)
            .intensity(LightingConstants.BASE_INTENSITY)

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
     * Update lighting intensity based on ARCore light estimation.
     * Should be called each frame with the current ARCore frame.
     */
    fun updateFromARCore(frame: Frame) {
        val lightEstimate = frame.lightEstimate
        if (lightEstimate.state == LightEstimate.State.VALID) {
            val pixelIntensity = lightEstimate.pixelIntensity
            indirectLight?.intensity = LightingConstants.BASE_INTENSITY + pixelIntensity * LightingConstants.INTENSITY_FACTOR
        }
    }

    /**
     * Destroy all lighting resources.
     */
    fun destroy() {
        indirectLight?.let { engine.destroyIndirectLight(it) }
        skybox?.let { engine.destroySkybox(it) }
    }
}
