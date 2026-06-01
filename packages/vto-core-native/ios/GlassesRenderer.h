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

/// Articulate the temples (swing left/right hinge nodes) so the temple tips
/// land at ±earHalfWidth (face-local meters). No-op if the loaded glb does
/// not expose the expected hinge node names (HingeL_temple / HingeR_temple).
- (void)updateTempleArticulationWithEarHalfWidth:(float)earHalfWidth;

/// Hide glasses by moving off-screen
- (void)hide;

/// HARNESS (dev/simulator only): place the loaded glasses at a fixed pose in
/// front of the camera (no face tracking), so render order can be inspected.
/// No-op until the model has loaded.
- (void)setStaticPreviewTransform;

/// Switch to a different glasses model
- (void)switchModelWithUrl:(NSString *)modelUrl;

/// Set forward offset for glasses positioning (in meters)
- (void)setForwardOffset:(float)offset;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
