import UIKit
import NitroModules

/**
 * HybridNitroVtoView - NitroModules HybridView implementation for NitroVto.
 *
 * This class extends the generated HybridNitroVtoViewSpec and provides
 * the actual implementation for the NitroVto view on iOS.
 */
class HybridNitroVtoView: HybridNitroVtoViewSpec {

    // The underlying native view
    private let nitroVtoView: NitroVtoView

    public required override init() {
        self.nitroVtoView = NitroVtoView()
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
