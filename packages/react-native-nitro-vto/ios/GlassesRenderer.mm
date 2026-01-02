#import "GlassesRenderer.h"
#import "LoaderUtils.h"
#import "KalmanFilter.h"
#import "MatrixUtils.h"

#include <filament/Engine.h>
#include <filament/Scene.h>
#include <filament/TransformManager.h>
#include <gltfio/AssetLoader.h>
#include <gltfio/ResourceLoader.h>
#include <gltfio/MaterialProvider.h>
#include <gltfio/TextureProvider.h>
#include <gltfio/materials/uberarchive.h>
#include <gltfio/FilamentAsset.h>
#include <filament/Box.h>
#include <utils/EntityManager.h>

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
@property (nonatomic, assign) float currentWidthMeters;
@property (nonatomic, assign) float modelScaleFactor;  // targetWidth / modelBoundingBoxWidth

// Aspect ratio for scale correction
@property (nonatomic, assign) float aspectRatio;

// Kalman filters for smoothing
@property (nonatomic, strong) KalmanFilter3D *positionFilter;
@property (nonatomic, strong) KalmanFilterQuaternion *rotationFilter;

@end

@implementation GlassesRenderer

- (instancetype)init {
    self = [super init];
    if (self) {
        _loadQueue = dispatch_queue_create("com.nitrovto.glassesloader", DISPATCH_QUEUE_SERIAL);
        _isLoading = NO;
        _aspectRatio = 1.0f;
        _modelScaleFactor = 1.0f;
        _positionFilter = [[KalmanFilter3D alloc] initWithProcessNoise:0.1f measurementNoise:0.05f];
        _rotationFilter = [[KalmanFilterQuaternion alloc] initWithProcessNoise:0.1f measurementNoise:0.05f];
    }
    return self;
}

- (void)setupWithEngine:(Engine *)engine
                  scene:(Scene *)scene
               modelUrl:(NSString *)modelUrl
            widthMeters:(float)widthMeters {
    _engine = engine;
    _scene = scene;
    _currentModelUrl = modelUrl;
    _currentWidthMeters = widthMeters;

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

        // Calculate scale factor from bounding box
        filament::Aabb boundingBox = _glassesAsset->getBoundingBox();
        float modelWidth = boundingBox.max.x - boundingBox.min.x;
        if (modelWidth > 0.0001f) {
            _modelScaleFactor = _currentWidthMeters / modelWidth;
        } else {
            _modelScaleFactor = 1.0f;
        }
        NSLog(@"%@: Model bounding box width: %f, target width: %f, scale factor: %f",
              TAG, modelWidth, _currentWidthMeters, _modelScaleFactor);

        // Add all entities to scene
        const Entity *entities = _glassesAsset->getEntities();
        size_t entityCount = _glassesAsset->getEntityCount();
        for (size_t i = 0; i < entityCount; i++) {
            _scene->addEntity(entities[i]);
        }

        NSLog(@"%@: Glasses model loaded: %zu entities", TAG, entityCount);
        [self hide];
    } else {
        NSLog(@"%@: Failed to create glasses asset", TAG);
    }
}

- (void)setViewportSizeWithWidth:(int)width height:(int)height {
    if (height > 0) {
        _aspectRatio = (float)width / (float)height;
    }
}

- (void)updateTransformWithFace:(ARFaceAnchor *)face frame:(ARFrame *)frame {
    if (!_glassesAsset || !_engine) return;

    TransformManager &transformManager = _engine->getTransformManager();
    TransformManager::Instance instance = transformManager.getInstance(_glassesAsset->getRoot());

    // Get nose bridge position in world space
    simd_float3 noseBridgeWorld = [self getNoseBridgeWorldPosWithFace:face];

    // Get face rotation from transform (world space)
    simd_quatf faceRotationWorld = simd_quaternion(face.transform);

    // Apply Kalman filter smoothing
    simd_float3 smoothedPosition = [_positionFilter updateWithX:noseBridgeWorld.x y:noseBridgeWorld.y z:noseBridgeWorld.z];
    simd_quatf smoothedRotation = [_rotationFilter updateWithQuaternion:faceRotationWorld];

    // Build world-space transform matrix
    // Scale to match target width in meters (computed from bounding box)
    float scale = _modelScaleFactor;

    simd_float4x4 rotationMatrix = [MatrixUtils quaternionToMatrix:smoothedRotation];

    simd_float4x4 transformMatrix = matrix_identity_float4x4;

    // Apply uniform scale
    transformMatrix.columns[0] *= scale;
    transformMatrix.columns[1] *= scale;
    transformMatrix.columns[2] *= scale;

    // Multiply with rotation
    transformMatrix = simd_mul(rotationMatrix, transformMatrix);

    // Set world-space position
    transformMatrix.columns[3].x = smoothedPosition.x;
    transformMatrix.columns[3].y = smoothedPosition.y;
    transformMatrix.columns[3].z = smoothedPosition.z;

    // Convert simd matrix to filament matrix
    filament::math::mat4f filamentTransform;
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            filamentTransform[col][row] = transformMatrix.columns[col][row];
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

    [self resetFilters];
}

- (void)resetFilters {
    [_positionFilter reset];
    [_rotationFilter reset];
}

- (void)switchModelWithUrl:(NSString *)modelUrl widthMeters:(float)widthMeters {
    if (!_scene || !_assetLoader) return;

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

    [self resetFilters];

    // Update current model info
    _currentModelUrl = modelUrl;
    _currentWidthMeters = widthMeters;

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
