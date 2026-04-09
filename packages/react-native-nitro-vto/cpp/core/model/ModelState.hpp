#pragma once

#include <cstdint>
#include <vector>

#include "../VtoTypes.hpp"

namespace margelo::nitro::nitrovto::core {

class ModelState {
 public:
  bool setFromBytes(const ModelData& model);
  void reset();

  bool hasModelBytes() const;
  const std::vector<std::uint8_t>& lastModelBytes() const;

 private:
  std::vector<std::uint8_t> lastModelBytes_;
};

} // namespace margelo::nitro::nitrovto::core
