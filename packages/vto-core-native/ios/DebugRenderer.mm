#import "DebugRenderer.h"
#import "LoaderUtils.h"

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

static NSString *const TAG = @"DebugRenderer";

// ARKit face mesh typically has ~1220 vertices and ~2304 triangles
static const size_t MAX_VERTICES = 1500;
static const size_t MAX_INDICES = 8000;

@interface DebugRenderer ()

@property (nonatomic, assign) Engine *engine;
@property (nonatomic, assign) Scene *scene;

// Materials
@property (nonatomic, assign) Material *debugFaceMaterial;
@property (nonatomic, assign) Material *debugPlaneMaterial;
@property (nonatomic, assign) MaterialInstance *faceMeshMaterialInstance;
@property (nonatomic, assign) MaterialInstance *backPlaneLeftMaterialInstance;
@property (nonatomic, assign) MaterialInstance *backPlaneRightMaterialInstance;

// Face mesh
@property (nonatomic, assign) Entity faceMeshEntity;
@property (nonatomic, assign) VertexBuffer *faceMeshVertexBuffer;
@property (nonatomic, assign) IndexBuffer *faceMeshIndexBuffer;

// Back planes
@property (nonatomic, assign) Entity backPlaneLeftEntity;
@property (nonatomic, assign) Entity backPlaneRightEntity;
@property (nonatomic, assign) VertexBuffer *backPlaneLeftVertexBuffer;
@property (nonatomic, assign) VertexBuffer *backPlaneRightVertexBuffer;
@property (nonatomic, assign) IndexBuffer *backPlaneIndexBuffer;

// State
@property (nonatomic, assign) BOOL isSetup;
@property (nonatomic, assign) BOOL isEnabled;
@property (nonatomic, assign) BOOL faceMeshVisible;
@property (nonatomic, assign) BOOL backPlaneLeftVisible;
@property (nonatomic, assign) BOOL backPlaneRightVisible;
@property (nonatomic, assign) size_t currentVertexCount;
@property (nonatomic, assign) size_t currentIndexCount;

// Reusable buffers
@property (nonatomic, assign) float3 *vertexData;
@property (nonatomic, assign) int16_t *indexData;
@property (nonatomic, assign) float3 *backPlaneLeftVertices;
@property (nonatomic, assign) float3 *backPlaneRightVertices;

@end

@implementation DebugRenderer

- (instancetype)init {
    self = [super init];
    if (self) {
        _isSetup = NO;
        _isEnabled = NO;
        _faceMeshVisible = NO;
        _backPlaneLeftVisible = NO;
        _backPlaneRightVisible = NO;
        _currentVertexCount = 0;
        _currentIndexCount = 0;
        _vertexData = (float3 *)malloc(MAX_VERTICES * sizeof(float3));
        _indexData = (int16_t *)malloc(MAX_INDICES * sizeof(int16_t));
        _backPlaneLeftVertices = (float3 *)malloc(4 * sizeof(float3));
        _backPlaneRightVertices = (float3 *)malloc(4 * sizeof(float3));
    }
    return self;
}

- (void)dealloc {
    if (_vertexData) {
        free(_vertexData);
        _vertexData = nullptr;
    }
    if (_indexData) {
        free(_indexData);
        _indexData = nullptr;
    }
    if (_backPlaneLeftVertices) {
        free(_backPlaneLeftVertices);
        _backPlaneLeftVertices = nullptr;
    }
    if (_backPlaneRightVertices) {
        free(_backPlaneRightVertices);
        _backPlaneRightVertices = nullptr;
    }
}

