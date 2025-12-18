#import <Foundation/Foundation.h>
#import <ARKit/ARKit.h>

namespace filament {
    class Engine;
    class Scene;
}

NS_ASSUME_NONNULL_BEGIN

/**
 * Handles camera texture rendering for AR background.
 * Converts ARKit camera frames to Filament textures and renders fullscreen quad.
 */
@interface CameraTextureRenderer : NSObject

/// Setup the camera background rendering
- (void)setupWithEngine:(filament::Engine *)engine scene:(filament::Scene *)scene;

/// Set viewport size for correct aspect ratio transform
- (void)setViewportSize:(CGSize)size;

/// Update camera texture from ARKit frame
- (void)updateTextureWithFrame:(ARFrame *)frame;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
