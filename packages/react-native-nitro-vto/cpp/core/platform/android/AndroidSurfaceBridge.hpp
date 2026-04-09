#pragma once

#include <jni.h>

namespace margelo::nitro::nitrovto::core::platform::android {

void* toNativeRenderTarget(JNIEnv* env, jobject surface);

} // namespace margelo::nitro::nitrovto::core::platform::android
