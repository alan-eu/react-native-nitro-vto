#import "DebugRenderer.h"
#import "FaceMeshTopology.h"
#import "LoaderUtils.h"
#import "OcclusionConstants.h"

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

// Match FaceMeshTopology's cap — see FaceOcclusionRenderer.mm comment.
static const size_t MAX_VERTICES = 1500;
static const size_t MAX_INDICES = 8000;

@interface DebugRenderer ()

@property (nonatomic, assign) Engine *engine;
@property (nonatomic, assign) Scene *scene;

// Materials
@property (nonatomic, assign) Material *debugFaceMaterial;
@property (nonatomic, assign) Material *debugPlaneMaterial;
@property (nonatomic, assign) MaterialInstance *faceMeshMaterialInstance;
@property (nonatomic, assign) MaterialInstance *backPlaneMaterialInstance;

// Face mesh
@property (nonatomic, assign) Entity faceMeshEntity;
@property (nonatomic, assign) VertexBuffer *faceMeshVertexBuffer;
@property (nonatomic, assign) IndexBuffer *faceMeshIndexBuffer;

// Back plane (single, spans full ear-line width)
@property (nonatomic, assign) Entity backPlaneEntity;
@property (nonatomic, assign) VertexBuffer *backPlaneVertexBuffer;
@property (nonatomic, assign) IndexBuffer *backPlaneIndexBuffer;

// State
@property (nonatomic, assign) BOOL isSetup;
@property (nonatomic, assign) BOOL isEnabled;
@property (nonatomic, assign) BOOL faceMeshVisible;
@property (nonatomic, assign) BOOL backPlaneVisible;
@property (nonatomic, assign) size_t currentVertexCount;
@property (nonatomic, assign) size_t currentIndexCount;

// Reusable buffers
@property (nonatomic, assign) float3 *vertexData;
@property (nonatomic, assign) int16_t *indexData;
@property (nonatomic, assign) float3 *backPlaneVertices;

@end

@implementation DebugRenderer

