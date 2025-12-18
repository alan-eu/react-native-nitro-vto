#import "VTORendererBridge.h"

#include <filament/Engine.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/View.h>
#include <filament/Camera.h>
#include <filament/SwapChain.h>
#include <filament/Viewport.h>
#include <utils/EntityManager.h>

#import "CameraTextureRenderer.h"
#import "EnvironmentLightingRenderer.h"
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
@property (nonatomic, strong) GlassesRenderer *glassesRenderer;

// ARKit
@property (nonatomic, weak) ARSession *arSession;

// State
@property (nonatomic, assign) BOOL initialized;
@property (nonatomic, assign) int width;
@property (nonatomic, assign) int height;

// Model configuration
@property (nonatomic, copy) NSString *modelUrl;
@property (nonatomic, assign) float modelWidthMeters;

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

- (void)initializeWithModelUrl:(NSString *)modelUrl widthMeters:(float)widthMeters {
    _modelUrl = modelUrl;
    _modelWidthMeters = widthMeters;

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
    
    // Configure renderer for AR - don't clear color buffer (camera texture is the background)
    Renderer::ClearOptions clearOptions = {};
    clearOptions.clearColor = {0.0f, 0.0f, 0.0f, 0.0f};  // Transparent black
    clearOptions.clear = false;  // Don't clear color buffer - camera texture fills it
    clearOptions.discard = true;  // Discard previous frame content
    _renderer->setClearOptions(clearOptions);

    // Create camera
    utils::EntityManager &em = utils::EntityManager::get();
    _cameraEntity = em.create();
    _camera = _engine->createCamera(_cameraEntity);

    _filamentView->setCamera(_camera);
    _filamentView->setScene(_scene);

    // Configure view for AR rendering
    _filamentView->setPostProcessingEnabled(false);

    // Create swap chain from Metal layer
    CAMetalLayer *metalLayer = (CAMetalLayer *)_metalView.layer;
    _swapChain = _engine->createSwapChain((__bridge void *)metalLayer);

    // Setup environment lighting
    _environmentLightingRenderer = [[EnvironmentLightingRenderer alloc] init];
    [_environmentLightingRenderer setupWithEngine:_engine scene:_scene];

    // Setup camera background
    _cameraTextureRenderer = [[CameraTextureRenderer alloc] init];
    [_cameraTextureRenderer setupWithEngine:_engine scene:_scene];

    // Setup glasses renderer
    _glassesRenderer = [[GlassesRenderer alloc] init];
    __weak __typeof__(self) weakSelf = self;
    _glassesRenderer.onModelLoaded = ^(NSString *url) {
        if (weakSelf.onModelLoaded) {
            weakSelf.onModelLoaded(url);
        }
    };
    [_glassesRenderer setupWithEngine:_engine scene:_scene modelUrl:modelUrl widthMeters:widthMeters];

    _initialized = YES;
    NSLog(@"%@: Filament renderer initialized", TAG);
}

- (void)setViewportSizeWithWidth:(int)width height:(int)height {
    if (width <= 0 || height <= 0) return;
    
    // Skip if size hasn't changed
    if (_width == width && _height == height) return;

    _width = width;
    _height = height;

    // Update Filament viewport
    _filamentView->setViewport({0, 0, (uint32_t)width, (uint32_t)height});
    [self updateCameraProjection];
    
    // Update glasses renderer viewport
    [_glassesRenderer setViewportSizeWithWidth:width height:height];
    
    // Note: Swap chain doesn't need to be recreated for size changes
    // as it's bound to the CAMetalLayer which handles resizing automatically
}

- (void)updateCameraProjection {
    if (_width <= 0 || _height <= 0) return;

    _camera->setProjection(Camera::Projection::ORTHO, -1.0, 1.0, -1.0, 1.0, -1.0, 1.15);
}

- (void)resume {
    // Nothing specific needed for resume
}

- (void)pause {
    // Nothing specific needed for pause
}

- (void)switchModelWithUrl:(NSString *)modelUrl widthMeters:(float)widthMeters {
    _modelUrl = modelUrl;
    _modelWidthMeters = widthMeters;
    [_glassesRenderer switchModelWithUrl:modelUrl widthMeters:widthMeters];
}

- (void)resetSession {
    [_cameraTextureRenderer resetUvTransform];
    [_glassesRenderer hide];
}

- (void)renderWithFrame:(ARFrame *)frame faces:(NSArray<ARFaceAnchor *> *)faces {
    if (!_initialized || !_renderer || !_swapChain || !_filamentView) return;

    // Update camera texture from ARKit frame
    [_cameraTextureRenderer updateTextureWithFrame:frame];

    // Update lighting from ARKit light estimation
    if (frame.lightEstimate) {
        [_environmentLightingRenderer updateFromARKitWithLightEstimate:frame.lightEstimate];
    }

    // Update glasses transform if face detected
    if (faces.count > 0) {
        [_glassesRenderer updateTransformWithFace:faces[0] frame:frame];
    } else {
        [_glassesRenderer hide];
    }

    // Render frame with Filament
    // beginFrame returns false if no drawable is available (e.g., app is backgrounded)
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
