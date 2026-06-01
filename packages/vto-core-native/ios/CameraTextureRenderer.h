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

/// Update background transform to compensate for perspective camera
- (void)updateTransformWithFrame:(ARFrame *)frame;

/// HARNESS (dev/simulator only): bind a static test-pattern texture to the
/// camera feed with an identity UV transform, so the renderer can be exercised
/// without a live AR camera. Idempotent.
- (void)useStaticTestPattern;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
