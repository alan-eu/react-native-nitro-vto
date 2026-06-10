import UIKit

/// RCTDirectEventBlock from React is `void (^)(NSDictionary *)`. Declaring a
/// typealias with the matching block signature avoids a React bridging header
/// in the Swift target while keeping the ObjC-side selectors identical.
/// Must be `public` because it's referenced by public event-block properties
/// below — if it were internal, Swift would refuse to expose those properties.
public typealias VtoRCTDirectEventBlock = @convention(block) ([AnyHashable: Any]) -> Void

/// Old-architecture bridge view: subclasses the shared `VtoView` and adds
/// @objc-exposed direct-event blocks the RCTViewManager can connect to via
/// `RCT_EXPORT_VIEW_PROPERTY`. The core closures (`onModelLoaded`,
/// `onFaceTracked`, `onGlassesDisplayed`) are wired to fire the matching event.
///
/// The class is `public` so it appears in the pod's auto-generated
/// `ReactNativeVto-Swift.h`, which is what `VtoViewManager.mm` imports.
/// Internal `@objc` members (marked below) are still visible to ObjC in the
/// same module thanks to the `@objc` attribute — they don't need `public`.
@objc(VtoBridgeView)
public class VtoBridgeView: VtoView {

    // `public` so they appear in the *public* section of the auto-generated
    // `ReactNativeVto-Swift.h` that VtoViewManager.mm imports via angle-bracket
    // include. Without `public`, they're only in the internal section which
    // isn't reachable from a module-style import.
    @objc public var onModelLoadedEvent: VtoRCTDirectEventBlock?
    @objc public var onFaceTrackedEvent: VtoRCTDirectEventBlock?
    @objc public var onGlassesDisplayedEvent: VtoRCTDirectEventBlock?

    public override init(frame: CGRect) {
        super.init(frame: frame)
        wireCallbacks()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        wireCallbacks()
    }

    private func wireCallbacks() {
        self.onModelLoaded = { [weak self] url in
            self?.onModelLoadedEvent?(["modelUrl": url])
        }
        self.onFaceTracked = { [weak self] in
            self?.onFaceTrackedEvent?([:])
        }
        self.onGlassesDisplayed = { [weak self] url in
            self?.onGlassesDisplayedEvent?(["modelUrl": url])
        }
    }

    // Convenience typed wrappers for the core view's setters so the
    // RCTViewManager can hand them native-shaped values.

    @objc public func rnSetModelUrl(_ url: NSString) {
        super.setModelUrl(url as String)
    }

    @objc public func rnSetIsActive(_ active: Bool) {
        super.setIsActive(active)
    }

    @objc public func rnSetForwardOffset(_ offset: NSNumber?) {
        super.setForwardOffset(offset?.doubleValue)
    }

    @objc public func rnSetDebug(_ enabled: NSNumber?) {
        super.setDebug(enabled?.boolValue)
    }

    @objc public func rnSetShowNativeFPS(_ enabled: NSNumber?) {
        super.setShowNativeFPS(enabled?.boolValue)
    }

    @objc public func rnSetIsClipOn(_ enabled: Bool) {
        super.setIsClipOn(enabled)
    }

    @objc public func rnHideGlasses() {
        super.hideGlasses()
    }

    @objc public func rnShowGlasses() {
        super.showGlasses()
    }
}
