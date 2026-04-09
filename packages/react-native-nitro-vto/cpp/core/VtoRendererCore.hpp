#pragma once

#include <array>
#include <cstdint>
#include <string>

#include "VtoTypes.hpp"
#include "lighting/LightingState.hpp"
#include "model/ModelState.hpp"
#include "render/FilamentRenderer.hpp"
#include "tracking/FaceTrackingState.hpp"

namespace margelo::nitro::nitrovto::core {

class VtoRendererCore {
 public:
  VtoRendererCore();
  ~VtoRendererCore();

  bool initialize(void* nativeRenderTarget, const VtoConfig& config);
  bool setRenderTarget(void* nativeRenderTarget);
  void resize(int width, int height);
  void updateConfig(const VtoConfig& config);

  bool setMaterialPackage(render::MaterialKind kind, const std::uint8_t* bytes, std::size_t size);
  bool setEnvironmentIblKtx(const std::uint8_t* bytes, std::size_t size);
  bool setEnvironmentSkyboxKtx(const std::uint8_t* bytes, std::size_t size);
  void setEnvironmentSphericalHarmonics(const float* sh27);

  void makeCameraContextCurrent();
  std::size_t getCameraTextureIds(std::uint32_t* outTextureIds, std::size_t capacity) const;
  bool setCameraStream(void* nativeStream, int width, int height);

  bool setModelFromBytes(const ModelData& model);

  void resetSession();
  void submitFrame(const FrameInput& frame);
  void render();
  void destroy();

 private:
  void* nativeRenderTarget_ = nullptr;
  VtoConfig config_{};
  int width_ = 0;
  int height_ = 0;
  bool initialized_ = false;
  bool hasRenderTarget_ = false;
  float cameraProjection_[16] = {0.0f};
  float cameraModel_[16] = {0.0f};
  bool hasCameraMatrices_ = false;
  CameraFeedData cameraFeed_{};
  bool hasCameraFeed_ = false;

  ModelState modelState_{};
  LightingState lightingState_{};
  FaceTrackingState faceTrackingState_{};
#if defined(__ANDROID__)
  render::FilamentRenderer filamentRenderer_{render::RenderBackend::OpenGL};
#else
  render::FilamentRenderer filamentRenderer_{render::RenderBackend::Metal};
#endif
};

} // namespace margelo::nitro::nitrovto::core
