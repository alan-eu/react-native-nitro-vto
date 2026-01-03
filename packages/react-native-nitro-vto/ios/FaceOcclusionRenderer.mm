#import "FaceOcclusionRenderer.h"
#import "LoaderUtils.h"
#import "MatrixUtils.h"

#include <filament/Engine.h>
#include <filament/Scene.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/VertexBuffer.h>
#include <filament/IndexBuffer.h>
#include <filament/RenderableManager.h>
#include <filament/TransformManager.h>
#include <filament/Box.h>
#include <utils/EntityManager.h>
#include <math/mat4.h>

using namespace filament;
using namespace filament::math;
using namespace utils;

static NSString *const TAG = @"FaceOcclusionRenderer";

// ARKit face mesh typically has ~1220 vertices and ~2304 triangles
// We allocate a bit more to be safe
static const size_t MAX_VERTICES = 1500;
static const size_t MAX_INDICES = 8000;

@interface FaceOcclusionRenderer ()

@property (nonatomic, assign) Engine *engine;
@property (nonatomic, assign) Scene *scene;

@property (nonatomic, assign) Material *occlusionMaterial;
@property (nonatomic, assign) MaterialInstance *occlusionMaterialInstance;
@property (nonatomic, assign) Entity faceMeshEntity;
@property (nonatomic, assign) VertexBuffer *vertexBuffer;
@property (nonatomic, assign) IndexBuffer *indexBuffer;

@property (nonatomic, assign) BOOL isSetup;
@property (nonatomic, assign) BOOL isVisible;
@property (nonatomic, assign) size_t currentVertexCount;
@property (nonatomic, assign) size_t currentIndexCount;

// Reusable buffer for vertex data
@property (nonatomic, assign) float3 *vertexData;

@end

@implementation FaceOcclusionRenderer

- (instancetype)init {
    self = [super init];
    if (self) {
        _isSetup = NO;
        _isVisible = NO;
        _currentVertexCount = 0;
        _currentIndexCount = 0;
        _vertexData = (float3 *)malloc(MAX_VERTICES * sizeof(float3));
    }
    return self;
}

- (void)dealloc {
    if (_vertexData) {
        free(_vertexData);
        _vertexData = nullptr;
    }
}

- (void)setupWithEngine:(Engine *)engine scene:(Scene *)scene {
    _engine = engine;
    _scene = scene;

    // Load face occlusion material
    NSData *materialData = [LoaderUtils loadAssetNamed:@"materials/face_occlusion.filamat"];
    if (!materialData) {
        NSLog(@"%@: Failed to load face occlusion material", TAG);
        return;
    }

    _occlusionMaterial = Material::Builder()
        .package(materialData.bytes, materialData.length)
        .build(*engine);

    if (!_occlusionMaterial) {
        NSLog(@"%@: Failed to create face occlusion material", TAG);
        return;
    }

    _occlusionMaterialInstance = _occlusionMaterial->getDefaultInstance();

    // Create vertex buffer with capacity for face mesh
    // Using FLOAT3 for positions (ARKit provides float3 vertices)
    _vertexBuffer = VertexBuffer::Builder()
        .vertexCount((uint32_t)MAX_VERTICES)
        .bufferCount(1)
        .attribute(VertexAttribute::POSITION, 0,
                   VertexBuffer::AttributeType::FLOAT3, 0, sizeof(float3))
        .build(*engine);

    // Create index buffer with capacity for face mesh triangles
    _indexBuffer = IndexBuffer::Builder()
        .indexCount((uint32_t)MAX_INDICES)
        .bufferType(IndexBuffer::IndexType::USHORT)
        .build(*engine);

    // Create entity
    _faceMeshEntity = EntityManager::get().create();

    // Initial bounding box (will be updated with actual face mesh bounds)
    filament::Box boundingBox = {{-0.2f, -0.2f, -0.2f}, {0.2f, 0.2f, 0.2f}};

    // Build renderable - priority 0 so it renders FIRST (before camera background)
    // Face mesh writes depth, then camera background overwrites color (with depth test disabled)
    // Then glasses render with depth test and get occluded by face mesh depth
    RenderableManager::Builder(1)
        .material(0, _occlusionMaterialInstance)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES, _vertexBuffer, _indexBuffer, 0, 0)
        .boundingBox(boundingBox)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(0)
        .build(*engine, _faceMeshEntity);

    // Don't add to scene yet - will add when we have valid face data
    _isSetup = YES;
    NSLog(@"%@: Face occlusion renderer setup complete", TAG);
}

- (void)updateWithFace:(ARFaceAnchor *)face {
    if (!_isSetup || !_engine) return;

    ARFaceGeometry *geometry = face.geometry;
    NSUInteger vertexCount = geometry.vertexCount;
    NSUInteger triangleCount = geometry.triangleCount;
    NSUInteger indexCount = triangleCount * 3;

    if (vertexCount > MAX_VERTICES || indexCount > MAX_INDICES) {
        NSLog(@"%@: Face mesh too large: %lu vertices, %lu indices", TAG,
              (unsigned long)vertexCount, (unsigned long)indexCount);
        return;
    }

    // Copy vertex positions (already in face local space)
    const simd_float3 *vertices = geometry.vertices;
    for (NSUInteger i = 0; i < vertexCount; i++) {
        _vertexData[i] = float3(vertices[i].x, vertices[i].y, vertices[i].z);
    }

    // Update vertex buffer
    _vertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(_vertexData, vertexCount * sizeof(float3), nullptr));

    // Update index buffer only if index count changed
    if (indexCount != _currentIndexCount) {
        const int16_t *indices = geometry.triangleIndices;
        _indexBuffer->setBuffer(*_engine,
            IndexBuffer::BufferDescriptor(indices, indexCount * sizeof(int16_t), nullptr));
        _currentIndexCount = indexCount;
    }

    // Update renderable geometry count
    if (vertexCount != _currentVertexCount || indexCount != _currentIndexCount) {
        RenderableManager &renderableManager = _engine->getRenderableManager();
        RenderableManager::Instance instance = renderableManager.getInstance(_faceMeshEntity);
        renderableManager.setGeometryAt(instance, 0,
            RenderableManager::PrimitiveType::TRIANGLES,
            _vertexBuffer, _indexBuffer,
            0, (uint32_t)indexCount);
        _currentVertexCount = vertexCount;
    }

    // Update transform to match face position/rotation in world space
    TransformManager &transformManager = _engine->getTransformManager();
    TransformManager::Instance instance = transformManager.getInstance(_faceMeshEntity);

    // Convert ARKit transform to Filament matrix
    mat4f filamentTransform;
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            filamentTransform[col][row] = face.transform.columns[col][row];
        }
    }

    transformManager.setTransform(instance, filamentTransform);

    // Add to scene if not already visible
    if (!_isVisible) {
        _scene->addEntity(_faceMeshEntity);
        _isVisible = YES;
    }
}

- (void)hide {
    if (!_isSetup || !_engine) return;

    // Remove from scene if visible
    if (_isVisible) {
        _scene->remove(_faceMeshEntity);
        _isVisible = NO;
    }
}

- (void)destroy {
    if (!_engine || !_scene) return;

    if (_isVisible) {
        _scene->remove(_faceMeshEntity);
    }
    EntityManager::get().destroy(_faceMeshEntity);

    if (_vertexBuffer) {
        _engine->destroy(_vertexBuffer);
    }
    if (_indexBuffer) {
        _engine->destroy(_indexBuffer);
    }
    if (_occlusionMaterial) {
        _engine->destroy(_occlusionMaterial);
    }

    _isSetup = NO;
}

@end
