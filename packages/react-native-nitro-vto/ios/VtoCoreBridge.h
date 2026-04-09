#import <Foundation/Foundation.h>
#import <CoreVideo/CoreVideo.h>
#import <QuartzCore/CAMetalLayer.h>
#import <stdint.h>

NS_ASSUME_NONNULL_BEGIN

@interface VtoCoreBridge : NSObject

- (BOOL)initializeWithMetalLayer:(CAMetalLayer *)metalLayer
               faceMeshOcclusion:(BOOL)faceMeshOcclusion
              backPlaneOcclusion:(BOOL)backPlaneOcclusion
             forwardOffsetMeters:(float)forwardOffsetMeters
                           debug:(BOOL)debug
             noseBridgeLeftIndex:(int)noseBridgeLeftIndex
            noseBridgeRightIndex:(int)noseBridgeRightIndex
    NS_SWIFT_NAME(initialize(withMetalLayer:faceMeshOcclusion:backPlaneOcclusion:forwardOffsetMeters:debug:noseBridgeLeftIndex:noseBridgeRightIndex:));

- (void)resizeWithWidth:(int)width
                 height:(int)height
    NS_SWIFT_NAME(resize(width:height:));

- (void)updateConfigWithFaceMeshOcclusion:(BOOL)faceMeshOcclusion
                        backPlaneOcclusion:(BOOL)backPlaneOcclusion
                       forwardOffsetMeters:(float)forwardOffsetMeters
                                     debug:(BOOL)debug
                        noseBridgeLeftIndex:(int)noseBridgeLeftIndex
                       noseBridgeRightIndex:(int)noseBridgeRightIndex
    NS_SWIFT_NAME(updateConfig(faceMeshOcclusion:backPlaneOcclusion:forwardOffsetMeters:debug:noseBridgeLeftIndex:noseBridgeRightIndex:));

- (BOOL)setMaterialPackageWithKind:(int)kind
                              bytes:(NSData *)bytes
    NS_SWIFT_NAME(setMaterialPackage(kind:bytes:));
- (BOOL)setEnvironmentIblKtx:(NSData *)bytes NS_SWIFT_NAME(setEnvironmentIblKtx(_:));
- (BOOL)setEnvironmentSkyboxKtx:(NSData *)bytes NS_SWIFT_NAME(setEnvironmentSkyboxKtx(_:));
- (void)setEnvironmentSphericalHarmonics:(const float *)sh27 NS_SWIFT_NAME(setEnvironmentSphericalHarmonics(_:));

- (BOOL)setModelFromBytes:(NSData *)bytes
                  sourceId:(NSString *)sourceId
    NS_SWIFT_NAME(setModelFromBytes(_:sourceId:));

- (void)resetSession;
- (void)submitFrameWithViewportWidth:(int)viewportWidth
                       viewportHeight:(int)viewportHeight
                     hasCameraMatrices:(BOOL)hasCameraMatrices
                            projection:(const float * _Nullable)projection16
                                 model:(const float * _Nullable)model16
                         hasCameraFeed:(BOOL)hasCameraFeed
                            pixelBuffer:(CVPixelBufferRef _Nullable)pixelBuffer
                           uvTransform:(const float * _Nullable)uvTransform3x3
                                hasFace:(BOOL)hasFace
                               vertices:(const float * _Nullable)vertices
                            vertexCount:(int)vertexCount
                                indices:(const uint16_t * _Nullable)indices
                             indexCount:(int)indexCount
                            faceToWorld:(const float * _Nullable)faceToWorld16
                      rotationQuaternion:(const float * _Nullable)rotationQuaternion4
                       hasLightEstimate:(BOOL)hasLightEstimate
                               lightValid:(BOOL)lightValid
                          linearIntensity:(float)linearIntensity
    NS_SWIFT_NAME(submitFrame(viewportWidth:viewportHeight:hasCameraMatrices:projection:model:hasCameraFeed:pixelBuffer:uvTransform:hasFace:vertices:vertexCount:indices:indexCount:faceToWorld:rotationQuaternion:hasLightEstimate:lightValid:linearIntensity:));

- (void)render;
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
