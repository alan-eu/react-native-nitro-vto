#pragma once

#include <array>
#include <cstdint>
#include <vector>

#include "../VtoTypes.hpp"

namespace margelo::nitro::nitrovto::core {

class FaceTrackingState {
 public:
  FaceTrackingState();

  void reset();
  void update(const FaceData* face, const VtoConfig& config);

  bool hasFaceTracking() const;
  bool isLeftBackPlaneVisible() const;
  bool isRightBackPlaneVisible() const;
  const float* glassesTransform() const;
  const float* faceTransform() const;
  const float* backPlaneTransform() const;
  const float* faceVertices() const;
  std::size_t faceVertexCount() const;
  const std::uint16_t* faceIndices() const;
  std::size_t faceIndexCount() const;

 private:
  struct Kalman1D {
    float processNoise = 0.1f;
    float measurementNoise = 0.05f;
    float estimate = 0.0f;
    float errorCovariance = 1.0f;

    float update(float measurement);
    void reset(float initialEstimate);
  };

  void setHideTransform(std::array<float, 16>& out) const;

  bool hasFaceTracking_ = false;
  bool leftBackPlaneVisible_ = false;
  bool rightBackPlaneVisible_ = false;

  std::array<float, 16> glassesTransform_{};
  std::array<float, 16> faceTransform_{};
  std::array<float, 16> backPlaneTransform_{};
  std::vector<float> faceVertices_;
  std::vector<std::uint16_t> faceIndices_;

  Kalman1D positionFilters_[3] = {};
  Kalman1D rotationFilters_[4] = {};
};

} // namespace margelo::nitro::nitrovto::core
