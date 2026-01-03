#import "VTORendererBridge.h"

#include <filament/Engine.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/View.h>
#include <filament/Camera.h>
#include <filament/SwapChain.h>
#include <filament/Viewport.h>
#include <utils/EntityManager.h>
#include <math/mat4.h>

#import "CameraTextureRenderer.h"
#import "EnvironmentLightingRenderer.h"
#import "FaceOcclusionRenderer.h"
#import "GlassesRenderer.h"

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

// Sub-renderers
@property (nonatomic, strong) CameraTextureRenderer *cameraTextureRenderer;
@property (nonatomic, strong) EnvironmentLightingRenderer *environmentLightingRenderer;
@property (nonatomic, strong) FaceOcclusionRenderer *faceOcclusionRenderer;
@property (nonatomic, strong) GlassesRenderer *glassesRenderer;

// ARKit
@property (nonatomic, weak) ARSession *arSession;

// State
@property (nonatomic, assign) BOOL initialized;
@property (nonatomic, assign) int width;
@property (nonatomic, assign) int height;

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
    _scene = _engine->createScene();
    _filamentView = _engine->createView();

    // Create camera
    utils::EntityManager &em = utils::EntityManager::get();
    _cameraEntity = em.create();
    _camera = _engine->createCamera(_cameraEntity);

    _filamentView->setCamera(_camera);
    _filamentView->setScene(_scene);

    // Configure view
    _filamentView->setPostProcessingEnabled(false);

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
    [_glassesRenderer setupWithEngine:_engine scene:_scene modelUrl:modelUrl];

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

- (void)resetSession {
    [_glassesRenderer hide];
}

- (void)renderWithFrame:(ARFrame *)frame faces:(NSArray<ARFaceAnchor *> *)faces {
    if (!_initialized) return;

    // Update Filament camera with ARKit camera matrices
    [self updateCameraProjectionWithFrame:frame];

    // Update camera texture and background transform
    [_cameraTextureRenderer updateTextureWithFrame:frame];
    [_cameraTextureRenderer updateTransformWithFrame:frame];

    // Update lighting from ARKit light estimation
    if (frame.lightEstimate) {
        [_environmentLightingRenderer updateFromARKitWithLightEstimate:frame.lightEstimate];
    }

    // Update face occlusion and glasses transform if face detected
    if (faces.count > 0) {
        [_faceOcclusionRenderer updateWithFace:faces[0]];
        [_glassesRenderer updateTransformWithFace:faces[0] frame:frame];
    } else {
        [_faceOcclusionRenderer hide];
        [_glassesRenderer hide];
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
    if (_scene) {
        _engine->destroy(_scene);
    }
    if (_renderer) {
        _engine->destroy(_renderer);
    }

    _engine->destroy(&_engine);
    _engine = nullptr;
    _initialized = NO;
}

@end
