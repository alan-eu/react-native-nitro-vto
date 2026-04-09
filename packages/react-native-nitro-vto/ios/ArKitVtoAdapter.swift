import Foundation
import UIKit
import ARKit
import MetalKit
import simd

@objcMembers
class ArKitVtoAdapter: NSObject {
    private static let tag = "ArKitVtoAdapter"

    private static let arKitNoseBridgeLeftIndex = 818
    private static let arKitNoseBridgeRightIndex = 366

    private static let materialCamera = "materials/camera_background.filamat"
    private static let materialFaceOcclusion = "materials/face_occlusion.filamat"
    private static let materialDebugFace = "materials/debug_face_material.filamat"
    private static let materialDebugPlane = "materials/debug_plane_material.filamat"
    private static let envIbl = "envs/studio_small_02_2k_ibl.ktx"
    private static let envSkybox = "envs/studio_small_02_2k_skybox.ktx"
    private static let envSh = "envs/studio_small_02_2k_sh.txt"

    var onModelLoaded: ((String) -> Void)?

    private let metalView: MTKView
    private var coreBridge: VtoCoreBridge?

    private var initialized = false
    private var width = 0
    private var height = 0

    private var faceMeshOcclusionEnabled = true
    private var backPlaneOcclusionEnabled = true
    private var debugEnabled = false
    private var forwardOffsetMeters: Float = 0.005
    private var modelUrl = ""

    private let modelLoadQueue = DispatchQueue(label: "com.nitrovto.arkit.model")
    private var modelLoadVersion = 0

    @objc(initWithMetalView:)
    init(metalView: MTKView) {
        self.metalView = metalView
        super.init()
    }

    @objc(initializeWithModelUrl:)
    func initialize(withModelUrl modelUrl: String) {
        self.modelUrl = modelUrl
        guard let metalLayer = metalView.layer as? CAMetalLayer else {
            print("\(Self.tag): Failed to get CAMetalLayer")
            return
        }

        let bridge = VtoCoreBridge()
        let ok = bridge.initialize(
            withMetalLayer: metalLayer,
            faceMeshOcclusion: faceMeshOcclusionEnabled,
            backPlaneOcclusion: backPlaneOcclusionEnabled,
            forwardOffsetMeters: forwardOffsetMeters,
            debug: debugEnabled,
            noseBridgeLeftIndex: Int32(Self.arKitNoseBridgeLeftIndex),
            noseBridgeRightIndex: Int32(Self.arKitNoseBridgeRightIndex)
        )
        if !ok {
            print("\(Self.tag): Failed to initialize VTO core")
            return
        }

        coreBridge = bridge

        if width > 0 && height > 0 {
            bridge.resize(width: Int32(width), height: Int32(height))
        }

        loadCoreAssets()
        initialized = true
        requestModelLoad(modelUrl)
    }

    @objc(setViewportSizeWithWidth:height:)
    func setViewportSizeWithWidth(_ width: Int32, height: Int32) {
        self.width = Int(width)
        self.height = Int(height)
        coreBridge?.resize(width: width, height: height)
    }

    @objc
    func resume() {}

    @objc
    func pause() {}

    @objc(switchModelWithUrl:)
    func switchModel(withUrl modelUrl: String) {
        self.modelUrl = modelUrl
        requestModelLoad(modelUrl)
    }

    @objc
    func resetSession() {
        coreBridge?.resetSession()
    }

    @objc(setFaceMeshOcclusion:)
    func setFaceMeshOcclusion(_ enabled: Bool) {
        faceMeshOcclusionEnabled = enabled
        syncCoreConfig()
    }

    @objc(setBackPlaneOcclusion:)
    func setBackPlaneOcclusion(_ enabled: Bool) {
        backPlaneOcclusionEnabled = enabled
        syncCoreConfig()
    }

    @objc(setForwardOffset:)
    func setForwardOffset(_ offset: Float) {
        forwardOffsetMeters = offset
        syncCoreConfig()
    }

    @objc(setDebug:)
    func setDebug(_ enabled: Bool) {
        debugEnabled = enabled
        syncCoreConfig()
    }

