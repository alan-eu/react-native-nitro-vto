#import <Foundation/Foundation.h>
#import <ARKit/ARKit.h>

namespace filament {
    class Engine;
    class Scene;
}

NS_ASSUME_NONNULL_BEGIN

/**
 * Handles environment-based lighting (IBL) for AR rendering.
 * Loads skybox and indirect light from KTX files and updates
 * intensity based on ARKit light estimation.
 */
@interface EnvironmentLightingRenderer : NSObject

/// Setup environment lighting with IBL from KTX files
- (void)setupWithEngine:(filament::Engine *)engine scene:(filament::Scene *)scene;

/// Update lighting intensity based on ARKit light estimation
- (void)updateFromARKitWithLightEstimate:(ARLightEstimate *)lightEstimate;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
