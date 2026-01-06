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

/// Whether the left back plane is currently visible (based on head yaw)
@property (nonatomic, readonly) BOOL isLeftBackPlaneVisible;

/// Whether the right back plane is currently visible (based on head yaw)
@property (nonatomic, readonly) BOOL isRightBackPlaneVisible;

/// Setup the face occlusion renderer with Filament engine and scene
- (void)setupWithEngine:(filament::Engine *)engine
                  scene:(filament::Scene *)scene;

/// Set face mesh occlusion enabled
- (void)setFaceMeshOcclusion:(BOOL)enabled;

/// Set back plane occlusion enabled
- (void)setBackPlaneOcclusion:(BOOL)enabled;

/// Update face mesh geometry from ARKit face anchor
- (void)updateWithFace:(ARFaceAnchor *)face;

/// Hide the face mesh (when no face is detected)
- (void)hide;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
