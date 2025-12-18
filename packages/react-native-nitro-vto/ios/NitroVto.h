#ifdef __OBJC__
#import <UIKit/UIKit.h>
#else
#ifndef FOUNDATION_EXPORT
#if defined(__cplusplus)
#define FOUNDATION_EXPORT extern "C"
#else
#define FOUNDATION_EXPORT extern
#endif
#endif
#endif

// Objective-C++ bridges for VTO rendering
#import "VTORendererBridge.h"
#import "CameraTextureRenderer.h"
#import "EnvironmentLightingRenderer.h"
#import "GlassesRenderer.h"
#import "KalmanFilter.h"
#import "LoaderUtils.h"
#import "MatrixUtils.h"

FOUNDATION_EXPORT double NitroVtoVersionNumber;
FOUNDATION_EXPORT const unsigned char NitroVtoVersionString[];

