#import "VtoCoreBridge.h"

#include <cstring>
#include <memory>

#include "VtoRendererCore.hpp"

using margelo::nitro::nitrovto::core::FaceData;
using margelo::nitro::nitrovto::core::FrameInput;
using margelo::nitro::nitrovto::core::ModelData;
using margelo::nitro::nitrovto::core::VtoConfig;
using margelo::nitro::nitrovto::core::VtoRendererCore;
using margelo::nitro::nitrovto::core::render::MaterialKind;

@implementation VtoCoreBridge {
    std::unique_ptr<VtoRendererCore> _core;
    int _width;
    int _height;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _core = std::make_unique<VtoRendererCore>();
        _width = 0;
        _height = 0;
    }
    return self;
}

- (void)dealloc {
    [self destroy];
}

- (BOOL)initializeWithMetalLayer:(CAMetalLayer *)metalLayer
               faceMeshOcclusion:(BOOL)faceMeshOcclusion
              backPlaneOcclusion:(BOOL)backPlaneOcclusion
             forwardOffsetMeters:(float)forwardOffsetMeters
                           debug:(BOOL)debug
             noseBridgeLeftIndex:(int)noseBridgeLeftIndex
            noseBridgeRightIndex:(int)noseBridgeRightIndex {
    if (!_core || metalLayer == nil) {
        return NO;
    }
    VtoConfig config;
    config.faceMeshOcclusion = faceMeshOcclusion;
    config.backPlaneOcclusion = backPlaneOcclusion;
    config.forwardOffsetMeters = forwardOffsetMeters;
    config.debug = debug;
    config.noseBridgeLeftIndex = noseBridgeLeftIndex;
    config.noseBridgeRightIndex = noseBridgeRightIndex;
    return _core->initialize((__bridge void *)metalLayer, config);
}

- (void)resizeWithWidth:(int)width height:(int)height {
    _width = width;
    _height = height;
    if (_core) {
        _core->resize(width, height);
    }
}

- (void)updateConfigWithFaceMeshOcclusion:(BOOL)faceMeshOcclusion
                        backPlaneOcclusion:(BOOL)backPlaneOcclusion
                       forwardOffsetMeters:(float)forwardOffsetMeters
                                     debug:(BOOL)debug
                       noseBridgeLeftIndex:(int)noseBridgeLeftIndex
                      noseBridgeRightIndex:(int)noseBridgeRightIndex {
    if (!_core) {
        return;
    }
    VtoConfig config;
    config.faceMeshOcclusion = faceMeshOcclusion;
    config.backPlaneOcclusion = backPlaneOcclusion;
    config.forwardOffsetMeters = forwardOffsetMeters;
    config.debug = debug;
    config.noseBridgeLeftIndex = noseBridgeLeftIndex;
    config.noseBridgeRightIndex = noseBridgeRightIndex;
    _core->updateConfig(config);
}

- (BOOL)setMaterialPackageWithKind:(int)kind bytes:(NSData *)bytes {
    if (!_core || bytes == nil || bytes.length == 0) {
        return NO;
    }

    MaterialKind materialKind;
    switch (kind) {
        case 0:
            materialKind = MaterialKind::CameraBackground;
            break;
        case 1:
            materialKind = MaterialKind::FaceOcclusion;
            break;
        case 2:
            materialKind = MaterialKind::DebugFace;
            break;
        case 3:
            materialKind = MaterialKind::DebugPlane;
            break;
        default:
            return NO;
    }

    return _core->setMaterialPackage(
        materialKind,
        reinterpret_cast<const std::uint8_t *>(bytes.bytes),
        static_cast<std::size_t>(bytes.length));
}

- (BOOL)setEnvironmentIblKtx:(NSData *)bytes {
    if (!_core || bytes == nil || bytes.length == 0) {
        return NO;
    }
    return _core->setEnvironmentIblKtx(
        reinterpret_cast<const std::uint8_t *>(bytes.bytes),
        static_cast<std::size_t>(bytes.length));
}

