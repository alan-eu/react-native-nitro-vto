#import <Foundation/Foundation.h>
#import <ARKit/ARKit.h>

namespace filament {
    class Engine;
    class Scene;
}

NS_ASSUME_NONNULL_BEGIN

/**
 * Renderer for glasses model with face tracking transform.
 * Handles GLTF loading and world-space positioning based on ARKit face mesh.
 */
@interface GlassesRenderer : NSObject

/// Callback for when model loading completes
@property (nonatomic, copy, nullable) void (^onModelLoaded)(NSString *url);

/// Callback fired the first time the newly loaded model's transform is driven
/// by a tracked face (i.e. the first frame the glasses actually become visible).
/// Re-armed on every successful load (initial load + switchModelWithUrl:).
@property (nonatomic, copy, nullable) void (^onGlassesDisplayed)(NSString *url);

/// Setup the glasses renderer with Filament engine and scene
- (void)setupWithEngine:(filament::Engine *)engine
                  scene:(filament::Scene *)scene
               modelUrl:(NSString *)modelUrl;

/// Update glasses transform based on detected face
- (void)updateTransformWithFace:(ARFaceAnchor *)face frame:(ARFrame *)frame;

/// Hide glasses by moving off-screen
- (void)hide;

/// Switch to a different glasses model
- (void)switchModelWithUrl:(NSString *)modelUrl;

/// Set forward offset for glasses positioning (in meters)
- (void)setForwardOffset:(float)offset;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
