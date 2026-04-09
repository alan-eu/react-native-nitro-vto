#include "FaceTrackingState.hpp"

#include <algorithm>
#include <cmath>

namespace {

constexpr float kBackPlaneYawThresholdRadians = 0.12f;
constexpr float kBackPlaneDepthPaddingMeters = 0.03f;

void copyIdentity(float* matrix16) {
  for (int i = 0; i < 16; ++i) {
    matrix16[i] = (i % 5 == 0) ? 1.0f : 0.0f;
  }
}

void copyArray16(const float* src, float* dst) {
  for (int i = 0; i < 16; ++i) {
    dst[i] = src[i];
  }
}

void normalizeQuaternion(float* qx, float* qy, float* qz, float* qw) {
  const float length = std::sqrt((*qx) * (*qx) + (*qy) * (*qy) + (*qz) * (*qz) + (*qw) * (*qw));
  if (length <= 0.0001f) {
    *qx = 0.0f;
    *qy = 0.0f;
    *qz = 0.0f;
    *qw = 1.0f;
    return;
  }

  *qx /= length;
  *qy /= length;
  *qz /= length;
  *qw /= length;
}

void quaternionToMatrix(float qx, float qy, float qz, float qw, float* out16) {
  normalizeQuaternion(&qx, &qy, &qz, &qw);

  out16[0] = 1.0f - 2.0f * qy * qy - 2.0f * qz * qz;
  out16[1] = 2.0f * qx * qy + 2.0f * qz * qw;
  out16[2] = 2.0f * qx * qz - 2.0f * qy * qw;
  out16[3] = 0.0f;

  out16[4] = 2.0f * qx * qy - 2.0f * qz * qw;
  out16[5] = 1.0f - 2.0f * qx * qx - 2.0f * qz * qz;
  out16[6] = 2.0f * qy * qz + 2.0f * qx * qw;
  out16[7] = 0.0f;

  out16[8] = 2.0f * qx * qz + 2.0f * qy * qw;
  out16[9] = 2.0f * qy * qz - 2.0f * qx * qw;
  out16[10] = 1.0f - 2.0f * qx * qx - 2.0f * qy * qy;
  out16[11] = 0.0f;

  out16[12] = 0.0f;
  out16[13] = 0.0f;
  out16[14] = 0.0f;
  out16[15] = 1.0f;
}

void transformLocalToWorld(const float* faceToWorld16, float localX, float localY, float localZ, float* out3) {
  out3[0] = faceToWorld16[0] * localX + faceToWorld16[4] * localY + faceToWorld16[8] * localZ + faceToWorld16[12];
  out3[1] = faceToWorld16[1] * localX + faceToWorld16[5] * localY + faceToWorld16[9] * localZ + faceToWorld16[13];
  out3[2] = faceToWorld16[2] * localX + faceToWorld16[6] * localY + faceToWorld16[10] * localZ + faceToWorld16[14];
}

} // namespace