- (void)setupWithEngine:(Engine *)engine scene:(Scene *)scene {
    _engine = engine;
    _scene = scene;

    NSLog(@"%@: Setting up debug renderer", TAG);

    // Load debug face material (writes depth, renders first)
    NSData *faceMaterialData = [LoaderUtils loadAssetNamed:@"materials/debug_face_material.filamat"];
    if (!faceMaterialData) {
        NSLog(@"%@: Failed to load debug face material", TAG);
        return;
    }

    _debugFaceMaterial = Material::Builder()
        .package(faceMaterialData.bytes, faceMaterialData.length)
        .build(*engine);

    if (!_debugFaceMaterial) {
        NSLog(@"%@: Failed to create debug face material", TAG);
        return;
    }

    // Load debug plane material (reads depth, renders after)
    NSData *planeMaterialData = [LoaderUtils loadAssetNamed:@"materials/debug_plane_material.filamat"];
    if (!planeMaterialData) {
        NSLog(@"%@: Failed to load debug plane material", TAG);
        return;
    }

    _debugPlaneMaterial = Material::Builder()
        .package(planeMaterialData.bytes, planeMaterialData.length)
        .build(*engine);

    if (!_debugPlaneMaterial) {
        NSLog(@"%@: Failed to create debug plane material", TAG);
        return;
    }

    // Create material instances with different colors (40% opacity)
    // Red for face mesh (uses face material)
    _faceMeshMaterialInstance = _debugFaceMaterial->createInstance();
    _faceMeshMaterialInstance->setParameter("debugColor", float4(1.0f, 0.0f, 0.0f, 0.4f));

    // Green for left back plane (uses plane material)
    _backPlaneLeftMaterialInstance = _debugPlaneMaterial->createInstance();
    _backPlaneLeftMaterialInstance->setParameter("debugColor", float4(0.0f, 1.0f, 0.0f, 0.4f));

    // Blue for right back plane (uses plane material)
    _backPlaneRightMaterialInstance = _debugPlaneMaterial->createInstance();
    _backPlaneRightMaterialInstance->setParameter("debugColor", float4(0.0f, 0.0f, 1.0f, 0.4f));

    // Create face mesh buffers
    _faceMeshVertexBuffer = VertexBuffer::Builder()
        .vertexCount((uint32_t)MAX_VERTICES)
        .bufferCount(1)
        .attribute(VertexAttribute::POSITION, 0,
                   VertexBuffer::AttributeType::FLOAT3, 0, sizeof(float3))
        .build(*engine);

    _faceMeshIndexBuffer = IndexBuffer::Builder()
        .indexCount((uint32_t)MAX_INDICES)
        .bufferType(IndexBuffer::IndexType::USHORT)
        .build(*engine);

    // Create face mesh entity
    _faceMeshEntity = EntityManager::get().create();

    filament::Box boundingBox = {{-0.2f, -0.2f, -0.2f}, {0.2f, 0.2f, 0.2f}};

    // Priority 7 so face mesh renders first (writes depth for plane occlusion)
    RenderableManager::Builder(1)
        .material(0, _faceMeshMaterialInstance)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES, _faceMeshVertexBuffer, _faceMeshIndexBuffer, 0, 0)
        .boundingBox(boundingBox)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(7)
        .build(*engine, _faceMeshEntity);

    // Create back planes
    [self createBackPlanes];

    _isSetup = YES;
    NSLog(@"%@: Debug renderer setup complete", TAG);
}

- (void)createBackPlanes {
    const float planeSizeX = 0.12f;
    const float planeSizeY = 0.08f;
    const float gap = 0.01f;

    // Left back plane vertices
    _backPlaneLeftVertices[0] = float3(-planeSizeX, -planeSizeY, 0.0f);
    _backPlaneLeftVertices[1] = float3(-gap,        -planeSizeY, 0.0f);
    _backPlaneLeftVertices[2] = float3(-planeSizeX,  planeSizeY, 0.0f);
    _backPlaneLeftVertices[3] = float3(-gap,         planeSizeY, 0.0f);

    // Right back plane vertices
    _backPlaneRightVertices[0] = float3(gap,        -planeSizeY, 0.0f);
    _backPlaneRightVertices[1] = float3(planeSizeX, -planeSizeY, 0.0f);
    _backPlaneRightVertices[2] = float3(gap,         planeSizeY, 0.0f);
    _backPlaneRightVertices[3] = float3(planeSizeX,  planeSizeY, 0.0f);

    // Create vertex buffers
    _backPlaneLeftVertexBuffer = VertexBuffer::Builder()
        .vertexCount(4)
        .bufferCount(1)
        .attribute(VertexAttribute::POSITION, 0,
                   VertexBuffer::AttributeType::FLOAT3, 0, sizeof(float3))
        .build(*_engine);

    _backPlaneLeftVertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(_backPlaneLeftVertices, 4 * sizeof(float3), nullptr));

    _backPlaneRightVertexBuffer = VertexBuffer::Builder()
        .vertexCount(4)
        .bufferCount(1)
        .attribute(VertexAttribute::POSITION, 0,
                   VertexBuffer::AttributeType::FLOAT3, 0, sizeof(float3))
        .build(*_engine);

    _backPlaneRightVertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(_backPlaneRightVertices, 4 * sizeof(float3), nullptr));

    // Shared index buffer
    static const uint16_t planeIndices[6] = {0, 1, 2, 2, 1, 3};

    _backPlaneIndexBuffer = IndexBuffer::Builder()
        .indexCount(6)
        .bufferType(IndexBuffer::IndexType::USHORT)
        .build(*_engine);

    _backPlaneIndexBuffer->setBuffer(*_engine,
        IndexBuffer::BufferDescriptor(planeIndices, sizeof(planeIndices), nullptr));

    // Create entities
    _backPlaneLeftEntity = EntityManager::get().create();
    _backPlaneRightEntity = EntityManager::get().create();

    filament::Box boundingBox = {{-planeSizeX, -planeSizeY, -0.1f}, {planeSizeX, planeSizeY, 0.1f}};

    // Build left back plane renderable (priority 8, renders after face mesh, gets occluded)
    RenderableManager::Builder(1)
        .material(0, _backPlaneLeftMaterialInstance)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES,
                  _backPlaneLeftVertexBuffer, _backPlaneIndexBuffer, 0, 6)
        .boundingBox(boundingBox)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(8)
        .build(*_engine, _backPlaneLeftEntity);

    // Build right back plane renderable (priority 8, renders after face mesh, gets occluded)
    RenderableManager::Builder(1)
        .material(0, _backPlaneRightMaterialInstance)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES,
                  _backPlaneRightVertexBuffer, _backPlaneIndexBuffer, 0, 6)
        .boundingBox(boundingBox)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(8)
        .build(*_engine, _backPlaneRightEntity);
}

