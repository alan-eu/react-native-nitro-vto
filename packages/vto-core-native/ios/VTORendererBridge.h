#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <MetalKit/MetalKit.h>
#import <ARKit/ARKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Objective-C bridge for the Filament VTO Renderer.
 * Provides a Swift-accessible interface to the C++ Filament rendering code.
 */
@interface VTORendererBridge : NSObject

/// Callback for when model loading completes
@property (nonatomic, copy, nullable) void (^onModelLoaded)(NSString *url);

/// Callback invoked once per session the first time ARKit reports a tracked face.
@property (nonatomic, copy, nullable) void (^onFaceTracked)(void);

/// Callback invoked once per loaded model the first time that model's transform
/// is driven by a valid face pose (i.e. first frame the glasses become visible).
@property (nonatomic, copy, nullable) void (^onGlassesDisplayed)(NSString *url);

/// Initialize with Metal view
- (instancetype)initWithMetalView:(MTKView *)metalView;

/// Initialize the renderer with model URL
- (void)initializeWithModelUrl:(NSString *)modelUrl;

/// Set viewport size
- (void)setViewportSizeWithWidth:(int)width height:(int)height;

/// Resume rendering
- (void)resume;

/// Pause rendering
- (void)pause;

/// Switch to a different model
- (void)switchModelWithUrl:(NSString *)modelUrl;

/// Hide the glasses and face occlusion meshes. Sticky: stays hidden across
/// frames until -showGlasses is called. The AR session keeps running and face
/// tracking state is untouched.
- (void)hideGlasses;

/// Show the glasses and face occlusion meshes again after -hideGlasses.
/// No-op if they weren't hidden.
- (void)showGlasses;

/// Set forward offset for glasses positioning (in meters)
- (void)setForwardOffset:(float)offset;

/// Set debug mode enabled
- (void)setDebug:(BOOL)enabled;

/// Mark the loaded model as a clip-on / solar frame (tinted-lens treatment)
- (void)setIsClipOn:(BOOL)isClipOn;

/// Render a frame with ARKit data
- (void)renderWithFrame:(ARFrame *)frame faces:(NSArray<ARFaceAnchor *> *)faces;

/// Set the AR session reference
- (void)setARSession:(ARSession *)session;

/// HARNESS (dev/simulator only): render the scene with a static camera-feed
/// pattern, a fixed perspective camera, and the glasses at a fixed pose — no
/// AR session required. Used to inspect render order in the simulator.
- (void)renderStaticPreview;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
