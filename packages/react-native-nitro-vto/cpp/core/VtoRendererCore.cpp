#include "VtoRendererCore.hpp"

#include <cstring>

namespace margelo::nitro::nitrovto::core {

VtoRendererCore::VtoRendererCore() = default;

VtoRendererCore::~VtoRendererCore() {
  destroy();
}

bool VtoRendererCore::initialize(void* nativeRenderTarget, const VtoConfig& config) {
  destroy();

  config_ = config;
  initialized_ = true;
  faceTrackingState_.reset();
  lightingState_.reset();
  hasRenderTarget_ = false;
  hasCameraFeed_ = false;

  if (nativeRenderTarget != nullptr) {
    return setRenderTarget(nativeRenderTarget);
  }

  return true;
}

bool VtoRendererCore::setRenderTarget(void* nativeRenderTarget) {
  nativeRenderTarget_ = nativeRenderTarget;
  hasRenderTarget_ = nativeRenderTarget != nullptr;
  if (!initialized_) {
    return false;
  }
  if (!hasRenderTarget_) {
    filamentRenderer_.destroy();
    return true;
  }

  if (!filamentRenderer_.initialize(nativeRenderTarget_)) {
    return false;
  }

  filamentRenderer_.resize(width_, height_);
  if (hasCameraMatrices_) {
    filamentRenderer_.setCameraMatrices(cameraProjection_, cameraModel_);
  }

  if (modelState_.hasModelBytes()) {
    const auto& bytes = modelState_.lastModelBytes();
    if (!filamentRenderer_.setModelFromBytes(bytes.data(), bytes.size())) {
      return false;
    }
  }

  return true;
}

void VtoRendererCore::resize(int width, int height) {
  width_ = width;
  height_ = height;
  if (hasRenderTarget_) {
    filamentRenderer_.resize(width_, height_);
  }
}

void VtoRendererCore::updateConfig(const VtoConfig& config) {
  config_ = config;
}

bool VtoRendererCore::setMaterialPackage(render::MaterialKind kind, const std::uint8_t* bytes, std::size_t size) {
  if (!initialized_) {
    return false;
  }
  return filamentRenderer_.setMaterialPackage(kind, bytes, size);
}

bool VtoRendererCore::setEnvironmentIblKtx(const std::uint8_t* bytes, std::size_t size) {
  if (!initialized_) {
    return false;
  }
  return filamentRenderer_.setEnvironmentIblKtx(bytes, size);
}

bool VtoRendererCore::setEnvironmentSkyboxKtx(const std::uint8_t* bytes, std::size_t size) {
  if (!initialized_) {
    return false;
  }
  return filamentRenderer_.setEnvironmentSkyboxKtx(bytes, size);
}

void VtoRendererCore::setEnvironmentSphericalHarmonics(const float* sh27) {
  if (!initialized_) {
    return;
  }
  filamentRenderer_.setEnvironmentSphericalHarmonics(sh27);
}

void VtoRendererCore::makeCameraContextCurrent() {
  if (!initialized_) {
    return;
  }
  filamentRenderer_.makeCameraContextCurrent();
}

std::size_t VtoRendererCore::getCameraTextureIds(std::uint32_t* outTextureIds, std::size_t capacity) const {
  if (!initialized_) {
    return 0;
  }
  return filamentRenderer_.getCameraTextureIds(outTextureIds, capacity);
}

bool VtoRendererCore::setCameraStream(void* nativeStream, int width, int height) {
  if (!initialized_) {
    return false;
  }
  return filamentRenderer_.setCameraStream(nativeStream, width, height);
}

bool VtoRendererCore::setModelFromBytes(const ModelData& model) {
  if (!initialized_) {
    return false;
  }

  const bool stored = modelState_.setFromBytes(model);
  if (!stored) {
    return false;
  }

  if (hasRenderTarget_) {
    return filamentRenderer_.setModelFromBytes(model.bytes, model.size);
  }

  return true;
}

void VtoRendererCore::resetSession() {
  faceTrackingState_.reset();
}

void VtoRendererCore::submitFrame(const FrameInput& frame) {
  if (!initialized_) {
    return;
  }

  if (frame.viewportWidth > 0 && frame.viewportHeight > 0 &&
      (frame.viewportWidth != width_ || frame.viewportHeight != height_)) {
    resize(frame.viewportWidth, frame.viewportHeight);
  }

  if (frame.hasCameraMatrices) {
    std::memcpy(cameraProjection_, frame.projection, sizeof(float) * 16);
    std::memcpy(cameraModel_, frame.cameraModel, sizeof(float) * 16);
    hasCameraMatrices_ = true;
  }

  if (frame.cameraFeed.hasValue) {
    cameraFeed_ = frame.cameraFeed;
    hasCameraFeed_ = frame.cameraFeed.handle != 0;
  }

  if (frame.light.hasValue) {
    lightingState_.updateEstimate(frame.light.valid, frame.light.linearIntensity);
  }

  if (frame.face != nullptr) {
    faceTrackingState_.update(frame.face, config_);
  } else {
    faceTrackingState_.reset();
  }
}

void VtoRendererCore::render() {
  if (!initialized_ || !hasRenderTarget_) {
    return;
  }

  filamentRenderer_.resize(width_, height_);
  if (hasCameraMatrices_) {
    filamentRenderer_.setCameraMatrices(cameraProjection_, cameraModel_);
  }

  if (hasCameraFeed_) {
    render::CameraFeedState feed;
    feed.handle = cameraFeed_.handle;
    std::memcpy(feed.uvTransform3x3, cameraFeed_.uvTransform3x3, sizeof(float) * 9);
    std::memcpy(feed.uvCoords8, cameraFeed_.uvCoords8, sizeof(float) * 8);
    feed.hasUvCoords = cameraFeed_.hasUvCoords;
    filamentRenderer_.setCameraFeed(feed);
  }

  render::FaceRenderState faceRenderState;
  faceRenderState.vertices = faceTrackingState_.faceVertices();
  faceRenderState.vertexCount = faceTrackingState_.faceVertexCount();
  faceRenderState.indices = faceTrackingState_.faceIndices();
  faceRenderState.indexCount = faceTrackingState_.faceIndexCount();
  faceRenderState.faceTransform16 = faceTrackingState_.faceTransform();
  faceRenderState.backPlaneTransform16 = faceTrackingState_.backPlaneTransform();
  faceRenderState.hasFace = faceTrackingState_.hasFaceTracking();
  faceRenderState.leftBackPlaneVisible = faceTrackingState_.isLeftBackPlaneVisible();
  faceRenderState.rightBackPlaneVisible = faceTrackingState_.isRightBackPlaneVisible();
  faceRenderState.faceMeshOcclusionEnabled = config_.faceMeshOcclusion;
  faceRenderState.backPlaneOcclusionEnabled = config_.backPlaneOcclusion;
  faceRenderState.debugEnabled = config_.debug;
  filamentRenderer_.setFaceRenderState(faceRenderState);

  filamentRenderer_.setLightIntensityScale(lightingState_.intensityScale());
  filamentRenderer_.setGlassesTransform(faceTrackingState_.glassesTransform(), faceTrackingState_.hasFaceTracking());
  filamentRenderer_.render();
}

void VtoRendererCore::destroy() {
  filamentRenderer_.destroy();
  nativeRenderTarget_ = nullptr;
  width_ = 0;
  height_ = 0;
  hasRenderTarget_ = false;
  hasCameraMatrices_ = false;
  hasCameraFeed_ = false;
  modelState_.reset();
  lightingState_.reset();
  faceTrackingState_.reset();
  initialized_ = false;
}

} // namespace margelo::nitro::nitrovto::core
