#import <Foundation/Foundation.h>
#import <ARKit/ARKit.h>

namespace filament {
    class Engine;
    class Scene;
}

NS_ASSUME_NONNULL_BEGIN

/**
 * Debug renderer for visualizing face mesh and back planes.
 * Renders colored overlays: red for face mesh, green for left plane, blue for right plane.
 */
@interface DebugRenderer : NSObject

/// Setup the debug renderer with Filament engine and scene
- (void)setupWithEngine:(filament::Engine *)engine scene:(filament::Scene *)scene;

/// Update debug visualization with face data and back plane visibility from occlusion renderer
- (void)updateWithFace:(ARFaceAnchor *)face
  showLeftBackPlane:(BOOL)showLeftBackPlane
 showRightBackPlane:(BOOL)showRightBackPlane;

/// Hide debug visualization
- (void)hide;

/// Set debug mode enabled
- (void)setEnabled:(BOOL)enabled;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
