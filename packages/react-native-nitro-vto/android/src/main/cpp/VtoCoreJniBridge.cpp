#include <jni.h>

#include <string>
#include <vector>

#include "core/VtoRendererCore.hpp"
#include "core/platform/android/AndroidSurfaceBridge.hpp"

#if defined(__ANDROID__)
#include <android/native_window.h>
#endif

using margelo::nitro::nitrovto::core::FaceData;
using margelo::nitro::nitrovto::core::FrameInput;
using margelo::nitro::nitrovto::core::ModelData;
using margelo::nitro::nitrovto::core::VtoConfig;
using margelo::nitro::nitrovto::core::VtoRendererCore;
using margelo::nitro::nitrovto::core::render::MaterialKind;

namespace {

jobject gCameraStreamRef = nullptr;
VtoRendererCore* gCameraStreamOwner = nullptr;

VtoRendererCore* fromHandle(jlong handle) {
  return reinterpret_cast<VtoRendererCore*>(handle);
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeSetMaterialPackageCore(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint kind,
    jbyteArray bytes) {
  auto* core = fromHandle(handle);
  if (core == nullptr || bytes == nullptr) {
    return static_cast<jboolean>(false);
  }

  const jsize length = env->GetArrayLength(bytes);
  if (length <= 0) {
    return static_cast<jboolean>(false);
  }

  std::vector<std::uint8_t> package(static_cast<std::size_t>(length));
  env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(package.data()));
  return static_cast<jboolean>(core->setMaterialPackage(static_cast<MaterialKind>(kind), package.data(), package.size()));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeSetEnvironmentIblCore(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray bytes) {
  auto* core = fromHandle(handle);
  if (core == nullptr || bytes == nullptr) {
    return static_cast<jboolean>(false);
  }
  const jsize length = env->GetArrayLength(bytes);
  if (length <= 0) {
    return static_cast<jboolean>(false);
  }
  std::vector<std::uint8_t> buffer(static_cast<std::size_t>(length));
  env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(buffer.data()));
  return static_cast<jboolean>(core->setEnvironmentIblKtx(buffer.data(), buffer.size()));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeSetEnvironmentSkyboxCore(
    JNIEnv* env,
    jobject,
    jlong handle,
    jbyteArray bytes) {
  auto* core = fromHandle(handle);
  if (core == nullptr || bytes == nullptr) {
    return static_cast<jboolean>(false);
  }
  const jsize length = env->GetArrayLength(bytes);
  if (length <= 0) {
    return static_cast<jboolean>(false);
  }
  std::vector<std::uint8_t> buffer(static_cast<std::size_t>(length));
  env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(buffer.data()));
  return static_cast<jboolean>(core->setEnvironmentSkyboxKtx(buffer.data(), buffer.size()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeSetEnvironmentShCore(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray sh) {
  auto* core = fromHandle(handle);
  if (core == nullptr || sh == nullptr || env->GetArrayLength(sh) < 27) {
    return;
  }
  float shValues[27];
  env->GetFloatArrayRegion(sh, 0, 27, shValues);
  core->setEnvironmentSphericalHarmonics(shValues);
}

extern "C" JNIEXPORT void JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeMakeCameraContextCurrentCore(
    JNIEnv*, jobject, jlong handle) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return;
  }
  core->makeCameraContextCurrent();
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeGetCameraTextureIdsCore(
    JNIEnv* env,
    jobject,
    jlong handle) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return env->NewIntArray(0);
  }

  std::uint32_t ids[8] = {0};
  const auto count = core->getCameraTextureIds(ids, 8);
  jintArray result = env->NewIntArray(static_cast<jsize>(count));
  if (result == nullptr || count == 0) {
    return result;
  }

  std::vector<jint> jintIds(count);
  for (std::size_t i = 0; i < count; ++i) {
    jintIds[i] = static_cast<jint>(ids[i]);
  }
  env->SetIntArrayRegion(result, 0, static_cast<jsize>(count), jintIds.data());
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeSetCameraStreamCore(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surfaceTexture,
    jint width,
    jint height) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return static_cast<jboolean>(false);
  }

