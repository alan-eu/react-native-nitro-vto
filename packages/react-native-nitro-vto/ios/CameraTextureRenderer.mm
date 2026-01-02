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
#include <filament/TransformManager.h>
#include <filament/Box.h>
#include <utils/EntityManager.h>
#include <math/mat3.h>
#include <math/mat4.h>
#include <math/half.h>

using namespace filament;
using namespace filament::math;
using namespace utils;

static NSString *const TAG = @"CameraTextureRenderer";

// Vertex structure matching hello-ar: HALF4 position + HALF2 uv
struct Vertex {
    half4 position;
    half2 uv;
};

// Full-screen quad in NDC with flipped V coordinates for portrait orientation
static const Vertex kVertices[4] = {
    { { -1.0_h, -1.0_h, 1.0_h, 1.0_h }, { 0.0_h, 1.0_h } },  // bottom-left
    { {  1.0_h, -1.0_h, 1.0_h, 1.0_h }, { 1.0_h, 1.0_h } },  // bottom-right
    { { -1.0_h,  1.0_h, 1.0_h, 1.0_h }, { 0.0_h, 0.0_h } },  // top-left
    { {  1.0_h,  1.0_h, 1.0_h, 1.0_h }, { 1.0_h, 0.0_h } },  // top-right
};

static constexpr uint16_t kIndices[6] = { 0, 1, 2, 2, 1, 3 };

@interface CameraTextureRenderer ()

@property (nonatomic, assign) Engine *engine;
@property (nonatomic, assign) Scene *scene;

// Camera texture (external sampler)
@property (nonatomic, assign) Texture *cameraFeedTexture;

// Background triangle
@property (nonatomic, assign) Material *cameraMaterial;
@property (nonatomic, assign) MaterialInstance *cameraMaterialInstance;
@property (nonatomic, assign) Entity cameraFeedTriangle;
@property (nonatomic, assign) VertexBuffer *vertexBuffer;
@property (nonatomic, assign) IndexBuffer *indexBuffer;

// Viewport size for transform calculation
@property (nonatomic, assign) CGSize viewportSize;

@end

@implementation CameraTextureRenderer

- (void)setupWithEngine:(Engine *)engine scene:(Scene *)scene {
    _engine = engine;
    _scene = scene;
    _viewportSize = CGSizeMake(1, 1);  // Default, will be updated

    // Load camera background material
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

    // Create external texture for camera feed
    // SAMPLER_EXTERNAL allows Filament to handle CVPixelBuffer directly
    _cameraFeedTexture = Texture::Builder()
        .levels(1)
        .sampler(Texture::Sampler::SAMPLER_EXTERNAL)
        .build(*engine);

    // Create full-screen triangle renderable
    [self createCameraFeedTriangle];

    NSLog(@"%@: Camera texture renderer setup complete", TAG);
}

- (void)updateTextureWithFrame:(ARFrame *)frame {
    if (!_engine || !_cameraFeedTexture || !_cameraMaterialInstance) return;

    CVPixelBufferRef pixelBuffer = frame.capturedImage;

    // No need to retain - Filament takes ownership and releases when appropriate
    // See: https://github.com/google/filament/blob/main/ios/samples/hello-ar/hello-ar/FilamentArView/FullScreenTriangle.cpp
    _cameraFeedTexture->setExternalImage(*_engine, pixelBuffer);

    // Update texture transform for proper orientation
    [self updateTextureTransformWithFrame:frame];
}

- (void)setViewportSize:(CGSize)size {
    _viewportSize = size;
}

- (void)updateTextureTransformWithFrame:(ARFrame *)frame {
    if (!_cameraMaterialInstance) return;

    // Get display transform for portrait orientation with actual viewport size
    CGAffineTransform displayTransform = [frame displayTransformForOrientation:UIInterfaceOrientationPortrait
                                                                  viewportSize:_viewportSize];

    // We want the inverse because we're applying the transform to the UV coordinates,
    // not the image itself. (See camera_feed.mat and hello-ar example)
    CGAffineTransform transformInv = CGAffineTransformInvert(displayTransform);

    // Debug: log transform values (only once)
    static bool logged = false;
    if (!logged) {
        NSLog(@"%@: Viewport size: %f x %f", TAG, _viewportSize.width, _viewportSize.height);
        NSLog(@"%@: ARKit displayTransform: a=%f b=%f c=%f d=%f tx=%f ty=%f",
              TAG, displayTransform.a, displayTransform.b, displayTransform.c,
              displayTransform.d, displayTransform.tx, displayTransform.ty);
        NSLog(@"%@: Inverted transform: a=%f b=%f c=%f d=%f tx=%f ty=%f",
              TAG, transformInv.a, transformInv.b, transformInv.c,
              transformInv.d, transformInv.tx, transformInv.ty);
        logged = true;
    }

    // Convert CGAffineTransform to mat3f for the shader
    // mat3f constructor is column-major: (col0, col1, col2)
    // This matches the layout from hello-ar FilamentArViewController.mm
    mat3f textureTransform(transformInv.a,  transformInv.b,  0.0f,
                           transformInv.c,  transformInv.d,  0.0f,
                           transformInv.tx, transformInv.ty, 1.0f);

    _cameraMaterialInstance->setParameter("textureTransform", textureTransform);
}

