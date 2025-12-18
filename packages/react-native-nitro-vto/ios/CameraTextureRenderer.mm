#import "CameraTextureRenderer.h"
#import "LoaderUtils.h"

#include <filament/Engine.h>
#include <filament/Scene.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/Texture.h>
#include <filament/TextureSampler.h>
#include <filament/VertexBuffer.h>
#include <filament/IndexBuffer.h>
#include <filament/RenderableManager.h>
#include <utils/EntityManager.h>
#include <math/mat4.h>

using namespace filament;
using namespace utils;

static NSString *const TAG = @"CameraTextureRenderer";

@interface CameraTextureRenderer ()

@property (nonatomic, assign) Engine *engine;
@property (nonatomic, assign) Scene *scene;
@property (nonatomic, assign) Texture *cameraTexture;
@property (nonatomic, assign) Material *cameraMaterial;
@property (nonatomic, assign) MaterialInstance *cameraMaterialInstance;
@property (nonatomic, assign) Entity backgroundQuadEntity;
@property (nonatomic, assign) VertexBuffer *backgroundQuadVertexBuffer;
@property (nonatomic, assign) IndexBuffer *backgroundQuadIndexBuffer;

@end

@implementation CameraTextureRenderer

- (void)setupWithEngine:(Engine *)engine scene:(Scene *)scene {
    _engine = engine;
    _scene = scene;

    NSData *materialData = [LoaderUtils loadAssetNamed:@"materials/camera_background_ios.filamat"];
    if (!materialData) {
        NSLog(@"%@: Failed to load camera material", TAG);
        return;
    }

    _cameraMaterial = Material::Builder()
        .package(materialData.bytes, materialData.length)
        .build(*engine);

    if (!_cameraMaterial) {
        NSLog(@"%@: Failed to create camera material", TAG);
        return;
    }

    _cameraMaterialInstance = _cameraMaterial->createInstance();

    _cameraTexture = Texture::Builder()
        .levels(1)
        .sampler(Texture::Sampler::SAMPLER_EXTERNAL)
        .build(*_engine);

    _cameraMaterialInstance->setParameter("cameraFeed", _cameraTexture, TextureSampler());

    [self createBackgroundQuad];

    NSLog(@"%@: Camera texture renderer setup complete", TAG);
}

- (void)updateTextureWithFrame:(ARFrame *)frame {
    if (!_engine || !_cameraTexture || !_cameraMaterialInstance) return;

    CVPixelBufferRef pixelBuffer = frame.capturedImage;
    if (!pixelBuffer) return;

    _cameraTexture->setExternalImage(*_engine, (void *)pixelBuffer);
    [self updateTextureTransformWithFrame:frame];
}

- (void)updateTextureTransformWithFrame:(ARFrame *)frame {
    if (!_cameraMaterialInstance) return;

    CGAffineTransform displayTransform = [frame displayTransformForOrientation:UIInterfaceOrientationPortrait 
                                                                  viewportSize:CGSizeMake(1, 1)];
    CGAffineTransform transformInv = CGAffineTransformInvert(displayTransform);
    
    filament::math::mat3f transform(
        transformInv.a,  transformInv.c,  transformInv.tx,
        transformInv.b,  transformInv.d,  transformInv.ty,
        0.0f,            0.0f,            1.0f
    );
    
    _cameraMaterialInstance->setParameter("textureTransform", transform);
}

- (void)resetUvTransform {
}

- (void)createBackgroundQuad {
    if (!_engine || !_scene || !_cameraMaterialInstance) return;

    float vertices[16] = {
        -1.0f, -1.0f, 0.0f, 1.0f,
         1.0f, -1.0f, 1.0f, 1.0f,
        -1.0f,  1.0f, 0.0f, 0.0f,
         1.0f,  1.0f, 1.0f, 0.0f
    };

    _backgroundQuadVertexBuffer = VertexBuffer::Builder()
        .vertexCount(4)
        .bufferCount(1)
        .attribute(VertexAttribute::POSITION, 0, VertexBuffer::AttributeType::FLOAT2, 0, 16)
        .attribute(VertexAttribute::UV0, 0, VertexBuffer::AttributeType::FLOAT2, 8, 16)
        .build(*_engine);

    _backgroundQuadVertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(vertices, sizeof(vertices), nullptr));

    uint16_t indices[6] = {0, 1, 2, 2, 1, 3};
    _backgroundQuadIndexBuffer = IndexBuffer::Builder()
        .indexCount(6)
        .bufferType(IndexBuffer::IndexType::USHORT)
        .build(*_engine);

    _backgroundQuadIndexBuffer->setBuffer(*_engine,
        IndexBuffer::BufferDescriptor(indices, sizeof(indices), nullptr));

    _backgroundQuadEntity = EntityManager::get().create();

    RenderableManager::Builder(1)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES, _backgroundQuadVertexBuffer, _backgroundQuadIndexBuffer)
        .material(0, _cameraMaterialInstance)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(0)
        .build(*_engine, _backgroundQuadEntity);

    _scene->addEntity(_backgroundQuadEntity);
}

- (void)destroy {
    if (!_engine || !_scene) return;

    _scene->remove(_backgroundQuadEntity);
    EntityManager::get().destroy(_backgroundQuadEntity);

    if (_cameraTexture) {
        _cameraTexture->setExternalImage(*_engine, nullptr);
        _engine->destroy(_cameraTexture);
    }
    if (_backgroundQuadVertexBuffer) {
        _engine->destroy(_backgroundQuadVertexBuffer);
    }
    if (_backgroundQuadIndexBuffer) {
        _engine->destroy(_backgroundQuadIndexBuffer);
    }
    if (_cameraMaterialInstance) {
        _engine->destroy(_cameraMaterialInstance);
    }
    if (_cameraMaterial) {
        _engine->destroy(_cameraMaterial);
    }
}

@end
