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

// Back clipping planes (split left/right for better occlusion based on head rotation)
@property (nonatomic, assign) Entity backPlaneLeftEntity;
@property (nonatomic, assign) Entity backPlaneRightEntity;
@property (nonatomic, assign) VertexBuffer *backPlaneLeftVertexBuffer;
@property (nonatomic, assign) VertexBuffer *backPlaneRightVertexBuffer;
@property (nonatomic, assign) IndexBuffer *backPlaneIndexBuffer;  // Shared between both planes
@property (nonatomic, assign) BOOL backPlaneLeftVisible;
@property (nonatomic, assign) BOOL backPlaneRightVisible;

@property (nonatomic, assign) BOOL isSetup;
@property (nonatomic, assign) BOOL isVisible;
@property (nonatomic, assign) size_t currentVertexCount;
@property (nonatomic, assign) size_t currentIndexCount;

// Occlusion settings (both enabled by default)
@property (nonatomic, assign) BOOL faceMeshEnabled;
@property (nonatomic, assign) BOOL backPlaneEnabled;

// Reusable buffer for vertex data
@property (nonatomic, assign) float3 *vertexData;

// Reusable buffer for index data (to avoid dangling pointer to ARKit data)
@property (nonatomic, assign) int16_t *indexData;

// Persistent back plane vertex data (to avoid dangling pointer)
@property (nonatomic, assign) float3 *backPlaneLeftVertices;
@property (nonatomic, assign) float3 *backPlaneRightVertices;

@end

@implementation FaceOcclusionRenderer

- (instancetype)init {
    self = [super init];
    if (self) {
        _isSetup = NO;
        _isVisible = NO;
        _backPlaneLeftVisible = NO;
        _backPlaneRightVisible = NO;
        _currentVertexCount = 0;
        _currentIndexCount = 0;
        _faceMeshEnabled = YES;
        _backPlaneEnabled = YES;
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

    // Create back clipping plane (a simple quad)
    [self createBackPlane];

    _isSetup = YES;
    NSLog(@"%@: Face occlusion renderer setup complete", TAG);
}

- (void)createBackPlane {
    // Create two quads (left and right) that clip glasses behind the face
    // Split vertically so we can show/hide based on head rotation
    const float planeSizeX = 0.12f;  // 12cm half-width for each plane
    const float planeSizeY = 0.08f;  // 8cm half-height (16cm total)
    const float gap = 0.01f;  // Small gap between planes at center

    // Left back plane (user's left side, camera's right side)
    // X range: -planeSizeX to -gap
    _backPlaneLeftVertices[0] = float3(-planeSizeX, -planeSizeY, 0.0f);  // bottom-left
    _backPlaneLeftVertices[1] = float3(-gap,        -planeSizeY, 0.0f);  // bottom-right
    _backPlaneLeftVertices[2] = float3(-planeSizeX,  planeSizeY, 0.0f);  // top-left
    _backPlaneLeftVertices[3] = float3(-gap,         planeSizeY, 0.0f);  // top-right

    // Right back plane (user's right side, camera's left side)
    // X range: gap to planeSizeX
    _backPlaneRightVertices[0] = float3(gap,        -planeSizeY, 0.0f);  // bottom-left
    _backPlaneRightVertices[1] = float3(planeSizeX, -planeSizeY, 0.0f);  // bottom-right
    _backPlaneRightVertices[2] = float3(gap,         planeSizeY, 0.0f);  // top-left
    _backPlaneRightVertices[3] = float3(planeSizeX,  planeSizeY, 0.0f);  // top-right

    // Create vertex buffers for each plane
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

    // Shared index buffer (same topology for both planes)
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

    // Build left back plane renderable
    RenderableManager::Builder(1)
        .material(0, _occlusionMaterialInstance)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES,
                  _backPlaneLeftVertexBuffer, _backPlaneIndexBuffer, 0, 6)
        .boundingBox(boundingBox)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(0)
        .build(*_engine, _backPlaneLeftEntity);

    // Build right back plane renderable
    RenderableManager::Builder(1)
        .material(0, _occlusionMaterialInstance)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES,
                  _backPlaneRightVertexBuffer, _backPlaneIndexBuffer, 0, 6)
        .boundingBox(boundingBox)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(0)
        .build(*_engine, _backPlaneRightEntity);
}

- (void)setFaceMeshOcclusion:(BOOL)enabled {
    // If face mesh is being disabled, remove from scene
    if (_faceMeshEnabled && !enabled && _isVisible) {
        _scene->remove(_faceMeshEntity);
        _isVisible = NO;
    }

    _faceMeshEnabled = enabled;
    NSLog(@"%@: Face mesh occlusion updated: %d", TAG, enabled);
}

