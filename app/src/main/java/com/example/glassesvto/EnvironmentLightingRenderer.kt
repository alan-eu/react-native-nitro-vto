package com.example.glassesvto

import android.content.Context
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
        private const val BASE_INTENSITY = 30_000f
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
        iblPath: String = "envs/studio_small_02_ibl.ktx",
        skyboxPath: String = "envs/studio_small_02_skybox.ktx"
    ) {
        this.engine = engine

        // Load IBL (indirect light) from ktx file
        val iblBuffer = LoaderUtils.loadAsset(context, iblPath)
        val iblBundle = KTX1Loader.createIndirectLight(engine, iblBuffer)
        indirectLight = iblBundle.indirectLight
        indirectLight?.intensity = BASE_INTENSITY
        scene.indirectLight = indirectLight

        // Load skybox from ktx file
        val skyBuffer = LoaderUtils.loadAsset(context, skyboxPath)
        val skyboxBundle = KTX1Loader.createSkybox(engine, skyBuffer)
        skybox = skyboxBundle.skybox
        scene.skybox = skybox
    }

    /**
     * Update lighting intensity based on ARCore light estimation.
     * Should be called each frame with the current ARCore frame.
     */
    fun updateFromARCore(frame: Frame) {
        val lightEstimate = frame.lightEstimate
        if (lightEstimate.state == LightEstimate.State.VALID) {
            val pixelIntensity = lightEstimate.pixelIntensity
            indirectLight?.intensity = BASE_INTENSITY * pixelIntensity
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
