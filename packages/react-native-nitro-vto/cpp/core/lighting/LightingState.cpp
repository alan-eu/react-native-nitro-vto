#include "LightingState.hpp"

#include <algorithm>

namespace {

constexpr float kMiddleGrayLinear = 0.18f;
constexpr float kMinIntensityScale = 0.35f;
constexpr float kMaxIntensityScale = 1.8f;

} // namespace

namespace margelo::nitro::nitrovto::core {

void LightingState::updateEstimate(bool valid, float linearIntensity) {
  if (!valid) {
    return;
  }

  const float safeLinear = std::max(linearIntensity, 0.0f);
  const float scale = safeLinear / kMiddleGrayLinear;
  intensityScale_ = std::max(kMinIntensityScale, std::min(scale, kMaxIntensityScale));
}

float LightingState::intensityScale() const {
  return intensityScale_;
}

void LightingState::reset() {
  intensityScale_ = 1.0f;
}

} // namespace margelo::nitro::nitrovto::core