- (void)setBackPlaneOcclusion:(BOOL)enabled {
    // If back planes are being disabled, remove from scene
    if (_backPlaneEnabled && !enabled) {
        if (_backPlaneLeftVisible) {
            _scene->remove(_backPlaneLeftEntity);
            _backPlaneLeftVisible = NO;
        }
        if (_backPlaneRightVisible) {
            _scene->remove(_backPlaneRightEntity);
            _backPlaneRightVisible = NO;
        }
    }

    _backPlaneEnabled = enabled;
    NSLog(@"%@: Back plane occlusion updated: %d", TAG, enabled);
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
        // Copy indices to persistent buffer (ARKit data may be deallocated before Filament uses it)
        const int16_t *indices = geometry.triangleIndices;
        memcpy(_indexData, indices, indexCount * sizeof(int16_t));
        _indexBuffer->setBuffer(*_engine,
            IndexBuffer::BufferDescriptor(_indexData, indexCount * sizeof(int16_t), nullptr));
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

    // Calculate min Z (furthest from camera in face local space)
    float minZ = FLT_MAX;
    for (NSUInteger i = 0; i < vertexCount; i++) {
        if (vertices[i].z < minZ) {
            minZ = vertices[i].z;
        }
    }

    // Update transform to match face position/rotation in world space
    TransformManager &transformManager = _engine->getTransformManager();
    TransformManager::Instance faceInstance = transformManager.getInstance(_faceMeshEntity);

    // Convert ARKit transform to Filament matrix
    mat4f filamentTransform;
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            filamentTransform[col][row] = face.transform.columns[col][row];
        }
    }

    transformManager.setTransform(faceInstance, filamentTransform);

    // Calculate back plane transform (behind the face)
    mat4f backPlaneTransform = filamentTransform;
    // Offset along local Z axis (minZ is behind the face in ARKit coords)
    float3 localOffset(0.0f, 0.0f, minZ + 0.03f);
    // Transform the offset by the rotation part of the face transform
    float3 worldOffset(
        filamentTransform[0][0] * localOffset.x + filamentTransform[1][0] * localOffset.y + filamentTransform[2][0] * localOffset.z,
        filamentTransform[0][1] * localOffset.x + filamentTransform[1][1] * localOffset.y + filamentTransform[2][1] * localOffset.z,
        filamentTransform[0][2] * localOffset.x + filamentTransform[1][2] * localOffset.y + filamentTransform[2][2] * localOffset.z
    );
    backPlaneTransform[3][0] += worldOffset.x;
    backPlaneTransform[3][1] += worldOffset.y;
    backPlaneTransform[3][2] += worldOffset.z;

    // Position both back planes with the same transform
    TransformManager::Instance backPlaneLeftInstance = transformManager.getInstance(_backPlaneLeftEntity);
    TransformManager::Instance backPlaneRightInstance = transformManager.getInstance(_backPlaneRightEntity);
    transformManager.setTransform(backPlaneLeftInstance, backPlaneTransform);
    transformManager.setTransform(backPlaneRightInstance, backPlaneTransform);

    // Extract yaw (Y-axis rotation) from face transform to determine head rotation
    // Yaw = atan2(m[2][0], m[0][0]) for a rotation matrix
    // Positive yaw = head turning left (user's perspective), negative = turning right
    float yaw = atan2f(filamentTransform[2][0], filamentTransform[0][0]);

    // Threshold for when to hide a back plane (about 15 degrees)
    const float yawThreshold = 0.12f;  // ~7 degrees in radians

    // Determine which back planes should be visible based on head rotation
    // When turning right (negative yaw): left temple visible, hide left back plane
    // When turning left (positive yaw): right temple visible, hide right back plane
    BOOL showLeftBackPlane = _backPlaneEnabled && (yaw < yawThreshold);
    BOOL showRightBackPlane = _backPlaneEnabled && (yaw > -yawThreshold);

    // Add face mesh to scene if enabled and not already visible
    if (_faceMeshEnabled && !_isVisible) {
        _scene->addEntity(_faceMeshEntity);
        _isVisible = YES;
    }

    // Update left back plane visibility
    if (showLeftBackPlane && !_backPlaneLeftVisible) {
        _scene->addEntity(_backPlaneLeftEntity);
        _backPlaneLeftVisible = YES;
    } else if (!showLeftBackPlane && _backPlaneLeftVisible) {
        _scene->remove(_backPlaneLeftEntity);
        _backPlaneLeftVisible = NO;
    }

    // Update right back plane visibility
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

    // Remove from scene if visible
    if (_isVisible) {
        _scene->remove(_faceMeshEntity);
        _isVisible = NO;
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

    if (_isVisible) {
        _scene->remove(_faceMeshEntity);
    }
    if (_backPlaneLeftVisible) {
        _scene->remove(_backPlaneLeftEntity);
    }
    if (_backPlaneRightVisible) {
        _scene->remove(_backPlaneRightEntity);
    }

    EntityManager::get().destroy(_faceMeshEntity);
    EntityManager::get().destroy(_backPlaneLeftEntity);
    EntityManager::get().destroy(_backPlaneRightEntity);

    if (_vertexBuffer) {
        _engine->destroy(_vertexBuffer);
    }
    if (_indexBuffer) {
        _engine->destroy(_indexBuffer);
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
    if (_occlusionMaterial) {
        _engine->destroy(_occlusionMaterial);
    }

    _isSetup = NO;
}

@end