  jobject nextStreamRef = nullptr;
  if (surfaceTexture != nullptr) {
    nextStreamRef = env->NewGlobalRef(surfaceTexture);
    if (nextStreamRef == nullptr) {
      return static_cast<jboolean>(false);
    }
  }

  const int safeWidth = width > 0 ? static_cast<int>(width) : 1;
  const int safeHeight = height > 0 ? static_cast<int>(height) : 1;
  const bool ok = core->setCameraStream(nextStreamRef, safeWidth, safeHeight);

  if (!ok) {
    if (nextStreamRef != nullptr) {
      env->DeleteGlobalRef(nextStreamRef);
    }
    return static_cast<jboolean>(false);
  }

  if (gCameraStreamRef != nullptr) {
    env->DeleteGlobalRef(gCameraStreamRef);
    gCameraStreamRef = nullptr;
  }

  gCameraStreamRef = nextStreamRef;
  gCameraStreamOwner = nextStreamRef != nullptr ? core : nullptr;

  return static_cast<jboolean>(true);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeCreateCore(JNIEnv*, jobject) {
  auto* core = new VtoRendererCore();
  if (!core->initialize(nullptr, VtoConfig{})) {
    delete core;
    return 0;
  }
  return reinterpret_cast<jlong>(core);
}

extern "C" JNIEXPORT void JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeDestroyCore(JNIEnv* env, jobject, jlong handle) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return;
  }

  if (gCameraStreamOwner == core) {
    core->setCameraStream(nullptr, 1, 1);
    if (gCameraStreamRef != nullptr) {
      env->DeleteGlobalRef(gCameraStreamRef);
      gCameraStreamRef = nullptr;
    }
    gCameraStreamOwner = nullptr;
  }

  core->destroy();
  delete core;
}

extern "C" JNIEXPORT void JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeSetSurfaceCore(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return;
  }

  if (surface == nullptr) {
    core->setRenderTarget(nullptr);
    return;
  }

  auto* nativeWindow = margelo::nitro::nitrovto::core::platform::android::toNativeRenderTarget(env, surface);
  core->setRenderTarget(nativeWindow);
}

extern "C" JNIEXPORT void JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeResizeCore(
    JNIEnv*, jobject, jlong handle, jint width, jint height) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return;
  }
  core->resize(static_cast<int>(width), static_cast<int>(height));
}

extern "C" JNIEXPORT void JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeUpdateConfigCore(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean faceMeshOcclusion,
    jboolean backPlaneOcclusion,
    jfloat forwardOffset,
    jboolean debug,
    jint noseBridgeLeftIndex,
    jint noseBridgeRightIndex) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return;
  }

  VtoConfig config;
  config.faceMeshOcclusion = static_cast<bool>(faceMeshOcclusion);
  config.backPlaneOcclusion = static_cast<bool>(backPlaneOcclusion);
  config.forwardOffsetMeters = static_cast<float>(forwardOffset);
  config.debug = static_cast<bool>(debug);
  config.noseBridgeLeftIndex = static_cast<int>(noseBridgeLeftIndex);
  config.noseBridgeRightIndex = static_cast<int>(noseBridgeRightIndex);
  core->updateConfig(config);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeSetModelFromBytesCore(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring sourceId,
    jbyteArray bytes) {
  auto* core = fromHandle(handle);
  if (core == nullptr || bytes == nullptr) {
    return static_cast<jboolean>(false);
  }

  const jsize length = env->GetArrayLength(bytes);
  if (length <= 0) {
    return static_cast<jboolean>(false);
  }

  std::vector<std::uint8_t> modelBytes(static_cast<std::size_t>(length));
  env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(modelBytes.data()));

  std::string source;
  if (sourceId != nullptr) {
    const char* chars = env->GetStringUTFChars(sourceId, nullptr);
    if (chars != nullptr) {
      source.assign(chars);
      env->ReleaseStringUTFChars(sourceId, chars);
    }
  }

  ModelData model;
  model.bytes = modelBytes.data();
  model.size = modelBytes.size();
  model.sourceId = source;
  return static_cast<jboolean>(core->setModelFromBytes(model));
}

