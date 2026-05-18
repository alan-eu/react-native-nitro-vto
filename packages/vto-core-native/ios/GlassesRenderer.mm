#import "GlassesRenderer.h"
#import "LoaderUtils.h"
#import "MatrixUtils.h"
#import "OcclusionConstants.h"

#include <filament/Engine.h>
#include <filament/Scene.h>
#include <filament/TransformManager.h>
#include <filament/RenderableManager.h>
#include <filament/Box.h>
#include <gltfio/AssetLoader.h>
#include <gltfio/ResourceLoader.h>
#include <gltfio/MaterialProvider.h>
#include <gltfio/TextureProvider.h>
#include <gltfio/materials/uberarchive.h>
#include <gltfio/FilamentAsset.h>
#include <utils/EntityManager.h>
#include <math/mat4.h>
#include <math/vec3.h>

using namespace filament;
using namespace filament::gltfio;
using namespace utils;

static NSString *const TAG = @"GlassesRenderer";

@interface GlassesRenderer ()

@property (nonatomic, assign) Engine *engine;
@property (nonatomic, assign) Scene *scene;
@property (nonatomic, assign) AssetLoader *assetLoader;
@property (nonatomic, assign) ResourceLoader *resourceLoader;
@property (nonatomic, assign) FilamentAsset *glassesAsset;
@property (nonatomic, assign) MaterialProvider *materialProvider;
@property (nonatomic, assign) TextureProvider *textureProvider;

// Thread management
@property (nonatomic, strong) dispatch_queue_t loadQueue;

// Loading state
@property (nonatomic, assign) BOOL isLoading;

// Current model info
@property (nonatomic, copy) NSString *currentModelUrl;

// Forward offset for glasses positioning (in meters)
@property (nonatomic, assign) float forwardOffset;

// Fires onGlassesDisplayed once per loaded model — reset on every successful load.
@property (nonatomic, assign) BOOL hasDisplayedCurrentModel;

// Temple articulation state, populated when the loaded glb exposes the
// expected hinge node names. articulationEnabled stays NO if any of the four
// nodes is missing — articulation becomes a no-op for that asset.
@property (nonatomic, assign) BOOL articulationEnabled;
@property (nonatomic, assign) Entity hingeLEntity;
@property (nonatomic, assign) Entity hingeREntity;
@property (nonatomic, assign) filament::math::mat4f hingeLRest;
@property (nonatomic, assign) filament::math::mat4f hingeRRest;
@property (nonatomic, assign) float restTipXL;     // glb-cm (AABB center.x of TempleL_geometry)
@property (nonatomic, assign) float restTipXR;     // glb-cm
@property (nonatomic, assign) float templeLLength;  // glb-cm, hinge-to-tip distance along glb +Y
@property (nonatomic, assign) float templeRLength;  // glb-cm

@end

@implementation GlassesRenderer

- (instancetype)init {
    self = [super init];
    if (self) {
        _loadQueue = dispatch_queue_create("com.nitrovto.glassesloader", DISPATCH_QUEUE_SERIAL);
        _isLoading = NO;
        _forwardOffset = kForwardOffset;
    }
    return self;
}

- (void)setupWithEngine:(Engine *)engine
                  scene:(Scene *)scene
               modelUrl:(NSString *)modelUrl {
    _engine = engine;
    _scene = scene;
    _currentModelUrl = modelUrl;

    // Setup GLTF loader
    _materialProvider = createUbershaderProvider(engine, UBERARCHIVE_DEFAULT_DATA, UBERARCHIVE_DEFAULT_SIZE);
    _assetLoader = AssetLoader::create({
        .engine = engine,
        .materials = _materialProvider,
        .names = nullptr,
        .entities = &EntityManager::get()
    });
    _resourceLoader = new ResourceLoader({engine, ".", true});

    // Create texture provider for PNG/JPEG decoding using stb_image
    _textureProvider = createStbProvider(engine);
    _resourceLoader->addTextureProvider("image/png", _textureProvider);
    _resourceLoader->addTextureProvider("image/jpeg", _textureProvider);

    // Load model
    [self loadModelFromUrl:modelUrl];
}

