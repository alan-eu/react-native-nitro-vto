import UIKit
import ARKit
import MetalKit

/**
 * VtoView - A UIView containing the AR glasses try-on view.
 *
 * This view handles:
 * - ARKit session management for face tracking
 * - Filament rendering via VTORendererBridge
 * - Face tracking and glasses overlay
 *
 * Note: Camera permissions must be handled by the consuming React Native app
 * before this view becomes active.
 */
// `public` so Swift emits this class into each wrapper's auto-generated
// ObjC interface header (`<ModuleName>-Swift.h`). Subclasses in wrappers
// (e.g. VtoBridgeView in react-native-vto) need to be public themselves,
// and a public subclass requires a public superclass. Internal members
// remain internal — ObjC visibility is still gated by @objc.
public class VtoView: UIView {

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
    private var forwardOffsetState: Float = kForwardOffset
    private var debugState: Bool = false

    // Callbacks
    var onModelLoaded: ((String) -> Void)?
    var onFaceTracked: (() -> Void)?
    var onGlassesDisplayed: ((String) -> Void)?

    // State
    private var isInitialized = false
    private var isResumed = false

    // Display link for rendering
    private var displayLink: CADisplayLink?

    // FPS counter overlay (visible when debug=true).
    private var fpsLabel: UILabel?
    private var fpsFrameCount: Int = 0
    private var fpsLastUpdateTime: CFTimeInterval = 0

    public override init(frame: CGRect) {
        super.init(frame: frame)
        setupMetalView()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupMetalView()
    }

