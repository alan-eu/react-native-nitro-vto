import UIKit
import ARKit
import MetalKit

/**
 * NitroVtoView - A UIView containing the AR glasses try-on view.
 *
 * This view handles:
 * - ARKit session management for face tracking
 * - Filament rendering via VTORendererBridge
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

    // Filament renderer (Objective-C++ bridge)
    private var vtoRenderer: VTORendererBridge?

    // Configuration
    private var modelUrl: String = ""
    private var isActiveState: Bool = true

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
                vtoRenderer?.switchModel(withUrl: modelUrl)
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
        vtoRenderer?.switchModel(withUrl: modelUrl)
    }

    func resetSession() {
        vtoRenderer?.resetSession()
        if let session = arSession {
            let configuration = createARConfiguration()
            session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
        }
    }

    func setOcclusion(_ settings: OcclusionSettings?) {
        let faceMesh = settings?.faceMesh ?? true
        let backPlane = settings?.backPlane ?? true
        vtoRenderer?.setOcclusionWithFaceMesh(faceMesh, backPlane: backPlane)
    }

    // MARK: - Initialization

    private func initialize() {
        guard !isInitialized else { return }
        guard let mtkView = metalView else {
            print("\(NitroVtoView.TAG): Metal not available")
            return
        }

        // Create and initialize renderer
        vtoRenderer = VTORendererBridge(metalView: mtkView)
        vtoRenderer?.onModelLoaded = onModelLoaded
        vtoRenderer?.initialize(withModelUrl: modelUrl)

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
        vtoRenderer?.resume()
    }

    func pause() {
        stopDisplayLink()
        vtoRenderer?.pause()
        arSession?.pause()
        isResumed = false
    }

    func destroy() {
        stopDisplayLink()
        arSession?.pause()
        arSession = nil
        vtoRenderer?.destroy()
        vtoRenderer = nil
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
        vtoRenderer?.render(with: frame, faces: faces)
    }

    // MARK: - ARKit Setup

    private func setupARSession() {
        if let session = arSession {
            let configuration = createARConfiguration()
            session.run(configuration)
            vtoRenderer?.setARSession(session)
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

        // Connect session to renderer
        vtoRenderer?.setARSession(session)

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
            vtoRenderer?.setViewportSizeWithWidth(widthPixels, height: heightPixels)
        }
    }
}