- (void)loadModelFromUrl:(NSString *)url {
    if (url.length == 0) {
        NSLog(@"%@: Empty URL, skipping model load", TAG);
        return;
    }

    if (_isLoading) {
        NSLog(@"%@: Already loading a model, skipping request for: %@", TAG, url);
        return;
    }

    _isLoading = YES;
    NSLog(@"%@: Starting download from URL: %@", TAG, url);

    __weak __typeof__(self) weakSelf = self;
    dispatch_async(_loadQueue, ^{
        __strong __typeof__(weakSelf) strongSelf = weakSelf;
        if (!strongSelf) return;

        NSError *error = nil;
        NSData *modelData = [LoaderUtils loadFromUrl:url error:&error];

        dispatch_async(dispatch_get_main_queue(), ^{
            if (error) {
                NSLog(@"%@: Failed to download GLB from URL: %@", TAG, error.localizedDescription);
                strongSelf.isLoading = NO;
                return;
            }

            [strongSelf loadModelFromData:modelData];

            if (strongSelf.onModelLoaded) {
                strongSelf.onModelLoaded(url);
            }
            strongSelf.isLoading = NO;
        });
    });
}

- (void)loadModelFromData:(NSData *)data {
    if (!_assetLoader || !_resourceLoader || !_scene) return;

    _glassesAsset = _assetLoader->createAsset((const uint8_t *)data.bytes, (uint32_t)data.length);

    if (_glassesAsset) {
        _resourceLoader->loadResources(_glassesAsset);
        _glassesAsset->releaseSourceData();

        // Add all entities to scene
        const Entity *entities = _glassesAsset->getEntities();
        size_t entityCount = _glassesAsset->getEntityCount();
        for (size_t i = 0; i < entityCount; i++) {
            _scene->addEntity(entities[i]);
        }

        NSLog(@"%@: Glasses model loaded: %zu entities", TAG, entityCount);
        // Re-arm onGlassesDisplayed for this freshly-loaded model. Next successful
        // updateTransformWithFace will fire the callback.
        _hasDisplayedCurrentModel = NO;
        [self cacheTempleArticulationState];
        [self hide];
    } else {
        NSLog(@"%@: Failed to create glasses asset", TAG);
    }
}

- (void)cacheTempleArticulationState {
    _articulationEnabled = NO;
    if (!_glassesAsset || !_engine) return;

    Entity hingeL = _glassesAsset->getFirstEntityByName("HingeL_temple");
    Entity hingeR = _glassesAsset->getFirstEntityByName("HingeR_temple");
    Entity templeL = _glassesAsset->getFirstEntityByName("TempleL_geometry");
    Entity templeR = _glassesAsset->getFirstEntityByName("TempleR_geometry");

    if (hingeL.isNull() || hingeR.isNull() || templeL.isNull() || templeR.isNull()) {
        NSLog(@"%@: Hinge/temple nodes not found — articulation disabled for this asset", TAG);
        return;
    }

    TransformManager &tm = _engine->getTransformManager();
    RenderableManager &rm = _engine->getRenderableManager();

    _hingeLEntity = hingeL;
    _hingeREntity = hingeR;
    _hingeLRest = tm.getTransform(tm.getInstance(hingeL));
    _hingeRRest = tm.getTransform(tm.getInstance(hingeR));
    float hingeLY = _hingeLRest[3][1];
    float hingeRY = _hingeRRest[3][1];

    // Temples are authored extending along glb +Y in Z-up cm: the hinge sits
    // at the front of the frame (smaller Y) and the geometry runs back to a
    // larger +Y for the tip. Approximate the rest tip with the AABB:
    //   restTipX  ≈ AABB.center.x   (X is roughly constant along the temple)
    //   templeLen = AABB.maxY - hingeY  (distance from hinge to the +Y end)
    filament::Box bboxL = rm.getAxisAlignedBoundingBox(rm.getInstance(templeL));
    filament::Box bboxR = rm.getAxisAlignedBoundingBox(rm.getInstance(templeR));
    _restTipXL = bboxL.center.x;
    _restTipXR = bboxR.center.x;
    _templeLLength = (bboxL.center.y + bboxL.halfExtent.y) - hingeLY;
    _templeRLength = (bboxR.center.y + bboxR.halfExtent.y) - hingeRY;

    if (_templeLLength <= 0.0f || _templeRLength <= 0.0f) {
        NSLog(@"%@: Degenerate temple AABB — articulation disabled", TAG);
        return;
    }

    _articulationEnabled = YES;
    NSLog(@"%@: Temple articulation enabled (L tipX=%.2f cm, len=%.2f cm; R tipX=%.2f cm, len=%.2f cm)",
          TAG, _restTipXL, _templeLLength, _restTipXR, _templeRLength);
}