    private func setupMetalView() {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        metalDevice = device

        let mtkView = MTKView(frame: bounds, device: device)
        mtkView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        mtkView.backgroundColor = .clear
        mtkView.framebufferOnly = false
        mtkView.isPaused = true
        mtkView.enableSetNeedsDisplay = false
        addSubview(mtkView)
        metalView = mtkView

        // FPS overlay — top-right, monospace, hidden until debug=true.
        let label = UILabel(frame: CGRect(x: bounds.width - 110, y: 30, width: 100, height: 18))
        label.autoresizingMask = [.flexibleLeftMargin, .flexibleBottomMargin]
        label.font = UIFont.monospacedDigitSystemFont(ofSize: 11, weight: .semibold)
        label.textColor = .yellow
        label.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        label.textAlignment = .right
        label.text = "—"
        label.isHidden = true
        addSubview(label)
        fpsLabel = label
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

    func hideGlasses() {
        vtoRenderer?.hideGlasses()
    }

    func showGlasses() {
        vtoRenderer?.showGlasses()
    }

    func setForwardOffset(_ offset: Double?) {
        forwardOffsetState = offset.map { Float($0) } ?? kForwardOffset
        vtoRenderer?.setForwardOffset(forwardOffsetState)
    }

    func setDebug(_ enabled: Bool?) {
        debugState = enabled ?? false
        vtoRenderer?.setDebug(debugState)
    }

    func setShowNativeFPS(_ enabled: Bool?) {
        fpsLabel?.isHidden = !(enabled ?? false)
    }

    // MARK: - Initialization

    private func initialize() {
        guard !isInitialized else { return }
        guard let mtkView = metalView else { return }

        // Force the metal view + drawable size to match our current bounds
        // BEFORE Filament builds its swap chain around the layer. `MTKView`'s
        // autoResizeDrawable normally handles this on the next layout pass,
        // but Filament captures the layer size at swap-chain creation — if
        // that happens before the pending layout pass, the swap chain gets
        // pinned to the old (potentially zero) size and all subsequent
        // rendering is confined to that region, even after the view grows.
        mtkView.frame = bounds
        let scale = mtkView.contentScaleFactor
        mtkView.drawableSize = CGSize(
            width: bounds.width * scale,
            height: bounds.height * scale
        )

        // Create and initialize renderer
        vtoRenderer = VTORendererBridge(metalView: mtkView)
        vtoRenderer?.onModelLoaded = onModelLoaded
        vtoRenderer?.onFaceTracked = onFaceTracked
        vtoRenderer?.onGlassesDisplayed = onGlassesDisplayed
        vtoRenderer?.initialize(withModelUrl: modelUrl)

        // Apply stored configuration states
        vtoRenderer?.setForwardOffset(forwardOffsetState)
        vtoRenderer?.setDebug(debugState)

        // Sync viewport from current bounds. `layoutSubviews` may have already
        // fired before the renderer existed (happens on old-arch RN where the
        // view's frame is set before it's attached to a window, so its
        // `setViewportSize` call was a no-op against a nil renderer). Without
        // this, the camera-background pass never covers the full drawable and
        // trails from previous frames accumulate.
        syncViewportSize()

        isInitialized = true
    }

    private func syncViewportSize() {
        guard let mtkView = metalView, bounds.width > 0, bounds.height > 0 else { return }
        let scale = mtkView.contentScaleFactor
        let widthPixels = Int32(bounds.width * scale)
        let heightPixels = Int32(bounds.height * scale)
        vtoRenderer?.setViewportSizeWithWidth(widthPixels, height: heightPixels)
    }

    // MARK: - Lifecycle

    func resume() {
        isResumed = true

        guard isActiveState else { return }

        // Defer until the view has real bounds — Filament's swap chain is built
        // around the metal layer at `initialize()` time and caches the size
        // then, so creating it while the layer is zero-sized pins rendering to
        // a tiny region forever. On old-arch RN this can happen: didMoveToWindow
        // fires before layoutSubviews assigns the real frame. `layoutSubviews`
        // retries this when the frame comes in.
        guard bounds.width > 0, bounds.height > 0 else { return }

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

        // Release Metal resources held by the MTKView so iOS can reclaim them
        // without jetsam pressure. Without this, the view keeps ~3 drawable
        // textures (triple buffering) + its command queue + device references
        // resident until the VtoView itself is deallocated — which can take
        // additional runloop passes if any framework is still holding it.
        metalView?.releaseDrawables()
        metalView?.removeFromSuperview()
        metalView = nil

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

        // FPS counter — refresh once per 500ms.
        fpsFrameCount += 1
        let now = CACurrentMediaTime()
        if fpsLastUpdateTime == 0 { fpsLastUpdateTime = now }
        let dt = now - fpsLastUpdateTime
        if dt >= 0.5, let label = fpsLabel, !label.isHidden {
            let fps = Double(fpsFrameCount) / dt
            label.text = String(format: "%.0f fps · %.1f ms", fps, 1000.0 / max(fps, 1))
            fpsFrameCount = 0
            fpsLastUpdateTime = now
        }
    }

    // MARK: - ARKit Setup

    private func setupARSession() {
        if let session = arSession {
            let configuration = createARConfiguration()
            session.run(configuration)
            vtoRenderer?.setARSession(session)
            return
        }

        guard ARFaceTrackingConfiguration.isSupported else { return }

        let session = ARSession()
        arSession = session

        let configuration = createARConfiguration()
        session.run(configuration)

        // Connect session to renderer
        vtoRenderer?.setARSession(session)
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

    public override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil {
            // View is being removed
            destroy()
        }
    }

    public override func didMoveToWindow() {
        super.didMoveToWindow()
        // Attached to a window — auto-resume so old-arch wrappers that don't
        // have an `afterUpdate`-style hook get the same behavior as Nitro.
        // `resume()` is idempotent; the Nitro path still calls it via
        // `afterUpdate`, which is a harmless no-op after the first time.
        if window != nil {
            resume()
        }
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        metalView?.frame = bounds

        // Use drawable size (in pixels) not bounds (in points) for proper Retina support
        if let mtkView = metalView {
            let scale = mtkView.contentScaleFactor
            let widthPixels = Int32(bounds.width * scale)
            let heightPixels = Int32(bounds.height * scale)
            vtoRenderer?.setViewportSizeWithWidth(widthPixels, height: heightPixels)
        }

        // If resume() was attempted before the view had real bounds (typical
        // on old-arch RN where didMoveToWindow fires before the frame is set),
        // retry now that bounds are valid.
        if isResumed && !isInitialized {
            resume()
        }
    }
}
