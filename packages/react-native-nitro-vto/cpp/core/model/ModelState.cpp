#include "ModelState.hpp"

namespace margelo::nitro::nitrovto::core {

bool ModelState::setFromBytes(const ModelData& model) {
  if (model.bytes == nullptr || model.size == 0) {
    return false;
  }

  lastModelBytes_.assign(model.bytes, model.bytes + model.size);
  return true;
}

void ModelState::reset() {
  lastModelBytes_.clear();
}

bool ModelState::hasModelBytes() const {
  return !lastModelBytes_.empty();
}

const std::vector<std::uint8_t>& ModelState::lastModelBytes() const {
  return lastModelBytes_;
}

} // namespace margelo::nitro::nitrovto::core