- (void)updateTransformWithFace:(ARFaceAnchor *)face frame:(ARFrame *)frame {
    if (!_glassesAsset || !_engine) return;

    if (!_hasDisplayedCurrentModel) {
        _hasDisplayedCurrentModel = YES;
        if (self.onGlassesDisplayed) {
            self.onGlassesDisplayed(_currentModelUrl);
        }
    }

    TransformManager &transformManager = _engine->getTransformManager();
    TransformManager::Instance instance = transformManager.getInstance(_glassesAsset->getRoot());

    // Get nose bridge position in world space
    simd_float3 noseBridgeWorld = [self getNoseBridgeWorldPosWithFace:face];

    // Get face rotation from transform (world space)
    simd_quatf faceRotationWorld = simd_quaternion(face.transform);

    // Build world-space transform matrix (no scaling - models are in real-world meters)
    simd_float4x4 rotationMatrix = [MatrixUtils quaternionToMatrix:faceRotationWorld];

    // Offset glasses along face's Z axis (forward/backward)
    simd_float3 forward = simd_make_float3(rotationMatrix.columns[2].x,
                                            rotationMatrix.columns[2].y,
                                            rotationMatrix.columns[2].z);

    // Set world-space position with forward offset
    rotationMatrix.columns[3].x = noseBridgeWorld.x + forward.x * _forwardOffset;
    rotationMatrix.columns[3].y = noseBridgeWorld.y + forward.y * _forwardOffset;
    rotationMatrix.columns[3].z = noseBridgeWorld.z + forward.z * _forwardOffset;

    // Convert simd matrix to filament matrix
    filament::math::mat4f filamentTransform;
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            filamentTransform[col][row] = rotationMatrix.columns[col][row];
        }
    }

    transformManager.setTransform(instance, filamentTransform);
}

- (simd_float3)getNoseBridgeWorldPosWithFace:(ARFaceAnchor *)face {
    // ARKit face mesh vertex indices for nose bridge
    // Reference: https://www.oxfordechoes.com/ios-arkit-face-tracking-vertices/
    const int leftIndex = 818;  // Left side of nose bridge
    const int rightIndex = 366; // Right side of nose bridge

    ARFaceGeometry *geometry = face.geometry;
    const simd_float3 *vertices = geometry.vertices;
    NSUInteger vertexCount = geometry.vertexCount;

    if (leftIndex >= vertexCount || rightIndex >= vertexCount) {
        // Fallback to face center
        return simd_make_float3(face.transform.columns[3].x,
                                face.transform.columns[3].y,
                                face.transform.columns[3].z);
    }

    simd_float3 left = vertices[leftIndex];
    simd_float3 right = vertices[rightIndex];

    // Calculate center in local face coordinates
    float centerX = (left.x + right.x) / 2.0f;
    float centerY = (left.y + right.y) / 2.0f;
    float centerZ = (left.z + right.z) / 2.0f;

    // Transform to world coordinates
    simd_float4 localPos = simd_make_float4(centerX, centerY, centerZ, 1.0f);
    simd_float4 worldPos = simd_mul(face.transform, localPos);

    return simd_make_float3(worldPos.x, worldPos.y, worldPos.z);
}

