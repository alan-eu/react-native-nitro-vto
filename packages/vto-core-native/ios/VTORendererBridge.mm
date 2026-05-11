#import "VTORendererBridge.h"

#include <filament/Engine.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/View.h>
#include <filament/Camera.h>
#include <filament/SwapChain.h>
#include <filament/Viewport.h>
#include <filament/ColorGrading.h>
#include <filament/ToneMapper.h>
#include <utils/EntityManager.h>
#include <math/mat4.h>

#import "CameraTextureRenderer.h"
#import "EnvironmentLightingRenderer.h"
#import "FaceMeshTopology.h"
#import "FaceOcclusionRenderer.h"
#import "GlassesRenderer.h"
#import "DebugRenderer.h"

using namespace filament;

static NSString *const TAG = @"VTORenderer";

@interface VTORendererBridge ()

@property (nonatomic, strong) MTKView *metalView;
@property (nonatomic, assign) id<MTLDevice> metalDevice;
@property (nonatomic, assign) id<MTLCommandQueue> commandQueue;

// Filament components
@property (nonatomic, assign) Engine *engine;
@property (nonatomic, assign) Renderer *renderer;
@property (nonatomic, assign) Scene *scene;
@property (nonatomic, assign) View *filamentView;
@property (nonatomic, assign) Camera *camera;
@property (nonatomic, assign) SwapChain *swapChain;
@property (nonatomic, assign) utils::Entity cameraEntity;
@property (nonatomic, assign) ColorGrading *colorGrading;

// Sub-renderers
@property (nonatomic, strong) CameraTextureRenderer *cameraTextureRenderer;
@property (nonatomic, strong) EnvironmentLightingRenderer *environmentLightingRenderer;
@property (nonatomic, strong) FaceOcclusionRenderer *faceOcclusionRenderer;
@property (nonatomic, strong) GlassesRenderer *glassesRenderer;
@property (nonatomic, strong) DebugRenderer *debugRenderer;

// Shared face-mesh topology (filled eye/mouth holes). nil if SCN
// allocation failed at startup — renderers should still cope.
@property (nonatomic, strong, nullable) FaceMeshTopology *faceMeshTopology;

// ARKit
@property (nonatomic, weak) ARSession *arSession;

// State
@property (nonatomic, assign) BOOL initialized;
@property (nonatomic, assign) int width;
@property (nonatomic, assign) int height;
// Perf-callback state
@property (nonatomic, assign) BOOL hasFiredFaceTracked;
// Whether the glasses + face occlusion meshes are explicitly hidden. Sticky
// across frames — cleared only by -showGlasses.
@property (nonatomic, assign) BOOL isHidden;

// Model configuration
@property (nonatomic, copy) NSString *modelUrl;

@end

@implementation VTORendererBridge

- (instancetype)initWithMetalView:(MTKView *)metalView {
    self = [super init];
    if (self) {
        _metalView = metalView;
        _metalDevice = metalView.device;
        _commandQueue = [_metalDevice newCommandQueue];
        _initialized = NO;
        _width = 0;
        _height = 0;
    }
    return self;
}