- (instancetype)init {
    self = [super init];
    if (self) {
        _isSetup = NO;
        _isEnabled = NO;
        _faceMeshVisible = NO;
        _backPlaneVisible = NO;
        _currentVertexCount = 0;
        _currentIndexCount = 0;
        _vertexData = (float3 *)malloc(MAX_VERTICES * sizeof(float3));
        _indexData = (int16_t *)malloc(MAX_INDICES * sizeof(int16_t));
        _backPlaneVertices = (float3 *)malloc(4 * sizeof(float3));
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
    if (_backPlaneVertices) {
        free(_backPlaneVertices);
        _backPlaneVertices = nullptr;
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

    // Blue for the (single) back plane (uses plane material)
    _backPlaneMaterialInstance = _debugPlaneMaterial->createInstance();
    _backPlaneMaterialInstance->setParameter("debugColor", float4(0.0f, 0.0f, 1.0f, 0.4f));

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

    // Create back plane
    [self createBackPlane];

    _isSetup = YES;
    NSLog(@"%@: Debug renderer setup complete", TAG);
}

- (void)createBackPlane {
    const float planeSizeX = 0.12f;
    const float planeSizeY = 0.08f;

    _backPlaneVertices[0] = float3(-planeSizeX, -planeSizeY, 0.0f);
    _backPlaneVertices[1] = float3( planeSizeX, -planeSizeY, 0.0f);
    _backPlaneVertices[2] = float3(-planeSizeX,  planeSizeY, 0.0f);
    _backPlaneVertices[3] = float3( planeSizeX,  planeSizeY, 0.0f);

    _backPlaneVertexBuffer = VertexBuffer::Builder()
        .vertexCount(4)
        .bufferCount(1)
        .attribute(VertexAttribute::POSITION, 0,
                   VertexBuffer::AttributeType::FLOAT3, 0, sizeof(float3))
        .build(*_engine);

    _backPlaneVertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(_backPlaneVertices, 4 * sizeof(float3), nullptr));

    static const uint16_t planeIndices[6] = {0, 1, 2, 2, 1, 3};

    _backPlaneIndexBuffer = IndexBuffer::Builder()
        .indexCount(6)
        .bufferType(IndexBuffer::IndexType::USHORT)
        .build(*_engine);

    _backPlaneIndexBuffer->setBuffer(*_engine,
        IndexBuffer::BufferDescriptor(planeIndices, sizeof(planeIndices), nullptr));

    _backPlaneEntity = EntityManager::get().create();

    filament::Box boundingBox = {{-planeSizeX, -planeSizeY, -0.1f}, {planeSizeX, planeSizeY, 0.1f}};

    // Priority 8 — renders after the face mesh, gets occluded by it.
    RenderableManager::Builder(1)
        .material(0, _backPlaneMaterialInstance)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES,
                  _backPlaneVertexBuffer, _backPlaneIndexBuffer, 0, 6)
        .boundingBox(boundingBox)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(8)
        .build(*_engine, _backPlaneEntity);
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
              topology:(FaceMeshTopology *)topology
         showBackPlane:(BOOL)showBackPlane {
    if (!_isSetup || !_engine || !_isEnabled || !topology) return;

    NSUInteger vertexCount = topology.vertexCount;
    NSUInteger indexCount  = topology.indexCount;
    const simd_float3 *vertices = topology.vertices;
    const int16_t *indices      = topology.indices;

    if (vertexCount == 0 || indexCount == 0 || !vertices || !indices) return;
    if (vertexCount > MAX_VERTICES || indexCount > MAX_INDICES) return;

    // Copy vertex positions and track XY extents in a single pass — they drive
    // the per-frame back-plane size so the debug overlay matches the real
    // occluder in FaceOcclusionRenderer.
    float meshMinX = FLT_MAX, meshMaxX = -FLT_MAX;
    float meshMinY = FLT_MAX, meshMaxY = -FLT_MAX;
    for (NSUInteger i = 0; i < vertexCount; i++) {
        _vertexData[i] = float3(vertices[i].x, vertices[i].y, vertices[i].z);
        if (vertices[i].x < meshMinX) meshMinX = vertices[i].x;
        if (vertices[i].x > meshMaxX) meshMaxX = vertices[i].x;
        if (vertices[i].y < meshMinY) meshMinY = vertices[i].y;
        if (vertices[i].y > meshMaxY) meshMaxY = vertices[i].y;
    }

    // Update vertex buffer
    _faceMeshVertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(_vertexData, vertexCount * sizeof(float3), nullptr));

    // Update index buffer if changed
    if (indexCount != _currentIndexCount) {
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

    // Resize back plane from face mesh extents. Tuning lives in
    // OcclusionConstants.h — shared with FaceOcclusionRenderer.
    float meshHalfW = fmaxf(fabsf(meshMinX), fabsf(meshMaxX));
    float meshHalfH = fmaxf(fabsf(meshMinY), fabsf(meshMaxY));
    float halfW = fmaxf(meshHalfW * kEarMargin, kMinHalfWidth);
    float halfH = meshHalfH * kHeightMargin;

    _backPlaneVertices[0] = float3(-halfW, -halfH, 0.0f);
    _backPlaneVertices[1] = float3( halfW, -halfH, 0.0f);
    _backPlaneVertices[2] = float3(-halfW,  halfH, 0.0f);
    _backPlaneVertices[3] = float3( halfW,  halfH, 0.0f);

    _backPlaneVertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(_backPlaneVertices, 4 * sizeof(float3), nullptr));

    // Update face mesh transform
    TransformManager &transformManager = _engine->getTransformManager();
    TransformManager::Instance faceInstance = transformManager.getInstance(_faceMeshEntity);

    mat4f filamentTransform;
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            filamentTransform[col][row] = face.transform.columns[col][row];
        }
    }

    // Match FaceOcclusionRenderer's face-mesh X-shrink so the debug overlay
    // sits where the actual occluder sits.
    mat4f faceMeshShrink;
    faceMeshShrink[0][0] = kFaceMeshXShrink;
    transformManager.setTransform(faceInstance, filamentTransform * faceMeshShrink);

    // Calculate back plane transform — must match FaceOcclusionRenderer.
    mat4f backPlaneTransform = filamentTransform;
    float3 localOffset(0.0f, 0.0f, minZ - kBackPlaneZOffset);
    float3 worldOffset(
        filamentTransform[0][0] * localOffset.x + filamentTransform[1][0] * localOffset.y + filamentTransform[2][0] * localOffset.z,
        filamentTransform[0][1] * localOffset.x + filamentTransform[1][1] * localOffset.y + filamentTransform[2][1] * localOffset.z,
        filamentTransform[0][2] * localOffset.x + filamentTransform[1][2] * localOffset.y + filamentTransform[2][2] * localOffset.z
    );
    backPlaneTransform[3][0] += worldOffset.x;
    backPlaneTransform[3][1] += worldOffset.y;
    backPlaneTransform[3][2] += worldOffset.z;

    // Position the back plane.
    TransformManager::Instance backPlaneInstance = transformManager.getInstance(_backPlaneEntity);
    transformManager.setTransform(backPlaneInstance, backPlaneTransform);

    // Add face mesh to scene if not already visible
    if (!_faceMeshVisible) {
        _scene->addEntity(_faceMeshEntity);
        _faceMeshVisible = YES;
    }

    // Update back plane visibility
    if (showBackPlane && !_backPlaneVisible) {
        _scene->addEntity(_backPlaneEntity);
        _backPlaneVisible = YES;
    } else if (!showBackPlane && _backPlaneVisible) {
        _scene->remove(_backPlaneEntity);
        _backPlaneVisible = NO;
    }
}

- (void)hide {
    if (!_isSetup || !_engine) return;

    if (_faceMeshVisible) {
        _scene->remove(_faceMeshEntity);
        _faceMeshVisible = NO;
    }

    if (_backPlaneVisible) {
        _scene->remove(_backPlaneEntity);
        _backPlaneVisible = NO;
    }
}

- (void)destroy {
    if (!_engine || !_scene) return;

    [self hide];

    EntityManager::get().destroy(_faceMeshEntity);
    EntityManager::get().destroy(_backPlaneEntity);

    if (_faceMeshVertexBuffer) {
        _engine->destroy(_faceMeshVertexBuffer);
    }
    if (_faceMeshIndexBuffer) {
        _engine->destroy(_faceMeshIndexBuffer);
    }
    if (_backPlaneVertexBuffer) {
        _engine->destroy(_backPlaneVertexBuffer);
    }
    if (_backPlaneIndexBuffer) {
        _engine->destroy(_backPlaneIndexBuffer);
    }
    if (_faceMeshMaterialInstance) {
        _engine->destroy(_faceMeshMaterialInstance);
    }
    if (_backPlaneMaterialInstance) {
        _engine->destroy(_backPlaneMaterialInstance);
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
