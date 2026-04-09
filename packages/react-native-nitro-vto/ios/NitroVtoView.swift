import UIKit
import ARKit
import MetalKit

/**
 * NitroVtoView - A UIView containing the AR glasses try-on view.
 *
 * This view handles:
 * - ARKit session management for face tracking
 * - Filament rendering via ArKitVtoAdapter
 * - Face tracking and glasses overlay
 *
 * Note: Camera permissions must be handled by the consuming React Native app
 * before this view becomes active.
 */
class NitroVtoView: UIView {

    private static let TAG = "NitroVtoView"

    // ARKit session
    private var arSession: ARSession?

    // Metal view for rendering
    private var metalView: MTKView?
    private var metalDevice: MTLDevice?

    // Filament renderer adapter
    private var arKitVtoAdapter: ArKitVtoAdapter?

    // Configuration
    private var modelUrl: String = ""
    private var isActiveState: Bool = true
    private var faceMeshOcclusionState: Bool = true
    private var backPlaneOcclusionState: Bool = true
    private var forwardOffsetState: Float = 0.005
    private var debugState: Bool = false

    // Callbacks
    var onModelLoaded: ((String) -> Void)?

    // State
    private var isInitialized = false
    private var isResumed = false

    // Display link for rendering
    private var displayLink: CADisplayLink?

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupMetalView()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupMetalView()
    }

    private func setupMetalView() {
        guard let device = MTLCreateSystemDefaultDevice() else {
            print("\(NitroVtoView.TAG): Metal is not supported on this device")
            return
        }
        metalDevice = device

        let mtkView = MTKView(frame: bounds, device: device)
        mtkView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        mtkView.backgroundColor = .clear
        mtkView.framebufferOnly = false
        mtkView.isPaused = true
        mtkView.enableSetNeedsDisplay = false
        addSubview(mtkView)
        metalView = mtkView
    }

    // MARK: - Public API

    func setModelUrl(_ url: String) {
        if modelUrl != url {
            modelUrl = url
            if isInitialized {
                arKitVtoAdapter?.switchModel(withUrl: modelUrl)
            }
        }
    }

    func setIsActive(_ active: Bool) {
        if isActiveState != active {
            isActiveState = active
            if active && isResumed {
                resume()
            } else if !active {
                pause()
            }
        }
    }

    func switchModel(modelUrl: String) {
        self.modelUrl = modelUrl
        arKitVtoAdapter?.switchModel(withUrl: modelUrl)
    }

    func resetSession() {
        arKitVtoAdapter?.resetSession()
        if let session = arSession {
            let configuration = createARConfiguration()
            session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
        }
    }

    func setFaceMeshOcclusion(_ enabled: Bool?) {
        faceMeshOcclusionState = enabled ?? true
        arKitVtoAdapter?.setFaceMeshOcclusion(faceMeshOcclusionState)
    }

    func setBackPlaneOcclusion(_ enabled: Bool?) {
        backPlaneOcclusionState = enabled ?? true
        arKitVtoAdapter?.setBackPlaneOcclusion(backPlaneOcclusionState)
    }

    func setForwardOffset(_ offset: Double?) {
        forwardOffsetState = Float(offset ?? 0.005)
        arKitVtoAdapter?.setForwardOffset(forwardOffsetState)
    }

    func setDebug(_ enabled: Bool?) {
        debugState = enabled ?? false
        arKitVtoAdapter?.setDebug(debugState)
    }

    // MARK: - Initialization

    private func initialize() {
        guard !isInitialized else { return }
        guard let mtkView = metalView else {
            print("\(NitroVtoView.TAG): Metal not available")
            return
        }

        // Create and initialize renderer
        arKitVtoAdapter = ArKitVtoAdapter(metalView: mtkView)
        arKitVtoAdapter?.onModelLoaded = onModelLoaded
        arKitVtoAdapter?.initialize(withModelUrl: modelUrl)

        // Apply stored configuration states
        arKitVtoAdapter?.setFaceMeshOcclusion(faceMeshOcclusionState)
        arKitVtoAdapter?.setBackPlaneOcclusion(backPlaneOcclusionState)
        arKitVtoAdapter?.setForwardOffset(forwardOffsetState)
        arKitVtoAdapter?.setDebug(debugState)

        isInitialized = true
        print("\(NitroVtoView.TAG): NitroVtoView initialized")
    }

    // MARK: - Lifecycle

    func resume() {
        isResumed = true

        guard isActiveState else { return }

        // Initialize if not already done
        if !isInitialized {
            initialize()
        }

        // Setup AR session if needed
        setupARSession()

        // Start display link for rendering
        startDisplayLink()

        // Resume renderer
        arKitVtoAdapter?.resume()
    }

    func pause() {
        stopDisplayLink()
        arKitVtoAdapter?.pause()
        arSession?.pause()
        isResumed = false
    }

    func destroy() {
        stopDisplayLink()
        arSession?.pause()
        arSession = nil
        arKitVtoAdapter?.destroy()
        arKitVtoAdapter = nil
        isInitialized = false
    }

    // MARK: - Display Link

    private func startDisplayLink() {
        guard displayLink == nil else { return }
        displayLink = CADisplayLink(target: self, selector: #selector(render))
        displayLink?.preferredFramesPerSecond = 60
        displayLink?.add(to: .main, forMode: .common)
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func render() {
        guard isInitialized, isActiveState else { return }
        guard let session = arSession, let frame = session.currentFrame else { return }

        // Get tracked faces
        let faces = frame.anchors.compactMap { $0 as? ARFaceAnchor }
            .filter { $0.isTracked }

        // Update renderer with current frame
        arKitVtoAdapter?.render(with: frame, faces: faces)
    }

    // MARK: - ARKit Setup

    private func setupARSession() {
        if let session = arSession {
            let configuration = createARConfiguration()
            session.run(configuration)
            return
        }

        guard ARFaceTrackingConfiguration.isSupported else {
            print("\(NitroVtoView.TAG): Face tracking is not supported on this device")
            return
        }

        let session = ARSession()
        arSession = session

        let configuration = createARConfiguration()
        session.run(configuration)

        print("\(NitroVtoView.TAG): ARKit session created successfully")
    }

    private func createARConfiguration() -> ARFaceTrackingConfiguration {
        let configuration = ARFaceTrackingConfiguration()
        configuration.isLightEstimationEnabled = true
        if #available(iOS 13.0, *) {
            configuration.maximumNumberOfTrackedFaces = 1
        }
        return configuration
    }

    // MARK: - View Lifecycle

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil {
            // View is being removed
            destroy()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        metalView?.frame = bounds

        // Use drawable size (in pixels) not bounds (in points) for proper Retina support
        if let mtkView = metalView {
            let scale = mtkView.contentScaleFactor
            let widthPixels = Int32(bounds.width * scale)
            let heightPixels = Int32(bounds.height * scale)
            arKitVtoAdapter?.setViewportSizeWithWidth(widthPixels, height: heightPixels)
        }
    }
}