- (void)hide {
    if (!_glassesAsset || !_engine) return;

    TransformManager &transformManager = _engine->getTransformManager();
    TransformManager::Instance instance = transformManager.getInstance(_glassesAsset->getRoot());

    filament::math::mat4f hideMatrix = [MatrixUtils createHideMatrix];
    transformManager.setTransform(instance, hideMatrix);
}

- (void)setForwardOffset:(float)offset {
    _forwardOffset = offset;
}

- (void)updateTempleArticulationWithEarHalfWidth:(float)earHalfWidth {
    if (!_articulationEnabled || !_engine || !_glassesAsset) return;
    if (earHalfWidth <= 0.0f) return;

    // Scale ear half-width to the temple tip's depth (see kTempleTipScale
    // doc in OcclusionConstants.h). Convert face-local meters → glb-cm.
    float desiredXcm = earHalfWidth * kTempleTipScale * 100.0f;

    // Temples extend along glb +Y from the hinge. Rotating around the
    // hinge's local Z axis by θ moves the tip's parent X by approximately
    // -L·sin(θ). Solve for θ to land the tip at ±desiredXcm.
    float sinL = (_restTipXL - desiredXcm) / _templeLLength;
    float sinR = (_restTipXR - (-desiredXcm)) / _templeRLength;
    sinL = fmaxf(-1.0f, fminf(1.0f, sinL));
    sinR = fmaxf(-1.0f, fminf(1.0f, sinR));
    float thetaL = asinf(sinL);
    float thetaR = asinf(sinR);

    using namespace filament::math;
    mat4f rotL = mat4f::rotation(thetaL, float3{0.0f, 0.0f, 1.0f});
    mat4f rotR = mat4f::rotation(thetaR, float3{0.0f, 0.0f, 1.0f});

    TransformManager &tm = _engine->getTransformManager();
    tm.setTransform(tm.getInstance(_hingeLEntity), _hingeLRest * rotL);
    tm.setTransform(tm.getInstance(_hingeREntity), _hingeRRest * rotR);
}

- (void)switchModelWithUrl:(NSString *)modelUrl {
    if (!_scene || !_assetLoader) return;

    // Disable articulation immediately — the cached hinge entities belong to
    // the asset we're about to destroy. cacheTempleArticulationState reruns
    // when the new asset finishes loading.
    _articulationEnabled = NO;

    // Remove current model from scene
    if (_glassesAsset) {
        const Entity *entities = _glassesAsset->getEntities();
        size_t entityCount = _glassesAsset->getEntityCount();
        for (size_t i = 0; i < entityCount; i++) {
            _scene->remove(entities[i]);
        }
        _assetLoader->destroyAsset(_glassesAsset);
        _glassesAsset = nullptr;
    }

    // Update current model info
    _currentModelUrl = modelUrl;

    // Load new model
    [self loadModelFromUrl:modelUrl];
    NSLog(@"%@: Switched to model: %@", TAG, modelUrl);
}

- (void)destroy {
    if (!_assetLoader) return;

    if (_glassesAsset) {
        if (_scene) {
            const Entity *entities = _glassesAsset->getEntities();
            size_t entityCount = _glassesAsset->getEntityCount();
            for (size_t i = 0; i < entityCount; i++) {
                _scene->remove(entities[i]);
            }
        }
        _assetLoader->destroyAsset(_glassesAsset);
    }

    // ResourceLoader must be destroyed before TextureProvider
    if (_resourceLoader) {
        delete _resourceLoader;
    }
    if (_textureProvider) {
        delete _textureProvider;
    }
    if (_assetLoader) {
        AssetLoader::destroy(&_assetLoader);
    }
    if (_materialProvider) {
        _materialProvider->destroyMaterials();
        delete _materialProvider;
    }
}

@end