- (void)setEnabled:(BOOL)enabled {
    if (_isEnabled == enabled) return;

    _isEnabled = enabled;

    if (!enabled) {
        [self hide];
    }

    NSLog(@"%@: Debug mode %@", TAG, enabled ? @"enabled" : @"disabled");
}

- (void)updateWithFace:(ARFaceAnchor *)face
  showLeftBackPlane:(BOOL)showLeftBackPlane
 showRightBackPlane:(BOOL)showRightBackPlane {
    if (!_isSetup || !_engine || !_isEnabled) return;

    ARFaceGeometry *geometry = face.geometry;
    NSUInteger vertexCount = geometry.vertexCount;
    NSUInteger triangleCount = geometry.triangleCount;
    NSUInteger indexCount = triangleCount * 3;

    if (vertexCount > MAX_VERTICES || indexCount > MAX_INDICES) {
        return;
    }

    // Copy vertex positions
    const simd_float3 *vertices = geometry.vertices;
    for (NSUInteger i = 0; i < vertexCount; i++) {
        _vertexData[i] = float3(vertices[i].x, vertices[i].y, vertices[i].z);
    }

    // Update vertex buffer
    _faceMeshVertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(_vertexData, vertexCount * sizeof(float3), nullptr));

    // Update index buffer if changed
    if (indexCount != _currentIndexCount) {
        const int16_t *indices = geometry.triangleIndices;
        memcpy(_indexData, indices, indexCount * sizeof(int16_t));
        _faceMeshIndexBuffer->setBuffer(*_engine,
            IndexBuffer::BufferDescriptor(_indexData, indexCount * sizeof(int16_t), nullptr));
        _currentIndexCount = indexCount;
    }

    // Update geometry count
    if (vertexCount != _currentVertexCount || indexCount != _currentIndexCount) {
        RenderableManager &renderableManager = _engine->getRenderableManager();
        RenderableManager::Instance instance = renderableManager.getInstance(_faceMeshEntity);
        renderableManager.setGeometryAt(instance, 0,
            RenderableManager::PrimitiveType::TRIANGLES,
            _faceMeshVertexBuffer, _faceMeshIndexBuffer,
            0, (uint32_t)indexCount);
        _currentVertexCount = vertexCount;
    }

    // Calculate min Z for back plane positioning
    float minZ = FLT_MAX;
    for (NSUInteger i = 0; i < vertexCount; i++) {
        if (vertices[i].z < minZ) {
            minZ = vertices[i].z;
        }
    }

    // Update face mesh transform
    TransformManager &transformManager = _engine->getTransformManager();
    TransformManager::Instance faceInstance = transformManager.getInstance(_faceMeshEntity);

    mat4f filamentTransform;
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            filamentTransform[col][row] = face.transform.columns[col][row];
        }
    }

    transformManager.setTransform(faceInstance, filamentTransform);

    // Calculate back plane transform
    mat4f backPlaneTransform = filamentTransform;
    float3 localOffset(0.0f, 0.0f, minZ + 0.03f);
    float3 worldOffset(
        filamentTransform[0][0] * localOffset.x + filamentTransform[1][0] * localOffset.y + filamentTransform[2][0] * localOffset.z,
        filamentTransform[0][1] * localOffset.x + filamentTransform[1][1] * localOffset.y + filamentTransform[2][1] * localOffset.z,
        filamentTransform[0][2] * localOffset.x + filamentTransform[1][2] * localOffset.y + filamentTransform[2][2] * localOffset.z
    );
    backPlaneTransform[3][0] += worldOffset.x;
    backPlaneTransform[3][1] += worldOffset.y;
    backPlaneTransform[3][2] += worldOffset.z;

    // Position both back planes
    TransformManager::Instance backPlaneLeftInstance = transformManager.getInstance(_backPlaneLeftEntity);
    TransformManager::Instance backPlaneRightInstance = transformManager.getInstance(_backPlaneRightEntity);
    transformManager.setTransform(backPlaneLeftInstance, backPlaneTransform);
    transformManager.setTransform(backPlaneRightInstance, backPlaneTransform);

    // Add face mesh to scene if not already visible
    if (!_faceMeshVisible) {
        _scene->addEntity(_faceMeshEntity);
        _faceMeshVisible = YES;
    }

    // Update left back plane visibility based on yaw
    if (showLeftBackPlane && !_backPlaneLeftVisible) {
        _scene->addEntity(_backPlaneLeftEntity);
        _backPlaneLeftVisible = YES;
    } else if (!showLeftBackPlane && _backPlaneLeftVisible) {
        _scene->remove(_backPlaneLeftEntity);
        _backPlaneLeftVisible = NO;
    }

    // Update right back plane visibility based on yaw
    if (showRightBackPlane && !_backPlaneRightVisible) {
        _scene->addEntity(_backPlaneRightEntity);
        _backPlaneRightVisible = YES;
    } else if (!showRightBackPlane && _backPlaneRightVisible) {
        _scene->remove(_backPlaneRightEntity);
        _backPlaneRightVisible = NO;
    }
}

