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

/// Preview mode: paint the background quad with a flat color instead of the
/// camera feed. Components are sRGB in [0,1]; the material undoes the view's
/// tonemap so the pixel on screen is exactly this color. Idempotent.
- (void)useSolidBackgroundWithRed:(float)red green:(float)green blue:(float)blue;

/// Bind the camera feed back onto the background quad after
/// -useSolidBackgroundWithRed:green:blue:. Idempotent.
- (void)useCameraFeed;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
