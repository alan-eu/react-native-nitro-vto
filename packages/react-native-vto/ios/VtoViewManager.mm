#import "VtoViewManager.h"
#import <React/RCTBridge.h>
#import <React/RCTUIManager.h>

// The Swift-generated ObjC interface for this pod's Swift classes. CocoaPods
// synthesizes this header at build time using the pod name as module name
// (see `s.name = "ReactNativeVto"` in the podspec).
#if __has_include(<ReactNativeVto/ReactNativeVto-Swift.h>)
#import <ReactNativeVto/ReactNativeVto-Swift.h>
#else
// Fallback for some CocoaPods project layouts where the module umbrella header
// imports are rewritten.
#import "ReactNativeVto-Swift.h"
#endif

@implementation VtoViewManager

RCT_EXPORT_MODULE(VtoView)

- (UIView *)view {
  return [[VtoBridgeView alloc] initWithFrame:CGRectZero];
}

// --- Props (JS name → ObjC selector on VtoBridgeView) -----------------------
// Using `rnSetXxx:` rather than the Swift setter directly avoids clashing with
// the VtoView superclass's equally-named setters (those are not @objc exposed).

RCT_CUSTOM_VIEW_PROPERTY(modelUrl, NSString *, VtoBridgeView) {
  [view rnSetModelUrl:(json ?: @"")];
}

RCT_CUSTOM_VIEW_PROPERTY(isActive, BOOL, VtoBridgeView) {
  [view rnSetIsActive:(json ? [RCTConvert BOOL:json] : YES)];
}

RCT_CUSTOM_VIEW_PROPERTY(forwardOffset, CGFloat, VtoBridgeView) {
  [view rnSetForwardOffset:(json ? @([RCTConvert CGFloat:json]) : nil)];
}

RCT_CUSTOM_VIEW_PROPERTY(debug, BOOL, VtoBridgeView) {
  [view rnSetDebug:(json ? @([RCTConvert BOOL:json]) : nil)];
}

RCT_CUSTOM_VIEW_PROPERTY(showNativeFPS, BOOL, VtoBridgeView) {
  [view rnSetShowNativeFPS:(json ? @([RCTConvert BOOL:json]) : nil)];
}

RCT_CUSTOM_VIEW_PROPERTY(isClipOn, BOOL, VtoBridgeView) {
  [view rnSetIsClipOn:(json ? [RCTConvert BOOL:json] : NO)];
}

// --- Callbacks (RN wires these via the event names) -------------------------
// The JS-side prop names are onXxx; the Swift bridge view exposes them under
// `onXxxEvent` to avoid clashing with the core closure properties of the same
// base name on the superclass. RCT_REMAP_VIEW_PROPERTY bridges the two.

RCT_REMAP_VIEW_PROPERTY(onModelLoaded, onModelLoadedEvent, RCTDirectEventBlock)
RCT_REMAP_VIEW_PROPERTY(onFaceTracked, onFaceTrackedEvent, RCTDirectEventBlock)
RCT_REMAP_VIEW_PROPERTY(onGlassesDisplayed, onGlassesDisplayedEvent, RCTDirectEventBlock)

// --- Imperative commands ----------------------------------------------------

RCT_EXPORT_METHOD(hideGlasses:(nonnull NSNumber *)reactTag) {
  [self.bridge.uiManager addUIBlock:^(RCTUIManager *uiManager,
                                       NSDictionary<NSNumber *, UIView *> *viewRegistry) {
    UIView *view = viewRegistry[reactTag];
    if ([view isKindOfClass:[VtoBridgeView class]]) {
      [(VtoBridgeView *)view rnHideGlasses];
    }
  }];
}

RCT_EXPORT_METHOD(showGlasses:(nonnull NSNumber *)reactTag) {
  [self.bridge.uiManager addUIBlock:^(RCTUIManager *uiManager,
                                       NSDictionary<NSNumber *, UIView *> *viewRegistry) {
    UIView *view = viewRegistry[reactTag];
    if ([view isKindOfClass:[VtoBridgeView class]]) {
      [(VtoBridgeView *)view rnShowGlasses];
    }
  }];
}

@end
