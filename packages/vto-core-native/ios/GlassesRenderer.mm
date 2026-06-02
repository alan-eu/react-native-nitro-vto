#import "GlassesRenderer.h"
#import "LoaderUtils.h"
#import "MatrixUtils.h"
#import "OcclusionConstants.h"

#include <filament/Engine.h>
#include <filament/Scene.h>
#include <filament/TransformManager.h>
#include <filament/RenderableManager.h>
#include <filament/MaterialInstance.h>
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

// Transform of `node` relative to `root`, composed from local transforms by
// walking up the parent chain. Independent of any committed world transform,
// so it is valid immediately after load. Identity when node == root.
static filament::math::mat4f transformRelativeToRoot(filament::TransformManager &tm,
                                                     Entity node, Entity root) {
    filament::math::mat4f acc;  // identity
    Entity e = node;
    while (!e.isNull() && e != root) {
        filament::TransformManager::Instance inst = tm.getInstance(e);
        if (!inst.isValid()) break;
        acc = tm.getTransform(inst) * acc;
        e = tm.getParent(inst);
    }
    return acc;
}

// Temple tip in asset-root-local space: the rear-most (min root-local Z) corner
// of the temple renderable's bounding box. Temples extend back toward the ears,
// so that corner is the tip whose lateral position we articulate.
static filament::math::float3 templeTipRootLocal(filament::TransformManager &tm,
                                                 filament::RenderableManager &rm,
                                                 Entity temple, Entity root) {
    using namespace filament::math;
    mat4f toRoot = transformRelativeToRoot(tm, temple, root);
    filament::Box box = rm.getAxisAlignedBoundingBox(rm.getInstance(temple));
    float3 c = box.center, h = box.halfExtent;
    float3 best{}; bool first = true;
    for (float sx : {-1.0f, 1.0f})
        for (float sy : {-1.0f, 1.0f})
            for (float sz : {-1.0f, 1.0f}) {
                float4 w = toRoot * float4{c.x + sx * h.x, c.y + sy * h.y, c.z + sz * h.z, 1.0f};
                if (first || w.z < best.z) { best = float3{w.x, w.y, w.z}; first = false; }
            }
    return best;
}

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
//
// Everything is captured in asset-root-local space, which is convention-
// invariant: regardless of each glb's units (cm vs m), root scale, exporter
// (Fbx vs Blender) or hierarchy depth, the hinge lands at the same metric pose
// here and the temple extends back by the same metric lever. *LocalRest is the
// parent-relative transform setTransform expects; *RootRest is relative to the
// asset root; the lever (length + bearing in the root-local X–Z plane) is what
// the swing solver needs to drive the tip's X to the ear target.
@property (nonatomic, assign) BOOL articulationEnabled;
@property (nonatomic, assign) Entity hingeLEntity;
@property (nonatomic, assign) Entity hingeREntity;
@property (nonatomic, assign) filament::math::mat4f hingeLLocalRest;
@property (nonatomic, assign) filament::math::mat4f hingeRLocalRest;
@property (nonatomic, assign) filament::math::mat4f hingeLRootRest;
@property (nonatomic, assign) filament::math::mat4f hingeRRootRest;
@property (nonatomic, assign) float templeLLeverLen;    // meters
@property (nonatomic, assign) float templeRLeverLen;
@property (nonatomic, assign) float templeLLeverAngle;  // radians, atan2(dz, dx)
@property (nonatomic, assign) float templeRLeverAngle;

- (void)swingHinge:(Entity)hinge
         localRest:(filament::math::mat4f)Lr
          rootRest:(filament::math::mat4f)Hr
          leverLen:(float)leverLen
        leverAngle:(float)beta
           targetX:(float)targetX
    outwardYawSign:(float)outwardYawSign
                tm:(filament::TransformManager &)tm;
- (void)configureLensCulling;

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
        [self configureLensCulling];
        [self hide];
    } else {
        NSLog(@"%@: Failed to create glasses asset", TAG);
    }
}