- (void)initializeWithModelUrl:(NSString *)modelUrl {
    _modelUrl = modelUrl;

    // Initialize Filament engine with Metal backend
    // In Filament 1.67.0, the Engine creates and manages its own Metal backend
    _engine = Engine::create(Engine::Backend::METAL);

    if (!_engine) {
        NSLog(@"%@: Failed to create Filament engine", TAG);
        return;
    }

    _renderer = _engine->createRenderer();

    // Works around a Filament iOS Metal bug: with
    // `setPostProcessingEnabled(true)`, the intermediate HDR color
    // buffer is pool-allocated and not initialized at frame start. The
    // camera-background fullscreen quad fails to cover the rightmost
    // column on Metal, so recycled-texture memory bleeds through as a
    // white vertical band when geometry approaches that edge. Forcing
    // a per-frame clear masks the uncovered pixels.
    Renderer::ClearOptions co{};
    co.clear = true;
    co.clearColor = {0, 0, 0, 1};

    _renderer->setClearOptions(co);
    
    _scene = _engine->createScene();
    _filamentView = _engine->createView();

    // Create camera
    utils::EntityManager &em = utils::EntityManager::get();
    _cameraEntity = em.create();
    _camera = _engine->createCamera(_cameraEntity);

    // Pin Filament's photographic exposure model to a known reference
    // (sceneview's defaults: f/16, 1/125s, ISO 100). Locking the EV here
    // means our directional-light intensity calibration in
    // EnvironmentLightingRenderer reads the same lux values regardless
    // of any scene-driven exposure changes.
    _camera->setExposure(16.0f, 1.0f / 125.0f, 100.0f);

    _filamentView->setCamera(_camera);
    _filamentView->setScene(_scene);

    // Configure view
    _filamentView->setPostProcessingEnabled(true);

    // Filmic tone mapper — compresses bright highlights more than the
    // default ACES, taming the shine on the metallic glasses frame off
    // the IBL. The camera material inverts this exact curve + the
    // piecewise sRGB encoder so the feed round-trips unchanged.
    {
        FilmicToneMapper filmic;
        _colorGrading = ColorGrading::Builder()
            .toneMapper(&filmic)
            .build(*_engine);
        _filamentView->setColorGrading(_colorGrading);
    }

    // SSAO — grounds the glasses to the face (contact shadow at the
    // temple-skin boundary, around the nose-pad). 0.3m radius is right
    // for face-scale geometry; MEDIUM is the cheap-but-visible knee.
    // See ADR 0012.
    {
        View::AmbientOcclusionOptions ssao;
        ssao.enabled = true;
        ssao.radius = 0.3f;
        ssao.intensity = 1.0f;
        ssao.quality = View::QualityLevel::MEDIUM;
        _filamentView->setAmbientOcclusionOptions(ssao);
    }

    // TAA for edge AA — temporal accumulation smooths the metallic
    // frame silhouette as the head moves and damps sub-pixel jitter
    // FXAA can't reach. See ADR 0012.
    {
        View::TemporalAntiAliasingOptions taa;
        taa.enabled = true;
        _filamentView->setTemporalAntiAliasingOptions(taa);
    }

    // Create swap chain from Metal layer
    CAMetalLayer *metalLayer = (CAMetalLayer *)_metalView.layer;
    metalLayer.opaque = YES;  // We don't need transparency - we render full camera background
    _swapChain = _engine->createSwapChain((__bridge void *)metalLayer);

    // Setup environment lighting
    _environmentLightingRenderer = [[EnvironmentLightingRenderer alloc] init];
    [_environmentLightingRenderer setupWithEngine:_engine scene:_scene];

    // Setup camera background
    _cameraTextureRenderer = [[CameraTextureRenderer alloc] init];
    [_cameraTextureRenderer setupWithEngine:_engine scene:_scene];

    // Shared face-mesh topology — closes the eye/mouth holes by detecting
    // boundary loops in the (static) ARKit triangle list and emitting
    // centroid-fan triangulations.
    _faceMeshTopology = [[FaceMeshTopology alloc] init];

    // Setup face occlusion (renders face mesh to depth buffer for occlusion)
    _faceOcclusionRenderer = [[FaceOcclusionRenderer alloc] init];
    [_faceOcclusionRenderer setupWithEngine:_engine scene:_scene];

    // Setup glasses renderer
    _glassesRenderer = [[GlassesRenderer alloc] init];
    __weak __typeof__(self) weakSelf = self;
    _glassesRenderer.onModelLoaded = ^(NSString *url) {
        if (weakSelf.onModelLoaded) {
            weakSelf.onModelLoaded(url);
        }
    };
    _glassesRenderer.onGlassesDisplayed = ^(NSString *url) {
        if (weakSelf.onGlassesDisplayed) {
            weakSelf.onGlassesDisplayed(url);
        }
    };
    [_glassesRenderer setupWithEngine:_engine scene:_scene modelUrl:modelUrl];

    // Setup debug renderer
    _debugRenderer = [[DebugRenderer alloc] init];
    [_debugRenderer setupWithEngine:_engine scene:_scene];

    _initialized = YES;
    NSLog(@"%@: Filament renderer initialized", TAG);
}

- (void)setViewportSizeWithWidth:(int)width height:(int)height {
    if (width <= 0 || height <= 0) return;

    _width = width;
    _height = height;

    _filamentView->setViewport({0, 0, (uint32_t)width, (uint32_t)height});
    [_cameraTextureRenderer setViewportSize:CGSizeMake(width, height)];
}

- (void)updateCameraProjectionWithFrame:(ARFrame *)frame {
    if (_width <= 0 || _height <= 0 || !frame) return;

    CGSize viewportSize = CGSizeMake(_width, _height);

    // Get ARKit camera matrices
    simd_float4x4 viewMatrix = [frame.camera viewMatrixForOrientation:UIInterfaceOrientationPortrait];
    simd_float4x4 projMatrix = [frame.camera projectionMatrixForOrientation:UIInterfaceOrientationPortrait
                                                               viewportSize:viewportSize
                                                                      zNear:0.01
                                                                       zFar:100.0];

    // ARKit viewMatrix transforms world -> camera space
    // Filament camera needs model matrix (camera -> world), which is inverse(viewMatrix)
    simd_float4x4 cameraModelMatrix = simd_inverse(viewMatrix);

    // Convert simd matrices to Filament matrices
    // Note: setCustomProjection requires mat4 (double), setModelMatrix requires mat4f (float)
    filament::math::mat4f filamentModel;
    filament::math::mat4 filamentProj;  // double precision for projection

    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            filamentModel[col][row] = cameraModelMatrix.columns[col][row];
            filamentProj[col][row] = (double)projMatrix.columns[col][row];
        }
    }

    // Set custom projection and camera model matrix
    _camera->setCustomProjection(filamentProj, 0.01, 100.0);
    _camera->setModelMatrix(filamentModel);
}