- (BOOL)setEnvironmentSkyboxKtx:(NSData *)bytes {
    if (!_core || bytes == nil || bytes.length == 0) {
        return NO;
    }
    return _core->setEnvironmentSkyboxKtx(
        reinterpret_cast<const std::uint8_t *>(bytes.bytes),
        static_cast<std::size_t>(bytes.length));
}

- (void)setEnvironmentSphericalHarmonics:(const float *)sh27 {
    if (!_core || sh27 == nullptr) {
        return;
    }
    _core->setEnvironmentSphericalHarmonics(sh27);
}

- (BOOL)setModelFromBytes:(NSData *)bytes sourceId:(NSString *)sourceId {
    if (!_core || bytes == nil || bytes.length == 0 || sourceId == nil) {
        return NO;
    }
    ModelData model;
    model.bytes = reinterpret_cast<const std::uint8_t *>(bytes.bytes);
    model.size = static_cast<std::size_t>(bytes.length);
    model.sourceId = sourceId.UTF8String;
    return _core->setModelFromBytes(model);
}

- (void)resetSession {
    if (_core) {
        _core->resetSession();
    }
}

- (void)submitFrameWithViewportWidth:(int)viewportWidth
                       viewportHeight:(int)viewportHeight
                     hasCameraMatrices:(BOOL)hasCameraMatrices
                            projection:(const float * _Nullable)projection16
                                 model:(const float * _Nullable)model16
                         hasCameraFeed:(BOOL)hasCameraFeed
                            pixelBuffer:(CVPixelBufferRef _Nullable)pixelBuffer
                           uvTransform:(const float * _Nullable)uvTransform3x3
                                hasFace:(BOOL)hasFace
                               vertices:(const float * _Nullable)vertices
                            vertexCount:(int)vertexCount
                                indices:(const uint16_t * _Nullable)indices
                             indexCount:(int)indexCount
                            faceToWorld:(const float * _Nullable)faceToWorld16
                      rotationQuaternion:(const float * _Nullable)rotationQuaternion4
                       hasLightEstimate:(BOOL)hasLightEstimate
                               lightValid:(BOOL)lightValid
                          linearIntensity:(float)linearIntensity {
    if (!_core) {
        return;
    }

    FrameInput input;
    input.viewportWidth = viewportWidth > 0 ? viewportWidth : _width;
    input.viewportHeight = viewportHeight > 0 ? viewportHeight : _height;

    if (hasCameraMatrices && projection16 != nullptr && model16 != nullptr) {
        input.hasCameraMatrices = true;
        std::memcpy(input.projection, projection16, sizeof(float) * 16);
        std::memcpy(input.cameraModel, model16, sizeof(float) * 16);
    }

    if (hasCameraFeed && pixelBuffer != nil) {
        input.cameraFeed.hasValue = true;
        input.cameraFeed.handle = reinterpret_cast<std::uintptr_t>(pixelBuffer);
        if (uvTransform3x3 != nullptr) {
            std::memcpy(input.cameraFeed.uvTransform3x3, uvTransform3x3, sizeof(float) * 9);
        }
    }

    FaceData face;
    if (hasFace && vertices != nullptr && indices != nullptr && faceToWorld16 != nullptr && rotationQuaternion4 != nullptr && vertexCount > 0 && indexCount > 0) {
        face.vertices = vertices;
        face.vertexCount = static_cast<std::size_t>(vertexCount);
        face.indices = indices;
        face.indexCount = static_cast<std::size_t>(indexCount);
        std::memcpy(face.faceToWorld, faceToWorld16, sizeof(float) * 16);
        std::memcpy(face.rotationQuaternion, rotationQuaternion4, sizeof(float) * 4);
        face.hasRotationQuaternion = true;
        input.face = &face;
    }

    if (hasLightEstimate) {
        input.light.hasValue = true;
        input.light.valid = static_cast<bool>(lightValid);
        input.light.linearIntensity = linearIntensity;
    }

    _core->submitFrame(input);
}

- (void)render {
    if (_core) {
        _core->render();
    }
}

- (void)destroy {
    if (_core) {
        _core->destroy();
    }
}

@end