- (void)hide {
    if (!_isSetup || !_engine) return;

    if (_faceMeshVisible) {
        _scene->remove(_faceMeshEntity);
        _faceMeshVisible = NO;
    }

    if (_backPlaneLeftVisible) {
        _scene->remove(_backPlaneLeftEntity);
        _backPlaneLeftVisible = NO;
    }

    if (_backPlaneRightVisible) {
        _scene->remove(_backPlaneRightEntity);
        _backPlaneRightVisible = NO;
    }
}

- (void)destroy {
    if (!_engine || !_scene) return;

    [self hide];

    EntityManager::get().destroy(_faceMeshEntity);
    EntityManager::get().destroy(_backPlaneLeftEntity);
    EntityManager::get().destroy(_backPlaneRightEntity);

    if (_faceMeshVertexBuffer) {
        _engine->destroy(_faceMeshVertexBuffer);
    }
    if (_faceMeshIndexBuffer) {
        _engine->destroy(_faceMeshIndexBuffer);
    }
    if (_backPlaneLeftVertexBuffer) {
        _engine->destroy(_backPlaneLeftVertexBuffer);
    }
    if (_backPlaneRightVertexBuffer) {
        _engine->destroy(_backPlaneRightVertexBuffer);
    }
    if (_backPlaneIndexBuffer) {
        _engine->destroy(_backPlaneIndexBuffer);
    }
    if (_faceMeshMaterialInstance) {
        _engine->destroy(_faceMeshMaterialInstance);
    }
    if (_backPlaneLeftMaterialInstance) {
        _engine->destroy(_backPlaneLeftMaterialInstance);
    }
    if (_backPlaneRightMaterialInstance) {
        _engine->destroy(_backPlaneRightMaterialInstance);
    }
    if (_debugFaceMaterial) {
        _engine->destroy(_debugFaceMaterial);
    }
    if (_debugPlaneMaterial) {
        _engine->destroy(_debugPlaneMaterial);
    }

    _isSetup = NO;
}

@end
