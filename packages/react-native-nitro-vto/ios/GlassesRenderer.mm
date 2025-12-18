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
@property (nonatomic, assign) TextureProvider *stbTextureProvider;
@property (nonatomic, assign) TextureProvider *ktx2TextureProvider;

// Thread management
@property (nonatomic, strong) dispatch_queue_t loadQueue;

// Loading state
@property (nonatomic, assign) BOOL isLoading;

// Current model info
@property (nonatomic, copy) NSString *currentModelUrl;
@property (nonatomic, assign) float currentWidthMeters;

// Aspect ratio for scale correction
@property (nonatomic, assign) float aspectRatio;

// Kalman filters for smoothing
@property (nonatomic, strong) KalmanFilter2D *positionFilter;
@property (nonatomic, strong) KalmanFilter *scaleFilter;
@property (nonatomic, strong) KalmanFilterQuaternion *rotationFilter;

@end

@implementation GlassesRenderer

- (instancetype)init {
    self = [super init];
    if (self) {
        _loadQueue = dispatch_queue_create("com.nitrovto.glassesloader", DISPATCH_QUEUE_SERIAL);
        _isLoading = NO;
        _aspectRatio = 1.0f;
        _positionFilter = [[KalmanFilter2D alloc] initWithProcessNoise:0.1f measurementNoise:0.05f];
        _scaleFilter = [[KalmanFilter alloc] initWithProcessNoise:0.1f measurementNoise:0.05f initialEstimate:0.0f];
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
    
    // Setup texture providers for loading textures from GLB files
    _stbTextureProvider = createStbProvider(engine);
    _ktx2TextureProvider = createKtx2Provider(engine);
    
    // Register texture providers for different MIME types
    _resourceLoader->addTextureProvider("image/png", _stbTextureProvider);
    _resourceLoader->addTextureProvider("image/jpeg", _stbTextureProvider);
    _resourceLoader->addTextureProvider("image/jpg", _stbTextureProvider);
    _resourceLoader->addTextureProvider("image/ktx2", _ktx2TextureProvider);

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

    simd_float4x4 viewMatrix = [frame.camera viewMatrixForOrientation:UIInterfaceOrientationPortrait];
    CGSize viewportSize = CGSizeMake(1, _aspectRatio > 0 ? 1.0 / _aspectRatio : 1);
    simd_float4x4 projMatrix = [frame.camera projectionMatrixForOrientation:UIInterfaceOrientationPortrait
                                                               viewportSize:viewportSize
                                                                      zNear:0.1
                                                                       zFar:100];

    // Get nose bridge in world and NDC space
    simd_float3 noseBridgeWorld = [self getNoseBridgeWorldPosWithFace:face];
    simd_float2 noseBridgeNdcRaw = [MatrixUtils projectToNdcWithWorldPos:noseBridgeWorld
                                                              viewMatrix:viewMatrix
                                                              projMatrix:projMatrix];

    // Calculate depth (distance from camera) for scale calculation
    float depth = [MatrixUtils getDepthInViewSpaceWithWorldPos:noseBridgeWorld viewMatrix:viewMatrix];

    // Scale: use depth-based projection to maintain consistent size regardless of head turn
    float focalLength = fabsf(projMatrix.columns[0].x);
    float scaleRaw = focalLength / depth;

    // Apply Kalman filters to smooth position, scale, and rotation
    simd_float2 noseBridgeNdc = [_positionFilter updateWithX:noseBridgeNdcRaw.x y:noseBridgeNdcRaw.y];
    float scale = [_scaleFilter updateWithValue:scaleRaw];

    // Get rotation quaternion from face transform
    simd_quatf faceRotation = simd_quaternion(face.transform);
    simd_quatf smoothedQuaternion = [_rotationFilter updateWithQuaternion:faceRotation];

    // Build transform matrix: rotation * uniform scale
    simd_float4x4 rotationMatrix = [MatrixUtils quaternionToMatrix:smoothedQuaternion];

    simd_float4x4 transformMatrix = matrix_identity_float4x4;

    // Apply scale
    transformMatrix.columns[0] *= scale;
    transformMatrix.columns[1] *= scale;
    transformMatrix.columns[2] *= scale;

    // Multiply with rotation
    transformMatrix = simd_mul(rotationMatrix, transformMatrix);

    // Apply aspect ratio correction in screen space (after rotation)
    transformMatrix.columns[0].y *= _aspectRatio;
    transformMatrix.columns[1].y *= _aspectRatio;
    transformMatrix.columns[2].y *= _aspectRatio;

    // Set position (flip X for front camera mirror)
    transformMatrix.columns[3].x = -noseBridgeNdc.x;
    transformMatrix.columns[3].y = noseBridgeNdc.y;
    transformMatrix.columns[3].z = -0.5f;

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
    const int leftIndex = 9;    // Left side of nose bridge
    const int rightIndex = 175; // Right side of nose bridge

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
    [_scaleFilter reset];
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

    if (_resourceLoader) {
        delete _resourceLoader;
    }
    if (_stbTextureProvider) {
        delete _stbTextureProvider;
        _stbTextureProvider = nullptr;
    }
    if (_ktx2TextureProvider) {
        delete _ktx2TextureProvider;
        _ktx2TextureProvider = nullptr;
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
