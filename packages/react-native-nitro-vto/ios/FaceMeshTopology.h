#import <Foundation/Foundation.h>
#import <ARKit/ARKit.h>
#import <simd/simd.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Single source of truth for the face mesh's depth-write geometry, owned
 * by VTORendererBridge and consumed by both FaceOcclusionRenderer and
 * DebugRenderer.
 *
 * ARKit's face mesh has open holes at the eyes and the mouth (plus the
 * head's outer perimeter). Without filling, glasses behind the face leak
 * through the eye/mouth holes. We close them ourselves: at first frame,
 * detect boundary loops from the (static) triangle list and discard the
 * largest one (head perimeter); per frame, emit a centroid-fan
 * triangulation for each remaining loop on top of the raw mesh.
 *
 * Per-frame: call `-updateWithFace:` once. The vertex / index pointers
 * stay valid until the next call.
 */
@interface FaceMeshTopology : NSObject

- (instancetype)init;

/// Update the topology from the latest face anchor. The first call also
/// performs a one-time analysis of the boundary loops.
- (void)updateWithFace:(ARFaceAnchor *)face;

/// Pointers valid until the next -updateWithFace: call. Both buffers are
/// owned by the receiver.
@property (nonatomic, readonly, nullable) const simd_float3 *vertices;
@property (nonatomic, readonly, nullable) const int16_t *indices;
@property (nonatomic, readonly) NSUInteger vertexCount;
@property (nonatomic, readonly) NSUInteger indexCount;

@end

NS_ASSUME_NONNULL_END
