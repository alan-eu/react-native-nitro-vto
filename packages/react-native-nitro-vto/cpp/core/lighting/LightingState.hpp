#pragma once

namespace margelo::nitro::nitrovto::core {

class LightingState {
 public:
  void updateEstimate(bool valid, float linearIntensity);
  float intensityScale() const;
  void reset();

 private:
  float intensityScale_ = 1.0f;
};

} // namespace margelo::nitro::nitrovto::core
