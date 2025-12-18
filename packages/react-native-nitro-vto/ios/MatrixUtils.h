#import <Foundation/Foundation.h>
#import <simd/simd.h>

#include <math/mat4.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Utility functions for matrix operations.
 */
@interface MatrixUtils : NSObject

/// Convert quaternion to 4x4 rotation matrix
+ (simd_float4x4)quaternionToMatrix:(simd_quatf)q;

/// Project a world position to NDC (Normalized Device Coordinates)
+ (simd_float2)projectToNdcWithWorldPos:(simd_float3)worldPos
                             viewMatrix:(simd_float4x4)viewMatrix
                             projMatrix:(simd_float4x4)projMatrix;

/// Get depth (Z distance) from camera in view space
+ (float)getDepthInViewSpaceWithWorldPos:(simd_float3)worldPos
                              viewMatrix:(simd_float4x4)viewMatrix;

/// Create a hide matrix (translates far on Z axis)
+ (filament::math::mat4f)createHideMatrix;

@end

NS_ASSUME_NONNULL_END
