#import <Foundation/Foundation.h>
#import <ARKit/ARKit.h>

namespace filament {
    class Engine;
    class Scene;
}

NS_ASSUME_NONNULL_BEGIN

/**
 * Handles environment-based lighting for AR rendering.
 *
 * - Static studio IBL (specular cubemap + baked SH) loaded once at
 *   setup; never modulated per frame (see ADR 0011).
 * - Per-frame directional ("sun") light driven by ARKit's
 *   ARDirectionalLightEstimate (when face-tracking provides it):
 *   intensity in lux, direction in face-local-ish space.
 */
@interface EnvironmentLightingRenderer : NSObject

/// Setup environment lighting + create the directional light entity
- (void)setupWithEngine:(filament::Engine *)engine scene:(filament::Scene *)scene;

/// Update the directional light from an ARKit light estimate. No-op if
/// the estimate isn't an ARDirectionalLightEstimate (older iOS or
/// non-face-tracking sessions).
- (void)updateDirectionalFromARKitWithLightEstimate:(ARLightEstimate *)lightEstimate;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
