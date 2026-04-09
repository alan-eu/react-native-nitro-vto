#pragma once

#include <cstddef>
#include <cstdint>

namespace margelo::nitro::nitrovto::core::render {

enum class RenderBackend {
  OpenGL,
  Metal,
};

enum class MaterialKind {
  CameraBackground,
  FaceOcclusion,
  DebugFace,
  DebugPlane,
};

struct CameraFeedState {
  std::uintptr_t handle = 0;
  float uvTransform3x3[9] = {0.0f};
  float uvCoords8[8] = {0.0f};
  bool hasUvCoords = false;
};

struct FaceRenderState {
  const float* vertices = nullptr;
  std::size_t vertexCount = 0;
  const std::uint16_t* indices = nullptr;
  std::size_t indexCount = 0;
  const float* faceTransform16 = nullptr;
  const float* backPlaneTransform16 = nullptr;
  bool hasFace = false;
  bool leftBackPlaneVisible = false;
  bool rightBackPlaneVisible = false;
  bool faceMeshOcclusionEnabled = true;
  bool backPlaneOcclusionEnabled = true;
  bool debugEnabled = false;
};

class FilamentRenderer {
 public:
  explicit FilamentRenderer(RenderBackend backend);
  ~FilamentRenderer();

  bool initialize(void* nativeRenderTarget);
  bool setRenderTarget(void* nativeRenderTarget);
  void resize(int width, int height);

  bool setMaterialPackage(MaterialKind kind, const std::uint8_t* bytes, std::size_t size);
  bool setEnvironmentIblKtx(const std::uint8_t* bytes, std::size_t size);
  bool setEnvironmentSkyboxKtx(const std::uint8_t* bytes, std::size_t size);
  void setEnvironmentSphericalHarmonics(const float* sh27);

  void makeCameraContextCurrent();
  std::size_t getCameraTextureIds(std::uint32_t* outTextureIds, std::size_t capacity) const;
  bool setCameraStream(void* nativeStream, int width, int height);

  bool setModelFromBytes(const std::uint8_t* bytes, std::size_t size);
  void setCameraMatrices(const float* projection16, const float* model16);
  void setCameraFeed(const CameraFeedState& state);
  void setFaceRenderState(const FaceRenderState& state);
  void setLightIntensityScale(float intensityScale);
  void setGlassesTransform(const float* worldTransform16, bool visible);

  void render();
  void destroy();

 private:
  struct Impl;
  Impl* impl_ = nullptr;
};

} // namespace margelo::nitro::nitrovto::core::render
