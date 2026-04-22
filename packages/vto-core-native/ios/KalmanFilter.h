#import <Foundation/Foundation.h>
#import <simd/simd.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Simple 1D Kalman filter for smoothing noisy measurements.
 */
@interface KalmanFilter : NSObject

- (instancetype)initWithProcessNoise:(float)processNoise
                    measurementNoise:(float)measurementNoise
                     initialEstimate:(float)initialEstimate;

/// Update the filter with a new measurement and return the filtered estimate
- (float)updateWithValue:(float)measurement;

/// Reset the filter to a new initial state
- (void)reset;

/// Get current estimate without updating
- (float)getEstimate;

@end

/**
 * Kalman filter for 3D points (e.g., world coordinates).
 */
@interface KalmanFilter3D : NSObject

- (instancetype)initWithProcessNoise:(float)processNoise
                    measurementNoise:(float)measurementNoise;

- (simd_float3)updateWithX:(float)x y:(float)y z:(float)z;

- (void)reset;

@end

/**
 * Kalman filter for quaternions (rotation smoothing).
 */
@interface KalmanFilterQuaternion : NSObject

- (instancetype)initWithProcessNoise:(float)processNoise
                    measurementNoise:(float)measurementNoise;

- (simd_quatf)updateWithQuaternion:(simd_quatf)q;

- (void)reset;

@end

NS_ASSUME_NONNULL_END
