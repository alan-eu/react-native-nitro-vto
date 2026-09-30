#import "PreviewCameraController.h"
#import "PreviewConstants.h"

#include <filament/Camera.h>
#include <math/mat4.h>
#include <math/vec3.h>

#include <algorithm>
#include <cmath>

using namespace filament;
using namespace filament::math;

@interface PreviewCameraController ()

@property (nonatomic, assign) float3 target;
@property (nonatomic, assign) float azimuth;
@property (nonatomic, assign) float elevation;

// Bounding radius of the framed model, and the zoom as a multiple of the
// distance that frames it. The distance itself is derived per frame, because
// what fits depends on the viewport aspect.
@property (nonatomic, assign) float radius;
@property (nonatomic, assign) float zoom;

@end

@implementation PreviewCameraController

- (instancetype)init {
    self = [super init];
    if (self) {
        _target = float3{0.0f, 0.0f, 0.0f};
        _azimuth = kPreviewDefaultAzimuth;
        _elevation = kPreviewDefaultElevation;
        // Until a model is framed, a glasses-sized default keeps the first
        // frames sane rather than putting the camera inside the model.
        _radius = 0.1f;
        _zoom = 1.0f;
    }
    return self;
}

- (void)frameBoundsWithCenterX:(float)cx centerY:(float)cy centerZ:(float)cz radius:(float)radius {
    _target = float3{cx, cy, cz};
    _radius = std::max(radius, 0.001f);
    _zoom = 1.0f;

    _azimuth = kPreviewDefaultAzimuth;
    _elevation = kPreviewDefaultElevation;
}

// Distance at which the bounding sphere fits the *narrower* of the two fields
// of view — on a portrait viewport that's the horizontal one, and fitting the
// vertical would run the model off the sides.
- (float)framedDistanceForAspect:(double)aspect {
    float halfFovV = (float)(kPreviewFovDeg * M_PI / 180.0) * 0.5f;
    float tanV = std::tan(halfFovV);
    float tanH = tanV * (float)(aspect > 0.0 ? aspect : 1.0);
    return (_radius / std::min(tanV, tanH)) * kPreviewFramingMargin;
}

- (void)orbitByDx:(float)dx dy:(float)dy {
    _azimuth -= dx * kPreviewOrbitRadiansPerPoint;
    _elevation += dy * kPreviewOrbitRadiansPerPoint;
    _elevation = std::clamp(_elevation, -kPreviewMaxElevation, kPreviewMaxElevation);
}

- (void)zoomByScale:(float)scale {
    if (scale <= 0.0f) return;
    _zoom = std::clamp(_zoom / scale, kPreviewMinZoomFactor, kPreviewMaxZoomFactor);
}

- (void)applyToCamera:(Camera *)camera aspect:(double)aspect {
    if (!camera) return;

    float distance = [self framedDistanceForAspect:aspect] * _zoom;

    float cosElevation = std::cos(_elevation);
    float3 offset{
        distance * cosElevation * std::sin(_azimuth),
        distance * std::sin(_elevation),
        distance * cosElevation * std::cos(_azimuth),
    };
    float3 eye = _target + offset;

    // Near/far track the orbit distance: a fixed near plane would z-fight on a
    // close zoom, and a fixed far plane would clip when zoomed out.
    double near = std::max(0.001, (double)distance * 0.01);
    double far = (double)distance * 10.0;

    camera->setProjection(kPreviewFovDeg, aspect > 0.0 ? aspect : 1.0, near, far, Camera::Fov::VERTICAL);
    camera->lookAt(eye, _target, float3{0.0f, 1.0f, 0.0f});
}

@end
