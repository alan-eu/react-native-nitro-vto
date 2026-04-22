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

/// Create a hide matrix (translates far on Z axis)
+ (filament::math::mat4f)createHideMatrix;

@end

NS_ASSUME_NONNULL_END
