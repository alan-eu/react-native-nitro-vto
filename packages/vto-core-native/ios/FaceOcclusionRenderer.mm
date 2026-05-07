#import "FaceOcclusionRenderer.h"
#import "FaceMeshTopology.h"
#import "LoaderUtils.h"
#import "MatrixUtils.h"
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

static NSString *const TAG = @"FaceOcclusionRenderer";

// Sized for ARKit's face mesh + the closure triangles FaceMeshTopology
// adds (3 centroid verts and ~50 triangles → ~1223 verts / ~2480 indices).
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

// Single back clipping plane spanning the full ear-line width.
@property (nonatomic, assign) Entity backPlaneEntity;
@property (nonatomic, assign) VertexBuffer *backPlaneVertexBuffer;
@property (nonatomic, assign) IndexBuffer *backPlaneIndexBuffer;
@property (nonatomic, assign) BOOL backPlaneVisible;

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
@property (nonatomic, assign) float3 *backPlaneVertices;

// Last computed ear half-width (face-local meters). Exposed via the public
// readonly earHalfWidth property and consumed by GlassesRenderer to articulate
// the temples.
@property (nonatomic, assign) float earHalfWidthValue;

@end

@implementation FaceOcclusionRenderer

- (instancetype)init {
    self = [super init];
    if (self) {
        _isSetup = NO;
        _isVisible = NO;
        _backPlaneVisible = NO;
        _currentVertexCount = 0;
        _currentIndexCount = 0;
        _faceMeshEnabled = YES;
        _backPlaneEnabled = YES;
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

- (BOOL)isBackPlaneVisible {
    return _backPlaneVisible;
}

- (float)earHalfWidth {
    return _earHalfWidthValue;
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
    // Single quad that clips glasses behind the face. Vertices are
    // overwritten per-frame in updateWithFace: with the actual ±halfW /
    // ±halfH derived from the face mesh.
    const float planeSizeX = 0.12f;  // initial 12cm half-width
    const float planeSizeY = 0.08f;  // initial 8cm half-height

    _backPlaneVertices[0] = float3(-planeSizeX, -planeSizeY, 0.0f);  // bottom-left
    _backPlaneVertices[1] = float3( planeSizeX, -planeSizeY, 0.0f);  // bottom-right
    _backPlaneVertices[2] = float3(-planeSizeX,  planeSizeY, 0.0f);  // top-left
    _backPlaneVertices[3] = float3( planeSizeX,  planeSizeY, 0.0f);  // top-right

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

    RenderableManager::Builder(1)
        .material(0, _occlusionMaterialInstance)
        .geometry(0, RenderableManager::PrimitiveType::TRIANGLES,
                  _backPlaneVertexBuffer, _backPlaneIndexBuffer, 0, 6)
        .boundingBox(boundingBox)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(0)
        .build(*_engine, _backPlaneEntity);
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
    // If back plane is being disabled, remove from scene
    if (_backPlaneEnabled && !enabled && _backPlaneVisible) {
        _scene->remove(_backPlaneEntity);
        _backPlaneVisible = NO;
    }

    _backPlaneEnabled = enabled;
    NSLog(@"%@: Back plane occlusion updated: %d", TAG, enabled);
}

- (void)updateWithFace:(ARFaceAnchor *)face
              topology:(FaceMeshTopology *)topology {
    if (!_isSetup || !_engine || !topology) return;

    NSUInteger vertexCount = topology.vertexCount;
    NSUInteger indexCount  = topology.indexCount;
    const simd_float3 *vertices = topology.vertices;
    const int16_t *indices      = topology.indices;

    if (vertexCount == 0 || indexCount == 0 || !vertices || !indices) return;

    if (vertexCount > MAX_VERTICES || indexCount > MAX_INDICES) {
        NSLog(@"%@: Face mesh too large: %lu vertices, %lu indices", TAG,
              (unsigned long)vertexCount, (unsigned long)indexCount);
        return;
    }

    // Copy vertex positions (already in face local space) and track XYZ extents
    // in a single pass. X/Y extents drive the per-frame back-plane size; Z gives
    // the back-plane offset.
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
    _vertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(_vertexData, vertexCount * sizeof(float3), nullptr));

    // Update index buffer only if index count changed
    if (indexCount != _currentIndexCount) {
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

    // Resize the back plane from face mesh extents. Tuning lives in
    // OcclusionConstants.h.
    float meshHalfW = fmaxf(fabsf(meshMinX), fabsf(meshMaxX));
    float meshHalfH = fmaxf(fabsf(meshMinY), fabsf(meshMaxY));
    float halfW = fmaxf(meshHalfW * kEarMargin, kMinHalfWidth);
    float halfH = meshHalfH * kHeightMargin;

    // Publish the ear half-width for consumers that articulate around it
    // (GlassesRenderer's temple swing). Use the same factor the back-plane
    // sizing applies, so the temple tips track the plane.
    _earHalfWidthValue = meshHalfW * kEarMargin;

    // Single plane spanning the full ear-line width.
    _backPlaneVertices[0] = float3(-halfW, -halfH, 0.0f);
    _backPlaneVertices[1] = float3( halfW, -halfH, 0.0f);
    _backPlaneVertices[2] = float3(-halfW,  halfH, 0.0f);
    _backPlaneVertices[3] = float3( halfW,  halfH, 0.0f);

    _backPlaneVertexBuffer->setBufferAt(*_engine, 0,
        VertexBuffer::BufferDescriptor(_backPlaneVertices, 4 * sizeof(float3), nullptr));

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

    // Shrink the face mesh in X only when writing depth, so its lateral edge
    // pulls inward away from where the temples pass at the cheekbone. Back
    // planes use filamentTransform without this scale, so behind-head
    // occlusion is unaffected.
    mat4f faceMeshShrink;
    faceMeshShrink[0][0] = kFaceMeshXShrink;
    transformManager.setTransform(faceInstance, filamentTransform * faceMeshShrink);

    // Calculate back plane transform (behind the face). minZ is the
    // most-negative (deepest) mesh vertex; the plane sits behind it.
    mat4f backPlaneTransform = filamentTransform;
    float3 localOffset(0.0f, 0.0f, minZ - kBackPlaneZOffset);
    // Transform the offset by the rotation part of the face transform
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

    // Add face mesh to scene if enabled and not already visible
    if (_faceMeshEnabled && !_isVisible) {
        _scene->addEntity(_faceMeshEntity);
        _isVisible = YES;
    }

    // Add back plane to scene if enabled and not already visible
    if (_backPlaneEnabled && !_backPlaneVisible) {
        _scene->addEntity(_backPlaneEntity);
        _backPlaneVisible = YES;
    }
}

- (void)hide {
    if (!_isSetup || !_engine) return;

    // Remove from scene if visible
    if (_isVisible) {
        _scene->remove(_faceMeshEntity);
        _isVisible = NO;
    }

    if (_backPlaneVisible) {
        _scene->remove(_backPlaneEntity);
        _backPlaneVisible = NO;
    }
}

- (void)destroy {
    if (!_engine || !_scene) return;

    if (_isVisible) {
        _scene->remove(_faceMeshEntity);
    }
    if (_backPlaneVisible) {
        _scene->remove(_backPlaneEntity);
    }

    EntityManager::get().destroy(_faceMeshEntity);
    EntityManager::get().destroy(_backPlaneEntity);

    if (_vertexBuffer) {
        _engine->destroy(_vertexBuffer);
    }
    if (_indexBuffer) {
        _engine->destroy(_indexBuffer);
    }
    if (_backPlaneVertexBuffer) {
        _engine->destroy(_backPlaneVertexBuffer);
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
