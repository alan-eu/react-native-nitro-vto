#import "MatrixUtils.h"

@implementation MatrixUtils

+ (simd_float4x4)quaternionToMatrix:(simd_quatf)q {
    float qx = q.vector.x;
    float qy = q.vector.y;
    float qz = q.vector.z;
    float qw = q.vector.w;

    simd_float4x4 result;
    result.columns[0] = simd_make_float4(1 - 2*qy*qy - 2*qz*qz, 2*qx*qy + 2*qz*qw, 2*qx*qz - 2*qy*qw, 0);
    result.columns[1] = simd_make_float4(2*qx*qy - 2*qz*qw, 1 - 2*qx*qx - 2*qz*qz, 2*qy*qz + 2*qx*qw, 0);
    result.columns[2] = simd_make_float4(2*qx*qz + 2*qy*qw, 2*qy*qz - 2*qx*qw, 1 - 2*qx*qx - 2*qy*qy, 0);
    result.columns[3] = simd_make_float4(0, 0, 0, 1);

    return result;
}

+ (simd_float2)projectToNdcWithWorldPos:(simd_float3)worldPos
                             viewMatrix:(simd_float4x4)viewMatrix
                             projMatrix:(simd_float4x4)projMatrix {
    simd_float4 pos4 = simd_make_float4(worldPos.x, worldPos.y, worldPos.z, 1.0f);
    simd_float4 viewPos = simd_mul(viewMatrix, pos4);
    simd_float4 clipPos = simd_mul(projMatrix, viewPos);

    return simd_make_float2(clipPos.x / clipPos.w, clipPos.y / clipPos.w);
}

+ (float)getDepthInViewSpaceWithWorldPos:(simd_float3)worldPos
                              viewMatrix:(simd_float4x4)viewMatrix {
    simd_float4 pos4 = simd_make_float4(worldPos.x, worldPos.y, worldPos.z, 1.0f);
    simd_float4 viewPos = simd_mul(viewMatrix, pos4);
    return fabsf(viewPos.z);
}

+ (filament::math::mat4f)createHideMatrix {
    filament::math::mat4f hideMatrix;
    // Set identity
    for (int i = 0; i < 4; i++) {
        for (int j = 0; j < 4; j++) {
            hideMatrix[i][j] = (i == j) ? 1.0f : 0.0f;
        }
    }
    // Translate far on Z axis
    hideMatrix[3][2] = -1000.0f;
    return hideMatrix;
}

@end
