import UIKit
import NitroModules

/**
 * HybridNitroVtoView - NitroModules HybridView implementation for NitroVto.
 *
 * This class extends the generated HybridNitroVtoViewSpec and provides
 * the actual implementation for the NitroVto view on iOS.
 */
class HybridNitroVtoView: HybridNitroVtoViewSpec {

    // The underlying native view (shared core — class `VtoView` lives in
    // core/ios/VtoView.swift and is bundled into this package on install).
    private let nitroVtoView: VtoView

    public required override init() {
        self.nitroVtoView = VtoView()
        super.init()
    }

    /// Returns the native view
    public var view: UIView {
        return nitroVtoView
    }

    // MARK: - Props implementation

    public var modelUrl: String = "" {
        didSet {
            nitroVtoView.setModelUrl(modelUrl)
        }
    }

    public var isActive: Bool = true {
        didSet {
            nitroVtoView.setIsActive(isActive)
        }
    }

    public var onModelLoaded: ((String) -> Void)? = nil {
        didSet {
            nitroVtoView.onModelLoaded = onModelLoaded
        }
    }

    public var onFaceTracked: (() -> Void)? = nil {
        didSet {
            nitroVtoView.onFaceTracked = onFaceTracked
        }
    }

    public var onGlassesDisplayed: ((String) -> Void)? = nil {
        didSet {
            nitroVtoView.onGlassesDisplayed = onGlassesDisplayed
        }
    }

    public var faceMeshOcclusion: Bool? = nil {
        didSet {
            nitroVtoView.setFaceMeshOcclusion(faceMeshOcclusion)
        }
    }

    public var backPlaneOcclusion: Bool? = nil {
        didSet {
            nitroVtoView.setBackPlaneOcclusion(backPlaneOcclusion)
        }
    }

    public var forwardOffset: Double? = nil {
        didSet {
            nitroVtoView.setForwardOffset(forwardOffset)
        }
    }

    public var debug: Bool? = nil {
        didSet {
            nitroVtoView.setDebug(debug)
        }
    }

    // MARK: - Methods implementation

    public func switchModel(modelUrl: String) throws {
        nitroVtoView.switchModel(modelUrl: modelUrl)
    }

    public func resetSession() throws {
        nitroVtoView.resetSession()
    }

    // MARK: - Lifecycle callbacks from HybridView protocol

    public func beforeUpdate() {
        // Called before props are updated
    }

    public func afterUpdate() {
        // Called after props are updated
        // Resume the view if active
        if isActive {
            nitroVtoView.resume()
        }
    }
}