- (void)createCameraFeedTriangle {
    if (!_engine || !_scene || !_cameraMaterial) return;

    _cameraMaterialInstance = _cameraMaterial->getDefaultInstance();

    // Create vertex buffer using full-screen quad with HALF4/HALF2 attributes
    _vertexBuffer = VertexBuffer::Builder()
        .vertexCount(4)
        .bufferCount(1)
        .attribute(VertexAttribute::POSITION, 0,
                   VertexBuffer::AttributeType::HALF4, offsetof(Vertex, position), sizeof(Vertex))
        .attribute(VertexAttribute::UV0, 0,
                   VertexBuffer::AttributeType::HALF2, offsetof(Vertex, uv), sizeof(Vertex))
        .build(*_engine);

    _vertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(kVertices, sizeof(kVertices), nullptr));

    // Create index buffer
    _indexBuffer = IndexBuffer::Builder()
        .indexCount(6)
        .bufferType(IndexBuffer::IndexType::USHORT)
        .build(*_engine);

    _indexBuffer->setBuffer(*_engine,
        IndexBuffer::BufferDescriptor(kIndices, sizeof(kIndices), nullptr));

    // Create entity and renderable
    _cameraFeedTriangle = EntityManager::get().create();

    // Bounding box for the fullscreen quad (prevents frustum culling)
    filament::Box boundingBox = {{-1, -1, 0}, {1, 1, 0}};

    RenderableManager::Builder(1)
        .material(0, _cameraMaterialInstance)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES, _vertexBuffer, _indexBuffer)
        .boundingBox(boundingBox)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(7)  // Render first (at the back)
        .build(*_engine, _cameraFeedTriangle);

    // Set texture parameter
    _cameraMaterialInstance->setParameter("cameraFeed", _cameraFeedTexture, TextureSampler());

    _scene->addEntity(_cameraFeedTriangle);
}

- (void)updateTransformWithFrame:(ARFrame *)frame {
    if (!_engine || !frame) return;

    // Get ARKit camera matrices
    simd_float4x4 viewMatrix = [frame.camera viewMatrixForOrientation:UIInterfaceOrientationPortrait];
    simd_float4x4 projMatrix = [frame.camera projectionMatrixForOrientation:UIInterfaceOrientationPortrait
                                                               viewportSize:_viewportSize
                                                                      zNear:0.01
                                                                       zFar:100.0];

    // Compute inverse(viewProj) to transform NDC coordinates back to world space
    // This way, after MVP is applied, the quad vertices end up at their original NDC positions
    simd_float4x4 viewProj = simd_mul(projMatrix, viewMatrix);
    simd_float4x4 invViewProj = simd_inverse(viewProj);

    // Convert to Filament matrix
    mat4f backgroundTransform;
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            backgroundTransform[col][row] = invViewProj.columns[col][row];
        }
    }

    // Apply transform to background quad
    TransformManager &transformManager = _engine->getTransformManager();
    TransformManager::Instance instance = transformManager.getInstance(_cameraFeedTriangle);
    transformManager.setTransform(instance, backgroundTransform);
}

- (void)destroy {
    if (!_engine || !_scene) return;

    _scene->remove(_cameraFeedTriangle);
    EntityManager::get().destroy(_cameraFeedTriangle);

    if (_cameraFeedTexture) {
        _engine->destroy(_cameraFeedTexture);
    }
    if (_vertexBuffer) {
        _engine->destroy(_vertexBuffer);
    }
    if (_indexBuffer) {
        _engine->destroy(_indexBuffer);
    }
    if (_cameraMaterial) {
        _engine->destroy(_cameraMaterial);
    }
}

@end