// The lenses are thin single-sided shells. Tinted (solar/clip-on) lenses
// vanish at view angles where the camera sees their back face — backface
// culling drops the only face, so the lens shows the background instead of its
// tint. Disable culling on the lens material instances so both faces always
// render and the lens is stable from every angle. Clear lenses are unaffected
// (invisible either way); other geometry keeps its normal culling.
- (void)configureLensCulling {
    if (!_glassesAsset || !_engine) return;
    RenderableManager &rm = _engine->getRenderableManager();
    const char *lensNodes[] = {"LensL_geometry", "LensR_geometry"};
    for (const char *name : lensNodes) {
        Entity e = _glassesAsset->getFirstEntityByName(name);
        if (e.isNull()) continue;
        RenderableManager::Instance ri = rm.getInstance(e);
        if (!ri.isValid()) continue;
        size_t primCount = rm.getPrimitiveCount(ri);
        for (size_t p = 0; p < primCount; p++) {
            MaterialInstance *mi = rm.getMaterialInstanceAt(ri, p);
            if (mi) mi->setCullingMode(MaterialInstance::CullingMode::NONE);
        }
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
    Entity root = _glassesAsset->getRoot();

    _hingeLEntity = hingeL;
    _hingeREntity = hingeR;
    _hingeLLocalRest = tm.getTransform(tm.getInstance(hingeL));
    _hingeRLocalRest = tm.getTransform(tm.getInstance(hingeR));
    _hingeLRootRest = transformRelativeToRoot(tm, hingeL, root);
    _hingeRRootRest = transformRelativeToRoot(tm, hingeR, root);

    // Rest temple tips in root-local space, then the hinge→tip lever in the
    // horizontal (X–Z) plane. Articulation swings the temple about the
    // root-local vertical (Y) to drive the tip's X to the ear target; length +
    // bearing of the lever are all the solver needs.
    filament::math::float3 tipL = templeTipRootLocal(tm, rm, templeL, root);
    filament::math::float3 tipR = templeTipRootLocal(tm, rm, templeR, root);
    float dxL = tipL.x - _hingeLRootRest[3][0], dzL = tipL.z - _hingeLRootRest[3][2];
    float dxR = tipR.x - _hingeRRootRest[3][0], dzR = tipR.z - _hingeRRootRest[3][2];
    _templeLLeverLen = hypotf(dxL, dzL);
    _templeRLeverLen = hypotf(dxR, dzR);
    _templeLLeverAngle = atan2f(dzL, dxL);
    _templeRLeverAngle = atan2f(dzR, dxR);

    if (_templeLLeverLen <= 0.0f || _templeRLeverLen <= 0.0f) {
        NSLog(@"%@: Degenerate temple lever — articulation disabled", TAG);
        return;
    }

    _articulationEnabled = YES;
    NSLog(@"%@: Temple articulation enabled (L pivotX=%.3f m, len=%.3f m; R pivotX=%.3f m, len=%.3f m)",
          TAG, _hingeLRootRest[3][0], _templeLLeverLen, _hingeRRootRest[3][0], _templeRLeverLen);
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
    // ARKit face mesh vertex indices flanking the nose bridge. Only Y/Z are
    // taken from these — see ADR 0016 for why X comes from the face anchor's
    // symmetry axis instead.
    const int aIndex = 818;
    const int bIndex = 366;

    ARFaceGeometry *geometry = face.geometry;
    const simd_float3 *vertices = geometry.vertices;
    NSUInteger vertexCount = geometry.vertexCount;

    if (aIndex >= vertexCount || bIndex >= vertexCount) {
        // Fallback to face center
        return simd_make_float3(face.transform.columns[3].x,
                                face.transform.columns[3].y,
                                face.transform.columns[3].z);
    }

    simd_float3 a = vertices[aIndex];
    simd_float3 b = vertices[bIndex];

    float centerX = 0.0f;
    float centerY = (a.y + b.y) / 2.0f;
    float centerZ = (a.z + b.z) / 2.0f;

    simd_float4 localPos = simd_make_float4(centerX, centerY, centerZ, 1.0f);
    simd_float4 worldPos = simd_mul(face.transform, localPos);

    return simd_make_float3(worldPos.x, worldPos.y, worldPos.z);
}

// HARNESS (dev/simulator only). Park the glasses ~0.4 m in front of the camera
// (which looks down -Z) so they're visible without a tracked face. Rotated 180°
// about Y so the front of the frame faces the camera.
- (void)setStaticPreviewTransform {
    if (!_glassesAsset || !_engine) return;
    using namespace filament::math;
    TransformManager &tm = _engine->getTransformManager();
    auto inst = tm.getInstance(_glassesAsset->getRoot());
    // Angled 3/4 + slightly-top view so the temples (which extend back from the
    // hinges) are visible — needed to inspect temple articulation/splay (#3).
    mat4f t = mat4f::translation(float3{0.0f, 0.0f, -0.42f}) *
              mat4f::rotation(0.45f, float3{1.0f, 0.0f, 0.0f}) *           // tilt top toward camera
              mat4f::rotation((float)M_PI + 0.6f, float3{0.0f, 1.0f, 0.0f}); // face camera + 3/4 yaw
    tm.setTransform(inst, t);
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
    if (!_articulationEnabled || !_engine) return;
    if (earHalfWidth <= 0.0f) return;

    // Temple tips target ±(earHalfWidth · kTempleTipScale) on the glasses' own
    // left/right axis (root-local X, metric — no unit conversion). HingeL is
    // on +X, HingeR on −X (see the cached pivots).
    TransformManager &tm = _engine->getTransformManager();
    float targetX = earHalfWidth * kTempleTipScale;
    // HingeL sits on +X: outward (away from the head) is a negative yaw about
    // root-local +Y; HingeR mirrors it.
    [self swingHinge:_hingeLEntity localRest:_hingeLLocalRest rootRest:_hingeLRootRest
            leverLen:_templeLLeverLen leverAngle:_templeLLeverAngle targetX:+targetX
       outwardYawSign:-1.0f tm:tm];
    [self swingHinge:_hingeREntity localRest:_hingeRLocalRest rootRest:_hingeRRootRest
            leverLen:_templeRLeverLen leverAngle:_templeRLeverAngle targetX:-targetX
       outwardYawSign:+1.0f tm:tm];
}

// Swing one temple about the root-local vertical (Y) through its hinge pivot so
// the tip's root-local X reaches targetX. The tip traces a circle of radius
// `leverLen` about the pivot; with the rest bearing β = leverAngle the tip X is
//   pivotX + leverLen·cos(φ − β),
// so φ − β = ±acos((targetX − pivotX)/leverLen). We take the root nearest the
// rest pose (φ = 0) for a gentle swing. The root-local rotation M is conjugated
// into the hinge's parent-relative frame: newLocal = Lr · Hr⁻¹ · M · Hr (which
// collapses to Lr when φ = 0), so it is correct whatever the hierarchy above.
- (void)swingHinge:(Entity)hinge
         localRest:(filament::math::mat4f)Lr
          rootRest:(filament::math::mat4f)Hr
          leverLen:(float)leverLen
        leverAngle:(float)beta
           targetX:(float)targetX
    outwardYawSign:(float)outwardYawSign
                tm:(filament::TransformManager &)tm {
    using namespace filament::math;

    float pivotX = Hr[3][0];
    float c = fmaxf(-1.0f, fminf(1.0f, (targetX - pivotX) / leverLen));
    float d = acosf(c);
    auto wrap = [](float a) { while (a > M_PI) a -= 2.0f * M_PI; while (a < -M_PI) a += 2.0f * M_PI; return a; };
    float phiA = wrap(beta + d), phiB = wrap(beta - d);
    float phi = (fabsf(phiA) <= fabsf(phiB)) ? phiA : phiB;

    // On top of the solved lateral swing: yaw the temple a touch outward (off
    // the facemesh occluder) and pitch its tip down to ear height.
    float yaw = outwardYawSign * kTempleOutwardYawDeg * (float)(M_PI / 180.0);
    float pitch = -kTempleDownPitchDeg * (float)(M_PI / 180.0);

    float3 pivot{Hr[3][0], Hr[3][1], Hr[3][2]};
    mat4f M = mat4f::translation(pivot)
            * mat4f::rotation(phi + yaw, float3{0.0f, 1.0f, 0.0f})
            * mat4f::rotation(pitch, float3{1.0f, 0.0f, 0.0f})
            * mat4f::translation(-pivot);
    mat4f newLocal = Lr * inverse(Hr) * M * Hr;
    tm.setTransform(tm.getInstance(hinge), newLocal);
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