extern "C" JNIEXPORT void JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeResetCore(JNIEnv*, jobject, jlong handle) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return;
  }
  core->resetSession();
}

extern "C" JNIEXPORT void JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeSubmitFrameCore(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint viewportWidth,
    jint viewportHeight,
    jfloatArray projection,
    jfloatArray model,
    jint textureId,
    jfloatArray uvCoords8,
    jboolean hasLightEstimate,
    jboolean lightValid,
    jfloat linearIntensity,
    jboolean hasFace,
    jobject verticesBuffer,
    jint vertexCount,
    jobject indicesBuffer,
    jint indexCount,
    jfloatArray faceToWorld,
    jfloatArray rotationQuaternion) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return;
  }

  FrameInput input;
  input.viewportWidth = static_cast<int>(viewportWidth);
  input.viewportHeight = static_cast<int>(viewportHeight);

  if (projection != nullptr && model != nullptr &&
      env->GetArrayLength(projection) >= 16 && env->GetArrayLength(model) >= 16) {
    input.hasCameraMatrices = true;
    env->GetFloatArrayRegion(projection, 0, 16, input.projection);
    env->GetFloatArrayRegion(model, 0, 16, input.cameraModel);
  }

  if (textureId != 0) {
    input.cameraFeed.hasValue = true;
    input.cameraFeed.handle = static_cast<std::uintptr_t>(textureId);
    if (uvCoords8 != nullptr && env->GetArrayLength(uvCoords8) >= 8) {
      env->GetFloatArrayRegion(uvCoords8, 0, 8, input.cameraFeed.uvCoords8);
      input.cameraFeed.hasUvCoords = true;
    }
  }

  if (static_cast<bool>(hasLightEstimate)) {
    input.light.hasValue = true;
    input.light.valid = static_cast<bool>(lightValid);
    input.light.linearIntensity = static_cast<float>(linearIntensity);
  }

  FaceData face;
  if (static_cast<bool>(hasFace) && verticesBuffer != nullptr && indicesBuffer != nullptr &&
      faceToWorld != nullptr && rotationQuaternion != nullptr &&
      vertexCount > 0 && indexCount > 0 &&
      env->GetArrayLength(faceToWorld) >= 16 && env->GetArrayLength(rotationQuaternion) >= 4) {
    auto* vertices = reinterpret_cast<float*>(env->GetDirectBufferAddress(verticesBuffer));
    auto* indices = reinterpret_cast<jshort*>(env->GetDirectBufferAddress(indicesBuffer));
    if (vertices != nullptr && indices != nullptr) {
      const int safeIndexCount = indexCount > 0 ? indexCount : 0;
      face.vertices = vertices;
      face.vertexCount = static_cast<std::size_t>(vertexCount);
      face.indices = reinterpret_cast<const std::uint16_t*>(indices);
      face.indexCount = static_cast<std::size_t>(safeIndexCount);
      env->GetFloatArrayRegion(faceToWorld, 0, 16, face.faceToWorld);
      env->GetFloatArrayRegion(rotationQuaternion, 0, 4, face.rotationQuaternion);
      face.hasRotationQuaternion = true;
      input.face = &face;
    }
  }

  core->submitFrame(input);
}

extern "C" JNIEXPORT void JNICALL
Java_com_margelo_nitro_nitrovto_ArCoreVtoAdapter_nativeRenderCore(JNIEnv*, jobject, jlong handle) {
  auto* core = fromHandle(handle);
  if (core == nullptr) {
    return;
  }
  core->render();
}
