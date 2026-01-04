#import <Foundation/Foundation.h>
#import <ARKit/ARKit.h>

namespace filament {
    class Engine;
    class Scene;
}

NS_ASSUME_NONNULL_BEGIN

/**
 * Renderer for face occlusion mesh.
 * Renders the ARKit face mesh to the depth buffer only (no color),
 * allowing the face to occlude parts of the glasses.
 */
@interface FaceOcclusionRenderer : NSObject

/// Setup the face occlusion renderer with Filament engine and scene
- (void)setupWithEngine:(filament::Engine *)engine
                  scene:(filament::Scene *)scene;

/// Set occlusion settings (faceMesh and backPlane booleans)
- (void)setOcclusionWithFaceMesh:(BOOL)faceMesh backPlane:(BOOL)backPlane;

/// Update face mesh geometry from ARKit face anchor
- (void)updateWithFace:(ARFaceAnchor *)face;

/// Hide the face mesh (when no face is detected)
- (void)hide;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
