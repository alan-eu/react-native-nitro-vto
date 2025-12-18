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
    private var modelWidthMeters: Float = 0
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
        
        // Configure for manual rendering control
        mtkView.framebufferOnly = false  // Allow reading from the drawable
        mtkView.isPaused = true  // We control rendering with CADisplayLink
        mtkView.enableSetNeedsDisplay = false  // Disable automatic rendering
        
        // Set color pixel format to match ARKit camera feed
        mtkView.colorPixelFormat = .bgra8Unorm
        mtkView.depthStencilPixelFormat = .depth32Float
        
        // Set drawable size to match view size for crisp rendering
        mtkView.contentScaleFactor = UIScreen.main.scale
        mtkView.drawableSize = CGSize(
            width: bounds.width * UIScreen.main.scale,
            height: bounds.height * UIScreen.main.scale
        )
        
        // Configure Metal layer for opaque rendering (AR camera background is opaque)
        if let metalLayer = mtkView.layer as? CAMetalLayer {
            metalLayer.isOpaque = true  // Set to true to match Filament's swap chain config
        }
        
        addSubview(mtkView)
        metalView = mtkView
    }

    // MARK: - Public API

    func setModelUrl(_ url: String) {
        if modelUrl != url {
            modelUrl = url
            if isInitialized {
                vtoRenderer?.switchModel(withUrl: modelUrl, widthMeters: modelWidthMeters)
            }
        }
    }

    func setModelWidthMeters(_ width: Float) {
        if modelWidthMeters != width {
            modelWidthMeters = width
            if isInitialized {
                vtoRenderer?.switchModel(withUrl: modelUrl, widthMeters: modelWidthMeters)
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

    func switchModel(modelUrl: String, widthMeters: Float) {
        self.modelUrl = modelUrl
        self.modelWidthMeters = widthMeters
        vtoRenderer?.switchModel(withUrl: modelUrl, widthMeters: widthMeters)
    }

    func resetSession() {
        vtoRenderer?.resetSession()
        if let session = arSession {
            let configuration = createARConfiguration()
            session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
        }
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
        vtoRenderer?.initialize(withModelUrl: modelUrl, widthMeters: modelWidthMeters)

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
        // Match ARKit's frame rate (typically 60 FPS, but can be lower on older devices)
        // Use 0 to let the system determine the best frame rate
        displayLink?.preferredFramesPerSecond = 0  // System determines optimal rate
        displayLink?.add(to: .main, forMode: .common)
        print("\(NitroVtoView.TAG): Display link started")
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func render() {
        guard isInitialized, isActiveState else { return }
        guard let session = arSession, let frame = session.currentFrame else { return }
        
        // Ensure we have a valid drawable before rendering
        guard let _ = metalView?.currentDrawable else { return }

        // Get tracked faces
        let faces = frame.anchors.compactMap { $0 as? ARFaceAnchor }
            .filter { $0.isTracked }

        // Update renderer with current frame
        // This runs on the main thread via CADisplayLink
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
        
        // Update drawable size to match new bounds with proper scale
        let scale = UIScreen.main.scale
        let drawableWidth = bounds.width * scale
        let drawableHeight = bounds.height * scale
        metalView?.drawableSize = CGSize(width: drawableWidth, height: drawableHeight)
        
        // Update renderer viewport
        vtoRenderer?.setViewportSizeWithWidth(Int32(drawableWidth), height: Int32(drawableHeight))
    }
}
