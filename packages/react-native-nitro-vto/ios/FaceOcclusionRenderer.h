#import <Foundation/Foundation.h>
#import <ARKit/ARKit.h>

@class FaceMeshTopology;

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

/// Whether the back plane is currently visible.
@property (nonatomic, readonly) BOOL isBackPlaneVisible;

/// Half-width of the user's ears in face-local meters, derived from the face
/// mesh's lateral extent and the same earMargin constant used to size the
/// back planes. Updated each call to -updateWithFace:. 0 if no face has been
/// processed yet.
@property (nonatomic, readonly) float earHalfWidth;

/// Setup the face occlusion renderer with Filament engine and scene
- (void)setupWithEngine:(filament::Engine *)engine
                  scene:(filament::Scene *)scene;

/// Update face mesh geometry. The face anchor supplies the world transform;
/// the topology supplies the (filled) vertex + index buffers — never read
/// `face.geometry` directly from this method.
- (void)updateWithFace:(ARFaceAnchor *)face
              topology:(FaceMeshTopology *)topology;

/// Hide the face mesh (when no face is detected)
- (void)hide;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