    @objc(renderWithFrame:faces:)
    func render(with frame: ARFrame, faces: [ARFaceAnchor]) {
        guard initialized, let coreBridge else {
            return
        }

        guard let cameraMatrices = computeCameraMatrices(from: frame),
              let uvTransform = computeCameraUvTransform(from: frame) else {
            return
        }

        let faceSubmission = faces.first.flatMap(extractFaceData)
        let lightEstimate = frame.lightEstimate.map { max(0.0, Float($0.ambientIntensity) / 1000.0) }
        let lightValid = lightEstimate != nil
        let lightIntensity = lightEstimate ?? 0.0

        withUnsafeBufferPointerOrNil(cameraMatrices.projection) { projectionPtr in
            withUnsafeBufferPointerOrNil(cameraMatrices.model) { modelPtr in
                withUnsafeBufferPointerOrNil(uvTransform) { uvPtr in
                    withUnsafeBufferPointerOrNil(faceSubmission?.vertices) { verticesPtr in
                        withUnsafeBufferPointerOrNil(faceSubmission?.indices) { indicesPtr in
                            withUnsafeBufferPointerOrNil(faceSubmission?.faceToWorld) { faceToWorldPtr in
                                withUnsafeBufferPointerOrNil(faceSubmission?.rotationQuaternion) { rotationPtr in
                                    coreBridge.submitFrame(
                                        viewportWidth: Int32(width),
                                        viewportHeight: Int32(height),
                                        hasCameraMatrices: true,
                                        projection: projectionPtr,
                                        model: modelPtr,
                                        hasCameraFeed: true,
                                        pixelBuffer: frame.capturedImage,
                                        uvTransform: uvPtr,
                                        hasFace: faceSubmission != nil,
                                        vertices: verticesPtr,
                                        vertexCount: Int32(faceSubmission?.vertexCount ?? 0),
                                        indices: indicesPtr,
                                        indexCount: Int32(faceSubmission?.indexCount ?? 0),
                                        faceToWorld: faceToWorldPtr,
                                        rotationQuaternion: rotationPtr,
                                        hasLightEstimate: true,
                                        lightValid: lightValid,
                                        linearIntensity: lightIntensity
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        coreBridge.render()
    }

    @objc
    func destroy() {
        coreBridge?.destroy()
        coreBridge = nil
        initialized = false
    }

    private struct FaceSubmission {
        let vertices: [Float]
        let indices: [UInt16]
        let faceToWorld: [Float]
        let rotationQuaternion: [Float]

        var vertexCount: Int { vertices.count / 3 }
        var indexCount: Int { indices.count }
    }

    private func withUnsafeBufferPointerOrNil<T, R>(_ values: [T]?, body: (UnsafePointer<T>?) -> R) -> R {
        guard let values else {
            return body(nil)
        }
        return values.withUnsafeBufferPointer { pointer in
            body(pointer.baseAddress)
        }
    }

    private func computeCameraMatrices(from frame: ARFrame) -> (projection: [Float], model: [Float])? {
        guard width > 0, height > 0 else {
            return nil
        }

        let viewportSize = CGSize(width: width, height: height)
        let viewMatrix = frame.camera.viewMatrix(for: .portrait)
        let projectionMatrix = frame.camera.projectionMatrix(for: .portrait, viewportSize: viewportSize, zNear: 0.01, zFar: 100.0)
        let cameraModelMatrix = simd_inverse(viewMatrix)

        var projection = [Float](repeating: 0.0, count: 16)
        var model = [Float](repeating: 0.0, count: 16)
        for col in 0..<4 {
            for row in 0..<4 {
                projection[col * 4 + row] = projectionMatrix[col][row]
                model[col * 4 + row] = cameraModelMatrix[col][row]
            }
        }

        return (projection, model)
    }

    private func computeCameraUvTransform(from frame: ARFrame) -> [Float]? {
        guard width > 0, height > 0 else {
            return nil
        }

        let viewportSize = CGSize(width: width, height: height)
        let displayTransform = frame.displayTransform(for: .portrait, viewportSize: viewportSize)
        let transformInv = displayTransform.inverted()

        let corrected = CGAffineTransform(
            a: -transformInv.a,
            b: transformInv.b,
            c: -transformInv.c,
            d: transformInv.d,
            tx: 1.0 - transformInv.tx,
            ty: transformInv.ty
        )

        return [
            Float(corrected.a), Float(corrected.b), 0.0,
            Float(corrected.c), Float(corrected.d), 0.0,
            Float(corrected.tx), Float(corrected.ty), 1.0,
        ]
    }

    private func extractFaceData(_ face: ARFaceAnchor) -> FaceSubmission? {
        let geometry = face.geometry
        let vertices = geometry.vertices
        let indices = geometry.triangleIndices
        let vertexCount = vertices.count
        let indexCount = indices.count

        if vertexCount == 0 || indexCount == 0 {
            return nil
        }

        var packedVertices = [Float](repeating: 0.0, count: vertexCount * 3)
        for i in 0..<vertexCount {
            let vertex = vertices[i]
            let base = i * 3
            packedVertices[base] = vertex.x
            packedVertices[base + 1] = vertex.y
            packedVertices[base + 2] = vertex.z
        }

        var packedIndices = [UInt16](repeating: 0, count: indexCount)
        for i in 0..<indexCount {
            packedIndices[i] = UInt16(bitPattern: indices[i])
        }

        var faceToWorld = [Float](repeating: 0.0, count: 16)
        for col in 0..<4 {
            for row in 0..<4 {
                faceToWorld[col * 4 + row] = face.transform[col][row]
            }
        }

        let rotation = simd_quaternion(face.transform)
        let rotationQuaternion: [Float] = [
            rotation.vector.x,
            rotation.vector.y,
            rotation.vector.z,
            rotation.vector.w,
        ]

        return FaceSubmission(
            vertices: packedVertices,
            indices: packedIndices,
            faceToWorld: faceToWorld,
            rotationQuaternion: rotationQuaternion
        )
    }

    private func syncCoreConfig() {
        coreBridge?.updateConfig(
            faceMeshOcclusion: faceMeshOcclusionEnabled,
            backPlaneOcclusion: backPlaneOcclusionEnabled,
            forwardOffsetMeters: forwardOffsetMeters,
            debug: debugEnabled,
            noseBridgeLeftIndex: Int32(Self.arKitNoseBridgeLeftIndex),
            noseBridgeRightIndex: Int32(Self.arKitNoseBridgeRightIndex)
        )
    }

    private func loadCoreAssets() {
        guard let coreBridge else {
            return
        }

        if let cameraMat = loadAssetData(Self.materialCamera) {
            _ = coreBridge.setMaterialPackage(kind: 0, bytes: cameraMat)
        }
        if let faceMat = loadAssetData(Self.materialFaceOcclusion) {
            _ = coreBridge.setMaterialPackage(kind: 1, bytes: faceMat)
        }
        if let debugFaceMat = loadAssetData(Self.materialDebugFace) {
            _ = coreBridge.setMaterialPackage(kind: 2, bytes: debugFaceMat)
        }
        if let debugPlaneMat = loadAssetData(Self.materialDebugPlane) {
            _ = coreBridge.setMaterialPackage(kind: 3, bytes: debugPlaneMat)
        }

        if let ibl = loadAssetData(Self.envIbl) {
            _ = coreBridge.setEnvironmentIblKtx(ibl)
        }

        if let skybox = loadAssetData(Self.envSkybox) {
            _ = coreBridge.setEnvironmentSkyboxKtx(skybox)
        }

        if let shData = loadAssetData(Self.envSh),
           let shText = String(data: shData, encoding: .utf8),
           let sh = parseShCoefficients(shText),
           sh.count == 27 {
            sh.withUnsafeBufferPointer { shPtr in
                if let shBase = shPtr.baseAddress {
                    coreBridge.setEnvironmentSphericalHarmonics(shBase)
                }
            }
        }
    }

    private func requestModelLoad(_ url: String) {
        guard !url.isEmpty else {
            return
        }

        modelLoadVersion += 1
        let requestVersion = modelLoadVersion
        modelLoadQueue.async { [weak self] in
            guard let self else { return }
            let modelBytes = self.loadModelBytes(url)
            DispatchQueue.main.async {
                guard requestVersion == self.modelLoadVersion,
                      self.modelUrl == url,
                      let modelBytes,
                      let coreBridge = self.coreBridge else {
                    return
                }
                if coreBridge.setModelFromBytes(modelBytes, sourceId: url), let onModelLoaded = self.onModelLoaded {
                    onModelLoaded(url)
                }
            }
        }
    }

    private func loadModelBytes(_ url: String) -> Data? {
        if url.hasPrefix("http://") || url.hasPrefix("https://") {
            do {
                return try LoaderUtils.loadModelFromUrl(url)
            } catch {
                print("\(Self.tag): model download failed: \(error.localizedDescription)")
                return nil
            }
        }

        if url.hasPrefix("asset://") {
            let assetPath = String(url.dropFirst(8))
            return loadAssetData(assetPath)
        }

        let filePath = url.hasPrefix("file://") ? String(url.dropFirst(7)) : url
        return FileManager.default.contents(atPath: filePath)
    }

    private func loadAssetData(_ filename: String) -> Data? {
        let pathComponents = filename.split(separator: "/").map(String.init)
        let lastComponent = pathComponents.last ?? filename
        let resourceName = (lastComponent as NSString).deletingPathExtension
        let ext = (lastComponent as NSString).pathExtension
        let subdirectory = pathComponents.dropLast().joined(separator: "/")

        let classBundle = Bundle(for: ArKitVtoAdapter.self)
        let assetsBundle = classBundle.url(forResource: "NitroVtoAssets", withExtension: "bundle").flatMap(Bundle.init(url:))
        let bundles = [assetsBundle, classBundle, Bundle.main].compactMap { $0 }

        for bundle in bundles {
            let urlWithPath = bundle.url(
                forResource: resourceName,
                withExtension: ext.isEmpty ? nil : ext,
                subdirectory: subdirectory.isEmpty ? nil : subdirectory
            )
            let url = urlWithPath ?? bundle.url(forResource: filename, withExtension: nil)
            if let url, let data = try? Data(contentsOf: url) {
                return data
            }
        }

        print("\(Self.tag): Failed to find asset: \(filename)")
        return nil
    }

    private func parseShCoefficients(_ text: String) -> [Float]? {
        var values = [Float]()
        values.reserveCapacity(27)

        for line in text.split(separator: "\n") {
            if values.count >= 27 {
                break
            }
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            guard trimmed.hasPrefix("("), let close = trimmed.firstIndex(of: ")") else {
                continue
            }
            let inner = trimmed[trimmed.index(after: trimmed.startIndex)..<close]
            let parts = inner.split(separator: ",")
            guard parts.count == 3 else {
                continue
            }
            for part in parts {
                guard let value = Float(part.trimmingCharacters(in: .whitespacesAndNewlines)) else {
                    return nil
                }
                values.append(value)
            }
        }

        return values.count == 27 ? values : nil
    }
}
