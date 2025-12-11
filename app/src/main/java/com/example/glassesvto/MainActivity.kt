package com.example.glassesvto

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.util.EnumSet

/**
 * Main activity for Glasses Virtual Try-On.
 * Uses ARCore for face tracking and Filament for 3D rendering.
 */
class MainActivity : AppCompatActivity() {

    // ARCore session
    private var arSession: Session? = null

    // SurfaceView for rendering
    private lateinit var surfaceView: SurfaceView

    // Filament renderer
    private lateinit var vtoRenderer: VTORenderer

    // Camera permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            setupArSession()
        } else {
            Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create SurfaceView
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)

        // Create and initialize renderer
        vtoRenderer = VTORenderer(this)
        vtoRenderer.initialize(surfaceView)
    }

    override fun onResume() {
        super.onResume()

        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            setupArSession()
            vtoRenderer.resume()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        vtoRenderer.pause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
        vtoRenderer.destroy()
    }

    /**
     * Sets up the ARCore session with face tracking
     */
    private fun setupArSession() {
        if (arSession != null) {
            arSession?.resume()
            vtoRenderer.session = arSession
            return
        }

        try {
            // Check ARCore availability
            when (ArCoreApk.getInstance().requestInstall(this, true)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return
                ArCoreApk.InstallStatus.INSTALLED -> { /* Continue */ }
            }

            // Create AR session with front camera for face tracking
            arSession = Session(this, EnumSet.of(Session.Feature.FRONT_CAMERA))
            
            // Configure session for face tracking
            val config = Config(arSession).apply {
                augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
                planeFindingMode = Config.PlaneFindingMode.DISABLED
                lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                // Enable depth if supported by device
                depthMode = if (arSession!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
            }
            arSession?.configure(config)

            // Resume session
            arSession?.resume()

            // Connect session to renderer
            vtoRenderer.session = arSession

        } catch (e: UnavailableArcoreNotInstalledException) {
            showError("ARCore is not installed")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            showError("This device does not support AR")
        } catch (e: UnavailableSdkTooOldException) {
            showError("Please update ARCore")
        } catch (e: UnavailableApkTooOldException) {
            showError("Please update this app")
        } catch (e: CameraNotAvailableException) {
            showError("Camera not available")
        } catch (e: Exception) {
            showError("Failed to create AR session: ${e.message}")
        }
    }

    /**
     * Shows an error message and finishes the activity
     */
    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
