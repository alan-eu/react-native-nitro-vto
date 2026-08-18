package eu.alan.vto.core

import android.content.Context
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.Entity
import com.google.android.filament.EntityManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.ar.core.AugmentedFace
import com.google.ar.core.Frame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Renderer for glasses model with face tracking transform.
 * Handles GLTF loading and NDC-space positioning based on ARCore face mesh.
 */
class GlassesRenderer(private val context: Context) {

    companion object {
        private const val TAG = "GlassesRenderer"
    }

    private lateinit var engine: Engine
    private lateinit var scene: Scene
    private lateinit var assetLoader: AssetLoader
    private lateinit var resourceLoader: ResourceLoader
    private var glassesAsset: FilamentAsset? = null

    // Thread management for URL loading
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Monotonic load-request counter. Every load request takes the next
    // generation; when a download lands whose generation is no longer current it
    // has been superseded and its bytes are dropped, so the last requested model
    // is always the one installed and an older download can never paint over a
    // newer one.
    private val loadGeneration = AtomicLong(0)

    // Current model info
    private var currentModelUrl: String = ""

    // Callbacks
    var onModelLoaded: ((modelUrl: String) -> Unit)? = null
    var onGlassesDisplayed: ((modelUrl: String) -> Unit)? = null

    // Fires onGlassesDisplayed once per loaded model — reset on every successful load.
    private var hasDisplayedCurrentModel = false

    // Reusable arrays to avoid per-frame allocations
    private val tempVec4 = FloatArray(4)
    private val tempMatrix16 = FloatArray(16)

    // Forward offset for glasses positioning (in meters)
    private var forwardOffset = OcclusionConstants.FORWARD_OFFSET

    // Whether the current model is a clip-on / solar (tinted sunglass) frame. Set
    // from the JS `isClipOn` prop; drives the clip-on lens treatment in
    // configureLensMaterial.
    private var isClipOn = false

    // Lens material baseColor RGB, parsed from the glb at load (the Android
    // Filament binding has no getParameter). Used as the clip-on tint. Null if the
    // glb couldn't be parsed (then we skip the clip-on material swap).
    private var lensBaseColorRgb: FloatArray? = null

    // Clip-on lens material (unlit flat tint) and its reused instance. For clip-on
    // models the lens primitives' material is swapped to this so the glossy glb
    // lens stops reflecting the IBL; the `tint` uniform carries the per-SKU color.
    private var cliponLensMaterial: Material? = null
    private var cliponLensInstance: MaterialInstance? = null

    // Temple articulation state. articulationEnabled stays false if any of
    // the four expected hinge/temple node names is missing from the asset —
    // articulation becomes a no-op for that asset.
    //
    // Everything is captured in asset-root-local space, which is convention-
    // invariant: regardless of each glb's units (cm vs m), root scale, exporter
    // (Fbx vs Blender) or hierarchy depth, the hinge lands at the same metric
    // pose here and the temple extends back by the same metric lever. *LocalRest
    // is the parent-relative transform setTransform expects; *RootRest is
    // relative to the asset root; the lever (length + bearing in the root-local
    // X–Z plane) is what the swing solver needs to drive the tip's X.
    private var articulationEnabled = false
    @Entity private var hingeLEntity: Int = 0
    @Entity private var hingeREntity: Int = 0
    private val hingeLLocalRest = FloatArray(16)
    private val hingeRLocalRest = FloatArray(16)
    private val hingeLRootRest = FloatArray(16)
    private val hingeRRootRest = FloatArray(16)
    private var templeLLeverLen = 0f      // meters
    private var templeRLeverLen = 0f
    private var templeLLeverAngle = 0f    // radians, atan2(dz, dx)
    private var templeRLeverAngle = 0f
    private val articulationBbox = Box(0f, 0f, 0f, 0f, 0f, 0f)
    // Per-call scratch (avoid allocations): parent-walk accumulators, swing math.
    private val artAcc = FloatArray(16)
    private val artLocal = FloatArray(16)
    private val artTmp = FloatArray(16)
    private val artToRoot = FloatArray(16)
    private val artCorner = FloatArray(4)
    private val artCornerOut = FloatArray(4)
    private val artTip = FloatArray(3)
    private val artRotY = FloatArray(16)
    private val artRotX = FloatArray(16)
    private val artM = FloatArray(16)
    private val artInvHr = FloatArray(16)
    private val artTmp2 = FloatArray(16)
    private val articulationOutMatrix = FloatArray(16)
    // Lens-center height above the model origin (root-local meters). Some models
    // are authored with the frame shifted vertically off the origin, so anchoring
    // the origin to the nose bridge makes them ride too high. We instead anchor
    // the lens-center, shifting placement down by this. ~0 for normal models.
    private var lensVerticalOffset = 0f