- (void)resume {
    // Nothing specific needed for resume
}

- (void)pause {
    // Nothing specific needed for pause
}

- (void)switchModelWithUrl:(NSString *)modelUrl {
    _modelUrl = modelUrl;
    [_glassesRenderer switchModelWithUrl:modelUrl];
}

- (void)hideGlasses {
    _isHidden = YES;
    [_glassesRenderer hide];
    [_faceOcclusionRenderer hide];
}

- (void)showGlasses {
    // Clearing the flag is enough — the next frame with a tracked face will
    // run the glasses + occlusion updates, which overwrite the hide-transform
    // we set in -hideGlasses.
    _isHidden = NO;
}

- (void)setFaceMeshOcclusion:(BOOL)enabled {
    if (_initialized) {
        [_faceOcclusionRenderer setFaceMeshOcclusion:enabled];
    }
}

- (void)setBackPlaneOcclusion:(BOOL)enabled {
    if (_initialized) {
        [_faceOcclusionRenderer setBackPlaneOcclusion:enabled];
    }
}

- (void)setForwardOffset:(float)offset {
    if (_initialized) {
        [_glassesRenderer setForwardOffset:offset];
    }
}

- (void)setDebug:(BOOL)enabled {
    if (_initialized) {
        [_debugRenderer setEnabled:enabled];
    }
}

- (void)renderWithFrame:(ARFrame *)frame faces:(NSArray<ARFaceAnchor *> *)faces {
    if (!_initialized) return;

    // Update Filament camera with ARKit camera matrices
    [self updateCameraProjectionWithFrame:frame];

    // Update camera texture and background transform
    [_cameraTextureRenderer updateTextureWithFrame:frame];
    [_cameraTextureRenderer updateTransformWithFrame:frame];

    // Per-frame directional ("sun") light tracks the AR light estimate.
    // The IBL itself stays static — see ADR 0011 — so specular reflections
    // are platform-equivalent; the directional light adds scene-tracking
    // shading on top. In face-tracking sessions ARKit returns an
    // ARDirectionalLightEstimate via frame.lightEstimate (the light
    // estimate lives on the frame, not on the face anchor).
    if (frame.lightEstimate) {
        [_environmentLightingRenderer updateDirectionalFromARKitWithLightEstimate:frame.lightEstimate];
    }

    // Update face occlusion and glasses transform if face detected
    if (faces.count > 0) {
        if (!_hasFiredFaceTracked) {
            _hasFiredFaceTracked = YES;
            if (self.onFaceTracked) self.onFaceTracked();
        }
        // Update the shared topology once per frame; both renderers below
        // read from it instead of from face.geometry directly.
        [_faceMeshTopology updateWithFace:faces[0]];

        if (!_isHidden) {
            [_faceOcclusionRenderer updateWithFace:faces[0] topology:_faceMeshTopology];
            [_glassesRenderer updateTransformWithFace:faces[0] frame:frame];
            [_glassesRenderer updateTempleArticulationWithEarHalfWidth:_faceOcclusionRenderer.earHalfWidth];
        }
        [_debugRenderer updateWithFace:faces[0]
                              topology:_faceMeshTopology
                         showBackPlane:_faceOcclusionRenderer.isBackPlaneVisible];
    } else {
        [_faceOcclusionRenderer hide];
        [_glassesRenderer hide];
        [_debugRenderer hide];
    }

    // Render frame with Filament
    if (_renderer->beginFrame(_swapChain)) {
        _renderer->render(_filamentView);
        _renderer->endFrame();
    }
}

- (void)setARSession:(ARSession *)session {
    _arSession = session;
}

- (void)destroy {
    if (!_engine) return;

    [_debugRenderer destroy];
    [_glassesRenderer destroy];
    [_faceOcclusionRenderer destroy];
    [_cameraTextureRenderer destroy];
    [_environmentLightingRenderer destroy];

    if (_camera) {
        _engine->destroyCameraComponent(_cameraEntity);
        utils::EntityManager::get().destroy(_cameraEntity);
    }

    if (_swapChain) {
        _engine->destroy(_swapChain);
    }
    if (_filamentView) {
        _engine->destroy(_filamentView);
    }
    if (_colorGrading) {
        _engine->destroy(_colorGrading);
    }
    if (_scene) {
        _engine->destroy(_scene);
    }
    if (_renderer) {
        _engine->destroy(_renderer);
    }

    // Flush and wait for any pending GPU command buffers to complete before
    // destroying the engine. Without this, in-flight Metal command buffers
    // still hold strong refs to textures/buffers we just released, and iOS
    // can't reclaim the memory — observed as a flood of `Received memory
    // warning` and eventual jetsam kill a few seconds after leaving the VTO
    // screen.
    _engine->flushAndWait();

    _engine->destroy(&_engine);
    _engine = nullptr;
    _initialized = NO;
}

@end
