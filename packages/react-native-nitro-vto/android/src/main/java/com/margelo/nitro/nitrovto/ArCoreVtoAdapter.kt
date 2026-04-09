package com.margelo.nitro.nitrovto

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Coordinates2d
import com.google.ar.core.LightEstimate
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.io.File
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ArCoreVtoAdapter(private val context: Context) {

    companion object {
        private const val TAG = "ArCoreVtoAdapter"
        private const val TESTBED_PARITY_MODE = false
        private const val ARCORE_NOSE_BRIDGE_LEFT_INDEX = 351
        private const val ARCORE_NOSE_BRIDGE_RIGHT_INDEX = 122
        private const val MATERIAL_CAMERA = "materials/camera_background.filamat"
        private const val MATERIAL_FACE_OCCLUSION = "materials/face_occlusion.filamat"
        private const val MATERIAL_DEBUG_FACE = "materials/debug_face_material.filamat"
        private const val MATERIAL_DEBUG_PLANE = "materials/debug_plane_material.filamat"
        private const val ENV_IBL = "envs/studio_small_02_2k_ibl.ktx"
        private const val ENV_SKYBOX = "envs/studio_small_02_2k_skybox.ktx"
        private const val ENV_SH = "envs/studio_small_02_2k_sh.txt"
    }

    var session: Session? = null
        set(value) {
            field = value
            sharedCamera = try {
                value?.sharedCamera
            } catch (_: Throwable) {
                null
            }
            sharedCameraId = try {
                value?.cameraConfig?.cameraId
            } catch (_: Throwable) {
                null
            }
            if (initialized) {
                startSharedCameraIfNeeded()
            }
        }
    var onModelLoaded: ((modelUrl: String) -> Unit)? = null

    private var coreHandle: Long = 0L
    private var initialized = false
    private var modelUrl: String = ""

    private var faceMeshOcclusionEnabled = true
    private var backPlaneOcclusionEnabled = true
    private var forwardOffsetMeters = 0.005f
    private var debugEnabled = false

    private var surfaceViewRef: SurfaceView? = null
    private var width = 0
    private var height = 0
    private var lastDisplayRotation = -1
    private var lastDisplayWidth = 0
    private var lastDisplayHeight = 0
    private var activeFace: AugmentedFace? = null
    private val modelLoadVersion = AtomicInteger(0)

    private val cameraManager: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var sharedCamera: com.google.ar.core.SharedCamera? = null
    private var sharedCameraId: String? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var cameraStreamTexture: SurfaceTexture? = null
    private var cameraStreamSurface: Surface? = null
    private var sharedCameraStarted = false
    private var openingSharedCamera = false
    private var isPaused = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelLoaderExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val cameraModelMatrix = FloatArray(16)
    private val coreFaceMatrix = FloatArray(16)
    private val coreFaceQuaternion = FloatArray(4)
    private val cameraUvCoords = FloatArray(8)
    private val viewNormalizedCoords = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f
    )

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            syncViewportFromSurfaceView()
            bindSurface(holder.surface)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this@ArCoreVtoAdapter.width = width
            this@ArCoreVtoAdapter.height = height
            if (coreHandle != 0L) {
                nativeResizeCore(coreHandle, width, height)
            }
            bindSurface(holder.surface)
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            stopSharedCamera()
            if (coreHandle != 0L) {
                nativeSetSurfaceCore(coreHandle, null)
            }
        }
    }

    private val cameraCaptureCallback = object : CameraCaptureSession.CaptureCallback() {}

    private val captureSessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            val device = cameraDevice ?: run {
                session.close()
                return
            }
            val arSharedCamera = sharedCamera ?: run {
                session.close()
                return
            }
            val streamSurface = cameraStreamSurface ?: run {
                session.close()
                return
            }
            val handler = cameraHandler ?: run {
                session.close()
                return
            }

            try {
                val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                arSharedCamera.arCoreSurfaces.forEach { requestBuilder.addTarget(it) }
                requestBuilder.addTarget(streamSurface)
                requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

                val callback = arSharedCamera.createARCaptureCallback(cameraCaptureCallback, handler)
                session.setRepeatingRequest(requestBuilder.build(), callback, handler)
                cameraCaptureSession = session
                sharedCameraStarted = true
                openingSharedCamera = false
                Log.d(TAG, "Shared camera capture session started")
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to start shared camera repeating request: ${error.message}")
                session.close()
                cameraCaptureSession = null
                sharedCameraStarted = false
                openingSharedCamera = false
            }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            Log.e(TAG, "Shared camera capture session configuration failed")
            session.close()
            if (cameraCaptureSession === session) {
                cameraCaptureSession = null
            }
            sharedCameraStarted = false
            openingSharedCamera = false
        }
    }

    private val cameraDeviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            openingSharedCamera = false

            val arSharedCamera = sharedCamera
            val streamSurface = cameraStreamSurface
            val handler = cameraHandler
            if (arSharedCamera == null || streamSurface == null) {
                Log.e(TAG, "Shared camera missing resources on open")
                stopSharedCamera()
                return
            }
            if (handler == null) {
                Log.e(TAG, "Shared camera handler missing on open")
                stopSharedCamera()
                return
            }

            try {
                val surfaces = ArrayList<Surface>(arSharedCamera.arCoreSurfaces.size + 1)
                surfaces.addAll(arSharedCamera.arCoreSurfaces)
                surfaces.add(streamSurface)

                val callback = arSharedCamera.createARSessionStateCallback(captureSessionStateCallback, handler)
                device.createCaptureSession(surfaces, callback, handler)
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to create shared camera capture session: ${error.message}")
                stopSharedCamera()
            }
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            if (cameraDevice === device) {
                cameraDevice = null
            }
            sharedCameraStarted = false
            openingSharedCamera = false
        }

        override fun onError(device: CameraDevice, error: Int) {
            Log.e(TAG, "Shared camera device error: $error")
            device.close()
            if (cameraDevice === device) {
                cameraDevice = null
            }
            sharedCameraStarted = false
            openingSharedCamera = false
        }
    }

    private external fun nativeCreateCore(): Long
    private external fun nativeDestroyCore(handle: Long)
    private external fun nativeSetMaterialPackageCore(handle: Long, kind: Int, bytes: ByteArray): Boolean
    private external fun nativeSetEnvironmentIblCore(handle: Long, bytes: ByteArray): Boolean
    private external fun nativeSetEnvironmentSkyboxCore(handle: Long, bytes: ByteArray): Boolean
    private external fun nativeSetEnvironmentShCore(handle: Long, sh27: FloatArray)
    private external fun nativeMakeCameraContextCurrentCore(handle: Long)
    private external fun nativeSetCameraStreamCore(handle: Long, surfaceTexture: SurfaceTexture?, width: Int, height: Int): Boolean
    private external fun nativeSetSurfaceCore(handle: Long, surface: Surface?)
    private external fun nativeResizeCore(handle: Long, width: Int, height: Int)
    private external fun nativeUpdateConfigCore(
        handle: Long,
        faceMeshOcclusion: Boolean,
        backPlaneOcclusion: Boolean,
        forwardOffset: Float,
        debug: Boolean,
        noseBridgeLeftIndex: Int,
        noseBridgeRightIndex: Int
    )
    private external fun nativeSetModelFromBytesCore(handle: Long, sourceId: String, bytes: ByteArray): Boolean
    private external fun nativeResetCore(handle: Long)
    private external fun nativeSubmitFrameCore(
        handle: Long,
        viewportWidth: Int,
        viewportHeight: Int,
        projection: FloatArray,
        model: FloatArray,
        textureId: Int,
        uvCoords8: FloatArray?,
        hasLightEstimate: Boolean,
        lightValid: Boolean,
        linearIntensity: Float,
        hasFace: Boolean,
        vertices: FloatBuffer?,
        vertexCount: Int,
        indices: ShortBuffer?,
        indexCount: Int,
        faceToWorld: FloatArray?,
        rotationQuaternion: FloatArray?
    )
    private external fun nativeRenderCore(handle: Long)

    fun initialize(surfaceView: SurfaceView, modelUrl: String) {
        this.modelUrl = modelUrl
        surfaceViewRef = surfaceView
        coreHandle = nativeCreateCore()
        if (coreHandle == 0L) {
            Log.e(TAG, "Failed to create native core")
            return
        }

        if (TESTBED_PARITY_MODE) {
            Log.i(TAG, "Testbed parity mode enabled (no skybox, no AR light estimate, no face/back-plane occlusion)")
        }

        syncCoreConfig()
        loadCoreAssets()
        sharedCamera = try {
            session?.sharedCamera
        } catch (_: Throwable) {
            null
        }
        sharedCameraId = try {
            session?.cameraConfig?.cameraId
        } catch (_: Throwable) {
            null
        }

        surfaceView.holder.addCallback(surfaceCallback)
        syncViewportFromSurfaceView()
        if (width > 0 && height > 0) {
            nativeResizeCore(coreHandle, width, height)
        }
        if (surfaceView.holder.surface?.isValid == true) {
            bindSurface(surfaceView.holder.surface)
        }

        requestModelLoad(modelUrl)
        initialized = true
    }

    fun resume() {
        isPaused = false
        startSharedCameraIfNeeded()
    }

    fun pause() {
        isPaused = true
        stopSharedCamera()
    }

    fun renderOnce() {
        renderFrame()
    }

    fun switchModel(modelUrl: String) {
        this.modelUrl = modelUrl
        requestModelLoad(modelUrl)
    }

    fun resetSession() {
        if (coreHandle != 0L) {
            nativeResetCore(coreHandle)
        }
        activeFace = null
        stopSharedCamera()
    }

    fun setFaceMeshOcclusion(enabled: Boolean) {
        faceMeshOcclusionEnabled = enabled
        syncCoreConfig()
    }

    fun setBackPlaneOcclusion(enabled: Boolean) {
        backPlaneOcclusionEnabled = enabled
        syncCoreConfig()
    }

    fun setForwardOffset(offset: Float) {
        forwardOffsetMeters = offset
        syncCoreConfig()
    }

    fun setDebug(enabled: Boolean) {
        debugEnabled = enabled
        syncCoreConfig()
    }

    fun destroy() {
        surfaceViewRef?.holder?.removeCallback(surfaceCallback)
        modelLoaderExecutor.shutdownNow()

        if (coreHandle != 0L) {
            stopSharedCamera()
            nativeSetSurfaceCore(coreHandle, null)
            nativeDestroyCore(coreHandle)
            coreHandle = 0L
        }

        initialized = false
        activeFace = null
        sharedCamera = null
        sharedCameraId = null
    }

    private fun bindSurface(surface: Surface?) {
        if (coreHandle == 0L) {
            return
        }
        syncViewportFromSurfaceView()
        if (surface != null && surface.isValid) {
            nativeSetSurfaceCore(coreHandle, surface)
            startSharedCameraIfNeeded()
        }
    }

    private fun startCameraThreadIfNeeded() {
        if (cameraThread != null) {
            return
        }
        cameraThread = HandlerThread("nitro-vto-shared-camera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopCameraThread() {
        cameraHandler = null
        cameraThread?.quitSafely()
        cameraThread?.join(500)
        cameraThread = null
    }

    @Suppress("MissingPermission")
    private fun startSharedCameraIfNeeded() {
        if (!initialized || isPaused || coreHandle == 0L || openingSharedCamera || sharedCameraStarted || cameraCaptureSession != null) {
            return
        }

        val renderSurface = surfaceViewRef?.holder?.surface
        if (renderSurface == null || !renderSurface.isValid) {
            return
        }

        val currentSession = session ?: return
        val arSharedCamera = sharedCamera ?: try {
            currentSession.sharedCamera
        } catch (_: Throwable) {
            null
        }
        val cameraId = sharedCameraId ?: try {
            currentSession.cameraConfig.cameraId
        } catch (_: Throwable) {
            null
        }
        if (arSharedCamera == null) {
            Log.w(TAG, "ARCore shared camera is unavailable for current session")
            return
        }
        if (cameraId.isNullOrBlank()) {
            Log.e(TAG, "No ARCore camera id available for shared camera")
            return
        }

        sharedCamera = arSharedCamera
        sharedCameraId = cameraId

        startCameraThreadIfNeeded()
        val handler = cameraHandler ?: return

        if (cameraStreamTexture == null) {
            cameraStreamTexture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                SurfaceTexture(false)
            } else {
                SurfaceTexture(0)
            }
        }

        val streamTexture = cameraStreamTexture ?: return
        val streamWidth = width.coerceAtLeast(1)
        val streamHeight = height.coerceAtLeast(1)
        streamTexture.setDefaultBufferSize(streamWidth, streamHeight)

        if (cameraStreamSurface == null) {
            cameraStreamSurface = Surface(streamTexture)
        }

        nativeMakeCameraContextCurrentCore(coreHandle)
        val streamBound = nativeSetCameraStreamCore(coreHandle, streamTexture, streamWidth, streamHeight)
        if (!streamBound) {
            Log.e(TAG, "Failed to bind camera stream surface texture to native renderer")
            return
        }

        try {
            arSharedCamera.setAppSurfaces(cameraId, listOf(cameraStreamSurface!!))
            val wrappedCallback = arSharedCamera.createARDeviceStateCallback(cameraDeviceStateCallback, handler)
            openingSharedCamera = true
            cameraManager.openCamera(cameraId, wrappedCallback, handler)
        } catch (error: Throwable) {
            openingSharedCamera = false
            Log.e(TAG, "Failed to open shared camera: ${error.message}")
        }
    }

    private fun stopSharedCamera() {
        openingSharedCamera = false
        sharedCameraStarted = false

        cameraCaptureSession?.close()
        cameraCaptureSession = null

        cameraDevice?.close()
        cameraDevice = null

        if (coreHandle != 0L) {
            nativeMakeCameraContextCurrentCore(coreHandle)
            nativeSetCameraStreamCore(coreHandle, null, 1, 1)
        }

        cameraStreamSurface?.release()
        cameraStreamSurface = null

        cameraStreamTexture?.release()
        cameraStreamTexture = null

        stopCameraThread()
    }

    private fun syncViewportFromSurfaceView() {
        val surfaceView = surfaceViewRef ?: return
        val newWidth = surfaceView.width.coerceAtLeast(0)
        val newHeight = surfaceView.height.coerceAtLeast(0)
        if (newWidth <= 0 || newHeight <= 0) {
            return
        }
        if (newWidth == width && newHeight == height) {
            return
        }
        width = newWidth
        height = newHeight
        if (coreHandle != 0L) {
            nativeResizeCore(coreHandle, width, height)
        }
    }

    private fun renderFrame() {
        if (!initialized || coreHandle == 0L) {
            return
        }
        val currentSession = session ?: return

        try {
            syncViewportFromSurfaceView()
            nativeMakeCameraContextCurrentCore(coreHandle)
            startSharedCameraIfNeeded()

            if (width > 0 && height > 0) {
                val display = surfaceViewRef?.display
                val rotation = display?.rotation ?: 0
                if (rotation != lastDisplayRotation || width != lastDisplayWidth || height != lastDisplayHeight) {
                    currentSession.setDisplayGeometry(rotation, width, height)
                    lastDisplayRotation = rotation
                    lastDisplayWidth = width
                    lastDisplayHeight = height
                }
            }

            val frame = currentSession.update()

            frame.transformCoordinates2d(
                Coordinates2d.VIEW_NORMALIZED,
                viewNormalizedCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                cameraUvCoords
            )

            frame.camera.getViewMatrix(viewMatrix, 0)
            frame.camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100.0f)
            Matrix.invertM(cameraModelMatrix, 0, viewMatrix, 0)

            val lightEstimate = frame.lightEstimate
            val lightValid = !TESTBED_PARITY_MODE && lightEstimate.state == LightEstimate.State.VALID
            val linearIntensity = if (lightValid) {
                lightEstimate.pixelIntensity.coerceAtLeast(0f)
            } else {
                0f
            }
            val hasLightEstimate = !TESTBED_PARITY_MODE

            var hasFace = false
            var vertices: FloatBuffer? = null
            var indices: ShortBuffer? = null
            var vertexCount = 0
            var indexCount = 0
            var faceToWorld: FloatArray? = null
            var rotationQuaternion: FloatArray? = null

            val updatedFaces = frame.getUpdatedTrackables(AugmentedFace::class.java)
            for (candidate in updatedFaces) {
                if (candidate.trackingState == TrackingState.TRACKING) {
                    activeFace = candidate
                    break
                }
                if (activeFace === candidate && candidate.trackingState == TrackingState.STOPPED) {
                    activeFace = null
                }
            }

            val trackedFace = activeFace?.takeIf { it.trackingState == TrackingState.TRACKING }
            if (trackedFace == null) {
                activeFace = null
            }

            if (trackedFace != null) {
                val face = trackedFace
                val meshVertices = face.meshVertices
                val meshIndices = face.meshTriangleIndices
                meshVertices.rewind()
                meshIndices.rewind()

                vertexCount = meshVertices.limit() / 3
                indexCount = meshIndices.limit()

                face.centerPose.toMatrix(coreFaceMatrix, 0)
                val rotation = face.centerPose.rotationQuaternion
                coreFaceQuaternion[0] = rotation[0]
                coreFaceQuaternion[1] = rotation[1]
                coreFaceQuaternion[2] = rotation[2]
                coreFaceQuaternion[3] = rotation[3]

                hasFace = true
                vertices = meshVertices
                indices = meshIndices
                faceToWorld = coreFaceMatrix
                rotationQuaternion = coreFaceQuaternion
            }

            nativeSubmitFrameCore(
                coreHandle,
                width,
                height,
                projectionMatrix,
                cameraModelMatrix,
                0,
                cameraUvCoords,
                hasLightEstimate,
                lightValid,
                linearIntensity,
                hasFace,
                vertices,
                vertexCount,
                indices,
                indexCount,
                faceToWorld,
                rotationQuaternion
            )

            nativeRenderCore(coreHandle)
        } catch (error: Throwable) {
            Log.e(TAG, "Render frame failed: ${error.message}")
        }
    }

    private fun syncCoreConfig() {
        if (coreHandle == 0L) {
            return
        }
        nativeUpdateConfigCore(
            coreHandle,
            if (TESTBED_PARITY_MODE) false else faceMeshOcclusionEnabled,
            if (TESTBED_PARITY_MODE) false else backPlaneOcclusionEnabled,
            forwardOffsetMeters,
            debugEnabled,
            ARCORE_NOSE_BRIDGE_LEFT_INDEX,
            ARCORE_NOSE_BRIDGE_RIGHT_INDEX
        )
    }

    private fun requestModelLoad(url: String) {
        if (url.isBlank()) {
            return
        }
        val requestVersion = modelLoadVersion.incrementAndGet()
        modelLoaderExecutor.execute {
            try {
                val bytes = loadModelBytes(url) ?: return@execute
                if (requestVersion != modelLoadVersion.get() || url != modelUrl) {
                    return@execute
                }
                val ok = if (coreHandle != 0L) {
                    nativeSetModelFromBytesCore(coreHandle, url, bytes)
                } else {
                    false
                }
                if (ok) {
                    mainHandler.post {
                        if (requestVersion == modelLoadVersion.get() && url == modelUrl) {
                            onModelLoaded?.invoke(url)
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to load model bytes for $url: ${error.message}")
            }
        }
    }

    private fun loadModelBytes(url: String): ByteArray? {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> {
                LoaderUtils.loadModelFromUrl(context, url)
            }
            url.startsWith("asset://") -> {
                val assetPath = url.removePrefix("asset://")
                context.assets.open(assetPath).use { it.readBytes() }
            }
            else -> {
                val normalized = if (url.startsWith("file://")) url.removePrefix("file://") else url
                val file = File(normalized)
                if (file.exists()) file.readBytes() else null
            }
        }
    }

    private fun loadCoreAssets() {
        if (coreHandle == 0L) {
            return
        }
        try {
            nativeSetMaterialPackageCore(coreHandle, 0, context.assets.open(MATERIAL_CAMERA).use { it.readBytes() })
            nativeSetMaterialPackageCore(coreHandle, 1, context.assets.open(MATERIAL_FACE_OCCLUSION).use { it.readBytes() })
            nativeSetMaterialPackageCore(coreHandle, 2, context.assets.open(MATERIAL_DEBUG_FACE).use { it.readBytes() })
            nativeSetMaterialPackageCore(coreHandle, 3, context.assets.open(MATERIAL_DEBUG_PLANE).use { it.readBytes() })

            nativeSetEnvironmentIblCore(coreHandle, context.assets.open(ENV_IBL).use { it.readBytes() })
            if (!TESTBED_PARITY_MODE) {
                nativeSetEnvironmentSkyboxCore(coreHandle, context.assets.open(ENV_SKYBOX).use { it.readBytes() })
            }

            val shText = context.assets.open(ENV_SH).bufferedReader().use { it.readText() }
            val sh = parseShCoefficients(shText)
            if (sh != null) {
                nativeSetEnvironmentShCore(coreHandle, sh)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to load core assets: ${error.message}")
        }
    }

    private fun parseShCoefficients(text: String): FloatArray? {
        val out = FloatArray(27)
        var index = 0
        for (line in text.lineSequence()) {
            if (index >= 9) break
            val trimmed = line.trim()
            if (!trimmed.startsWith("(")) continue
            val values = trimmed.removePrefix("(").substringBefore(")").split(",")
            if (values.size != 3) continue
            try {
                out[index * 3] = values[0].trim().toFloat()
                out[index * 3 + 1] = values[1].trim().toFloat()
                out[index * 3 + 2] = values[2].trim().toFloat()
                index++
            } catch (_: Throwable) {
            }
        }
        return if (index == 9) out else null
    }
}