namespace margelo::nitro::nitrovto::core {

float FaceTrackingState::Kalman1D::update(float measurement) {
  errorCovariance += processNoise;

  const float kalmanGain = errorCovariance / (errorCovariance + measurementNoise);
  estimate += kalmanGain * (measurement - estimate);
  errorCovariance *= (1.0f - kalmanGain);
  return estimate;
}

void FaceTrackingState::Kalman1D::reset(float initialEstimate) {
  estimate = initialEstimate;
  errorCovariance = 1.0f;
}

FaceTrackingState::FaceTrackingState() {
  reset();
}

void FaceTrackingState::reset() {
  hasFaceTracking_ = false;
  leftBackPlaneVisible_ = false;
  rightBackPlaneVisible_ = false;

  setHideTransform(glassesTransform_);
  setHideTransform(faceTransform_);
  setHideTransform(backPlaneTransform_);

  faceVertices_.clear();
  faceIndices_.clear();

  for (auto& filter : positionFilters_) {
    filter.reset(0.0f);
  }
  rotationFilters_[0].reset(0.0f);
  rotationFilters_[1].reset(0.0f);
  rotationFilters_[2].reset(0.0f);
  rotationFilters_[3].reset(1.0f);
}

void FaceTrackingState::update(const FaceData* face, const VtoConfig& config) {
  if (face == nullptr || face->vertices == nullptr || face->vertexCount == 0) {
    reset();
    return;
  }

  const int leftIndex = config.noseBridgeLeftIndex;
  const int rightIndex = config.noseBridgeRightIndex;
  const auto vertexCount = static_cast<int>(face->vertexCount);
  if (leftIndex < 0 || rightIndex < 0 || leftIndex >= vertexCount || rightIndex >= vertexCount) {
    reset();
    return;
  }

  const float* vertices = face->vertices;
  faceVertices_.assign(vertices, vertices + face->vertexCount * 3);
  if (face->indices != nullptr && face->indexCount > 0) {
    faceIndices_.assign(face->indices, face->indices + face->indexCount);
  } else {
    faceIndices_.clear();
  }

  const int leftBase = leftIndex * 3;
  const int rightBase = rightIndex * 3;
  const float localCenterX = (vertices[leftBase] + vertices[rightBase]) * 0.5f;
  const float localCenterY = (vertices[leftBase + 1] + vertices[rightBase + 1]) * 0.5f;
  const float localCenterZ = (vertices[leftBase + 2] + vertices[rightBase + 2]) * 0.5f;

  float worldNose[3] = {0.0f, 0.0f, 0.0f};
  transformLocalToWorld(face->faceToWorld, localCenterX, localCenterY, localCenterZ, worldNose);

  const float smoothedPositionX = positionFilters_[0].update(worldNose[0]);
  const float smoothedPositionY = positionFilters_[1].update(worldNose[1]);
  const float smoothedPositionZ = positionFilters_[2].update(worldNose[2]);

  float qx = 0.0f;
  float qy = 0.0f;
  float qz = 0.0f;
  float qw = 1.0f;
  if (face->hasRotationQuaternion) {
    qx = face->rotationQuaternion[0];
    qy = face->rotationQuaternion[1];
    qz = face->rotationQuaternion[2];
    qw = face->rotationQuaternion[3];
  }

  float filteredQx = rotationFilters_[0].update(qx);
  float filteredQy = rotationFilters_[1].update(qy);
  float filteredQz = rotationFilters_[2].update(qz);
  float filteredQw = rotationFilters_[3].update(qw);
  normalizeQuaternion(&filteredQx, &filteredQy, &filteredQz, &filteredQw);

  quaternionToMatrix(filteredQx, filteredQy, filteredQz, filteredQw, glassesTransform_.data());

  const float forwardX = glassesTransform_[8];
  const float forwardY = glassesTransform_[9];
  const float forwardZ = glassesTransform_[10];
  glassesTransform_[12] = smoothedPositionX + forwardX * config.forwardOffsetMeters;
  glassesTransform_[13] = smoothedPositionY + forwardY * config.forwardOffsetMeters;
  glassesTransform_[14] = smoothedPositionZ + forwardZ * config.forwardOffsetMeters;

  copyArray16(face->faceToWorld, faceTransform_.data());

  float minZ = vertices[2];
  for (std::size_t i = 1; i < face->vertexCount; ++i) {
    minZ = std::min(minZ, vertices[i * 3 + 2]);
  }

  copyArray16(face->faceToWorld, backPlaneTransform_.data());
  const float zOffset = minZ + kBackPlaneDepthPaddingMeters;
  backPlaneTransform_[12] += backPlaneTransform_[8] * zOffset;
  backPlaneTransform_[13] += backPlaneTransform_[9] * zOffset;
  backPlaneTransform_[14] += backPlaneTransform_[10] * zOffset;

  const float yaw = std::atan2(face->faceToWorld[8], face->faceToWorld[0]);
  leftBackPlaneVisible_ = config.backPlaneOcclusion && (yaw < kBackPlaneYawThresholdRadians);
  rightBackPlaneVisible_ = config.backPlaneOcclusion && (yaw > -kBackPlaneYawThresholdRadians);
  hasFaceTracking_ = true;
}

bool FaceTrackingState::hasFaceTracking() const {
  return hasFaceTracking_;
}

bool FaceTrackingState::isLeftBackPlaneVisible() const {
  return leftBackPlaneVisible_;
}

bool FaceTrackingState::isRightBackPlaneVisible() const {
  return rightBackPlaneVisible_;
}

const float* FaceTrackingState::glassesTransform() const {
  return glassesTransform_.data();
}

const float* FaceTrackingState::faceTransform() const {
  return faceTransform_.data();
}

const float* FaceTrackingState::backPlaneTransform() const {
  return backPlaneTransform_.data();
}

const float* FaceTrackingState::faceVertices() const {
  return faceVertices_.empty() ? nullptr : faceVertices_.data();
}

std::size_t FaceTrackingState::faceVertexCount() const {
  return faceVertices_.size() / 3;
}

const std::uint16_t* FaceTrackingState::faceIndices() const {
  return faceIndices_.empty() ? nullptr : faceIndices_.data();
}

std::size_t FaceTrackingState::faceIndexCount() const {
  return faceIndices_.size();
}

void FaceTrackingState::setHideTransform(std::array<float, 16>& out) const {
  copyIdentity(out.data());
  out[14] = -1000.0f;
}

} // namespace margelo::nitro::nitrovto::core
