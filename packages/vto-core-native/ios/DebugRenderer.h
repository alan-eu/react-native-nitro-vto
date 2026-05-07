#import <Foundation/Foundation.h>
#import <ARKit/ARKit.h>

@class FaceMeshTopology;

namespace filament {
    class Engine;
    class Scene;
}

NS_ASSUME_NONNULL_BEGIN

/**
 * Debug renderer for visualizing face mesh and the back plane.
 * Renders colored overlays: red for the face mesh, blue for the back plane.
 */
@interface DebugRenderer : NSObject

/// Setup the debug renderer with Filament engine and scene
- (void)setupWithEngine:(filament::Engine *)engine scene:(filament::Scene *)scene;

/// Update debug visualization. The face anchor supplies the world transform;
/// the topology supplies the (filled) vertex + index buffers — never read
/// `face.geometry` directly from this method.
- (void)updateWithFace:(ARFaceAnchor *)face
              topology:(FaceMeshTopology *)topology
         showBackPlane:(BOOL)showBackPlane;

/// Hide debug visualization
- (void)hide;

/// Set debug mode enabled
- (void)setEnabled:(BOOL)enabled;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
