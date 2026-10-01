#ifndef PreviewCameraController_h
#define PreviewCameraController_h

#import <Foundation/Foundation.h>

namespace filament {
    class Camera;
}

NS_ASSUME_NONNULL_BEGIN

/**
 * Orbit camera for preview mode (no AR session, no face).
 *
 * The model stays where it is and the camera moves around it: state is
 * spherical around `target` — azimuth about Y, elevation clamped short of the
 * poles, and a distance clamped to the framing computed from the model's
 * bounds. Mirrored on Android in PreviewCameraController.kt.
 */
@interface PreviewCameraController : NSObject

/// Frame a model: `center`/`radius` are its world-space bounding sphere. Sets
/// the orbit target, the distance that fits it in view, and the zoom limits
/// (which are relative to that distance). Also restores the opening angles.
- (void)frameBoundsWithCenterX:(float)cx centerY:(float)cy centerZ:(float)cz radius:(float)radius;

/// Drag: screen-space finger delta in points. Right/down drags orbit the camera
/// left/up around the model, the way dragging the object itself would.
- (void)orbitByDx:(float)dx dy:(float)dy;

/// Pinch: `scale` > 1 (fingers apart) moves the camera closer.
- (void)zoomByScale:(float)scale;

/// Point the given camera at the target from the current orbit pose and apply
/// the preview projection for `aspect` (width / height).
- (void)applyToCamera:(filament::Camera *)camera aspect:(double)aspect;

@end

NS_ASSUME_NONNULL_END

#endif /* PreviewCameraController_h */