    /**
     * Setup the glasses renderer with Filament engine and scene.
     * @param engine Filament engine instance
     * @param scene Scene to add glasses entities to
     * @param modelUrl URL to the glasses model (GLB format)
     */
    fun setup(engine: Engine, scene: Scene, modelUrl: String) {
        this.engine = engine
        this.scene = scene

        // Setup GLTF loader
        val materialProvider = UbershaderProvider(engine)
        assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        // Clip-on lens material (unlit flat tint) — swapped onto clip-on lenses so
        // the glb's glossy lens doesn't reflect the IBL as chrome. One instance is
        // reused; its `tint` uniform is updated per model from the glb baseColor.
        try {
            val buf = LoaderUtils.loadAsset(context, "materials/clipon_lens.filamat")
            cliponLensMaterial = Material.Builder().payload(buf, buf.remaining()).build(engine)
            cliponLensInstance = cliponLensMaterial?.createInstance()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load clip-on lens material; clip-on tint disabled: ${e.message}")
        }

        // Load model
        loadModel(modelUrl)
    }

    private fun loadModel(url: String) {
        if (url.isEmpty()) {
            Log.d(TAG, "Empty URL, skipping model load")
            return
        }

        val generation = loadGeneration.incrementAndGet()
        Log.d(TAG, "Starting download from URL: $url")

        executor.execute {
            try {
                val modelBuffer = LoaderUtils.loadFromUrl(context, url)

                mainHandler.post {
                    if (generation != loadGeneration.get()) {
                        Log.d(TAG, "Load superseded by a newer request, discarding: $url")
                        return@post
                    }
                    try {
                        loadModelBuffer(modelBuffer, url)
                        onModelLoaded?.invoke(url)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load model buffer on main thread: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download GLB from URL: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun loadModelBuffer(modelBuffer: ByteBuffer, url: String) {
        // Parse the lens baseColor RGB before createAsset (which advances the
        // buffer / releases source data). Used by the clip-on opacity override.
        val rgb = parseLensBaseColorRgb(modelBuffer.duplicate())
        val asset = assetLoader.createAsset(modelBuffer)
        if (asset == null) {
            Log.e(TAG, "Failed to create glasses asset")
            return
        }

        resourceLoader.loadResources(asset)
        asset.releaseSourceData()

        // The model on screen is only swapped out once its replacement is ready,
        // so a switch never leaves an empty scene while the new glb downloads,
        // and a download or parse failure keeps the current frame instead of
        // nothing.
        removeCurrentModel()

        lensBaseColorRgb = rgb
        glassesAsset = asset
        currentModelUrl = url

        scene.addEntities(asset.entities)
        Log.d(TAG, "Glasses model loaded: ${asset.entities.size} entities")
        // Reset the "already displayed" flag so onGlassesDisplayed fires again
        // the next time updateTransform runs for this freshly-loaded model.
        hasDisplayedCurrentModel = false
        cacheTempleArticulationState(asset)
        configureLensMaterial(asset)
        cacheLensVerticalOffset(asset)
        hide()
    }

    /**
     * Detach the loaded asset from the scene and destroy it. Articulation is
     * turned off first: the cached hinge entities belong to this asset, and
     * cacheTempleArticulationState reruns when the next asset is installed.
     */
    private fun removeCurrentModel() {
        val asset = glassesAsset ?: return

        articulationEnabled = false
        scene.removeEntities(asset.entities)
        assetLoader.destroyAsset(asset)
        glassesAsset = null
    }

    // Configure the lens material on the loaded asset.
    //
    // Clip-on (isClipOn): the glb's lens is a glossy PBR transmission material whose
    // smooth surface reflects the bright IBL as "chrome". We can't fix that through
    // the ubershader at runtime (alphaMode / specular weight aren't settable), so we
    // SWAP the lens material to our own unlit flat-tint material (clipon_lens): no IBL
    // sampling → no reflection, see-through via the tint's alpha, per-SKU color from
    // the glb baseColor (parsed into lensBaseColorRgb).
    //
    // Non-clip-on: keep the glb lens, just disable culling so the thin single-sided
    // shell renders both faces.
    private fun configureLensMaterial(asset: FilamentAsset) {
        val rm = engine.renderableManager
        val rgb = lensBaseColorRgb
        val instance = cliponLensInstance

        // Update the shared clip-on instance: per-SKU tint (brightness-capped,
        // hue-preserving) plus the reflection/roughness knobs.
        if (isClipOn && instance != null && rgb != null) {
            val maxc = maxOf(rgb[0], rgb[1], rgb[2])
            val scale =
                if (maxc > LensConstants.CLIP_ON_MAX_CHANNEL) LensConstants.CLIP_ON_MAX_CHANNEL / maxc else 1f
            instance.setParameter("tint", rgb[0] * scale, rgb[1] * scale, rgb[2] * scale)
            instance.setParameter("reflectance", LensConstants.CLIP_ON_REFLECTANCE)
        }

        val swap = isClipOn && instance != null && rgb != null
        for (name in arrayOf("LensL_geometry", "LensR_geometry")) {
            val e = asset.getFirstEntityByName(name)
            if (e == 0) continue
            val ri = rm.getInstance(e)
            if (ri == 0) continue
            for (p in 0 until rm.getPrimitiveCount(ri)) {
                if (swap) {
                    rm.setMaterialInstanceAt(ri, p, instance!!)
                } else {
                    rm.getMaterialInstanceAt(ri, p).setCullingMode(Material.CullingMode.NONE)
                }
            }
        }
    }

    /**
     * Parse the lens material's baseColor RGB from a glb buffer's JSON chunk:
     * LensL_geometry node → mesh → primitive[0].material →
     * pbrMetallicRoughness.baseColorFactor. Returns [r, g, b] or null on any
     * parse miss. (iOS reads this via MaterialInstance.getParameter, which the
     * Android Filament binding doesn't expose — hence this glb parse.)
     */
    private fun parseLensBaseColorRgb(buffer: ByteBuffer): FloatArray? {
        return try {
            val buf = buffer.order(ByteOrder.LITTLE_ENDIAN)
            if (buf.remaining() < 20) return null
            buf.position(12) // skip 12-byte glb header (magic, version, length)
            val chunkLen = buf.int
            val chunkType = buf.int
            if (chunkType != 0x4E4F534A) return null // "JSON"
            val jsonBytes = ByteArray(chunkLen)
            buf.get(jsonBytes)
            val gltf = JSONObject(String(jsonBytes, Charsets.UTF_8))

            val nodes = gltf.getJSONArray("nodes")
            var meshIndex = -1
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                if (node.optString("name") == "LensL_geometry" && node.has("mesh")) {
                    meshIndex = node.getInt("mesh")
                    break
                }
            }
            if (meshIndex < 0) return null

            val primitives = gltf.getJSONArray("meshes").getJSONObject(meshIndex)
                .getJSONArray("primitives")
            val matIndex = primitives.getJSONObject(0).getInt("material")
            val bcf = gltf.getJSONArray("materials").getJSONObject(matIndex)
                .getJSONObject("pbrMetallicRoughness").getJSONArray("baseColorFactor")
            floatArrayOf(
                bcf.getDouble(0).toFloat(),
                bcf.getDouble(1).toFloat(),
                bcf.getDouble(2).toFloat(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse lens baseColor from glb: ${e.message}")
            null
        }
    }

    // Cache the lens-center height (root-local Y) so updateTransform can anchor
    // the lens-center — not the model origin — to the nose bridge. Stays 0 (no
    // correction) if the lens nodes are missing. Reuses articulation scratch.
    private fun cacheLensVerticalOffset(asset: FilamentAsset) {
        lensVerticalOffset = 0f
        val tm = engine.transformManager
        val rm = engine.renderableManager
        val root = asset.root
        var sum = 0f
        var n = 0
        for (name in arrayOf("LensL_geometry", "LensR_geometry")) {
            val e = asset.getFirstEntityByName(name)
            if (e == 0) continue
            val ri = rm.getInstance(e)
            if (ri == 0) continue
            rm.getAxisAlignedBoundingBox(ri, articulationBbox)
            transformRelativeToRoot(tm, e, root, artToRoot)
            artCorner[0] = articulationBbox.center[0]
            artCorner[1] = articulationBbox.center[1]
            artCorner[2] = articulationBbox.center[2]
            artCorner[3] = 1f
            Matrix.multiplyMV(artCornerOut, 0, artToRoot, 0, artCorner, 0)
            sum += artCornerOut[1]
            n++
        }
        if (n > 0) lensVerticalOffset = sum / n
    }

    private fun cacheTempleArticulationState(asset: FilamentAsset) {
        articulationEnabled = false

        val hingeL = asset.getFirstEntityByName("HingeL_temple")
        val hingeR = asset.getFirstEntityByName("HingeR_temple")
        val templeL = asset.getFirstEntityByName("TempleL_geometry")
        val templeR = asset.getFirstEntityByName("TempleR_geometry")

        if (hingeL == 0 || hingeR == 0 || templeL == 0 || templeR == 0) {
            Log.d(TAG, "Hinge/temple nodes not found — articulation disabled for this asset")
            return
        }

        val tm = engine.transformManager
        val rm = engine.renderableManager
        val root = asset.root

        hingeLEntity = hingeL
        hingeREntity = hingeR
        tm.getTransform(tm.getInstance(hingeL), hingeLLocalRest)
        tm.getTransform(tm.getInstance(hingeR), hingeRLocalRest)
        transformRelativeToRoot(tm, hingeL, root, hingeLRootRest)
        transformRelativeToRoot(tm, hingeR, root, hingeRRootRest)

        // Rest temple tips in root-local space, then the hinge→tip lever in the
        // horizontal (X–Z) plane. Articulation swings the temple about the
        // root-local vertical (Y) to drive the tip's X to the ear target;
        // length + bearing of the lever are all the solver needs.
        templeTipRootLocal(tm, rm, templeL, root, artTip)
        val dxL = artTip[0] - hingeLRootRest[12]
        val dzL = artTip[2] - hingeLRootRest[14]
        templeTipRootLocal(tm, rm, templeR, root, artTip)
        val dxR = artTip[0] - hingeRRootRest[12]
        val dzR = artTip[2] - hingeRRootRest[14]
        templeLLeverLen = kotlin.math.sqrt(dxL * dxL + dzL * dzL)
        templeRLeverLen = kotlin.math.sqrt(dxR * dxR + dzR * dzR)
        templeLLeverAngle = kotlin.math.atan2(dzL, dxL)
        templeRLeverAngle = kotlin.math.atan2(dzR, dxR)

        if (templeLLeverLen <= 0f || templeRLeverLen <= 0f) {
            Log.d(TAG, "Degenerate temple lever — articulation disabled")
            return
        }

        articulationEnabled = true
        Log.d(
            TAG,
            "Temple articulation enabled (L pivotX=${hingeLRootRest[12]} m, len=$templeLLeverLen m; R pivotX=${hingeRRootRest[12]} m, len=$templeRLeverLen m)"
        )
    }

    /**
     * Transform of [node] relative to [root], composed from local transforms by
     * walking up the parent chain. Independent of any committed world transform,
     * so it is valid immediately after load. Writes identity when node == root.
     */
    private fun transformRelativeToRoot(
        tm: com.google.android.filament.TransformManager,
        node: Int,
        root: Int,
        out: FloatArray,
    ) {
        Matrix.setIdentityM(out, 0)        // accumulates in `out`
        var e = node
        while (e != 0 && e != root) {
            val inst = tm.getInstance(e)
            if (inst == 0) break
            tm.getTransform(inst, artLocal)
            // out = artLocal × out
            Matrix.multiplyMM(artAcc, 0, artLocal, 0, out, 0)
            System.arraycopy(artAcc, 0, out, 0, 16)
            e = tm.getParent(inst)
        }
    }

    /**
     * Temple tip in asset-root-local space: the rear-most (min root-local Z)
     * corner of the temple renderable's bounding box. Temples extend back toward
     * the ears, so that corner is the tip whose lateral position we articulate.
     */
    private fun templeTipRootLocal(
        tm: com.google.android.filament.TransformManager,
        rm: RenderableManager,
        temple: Int,
        root: Int,
        outXYZ: FloatArray,
    ) {
        transformRelativeToRoot(tm, temple, root, artToRoot)
        rm.getAxisAlignedBoundingBox(rm.getInstance(temple), articulationBbox)
        val c = articulationBbox.center
        val h = articulationBbox.halfExtent
        var first = true
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            artCorner[0] = c[0] + sx * h[0]
            artCorner[1] = c[1] + sy * h[1]
            artCorner[2] = c[2] + sz * h[2]
            artCorner[3] = 1f
            Matrix.multiplyMV(artCornerOut, 0, artToRoot, 0, artCorner, 0)
            if (first || artCornerOut[2] < outXYZ[2]) {
                outXYZ[0] = artCornerOut[0]; outXYZ[1] = artCornerOut[1]; outXYZ[2] = artCornerOut[2]
                first = false
            }
        }
    }

    /**
     * Update glasses transform based on detected face.
     */
    fun updateTransform(face: AugmentedFace, frame: Frame) {
        glassesAsset?.let { asset ->
            if (!hasDisplayedCurrentModel) {
                hasDisplayedCurrentModel = true
                onGlassesDisplayed?.invoke(currentModelUrl)
            }
            val instance = engine.transformManager.getInstance(asset.root)

            // Get nose bridge position in world space
            val noseBridgeWorld = getNoseBridgeWorldPos(face)

            // Get face rotation from pose (world space)
            val faceQuaternion = face.centerPose.rotationQuaternion

            // Build world-space transform matrix (no scaling - models are in real-world meters)
            val rotationMatrix = MatrixUtils.quaternionToMatrix(faceQuaternion)
            System.arraycopy(rotationMatrix, 0, tempMatrix16, 0, 16)

            // Offset glasses along face's Z axis (forward/backward)
            val forwardX = rotationMatrix[8]   // Z axis X component (column 2, row 0)
            val forwardY = rotationMatrix[9]   // Z axis Y component (column 2, row 1)
            val forwardZ = rotationMatrix[10]  // Z axis Z component (column 2, row 2)

            // Face up axis (column 1) — used to anchor the lens-center (not the
            // model origin) to the nose bridge, so models authored with a
            // vertical offset don't ride too high. ~0 shift for normal models.
            val upX = rotationMatrix[4]
            val upY = rotationMatrix[5]
            val upZ = rotationMatrix[6]

            // Set world-space position with forward offset and lens-center anchoring.
            tempMatrix16[12] = noseBridgeWorld[0] + forwardX * forwardOffset - upX * lensVerticalOffset
            tempMatrix16[13] = noseBridgeWorld[1] + forwardY * forwardOffset - upY * lensVerticalOffset
            tempMatrix16[14] = noseBridgeWorld[2] + forwardZ * forwardOffset - upZ * lensVerticalOffset

            // Mirror X for the front-facing camera: ARCore's projection encodes the mirror
            // in m[0] < 0, but VTORenderer uses Filament's setProjection(fov, aspect, …) which
            // can't express it — and setCustomProjection with ARCore's raw matrix silently
            // breaks screen-space refraction. Instead we mirror every ARCore-tracked
            // transform here (pre-multiply by scale(-1, 1, 1) → negate row 0 of this 4x4).
            // Keeps the Filament view matrix determinant-positive → refraction keeps working.
            tempMatrix16[0] = -tempMatrix16[0]
            tempMatrix16[4] = -tempMatrix16[4]
            tempMatrix16[8] = -tempMatrix16[8]
            tempMatrix16[12] = -tempMatrix16[12]

            engine.transformManager.setTransform(instance, tempMatrix16)
        }
    }

    /**
     * Get nose bridge center position in world coordinates. Uses vertices
     * 351 and 122 of the ARCore face mesh for the bridge's Y (height) and
     * Z (depth). X is locked to the face anchor's symmetry axis — see
     * ADR 0016 for the asymmetry that motivated that choice.
     */
    private fun getNoseBridgeWorldPos(face: AugmentedFace): FloatArray {
        val a = MatrixUtils.getPositionForVertice(351, face)
        val b = MatrixUtils.getPositionForVertice(122, face)

        val centerX = 0f
        val centerY = (a[1] + b[1]) / 2f
        val centerZ = (a[2] + b[2]) / 2f

        face.centerPose.toMatrix(tempMatrix16, 0)
        return MatrixUtils.transformToWorld(centerX, centerY, centerZ, tempMatrix16, tempVec4)
    }

    /**
     * Hide glasses by moving off-screen.
     */
    fun hide() {
        glassesAsset?.let { asset ->
            val instance = engine.transformManager.getInstance(asset.root)
            engine.transformManager.setTransform(instance, MatrixUtils.createHideMatrix())
        }
    }

    /**
     * Set forward offset for glasses positioning (in meters).
     */
    fun setForwardOffset(offset: Float) {
        forwardOffset = offset
    }

    /**
     * Mark the current model as a clip-on / solar frame. Re-applies the lens
     * treatment if a model is already loaded; otherwise it runs at load time
     * (configureLensMaterial reads this flag).
     */
    fun setIsClipOn(value: Boolean) {
        if (isClipOn == value) return
        isClipOn = value
        glassesAsset?.let { configureLensMaterial(it) }
    }

    /**
     * Articulate the temples (swing left/right hinge nodes) so the temple
     * tips land at ±earHalfWidth (face-local meters). No-op if the loaded
     * glb does not expose the expected hinge node names
     * (HingeL_temple / HingeR_temple).
     */
    fun updateTempleArticulation(earHalfWidth: Float) {
        if (!articulationEnabled || earHalfWidth <= 0f) return

        // Temple tips target ±(earHalfWidth · TEMPLE_TIP_SCALE) on the glasses'
        // own left/right axis (root-local X, metric — no unit conversion).
        val targetX = earHalfWidth * OcclusionConstants.TEMPLE_TIP_SCALE

        // The two temples are mirror-symmetric by construction, so we solve ONE
        // swing from mirror-folded, averaged geometry and apply it as equal-and-
        // opposite rotations. Solving each side independently against its own
        // hinge→tip lever was not mirror-consistent: the rest lever is derived
        // from the temple's bounding-box rear corner, a phantom point that lands
        // several degrees apart between the two meshes, so the per-side solve
        // rotated the temples by different amounts and broke symmetry on a
        // symmetric rest pose.
        val pivotXSym = 0.5f * (kotlin.math.abs(hingeLRootRest[12]) + kotlin.math.abs(hingeRRootRest[12]))
        val leverSym = 0.5f * (templeLLeverLen + templeRLeverLen)
        // betaL already lives in the +X half-space; mirror betaR (x→−x: atan2(z,−x))
        // and take the circular mean so the shared bearing is exporter-agnostic.
        val betaRFolded = kotlin.math.atan2(kotlin.math.sin(templeRLeverAngle), -kotlin.math.cos(templeRLeverAngle))
        val betaSym = kotlin.math.atan2(
            kotlin.math.sin(templeLLeverAngle) + kotlin.math.sin(betaRFolded),
            kotlin.math.cos(templeLLeverAngle) + kotlin.math.cos(betaRFolded),
        )

        val c = ((targetX - pivotXSym) / leverSym).coerceIn(-1f, 1f)
        val d = kotlin.math.acos(c)
        val phiA = wrapToPi(betaSym + d)
        val phiB = wrapToPi(betaSym - d)
        val phiSym = if (kotlin.math.abs(phiA) <= kotlin.math.abs(phiB)) phiA else phiB  // +X-side swing

        // HingeL on +X swings by +phiSym; HingeR mirrors with −phiSym. Each
        // rotates about its own pivot (correct conjugation); the outward yaw and
        // down pitch are applied inside swingHinge, already mirrored.
        swingHinge(hingeLEntity, hingeLLocalRest, hingeLRootRest, +phiSym, -1f)
        swingHinge(hingeREntity, hingeRLocalRest, hingeRRootRest, -phiSym, +1f)
    }

    /**
     * Swing one temple about the root-local vertical (Y) through its hinge pivot
     * by [phi], plus a small outward yaw (off the facemesh occluder) and a down
     * pitch (tip to ear height). The root-local rotation M is conjugated into the
     * hinge's parent-relative frame: newLocal = Lr · Hr⁻¹ · M · Hr (which
     * collapses to Lr when the total rotation is zero), so it is correct whatever
     * the hierarchy above the hinge.
     */
    private fun swingHinge(
        hinge: Int,
        localRest: FloatArray,  // Lr
        rootRest: FloatArray,   // Hr
        phi: Float,
        outwardYawSign: Float,
    ) {
        val phiDeg = Math.toDegrees(phi.toDouble()).toFloat()
        val yawDeg = outwardYawSign * OcclusionConstants.TEMPLE_OUTWARD_YAW_DEG
        val pitchDeg = -OcclusionConstants.TEMPLE_DOWN_PITCH_DEG

        // M = T(P) · Ry(φ + yaw) · Rx(pitch) · T(−P), P = hinge pivot in root-local space.
        val px = rootRest[12]; val py = rootRest[13]; val pz = rootRest[14]
        Matrix.setIdentityM(artM, 0)
        Matrix.translateM(artM, 0, px, py, pz)                 // T(P)
        Matrix.setRotateM(artRotY, 0, phiDeg + yawDeg, 0f, 1f, 0f)
        Matrix.multiplyMM(artTmp, 0, artM, 0, artRotY, 0)      // T(P)·Ry
        System.arraycopy(artTmp, 0, artM, 0, 16)
        Matrix.setRotateM(artRotX, 0, pitchDeg, 1f, 0f, 0f)
        Matrix.multiplyMM(artTmp, 0, artM, 0, artRotX, 0)      // T(P)·Ry·Rx
        System.arraycopy(artTmp, 0, artM, 0, 16)
        Matrix.translateM(artM, 0, -px, -py, -pz)              // T(P)·Ry·Rx·T(−P)

        // newLocal = Lr · Hr⁻¹ · M · Hr
        Matrix.invertM(artInvHr, 0, rootRest, 0)
        Matrix.multiplyMM(artTmp, 0, artInvHr, 0, artM, 0)     // Hr⁻¹·M
        Matrix.multiplyMM(artTmp2, 0, artTmp, 0, rootRest, 0)  // Hr⁻¹·M·Hr
        Matrix.multiplyMM(articulationOutMatrix, 0, localRest, 0, artTmp2, 0)
        engine.transformManager.setTransform(engine.transformManager.getInstance(hinge), articulationOutMatrix)
    }

    /** Wrap an angle (radians) into (−π, π] for a fair magnitude comparison. */
    private fun wrapToPi(angle: Float): Float {
        var a = angle
        while (a > Math.PI) a -= (2.0 * Math.PI).toFloat()
        while (a < -Math.PI) a += (2.0 * Math.PI).toFloat()
        return a
    }

    /**
     * Switch to a different glasses model.
     * @param modelUrl URL to the new model (GLB format)
     */
    fun switchModel(modelUrl: String) {
        // The swap happens in loadModelBuffer once the new glb is ready.
        loadModel(modelUrl)
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        executor.shutdown()
        // Invalidate any in-flight load so its completion can't touch the
        // Filament objects torn down below.
        loadGeneration.incrementAndGet()
        removeCurrentModel()
        resourceLoader.destroy()
        assetLoader.destroy()
        // Our own clip-on lens material/instance (not owned by gltfio).
        cliponLensInstance?.let { engine.destroyMaterialInstance(it) }
        cliponLensInstance = null
        cliponLensMaterial?.let { engine.destroyMaterial(it) }
        cliponLensMaterial = null
    }
}
