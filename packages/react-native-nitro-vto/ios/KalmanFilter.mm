#import "KalmanFilter.h"

#pragma mark - KalmanFilter

@interface KalmanFilter ()

@property (nonatomic, assign) float processNoise;
@property (nonatomic, assign) float measurementNoise;
@property (nonatomic, assign) float estimate;
@property (nonatomic, assign) float errorCovariance;
@property (nonatomic, assign) float initialEstimate;

@end

@implementation KalmanFilter

- (instancetype)initWithProcessNoise:(float)processNoise
                    measurementNoise:(float)measurementNoise
                     initialEstimate:(float)initialEstimate {
    self = [super init];
    if (self) {
        _processNoise = processNoise;
        _measurementNoise = measurementNoise;
        _estimate = initialEstimate;
        _initialEstimate = initialEstimate;
        _errorCovariance = 1.0f;
    }
    return self;
}

- (float)updateWithValue:(float)measurement {
    // Prediction step
    _errorCovariance += _processNoise;

    // Update step
    float kalmanGain = _errorCovariance / (_errorCovariance + _measurementNoise);
    _estimate += kalmanGain * (measurement - _estimate);
    _errorCovariance *= (1 - kalmanGain);

    return _estimate;
}

- (void)reset {
    _estimate = _initialEstimate;
    _errorCovariance = 1.0f;
}

- (float)getEstimate {
    return _estimate;
}

@end

#pragma mark - KalmanFilter3D

@interface KalmanFilter3D ()

@property (nonatomic, strong) KalmanFilter *filterX;
@property (nonatomic, strong) KalmanFilter *filterY;
@property (nonatomic, strong) KalmanFilter *filterZ;

@end

@implementation KalmanFilter3D

- (instancetype)initWithProcessNoise:(float)processNoise
                    measurementNoise:(float)measurementNoise {
    self = [super init];
    if (self) {
        _filterX = [[KalmanFilter alloc] initWithProcessNoise:processNoise
                                             measurementNoise:measurementNoise
                                              initialEstimate:0.0f];
        _filterY = [[KalmanFilter alloc] initWithProcessNoise:processNoise
                                             measurementNoise:measurementNoise
                                              initialEstimate:0.0f];
        _filterZ = [[KalmanFilter alloc] initWithProcessNoise:processNoise
                                             measurementNoise:measurementNoise
                                              initialEstimate:0.0f];
    }
    return self;
}

- (simd_float3)updateWithX:(float)x y:(float)y z:(float)z {
    return simd_make_float3([_filterX updateWithValue:x],
                            [_filterY updateWithValue:y],
                            [_filterZ updateWithValue:z]);
}

- (void)reset {
    [_filterX reset];
    [_filterY reset];
    [_filterZ reset];
}

@end

#pragma mark - KalmanFilterQuaternion

@interface KalmanFilterQuaternion ()

@property (nonatomic, strong) KalmanFilter *filterX;
@property (nonatomic, strong) KalmanFilter *filterY;
@property (nonatomic, strong) KalmanFilter *filterZ;
@property (nonatomic, strong) KalmanFilter *filterW;

@end

@implementation KalmanFilterQuaternion

- (instancetype)initWithProcessNoise:(float)processNoise
                    measurementNoise:(float)measurementNoise {
    self = [super init];
    if (self) {
        _filterX = [[KalmanFilter alloc] initWithProcessNoise:processNoise
                                             measurementNoise:measurementNoise
                                              initialEstimate:0.0f];
        _filterY = [[KalmanFilter alloc] initWithProcessNoise:processNoise
                                             measurementNoise:measurementNoise
                                              initialEstimate:0.0f];
        _filterZ = [[KalmanFilter alloc] initWithProcessNoise:processNoise
                                             measurementNoise:measurementNoise
                                              initialEstimate:0.0f];
        _filterW = [[KalmanFilter alloc] initWithProcessNoise:processNoise
                                             measurementNoise:measurementNoise
                                              initialEstimate:1.0f];
    }
    return self;
}

- (simd_quatf)updateWithQuaternion:(simd_quatf)q {
    simd_float4 filtered = simd_make_float4([_filterX updateWithValue:q.vector.x],
                                             [_filterY updateWithValue:q.vector.y],
                                             [_filterZ updateWithValue:q.vector.z],
                                             [_filterW updateWithValue:q.vector.w]);

    // Normalize to ensure valid quaternion
    float len = simd_length(filtered);
    if (len > 0.0001f) {
        filtered /= len;
    }

    return simd_quaternion(filtered);
}

- (void)reset {
    [_filterX reset];
    [_filterY reset];
    [_filterZ reset];
    [_filterW reset];
}

@end
