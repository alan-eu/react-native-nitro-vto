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

/// Initialize with Metal view
- (instancetype)initWithMetalView:(MTKView *)metalView;

/// Initialize the renderer with model URL and width
- (void)initializeWithModelUrl:(NSString *)modelUrl widthMeters:(float)widthMeters;

/// Set viewport size
- (void)setViewportSizeWithWidth:(int)width height:(int)height;

/// Resume rendering
- (void)resume;

/// Pause rendering
- (void)pause;

/// Switch to a different model
- (void)switchModelWithUrl:(NSString *)modelUrl widthMeters:(float)widthMeters;

/// Reset the AR session
- (void)resetSession;

/// Render a frame with ARKit data
- (void)renderWithFrame:(ARFrame *)frame faces:(NSArray<ARFaceAnchor *> *)faces;

/// Set the AR session reference
- (void)setARSession:(ARSession *)session;

/// Cleanup and destroy resources
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
