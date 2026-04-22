#import <Foundation/Foundation.h>
#import <React/RCTViewManager.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * VtoViewManager — RCTViewManager for the old-architecture `VtoView` component.
 * Pairs JS props / commands / events with the Swift `VtoBridgeView` subclass
 * (see VtoBridgeView.swift) which in turn wraps the shared `VtoView` from
 * vto-core-native.
 */
@interface VtoViewManager : RCTViewManager
@end

NS_ASSUME_NONNULL_END
