#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace margelo::nitro::nitrovto::core {

struct VtoConfig {
  bool faceMeshOcclusion = true;
  bool backPlaneOcclusion = true;
  bool debug = false;
  float forwardOffsetMeters = 0.005f;
  int noseBridgeLeftIndex = 351;
  int noseBridgeRightIndex = 122;
};

struct LightEstimateData {
  bool hasValue = false;
  bool valid = false;
  float linearIntensity = 0.0f;
};

struct CameraFeedData {
  bool hasValue = false;
  std::uintptr_t handle = 0;
  float uvTransform3x3[9] = {0.0f};
  float uvCoords8[8] = {0.0f};
  bool hasUvCoords = false;
};

struct FaceData {
  const float* vertices = nullptr;
  std::size_t vertexCount = 0;
  const std::uint16_t* indices = nullptr;
  std::size_t indexCount = 0;
  float faceToWorld[16] = {0.0f};
  float rotationQuaternion[4] = {0.0f, 0.0f, 0.0f, 1.0f};
  bool hasRotationQuaternion = false;
};

struct FrameInput {
  int viewportWidth = 0;
  int viewportHeight = 0;
  bool hasCameraMatrices = false;
  float projection[16] = {0.0f};
  float cameraModel[16] = {0.0f};
  LightEstimateData light;
  CameraFeedData cameraFeed;
  const FaceData* face = nullptr;
};

struct ModelData {
  const std::uint8_t* bytes = nullptr;
  std::size_t size = 0;
  std::string sourceId;
};

} // namespace margelo::nitro::nitrovto::core
