#include "AndroidSurfaceBridge.hpp"

#include <android/native_window_jni.h>

namespace margelo::nitro::nitrovto::core::platform::android {

void* toNativeRenderTarget(JNIEnv* env, jobject surface) {
  if (env == nullptr || surface == nullptr) {
    return nullptr;
  }
  return ANativeWindow_fromSurface(env, surface);
}

} // namespace margelo::nitro::nitrovto::core::platform::android
