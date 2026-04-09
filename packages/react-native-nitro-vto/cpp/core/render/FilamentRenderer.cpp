#include "FilamentRenderer.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include <filament/Camera.h>
#include <filament/ColorGrading.h>
#include <filament/Engine.h>
#include <filament/IndexBuffer.h>
#include <filament/IndirectLight.h>
#include <filament/LightManager.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/RenderableManager.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/Skybox.h>
#include <filament/SwapChain.h>
#include <filament/Texture.h>
#include <filament/TextureSampler.h>
#include <filament/TransformManager.h>
#include <filament/VertexBuffer.h>
#include <filament/View.h>
#include <filament/Viewport.h>

#include <gltfio/AssetLoader.h>
#include <gltfio/FilamentAsset.h>
#include <gltfio/MaterialProvider.h>
#include <gltfio/ResourceLoader.h>
#include <gltfio/TextureProvider.h>
#include <gltfio/materials/uberarchive.h>

#include <ktxreader/Ktx1Reader.h>

#include <math/mat3.h>
#include <math/mat4.h>
#include <math/vec4.h>

#include <utils/EntityManager.h>

#if defined(__ANDROID__)
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/native_window.h>
#include <filament/Stream.h>
#endif

namespace margelo::nitro::nitrovto::core::render {

namespace {

constexpr float kBaseSunIntensity = 30000.0f;
constexpr std::size_t kCameraTextureCount = 1;
constexpr std::size_t kMaxFaceVertices = 2500;
constexpr std::size_t kMaxFaceIndices = 12000;

filament::Engine::Backend toFilamentBackend(RenderBackend backend) {
  return backend == RenderBackend::Metal ? filament::Engine::Backend::METAL : filament::Engine::Backend::OPENGL;
}

filament::math::mat4f toMat4f(const float* matrix16) {
  filament::math::mat4f out;
  for (int col = 0; col < 4; ++col) {
    for (int row = 0; row < 4; ++row) {
      out[col][row] = matrix16[col * 4 + row];
    }
  }
  return out;
}

filament::math::mat4 toMat4(const float* matrix16) {
  filament::math::mat4 out;
  for (int col = 0; col < 4; ++col) {
    for (int row = 0; row < 4; ++row) {
      out[col][row] = static_cast<double>(matrix16[col * 4 + row]);
    }
  }
  return out;
}

void copyIdentity(float* matrix16) {
  for (int i = 0; i < 16; ++i) {
    matrix16[i] = (i % 5 == 0) ? 1.0f : 0.0f;
  }
}

void setHideTransform(float* matrix16) {
  copyIdentity(matrix16);
  matrix16[14] = -1000.0f;
}

std::array<float, 16> makeQuadVertices(const float* uv8, bool mirror) {
  std::array<float, 16> v{};
  if (uv8 == nullptr) {
    const float defaultUv[8] = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};
    uv8 = defaultUv;
  }

  float blU = uv8[0];
  float blV = uv8[1];
  float brU = uv8[2];
  float brV = uv8[3];
  float tlU = uv8[4];
  float tlV = uv8[5];
  float trU = uv8[6];
  float trV = uv8[7];

  if (mirror) {
    const float mapped[8] = {
        trU,
        trV,
        tlU,
        tlV,
        brU,
        brV,
        blU,
        blV,
    };
    blU = mapped[0];
    blV = mapped[1];
    brU = mapped[2];
    brV = mapped[3];
    tlU = mapped[4];
    tlV = mapped[5];
    trU = mapped[6];
    trV = mapped[7];
  }

  v[0] = -1.0f;
  v[1] = -1.0f;
  v[2] = blU;
  v[3] = blV;

  v[4] = 1.0f;
  v[5] = -1.0f;
  v[6] = brU;
  v[7] = brV;

  v[8] = -1.0f;
  v[9] = 1.0f;
  v[10] = tlU;
  v[11] = tlV;

  v[12] = 1.0f;
  v[13] = 1.0f;
  v[14] = trU;
  v[15] = trV;

  return v;
}

template <typename DescriptorT>
DescriptorT makeBufferDescriptorCopy(const void* data, std::size_t sizeBytes) {
  auto* copy = new std::uint8_t[sizeBytes];
  std::memcpy(copy, data, sizeBytes);
  return DescriptorT(copy, sizeBytes, [](void* buffer, size_t, void*) {
    delete[] static_cast<std::uint8_t*>(buffer);
  });
}

} // namespace

struct FilamentRenderer::Impl {
  explicit Impl(RenderBackend b) : backend(b) {
    setHideTransform(glassesTransform);
    copyIdentity(faceTransform);
    copyIdentity(backPlaneTransform);
    copyIdentity(cameraUvTransform);
    cameraUvTransform[4] = -1.0f;
    cameraUvTransform[7] = 1.0f;
  }

  RenderBackend backend;

  filament::Engine* engine = nullptr;
  filament::Renderer* renderer = nullptr;
  filament::Scene* scene = nullptr;
  filament::View* view = nullptr;
  filament::Camera* camera = nullptr;
  filament::SwapChain* swapChain = nullptr;
  filament::ColorGrading* colorGrading = nullptr;

  utils::Entity cameraEntity = {};
  utils::Entity sunEntity = {};

  filament::gltfio::MaterialProvider* gltfMaterialProvider = nullptr;
  filament::gltfio::AssetLoader* assetLoader = nullptr;
  filament::gltfio::ResourceLoader* resourceLoader = nullptr;
  filament::gltfio::TextureProvider* textureProvider = nullptr;
  filament::gltfio::FilamentAsset* glassesAsset = nullptr;

  int width = 1;
  int height = 1;

  float lightIntensityScale = 1.0f;
  float projection[16] = {0};
  float model[16] = {0};
  bool hasCameraMatrices = false;
  float glassesTransform[16] = {0};
  bool glassesVisible = false;

  // Material packages
  std::vector<std::uint8_t> cameraBackgroundPackage;
  std::vector<std::uint8_t> faceOcclusionPackage;
  std::vector<std::uint8_t> debugFacePackage;
  std::vector<std::uint8_t> debugPlanePackage;

  // Environment data
  std::vector<std::uint8_t> iblKtxBytes;
  std::vector<std::uint8_t> skyboxKtxBytes;
  float sh27[27] = {0.0f};
  bool hasSh27 = false;

  // Environment objects
  filament::Texture* iblTexture = nullptr;
  filament::Texture* skyboxTexture = nullptr;
  filament::IndirectLight* indirectLight = nullptr;
  filament::Skybox* skybox = nullptr;

  // Camera background resources
  filament::Material* cameraBackgroundMaterial = nullptr;
  filament::MaterialInstance* cameraBackgroundMaterialInstance = nullptr;
  filament::VertexBuffer* cameraQuadVertexBuffer = nullptr;
  filament::IndexBuffer* cameraQuadIndexBuffer = nullptr;
  utils::Entity cameraBackgroundEntity = {};
  bool cameraBackgroundInScene = false;

  // Face occlusion resources
  filament::Material* faceOcclusionMaterial = nullptr;
  filament::MaterialInstance* faceOcclusionMaterialInstance = nullptr;
  filament::VertexBuffer* faceOcclusionVertexBuffer = nullptr;
  filament::IndexBuffer* faceOcclusionIndexBuffer = nullptr;
  utils::Entity faceOcclusionEntity = {};
  bool faceOcclusionInScene = false;

  filament::VertexBuffer* backPlaneLeftVertexBuffer = nullptr;
  filament::VertexBuffer* backPlaneRightVertexBuffer = nullptr;
  filament::IndexBuffer* backPlaneIndexBuffer = nullptr;
  utils::Entity backPlaneLeftEntity = {};
  utils::Entity backPlaneRightEntity = {};
  bool backPlaneLeftInScene = false;
  bool backPlaneRightInScene = false;

  // Debug resources
  filament::Material* debugFaceMaterial = nullptr;
  filament::Material* debugPlaneMaterial = nullptr;
  filament::MaterialInstance* debugFaceMaterialInstance = nullptr;
  filament::MaterialInstance* debugLeftPlaneMaterialInstance = nullptr;
  filament::MaterialInstance* debugRightPlaneMaterialInstance = nullptr;
  filament::VertexBuffer* debugFaceVertexBuffer = nullptr;
  filament::IndexBuffer* debugFaceIndexBuffer = nullptr;
  utils::Entity debugFaceEntity = {};
  bool debugFaceInScene = false;
  utils::Entity debugLeftPlaneEntity = {};
  utils::Entity debugRightPlaneEntity = {};
  bool debugLeftPlaneInScene = false;
  bool debugRightPlaneInScene = false;

  // Current frame states
  std::uintptr_t cameraFeedHandle = 0;
  float cameraUvTransform[9] = {0.0f};
  float cameraUvCoords8[8] = {0.0f};
  bool cameraHasUvCoords = false;

  std::vector<float> faceVertices;
  std::vector<std::uint16_t> faceIndices;
  float faceTransform[16] = {0.0f};
  float backPlaneTransform[16] = {0.0f};
  bool hasFace = false;
  bool leftBackPlaneVisible = false;
  bool rightBackPlaneVisible = false;
  bool faceMeshOcclusionEnabled = true;
  bool backPlaneOcclusionEnabled = true;
  bool debugEnabled = false;

#if defined(__ANDROID__)
  ANativeWindow* nativeWindow = nullptr;
  EGLDisplay eglDisplay = EGL_NO_DISPLAY;
  EGLContext eglContext = EGL_NO_CONTEXT;
  EGLSurface eglSurface = EGL_NO_SURFACE;
  std::array<std::uint32_t, kCameraTextureCount> cameraTextureIds = {};
  filament::Texture* cameraExternalTexture = nullptr;
  filament::Stream* cameraStream = nullptr;
  std::uintptr_t cameraStreamHandle = 0;
#else
  filament::Texture* iosCameraExternalTexture = nullptr;
#endif

  bool initialize(void* nativeRenderTarget) {
    destroy();

#if defined(__ANDROID__)
    if (!createAndroidGlInterop()) {
      return false;
    }
    engine = filament::Engine::create(toFilamentBackend(backend), nullptr, eglContext);
#else
    engine = filament::Engine::create(toFilamentBackend(backend));
#endif

    if (engine == nullptr) {
      return false;
    }

    renderer = engine->createRenderer();
    scene = engine->createScene();
    view = engine->createView();
    view->setPostProcessingEnabled(true);
    view->setScreenSpaceRefractionEnabled(true);

    filament::Renderer::ClearOptions clearOptions;
    clearOptions.clearColor = {0.0f, 0.0f, 0.0f, 1.0f};
    clearOptions.clear = true;
    clearOptions.discard = true;
    renderer->setClearOptions(clearOptions);

    cameraEntity = utils::EntityManager::get().create();
    camera = engine->createCamera(cameraEntity);
    view->setCamera(camera);
    view->setScene(scene);

    colorGrading = filament::ColorGrading::Builder()
                       .toneMapping(filament::ColorGrading::ToneMapping::ACES_LEGACY)
                       .build(*engine);
    view->setColorGrading(colorGrading);

    sunEntity = utils::EntityManager::get().create();
    filament::LightManager::Builder(filament::LightManager::Type::SUN)
        .intensity(kBaseSunIntensity)
        .direction({0.0f, -1.0f, -0.35f})
        .castShadows(false)
        .build(*engine, sunEntity);
    scene->addEntity(sunEntity);

    gltfMaterialProvider = filament::gltfio::createUbershaderProvider(engine, UBERARCHIVE_DEFAULT_DATA, UBERARCHIVE_DEFAULT_SIZE);
    assetLoader = filament::gltfio::AssetLoader::create({
        .engine = engine,
        .materials = gltfMaterialProvider,
        .names = nullptr,
        .entities = &utils::EntityManager::get(),
    });
    resourceLoader = new filament::gltfio::ResourceLoader({engine, ".", true});
    textureProvider = filament::gltfio::createStbProvider(engine);
    resourceLoader->addTextureProvider("image/png", textureProvider);
    resourceLoader->addTextureProvider("image/jpeg", textureProvider);

    createMaterialResources();
    createEnvironmentResources();

    if (view != nullptr) {
      view->setViewport({0, 0, static_cast<uint32_t>(std::max(width, 1)), static_cast<uint32_t>(std::max(height, 1))});
    }

    return setRenderTarget(nativeRenderTarget);
  }

  void createMaterialResources() {
    createCameraBackgroundMaterial();
    createFaceOcclusionMaterial();
    createDebugMaterials();
  }

  void createCameraBackgroundMaterial() {
    destroyCameraBackgroundResources();
    if (engine == nullptr || cameraBackgroundPackage.empty()) {
      return;
    }

    cameraBackgroundMaterial = filament::Material::Builder()
                                   .package(cameraBackgroundPackage.data(), cameraBackgroundPackage.size())
                                   .build(*engine);
    if (cameraBackgroundMaterial == nullptr) {
      return;
    }

    cameraBackgroundMaterialInstance = cameraBackgroundMaterial->getDefaultInstance();
    if (cameraBackgroundMaterialInstance == nullptr) {
      return;
    }

#if defined(__ANDROID__)
    cameraExternalTexture = filament::Texture::Builder()
                                .sampler(filament::Texture::Sampler::SAMPLER_EXTERNAL)
                                .format(filament::Texture::InternalFormat::RGB8)
                                .build(*engine);

    if (cameraExternalTexture == nullptr) {
      return;
    }
#else
    iosCameraExternalTexture = filament::Texture::Builder()
                                   .sampler(filament::Texture::Sampler::SAMPLER_EXTERNAL)
                                   .format(filament::Texture::InternalFormat::RGBA8)
                                   .external()
                                   .build(*engine);
#endif

    constexpr std::uint16_t kIndices[6] = {0, 1, 2, 2, 1, 3};
    const auto initialQuad = makeQuadVertices(nullptr, false);

    cameraQuadVertexBuffer = filament::VertexBuffer::Builder()
                                 .vertexCount(4)
                                 .bufferCount(1)
                                 .attribute(filament::VertexAttribute::POSITION, 0,
                                            filament::VertexBuffer::AttributeType::FLOAT2, 0, 16)
                                 .attribute(filament::VertexAttribute::UV0, 0,
                                            filament::VertexBuffer::AttributeType::FLOAT2, 8, 16)
                                 .build(*engine);

    if (cameraQuadVertexBuffer == nullptr) {
      return;
    }

    cameraQuadVertexBuffer->setBufferAt(*engine, 0,
                                        makeBufferDescriptorCopy<filament::VertexBuffer::BufferDescriptor>(
                                            initialQuad.data(),
                                            initialQuad.size() * sizeof(float)));

    cameraQuadIndexBuffer = filament::IndexBuffer::Builder()
                                .indexCount(6)
                                .bufferType(filament::IndexBuffer::IndexType::USHORT)
                                .build(*engine);

    if (cameraQuadIndexBuffer == nullptr) {
      return;
    }

    cameraQuadIndexBuffer->setBuffer(*engine,
                                     makeBufferDescriptorCopy<filament::IndexBuffer::BufferDescriptor>(kIndices, sizeof(kIndices)));

    cameraBackgroundEntity = utils::EntityManager::get().create();
    filament::Box bounds({-1.0f, -1.0f, 0.0f}, {1.0f, 1.0f, 0.0f});

    filament::RenderableManager::Builder(1)
        .material(0, cameraBackgroundMaterialInstance)
        .geometry(0, filament::RenderableManager::PrimitiveType::TRIANGLES, cameraQuadVertexBuffer, cameraQuadIndexBuffer)
        .boundingBox(bounds)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(7)
        .build(*engine, cameraBackgroundEntity);

#if defined(__ANDROID__)
    if (cameraExternalTexture != nullptr) {
      cameraBackgroundMaterialInstance->setParameter(
          "cameraFeed",
          cameraExternalTexture,
          filament::TextureSampler(
              filament::TextureSampler::MinFilter::LINEAR,
              filament::TextureSampler::MagFilter::LINEAR,
              filament::TextureSampler::WrapMode::CLAMP_TO_EDGE));
    }

    const filament::math::mat3f identityUvTransform(
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f);
    cameraBackgroundMaterialInstance->setParameter("textureTransform", identityUvTransform);
#endif

    scene->addEntity(cameraBackgroundEntity);
    cameraBackgroundInScene = true;
  }

  void createFaceOcclusionMaterial() {
    destroyFaceOcclusionResources();
    if (engine == nullptr || faceOcclusionPackage.empty()) {
      return;
    }

    faceOcclusionMaterial = filament::Material::Builder()
                                .package(faceOcclusionPackage.data(), faceOcclusionPackage.size())
                                .build(*engine);
    if (faceOcclusionMaterial == nullptr) {
      return;
    }

    faceOcclusionMaterialInstance = faceOcclusionMaterial->getDefaultInstance();
    if (faceOcclusionMaterialInstance == nullptr) {
      return;
    }

    faceOcclusionVertexBuffer = filament::VertexBuffer::Builder()
                                    .vertexCount(static_cast<uint32_t>(kMaxFaceVertices))
                                    .bufferCount(1)
                                    .attribute(filament::VertexAttribute::POSITION, 0,
                                               filament::VertexBuffer::AttributeType::FLOAT3, 0, 12)
                                    .build(*engine);
    faceOcclusionIndexBuffer = filament::IndexBuffer::Builder()
                                   .indexCount(static_cast<uint32_t>(kMaxFaceIndices))
                                   .bufferType(filament::IndexBuffer::IndexType::USHORT)
                                   .build(*engine);

    if (faceOcclusionVertexBuffer == nullptr || faceOcclusionIndexBuffer == nullptr) {
      return;
    }

    faceOcclusionEntity = utils::EntityManager::get().create();
    filament::Box bounds({-0.2f, -0.2f, -0.2f}, {0.2f, 0.2f, 0.2f});

    filament::RenderableManager::Builder(1)
        .material(0, faceOcclusionMaterialInstance)
        .geometry(0, filament::RenderableManager::PrimitiveType::TRIANGLES, faceOcclusionVertexBuffer, faceOcclusionIndexBuffer, 0, 0)
        .boundingBox(bounds)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(0)
        .build(*engine, faceOcclusionEntity);

    createBackPlaneResources();
  }

  void createBackPlaneResources() {
    constexpr float planeSizeX = 0.12f;
    constexpr float planeSizeY = 0.08f;
    constexpr float gap = 0.01f;

    const float leftVertices[12] = {
        -planeSizeX, -planeSizeY, 0.0f,
        -gap, -planeSizeY, 0.0f,
        -planeSizeX, planeSizeY, 0.0f,
        -gap, planeSizeY, 0.0f,
    };
    const float rightVertices[12] = {
        gap, -planeSizeY, 0.0f,
        planeSizeX, -planeSizeY, 0.0f,
        gap, planeSizeY, 0.0f,
        planeSizeX, planeSizeY, 0.0f,
    };
    constexpr std::uint16_t planeIndices[6] = {0, 1, 2, 2, 1, 3};

    backPlaneLeftVertexBuffer = filament::VertexBuffer::Builder()
                                    .vertexCount(4)
                                    .bufferCount(1)
                                    .attribute(filament::VertexAttribute::POSITION, 0,
                                               filament::VertexBuffer::AttributeType::FLOAT3, 0, 12)
                                    .build(*engine);
    backPlaneRightVertexBuffer = filament::VertexBuffer::Builder()
                                     .vertexCount(4)
                                     .bufferCount(1)
                                     .attribute(filament::VertexAttribute::POSITION, 0,
                                                filament::VertexBuffer::AttributeType::FLOAT3, 0, 12)
                                     .build(*engine);
    backPlaneIndexBuffer = filament::IndexBuffer::Builder()
                               .indexCount(6)
                               .bufferType(filament::IndexBuffer::IndexType::USHORT)
                               .build(*engine);

    if (backPlaneLeftVertexBuffer == nullptr || backPlaneRightVertexBuffer == nullptr || backPlaneIndexBuffer == nullptr) {
      return;
    }

    backPlaneLeftVertexBuffer->setBufferAt(*engine, 0,
                                           makeBufferDescriptorCopy<filament::VertexBuffer::BufferDescriptor>(leftVertices, sizeof(leftVertices)));
    backPlaneRightVertexBuffer->setBufferAt(*engine, 0,
                                            makeBufferDescriptorCopy<filament::VertexBuffer::BufferDescriptor>(rightVertices, sizeof(rightVertices)));
    backPlaneIndexBuffer->setBuffer(*engine,
                                    makeBufferDescriptorCopy<filament::IndexBuffer::BufferDescriptor>(planeIndices, sizeof(planeIndices)));

    backPlaneLeftEntity = utils::EntityManager::get().create();
    backPlaneRightEntity = utils::EntityManager::get().create();
    filament::Box bounds({-planeSizeX, -planeSizeY, -0.1f}, {planeSizeX, planeSizeY, 0.1f});

    filament::RenderableManager::Builder(1)
        .material(0, faceOcclusionMaterialInstance)
        .geometry(0, filament::RenderableManager::PrimitiveType::TRIANGLES, backPlaneLeftVertexBuffer, backPlaneIndexBuffer)
        .boundingBox(bounds)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(0)
        .build(*engine, backPlaneLeftEntity);

    filament::RenderableManager::Builder(1)
        .material(0, faceOcclusionMaterialInstance)
        .geometry(0, filament::RenderableManager::PrimitiveType::TRIANGLES, backPlaneRightVertexBuffer, backPlaneIndexBuffer)
        .boundingBox(bounds)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(0)
        .build(*engine, backPlaneRightEntity);
  }

  void createDebugMaterials() {
    destroyDebugResources();
    if (engine == nullptr || debugFacePackage.empty() || debugPlanePackage.empty()) {
      return;
    }

    debugFaceMaterial = filament::Material::Builder().package(debugFacePackage.data(), debugFacePackage.size()).build(*engine);
    debugPlaneMaterial = filament::Material::Builder().package(debugPlanePackage.data(), debugPlanePackage.size()).build(*engine);
    if (debugFaceMaterial == nullptr || debugPlaneMaterial == nullptr) {
      return;
    }

    debugFaceMaterialInstance = debugFaceMaterial->createInstance();
    debugLeftPlaneMaterialInstance = debugPlaneMaterial->createInstance();
    debugRightPlaneMaterialInstance = debugPlaneMaterial->createInstance();
    if (debugFaceMaterialInstance == nullptr || debugLeftPlaneMaterialInstance == nullptr || debugRightPlaneMaterialInstance == nullptr) {
      return;
    }

    debugFaceMaterialInstance->setParameter("debugColor", filament::math::float4{1.0f, 0.0f, 0.0f, 0.4f});
#if defined(__ANDROID__)
    debugLeftPlaneMaterialInstance->setParameter("debugColor", filament::math::float4{0.0f, 1.0f, 0.0f, 0.4f});
    debugRightPlaneMaterialInstance->setParameter("debugColor", filament::math::float4{0.0f, 0.0f, 1.0f, 0.4f});
#else
    debugLeftPlaneMaterialInstance->setParameter("debugColor", filament::math::float4{0.0f, 0.0f, 1.0f, 0.4f});
    debugRightPlaneMaterialInstance->setParameter("debugColor", filament::math::float4{0.0f, 1.0f, 0.0f, 0.4f});
#endif

    debugFaceVertexBuffer = filament::VertexBuffer::Builder()
                                .vertexCount(static_cast<uint32_t>(kMaxFaceVertices))
                                .bufferCount(1)
                                .attribute(filament::VertexAttribute::POSITION, 0,
                                           filament::VertexBuffer::AttributeType::FLOAT3, 0, 12)
                                .build(*engine);
    debugFaceIndexBuffer = filament::IndexBuffer::Builder()
                               .indexCount(static_cast<uint32_t>(kMaxFaceIndices))
                               .bufferType(filament::IndexBuffer::IndexType::USHORT)
                               .build(*engine);
    if (debugFaceVertexBuffer == nullptr || debugFaceIndexBuffer == nullptr) {
      return;
    }

    debugFaceEntity = utils::EntityManager::get().create();
    debugLeftPlaneEntity = utils::EntityManager::get().create();
    debugRightPlaneEntity = utils::EntityManager::get().create();

    filament::Box faceBounds({-0.2f, -0.2f, -0.2f}, {0.2f, 0.2f, 0.2f});
    filament::RenderableManager::Builder(1)
        .material(0, debugFaceMaterialInstance)
        .geometry(0, filament::RenderableManager::PrimitiveType::TRIANGLES, debugFaceVertexBuffer, debugFaceIndexBuffer, 0, 0)
        .boundingBox(faceBounds)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(7)
        .build(*engine, debugFaceEntity);

    if (backPlaneLeftVertexBuffer == nullptr || backPlaneRightVertexBuffer == nullptr || backPlaneIndexBuffer == nullptr) {
      return;
    }

    constexpr float planeSizeX = 0.12f;
    constexpr float planeSizeY = 0.08f;
    filament::Box planeBounds({-planeSizeX, -planeSizeY, -0.1f}, {planeSizeX, planeSizeY, 0.1f});

    filament::RenderableManager::Builder(1)
        .material(0, debugLeftPlaneMaterialInstance)
        .geometry(0, filament::RenderableManager::PrimitiveType::TRIANGLES, backPlaneLeftVertexBuffer, backPlaneIndexBuffer)
        .boundingBox(planeBounds)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(8)
        .build(*engine, debugLeftPlaneEntity);

    filament::RenderableManager::Builder(1)
        .material(0, debugRightPlaneMaterialInstance)
        .geometry(0, filament::RenderableManager::PrimitiveType::TRIANGLES, backPlaneRightVertexBuffer, backPlaneIndexBuffer)
        .boundingBox(planeBounds)
        .culling(false)
        .receiveShadows(false)
        .castShadows(false)
        .priority(8)
        .build(*engine, debugRightPlaneEntity);
  }

  void createEnvironmentResources() {
    if (engine == nullptr || scene == nullptr) {
      return;
    }

    destroyEnvironmentResources();

    if (!iblKtxBytes.empty()) {
      auto* iblBundle = new image::Ktx1Bundle(iblKtxBytes.data(), static_cast<uint32_t>(iblKtxBytes.size()));
      iblTexture = ktxreader::Ktx1Reader::createTexture(engine, iblBundle, false);
      if (iblTexture != nullptr) {
        filament::IndirectLight::Builder builder;
        builder.reflections(iblTexture).intensity(kBaseSunIntensity);
        if (hasSh27) {
          filament::math::float3 sh[9];
          for (int i = 0; i < 9; ++i) {
            sh[i] = {sh27[i * 3], sh27[i * 3 + 1], sh27[i * 3 + 2]};
          }
          builder.irradiance(3, sh);
        }
        indirectLight = builder.build(*engine);
        if (indirectLight != nullptr) {
          scene->setIndirectLight(indirectLight);
        }
      }
    }

    if (!skyboxKtxBytes.empty()) {
      auto* skyBundle = new image::Ktx1Bundle(skyboxKtxBytes.data(), static_cast<uint32_t>(skyboxKtxBytes.size()));
      skyboxTexture = ktxreader::Ktx1Reader::createTexture(engine, skyBundle, false);
      if (skyboxTexture != nullptr) {
        skybox = filament::Skybox::Builder().environment(skyboxTexture).build(*engine);
        if (skybox != nullptr) {
          scene->setSkybox(skybox);
        }
      }
    }
  }

  bool setRenderTarget(void* nativeRenderTarget) {
    if (engine == nullptr) {
      return false;
    }

    if (swapChain != nullptr) {
      engine->destroy(swapChain);
      swapChain = nullptr;
    }

#if defined(__ANDROID__)
    if (nativeWindow != nullptr) {
      ANativeWindow_release(nativeWindow);
      nativeWindow = nullptr;
    }

    if (nativeRenderTarget == nullptr) {
      return false;
    }

    nativeWindow = reinterpret_cast<ANativeWindow*>(nativeRenderTarget);
    swapChain = engine->createSwapChain(nativeWindow);
#else
    if (nativeRenderTarget == nullptr) {
      return false;
    }
    swapChain = engine->createSwapChain(nativeRenderTarget);
#endif

    return swapChain != nullptr;
  }

  bool setMaterialPackage(MaterialKind kind, const std::uint8_t* bytes, std::size_t size) {
    if (bytes == nullptr || size == 0) {
      return false;
    }

    std::vector<std::uint8_t> data(bytes, bytes + size);
    switch (kind) {
      case MaterialKind::CameraBackground:
        cameraBackgroundPackage = std::move(data);
        if (engine != nullptr) {
          createCameraBackgroundMaterial();
        }
        return true;
      case MaterialKind::FaceOcclusion:
        faceOcclusionPackage = std::move(data);
        if (engine != nullptr) {
          createFaceOcclusionMaterial();
          if (!debugFacePackage.empty() && !debugPlanePackage.empty()) {
            createDebugMaterials();
          }
        }
        return true;
      case MaterialKind::DebugFace:
        debugFacePackage = std::move(data);
        if (engine != nullptr && !faceOcclusionPackage.empty() && !debugPlanePackage.empty()) {
          createDebugMaterials();
        }
        return true;
      case MaterialKind::DebugPlane:
        debugPlanePackage = std::move(data);
        if (engine != nullptr && !faceOcclusionPackage.empty() && !debugFacePackage.empty()) {
          createDebugMaterials();
        }
        return true;
    }
    return false;
  }

  bool setEnvironmentIblKtx(const std::uint8_t* bytes, std::size_t size) {
    if (bytes == nullptr || size == 0) {
      return false;
    }
    iblKtxBytes.assign(bytes, bytes + size);
    if (engine != nullptr) {
      createEnvironmentResources();
    }
    return true;
  }

  bool setEnvironmentSkyboxKtx(const std::uint8_t* bytes, std::size_t size) {
    if (bytes == nullptr || size == 0) {
      return false;
    }
    skyboxKtxBytes.assign(bytes, bytes + size);
    if (engine != nullptr) {
      createEnvironmentResources();
    }
    return true;
  }

  void setEnvironmentSh(const float* sh) {
    if (sh == nullptr) {
      hasSh27 = false;
      return;
    }
    std::memcpy(sh27, sh, sizeof(float) * 27);
    hasSh27 = true;
    if (engine != nullptr) {
      createEnvironmentResources();
    }
  }

  bool setCameraStream(void* nativeStream, int streamWidth, int streamHeight) {
#if defined(__ANDROID__)
    const auto nextHandle = reinterpret_cast<std::uintptr_t>(nativeStream);
    const auto previousHandle = cameraStreamHandle;
    cameraStreamHandle = nextHandle;

    if (engine == nullptr) {
      return true;
    }

    if (cameraStream != nullptr && previousHandle == nextHandle) {
      cameraStream->setDimensions(
          static_cast<uint32_t>(std::max(streamWidth, 1)),
          static_cast<uint32_t>(std::max(streamHeight, 1)));
      return true;
    }

    if (cameraExternalTexture != nullptr) {
      cameraExternalTexture->setExternalStream(*engine, nullptr);
    }
    if (cameraStream != nullptr) {
      engine->destroy(cameraStream);
      cameraStream = nullptr;
    }

    if (nativeStream == nullptr) {
      return true;
    }

    cameraStream = filament::Stream::Builder()
                       .stream(nativeStream)
                       .width(static_cast<uint32_t>(std::max(streamWidth, 1)))
                       .height(static_cast<uint32_t>(std::max(streamHeight, 1)))
                       .build(*engine);

    if (cameraStream == nullptr) {
      return false;
    }

    if (cameraExternalTexture != nullptr) {
      cameraExternalTexture->setExternalStream(*engine, cameraStream);
      if (cameraBackgroundMaterialInstance != nullptr) {
        cameraBackgroundMaterialInstance->setParameter(
            "cameraFeed",
            cameraExternalTexture,
            filament::TextureSampler(
                filament::TextureSampler::MinFilter::LINEAR,
                filament::TextureSampler::MagFilter::LINEAR,
                filament::TextureSampler::WrapMode::CLAMP_TO_EDGE));
      }
    }

    return true;
#else
    (void)nativeStream;
    (void)streamWidth;
    (void)streamHeight;
    return false;
#endif
  }

  void updateCamera() {
    if (camera == nullptr || !hasCameraMatrices) {
      return;
    }

    camera->setCustomProjection(toMat4(projection), 0.01, 100.0);
    camera->setModelMatrix(toMat4f(model));
  }

  void updateCameraBackground() {
    if (cameraBackgroundMaterialInstance == nullptr) {
      return;
    }

#if defined(__ANDROID__)
    if (cameraExternalTexture == nullptr) {
      return;
    }

    if (cameraExternalTexture != nullptr) {
      cameraBackgroundMaterialInstance->setParameter(
          "cameraFeed",
          cameraExternalTexture,
          filament::TextureSampler(
              filament::TextureSampler::MinFilter::LINEAR,
              filament::TextureSampler::MagFilter::LINEAR,
              filament::TextureSampler::WrapMode::CLAMP_TO_EDGE));
    }

    const filament::math::mat3f identityUvTransform(
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f);
    cameraBackgroundMaterialInstance->setParameter("textureTransform", identityUvTransform);

    if (cameraQuadVertexBuffer != nullptr) {
      const auto vertices = makeQuadVertices(cameraHasUvCoords ? cameraUvCoords8 : nullptr, true);
      cameraQuadVertexBuffer->setBufferAt(*engine, 0,
                                          makeBufferDescriptorCopy<filament::VertexBuffer::BufferDescriptor>(
                                              vertices.data(),
                                              vertices.size() * sizeof(float)));
    }
#else
    if (iosCameraExternalTexture != nullptr && cameraFeedHandle != 0) {
      iosCameraExternalTexture->setExternalImage(*engine, reinterpret_cast<void*>(cameraFeedHandle));
      cameraBackgroundMaterialInstance->setParameter(
          "cameraFeed",
          iosCameraExternalTexture,
          filament::TextureSampler(
              filament::TextureSampler::MinFilter::LINEAR,
              filament::TextureSampler::MagFilter::LINEAR,
              filament::TextureSampler::WrapMode::CLAMP_TO_EDGE));

      const filament::math::mat3f uvTransform(
          cameraUvTransform[0], cameraUvTransform[1], cameraUvTransform[2],
          cameraUvTransform[3], cameraUvTransform[4], cameraUvTransform[5],
          cameraUvTransform[6], cameraUvTransform[7], cameraUvTransform[8]);
      cameraBackgroundMaterialInstance->setParameter("textureTransform", uvTransform);
    }
#endif
  }

  void updateModelTransform() {
    if (engine == nullptr || glassesAsset == nullptr) {
      return;
    }
    filament::TransformManager& tm = engine->getTransformManager();
    auto root = tm.getInstance(glassesAsset->getRoot());
    if (!root) {
      return;
    }

    if (glassesVisible) {
      tm.setTransform(root, toMat4f(glassesTransform));
    } else {
      float hide[16];
      setHideTransform(hide);
      tm.setTransform(root, toMat4f(hide));
    }
  }

  void updateFaceOcclusion() {
    if (engine == nullptr || faceOcclusionVertexBuffer == nullptr || faceOcclusionIndexBuffer == nullptr || !faceOcclusionEntity) {
      return;
    }

    if (!hasFace || faceVertices.empty() || faceIndices.empty() || !faceMeshOcclusionEnabled) {
      if (faceOcclusionInScene) {
        scene->remove(faceOcclusionEntity);
        faceOcclusionInScene = false;
      }
      if (backPlaneLeftInScene) {
        scene->remove(backPlaneLeftEntity);
        backPlaneLeftInScene = false;
      }
      if (backPlaneRightInScene) {
        scene->remove(backPlaneRightEntity);
        backPlaneRightInScene = false;
      }
      return;
    }

    const auto safeVertexCount = std::min(faceVertices.size() / 3, kMaxFaceVertices);
    const auto safeIndexCount = std::min(faceIndices.size(), kMaxFaceIndices);
    if (safeVertexCount == 0 || safeIndexCount == 0) {
      return;
    }

    faceOcclusionVertexBuffer->setBufferAt(*engine, 0,
                                           filament::VertexBuffer::BufferDescriptor(faceVertices.data(), safeVertexCount * 3 * sizeof(float), nullptr));
    faceOcclusionIndexBuffer->setBuffer(*engine,
                                        filament::IndexBuffer::BufferDescriptor(faceIndices.data(), safeIndexCount * sizeof(std::uint16_t), nullptr));

    auto& rm = engine->getRenderableManager();
    auto instance = rm.getInstance(faceOcclusionEntity);
    rm.setGeometryAt(instance, 0, filament::RenderableManager::PrimitiveType::TRIANGLES, faceOcclusionVertexBuffer, faceOcclusionIndexBuffer, 0,
                     static_cast<uint32_t>(safeIndexCount));

    auto& tm = engine->getTransformManager();
    tm.setTransform(tm.getInstance(faceOcclusionEntity), toMat4f(faceTransform));
    tm.setTransform(tm.getInstance(backPlaneLeftEntity), toMat4f(backPlaneTransform));
    tm.setTransform(tm.getInstance(backPlaneRightEntity), toMat4f(backPlaneTransform));

    if (!faceOcclusionInScene) {
      scene->addEntity(faceOcclusionEntity);
      faceOcclusionInScene = true;
    }

    const bool wantLeft = backPlaneOcclusionEnabled && leftBackPlaneVisible;
    const bool wantRight = backPlaneOcclusionEnabled && rightBackPlaneVisible;

    if (wantLeft && !backPlaneLeftInScene) {
      scene->addEntity(backPlaneLeftEntity);
      backPlaneLeftInScene = true;
    } else if (!wantLeft && backPlaneLeftInScene) {
      scene->remove(backPlaneLeftEntity);
      backPlaneLeftInScene = false;
    }

    if (wantRight && !backPlaneRightInScene) {
      scene->addEntity(backPlaneRightEntity);
      backPlaneRightInScene = true;
    } else if (!wantRight && backPlaneRightInScene) {
      scene->remove(backPlaneRightEntity);
      backPlaneRightInScene = false;
    }
  }

  void updateDebug() {
    if (engine == nullptr || debugFaceVertexBuffer == nullptr || debugFaceIndexBuffer == nullptr) {
      return;
    }

    if (!debugEnabled || !hasFace || faceVertices.empty() || faceIndices.empty()) {
      if (debugFaceInScene) {
        scene->remove(debugFaceEntity);
        debugFaceInScene = false;
      }
      if (debugLeftPlaneInScene) {
        scene->remove(debugLeftPlaneEntity);
        debugLeftPlaneInScene = false;
      }
      if (debugRightPlaneInScene) {
        scene->remove(debugRightPlaneEntity);
        debugRightPlaneInScene = false;
      }
      return;
    }

    const auto safeVertexCount = std::min(faceVertices.size() / 3, kMaxFaceVertices);
    const auto safeIndexCount = std::min(faceIndices.size(), kMaxFaceIndices);
    if (safeVertexCount == 0 || safeIndexCount == 0) {
      return;
    }

    debugFaceVertexBuffer->setBufferAt(*engine, 0,
                                       filament::VertexBuffer::BufferDescriptor(faceVertices.data(), safeVertexCount * 3 * sizeof(float), nullptr));
    debugFaceIndexBuffer->setBuffer(*engine,
                                    filament::IndexBuffer::BufferDescriptor(faceIndices.data(), safeIndexCount * sizeof(std::uint16_t), nullptr));

    auto& rm = engine->getRenderableManager();
    auto debugFaceInstance = rm.getInstance(debugFaceEntity);
    rm.setGeometryAt(debugFaceInstance, 0, filament::RenderableManager::PrimitiveType::TRIANGLES, debugFaceVertexBuffer, debugFaceIndexBuffer, 0,
                     static_cast<uint32_t>(safeIndexCount));

    auto& tm = engine->getTransformManager();
    tm.setTransform(tm.getInstance(debugFaceEntity), toMat4f(faceTransform));
    tm.setTransform(tm.getInstance(debugLeftPlaneEntity), toMat4f(backPlaneTransform));
    tm.setTransform(tm.getInstance(debugRightPlaneEntity), toMat4f(backPlaneTransform));

    if (!debugFaceInScene) {
      scene->addEntity(debugFaceEntity);
      debugFaceInScene = true;
    }

    if (leftBackPlaneVisible && !debugLeftPlaneInScene) {
      scene->addEntity(debugLeftPlaneEntity);
      debugLeftPlaneInScene = true;
    } else if (!leftBackPlaneVisible && debugLeftPlaneInScene) {
      scene->remove(debugLeftPlaneEntity);
      debugLeftPlaneInScene = false;
    }

    if (rightBackPlaneVisible && !debugRightPlaneInScene) {
      scene->addEntity(debugRightPlaneEntity);
      debugRightPlaneInScene = true;
    } else if (!rightBackPlaneVisible && debugRightPlaneInScene) {
      scene->remove(debugRightPlaneEntity);
      debugRightPlaneInScene = false;
    }
  }

  bool loadModel(const std::uint8_t* bytes, std::size_t size) {
    if (assetLoader == nullptr || resourceLoader == nullptr || scene == nullptr || bytes == nullptr || size == 0) {
      return false;
    }

    if (glassesAsset != nullptr) {
      scene->removeEntities(glassesAsset->getEntities(), glassesAsset->getEntityCount());
      assetLoader->destroyAsset(glassesAsset);
      glassesAsset = nullptr;
    }

    glassesAsset = assetLoader->createAsset(bytes, static_cast<uint32_t>(size));
    if (glassesAsset == nullptr) {
      return false;
    }

    resourceLoader->loadResources(glassesAsset);
    glassesAsset->releaseSourceData();
    scene->addEntities(glassesAsset->getEntities(), glassesAsset->getEntityCount());
    return true;
  }

  void renderFrame() {
    if (renderer == nullptr || view == nullptr || swapChain == nullptr) {
      return;
    }

    updateCamera();
    updateCameraBackground();
    updateModelTransform();
    updateFaceOcclusion();
    updateDebug();

    if (engine != nullptr && sunEntity) {
      auto& lm = engine->getLightManager();
      auto light = lm.getInstance(sunEntity);
      if (light) {
        lm.setIntensity(light, kBaseSunIntensity * lightIntensityScale);
      }
    }

    if (indirectLight != nullptr) {
      indirectLight->setIntensity(kBaseSunIntensity * lightIntensityScale);
    }

    if (renderer->beginFrame(swapChain)) {
      renderer->render(view);
      renderer->endFrame();
    }
  }

  void destroyCameraBackgroundResources() {
    if (engine == nullptr) {
      return;
    }

    if (cameraBackgroundInScene) {
      scene->remove(cameraBackgroundEntity);
      cameraBackgroundInScene = false;
    }

    if (cameraBackgroundEntity) {
      engine->destroy(cameraBackgroundEntity);
      utils::EntityManager::get().destroy(cameraBackgroundEntity);
      cameraBackgroundEntity = {};
    }

#if defined(__ANDROID__)
    if (cameraExternalTexture != nullptr) {
      cameraExternalTexture->setExternalStream(*engine, nullptr);
      engine->destroy(cameraExternalTexture);
      cameraExternalTexture = nullptr;
    }

    if (cameraStream != nullptr) {
      engine->destroy(cameraStream);
      cameraStream = nullptr;
    }
    cameraStreamHandle = 0;
#else
    if (iosCameraExternalTexture != nullptr) {
      engine->destroy(iosCameraExternalTexture);
      iosCameraExternalTexture = nullptr;
    }
#endif

    if (cameraQuadVertexBuffer != nullptr) {
      engine->destroy(cameraQuadVertexBuffer);
      cameraQuadVertexBuffer = nullptr;
    }
    if (cameraQuadIndexBuffer != nullptr) {
      engine->destroy(cameraQuadIndexBuffer);
      cameraQuadIndexBuffer = nullptr;
    }
    if (cameraBackgroundMaterial != nullptr) {
      engine->destroy(cameraBackgroundMaterial);
      cameraBackgroundMaterial = nullptr;
      cameraBackgroundMaterialInstance = nullptr;
    }
  }

  void destroyFaceOcclusionResources() {
    if (engine == nullptr) {
      return;
    }

    if (faceOcclusionInScene) {
      scene->remove(faceOcclusionEntity);
      faceOcclusionInScene = false;
    }
    if (backPlaneLeftInScene) {
      scene->remove(backPlaneLeftEntity);
      backPlaneLeftInScene = false;
    }
    if (backPlaneRightInScene) {
      scene->remove(backPlaneRightEntity);
      backPlaneRightInScene = false;
    }

    if (faceOcclusionEntity) {
      engine->destroy(faceOcclusionEntity);
      utils::EntityManager::get().destroy(faceOcclusionEntity);
      faceOcclusionEntity = {};
    }
    if (backPlaneLeftEntity) {
      engine->destroy(backPlaneLeftEntity);
      utils::EntityManager::get().destroy(backPlaneLeftEntity);
      backPlaneLeftEntity = {};
    }
    if (backPlaneRightEntity) {
      engine->destroy(backPlaneRightEntity);
      utils::EntityManager::get().destroy(backPlaneRightEntity);
      backPlaneRightEntity = {};
    }

    if (faceOcclusionVertexBuffer != nullptr) {
      engine->destroy(faceOcclusionVertexBuffer);
      faceOcclusionVertexBuffer = nullptr;
    }
    if (faceOcclusionIndexBuffer != nullptr) {
      engine->destroy(faceOcclusionIndexBuffer);
      faceOcclusionIndexBuffer = nullptr;
    }
    if (backPlaneLeftVertexBuffer != nullptr) {
      engine->destroy(backPlaneLeftVertexBuffer);
      backPlaneLeftVertexBuffer = nullptr;
    }
    if (backPlaneRightVertexBuffer != nullptr) {
      engine->destroy(backPlaneRightVertexBuffer);
      backPlaneRightVertexBuffer = nullptr;
    }
    if (backPlaneIndexBuffer != nullptr) {
      engine->destroy(backPlaneIndexBuffer);
      backPlaneIndexBuffer = nullptr;
    }

    if (faceOcclusionMaterial != nullptr) {
      engine->destroy(faceOcclusionMaterial);
      faceOcclusionMaterial = nullptr;
      faceOcclusionMaterialInstance = nullptr;
    }
  }

  void destroyDebugResources() {
    if (engine == nullptr) {
      return;
    }

    if (debugFaceInScene) {
      scene->remove(debugFaceEntity);
      debugFaceInScene = false;
    }
    if (debugLeftPlaneInScene) {
      scene->remove(debugLeftPlaneEntity);
      debugLeftPlaneInScene = false;
    }
    if (debugRightPlaneInScene) {
      scene->remove(debugRightPlaneEntity);
      debugRightPlaneInScene = false;
    }

    if (debugFaceEntity) {
      engine->destroy(debugFaceEntity);
      utils::EntityManager::get().destroy(debugFaceEntity);
      debugFaceEntity = {};
    }
    if (debugLeftPlaneEntity) {
      engine->destroy(debugLeftPlaneEntity);
      utils::EntityManager::get().destroy(debugLeftPlaneEntity);
      debugLeftPlaneEntity = {};
    }
    if (debugRightPlaneEntity) {
      engine->destroy(debugRightPlaneEntity);
      utils::EntityManager::get().destroy(debugRightPlaneEntity);
      debugRightPlaneEntity = {};
    }

    if (debugFaceVertexBuffer != nullptr) {
      engine->destroy(debugFaceVertexBuffer);
      debugFaceVertexBuffer = nullptr;
    }
    if (debugFaceIndexBuffer != nullptr) {
      engine->destroy(debugFaceIndexBuffer);
      debugFaceIndexBuffer = nullptr;
    }

    if (debugFaceMaterialInstance != nullptr) {
      engine->destroy(debugFaceMaterialInstance);
      debugFaceMaterialInstance = nullptr;
    }
    if (debugLeftPlaneMaterialInstance != nullptr) {
      engine->destroy(debugLeftPlaneMaterialInstance);
      debugLeftPlaneMaterialInstance = nullptr;
    }
    if (debugRightPlaneMaterialInstance != nullptr) {
      engine->destroy(debugRightPlaneMaterialInstance);
      debugRightPlaneMaterialInstance = nullptr;
    }

    if (debugFaceMaterial != nullptr) {
      engine->destroy(debugFaceMaterial);
      debugFaceMaterial = nullptr;
    }
    if (debugPlaneMaterial != nullptr) {
      engine->destroy(debugPlaneMaterial);
      debugPlaneMaterial = nullptr;
    }
  }

  void destroyEnvironmentResources() {
    if (engine == nullptr) {
      return;
    }
    if (scene != nullptr) {
      scene->setIndirectLight(nullptr);
      scene->setSkybox(nullptr);
    }
    if (indirectLight != nullptr) {
      engine->destroy(indirectLight);
      indirectLight = nullptr;
    }
    if (skybox != nullptr) {
      engine->destroy(skybox);
      skybox = nullptr;
    }
    if (iblTexture != nullptr) {
      engine->destroy(iblTexture);
      iblTexture = nullptr;
    }
    if (skyboxTexture != nullptr) {
      engine->destroy(skyboxTexture);
      skyboxTexture = nullptr;
    }
  }

  void destroy() {
    if (engine == nullptr) {
#if defined(__ANDROID__)
      destroyAndroidGlInterop();
#endif
      return;
    }

    if (glassesAsset != nullptr) {
      if (scene != nullptr) {
        scene->removeEntities(glassesAsset->getEntities(), glassesAsset->getEntityCount());
      }
      if (assetLoader != nullptr) {
        assetLoader->destroyAsset(glassesAsset);
      }
      glassesAsset = nullptr;
    }

    destroyDebugResources();
    destroyFaceOcclusionResources();
    destroyCameraBackgroundResources();
    destroyEnvironmentResources();

    if (resourceLoader != nullptr) {
      delete resourceLoader;
      resourceLoader = nullptr;
    }

    if (textureProvider != nullptr) {
      delete textureProvider;
      textureProvider = nullptr;
    }

    if (assetLoader != nullptr) {
      filament::gltfio::AssetLoader::destroy(&assetLoader);
    }

    if (gltfMaterialProvider != nullptr) {
      gltfMaterialProvider->destroyMaterials();
      delete gltfMaterialProvider;
      gltfMaterialProvider = nullptr;
    }

    if (sunEntity && scene != nullptr) {
      scene->remove(sunEntity);
      engine->destroy(sunEntity);
      utils::EntityManager::get().destroy(sunEntity);
      sunEntity = {};
    }

    if (colorGrading != nullptr) {
      engine->destroy(colorGrading);
      colorGrading = nullptr;
    }

    if (camera != nullptr) {
      engine->destroyCameraComponent(cameraEntity);
      utils::EntityManager::get().destroy(cameraEntity);
      camera = nullptr;
      cameraEntity = {};
    }

    if (swapChain != nullptr) {
      engine->destroy(swapChain);
      swapChain = nullptr;
    }

    if (view != nullptr) {
      engine->destroy(view);
      view = nullptr;
    }

    if (scene != nullptr) {
      engine->destroy(scene);
      scene = nullptr;
    }

    if (renderer != nullptr) {
      engine->destroy(renderer);
      renderer = nullptr;
    }

#if defined(__ANDROID__)
    if (nativeWindow != nullptr) {
      ANativeWindow_release(nativeWindow);
      nativeWindow = nullptr;
    }
#endif

    filament::Engine::destroy(&engine);

#if defined(__ANDROID__)
    destroyAndroidGlInterop();
#endif
  }

#if defined(__ANDROID__)
  bool createAndroidGlInterop() {
    eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
      return false;
    }

    EGLint major = 0;
    EGLint minor = 0;
    if (!eglInitialize(eglDisplay, &major, &minor)) {
      return false;
    }

    const EGLint configAttrs[] = {
        EGL_RENDERABLE_TYPE,
        EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE,
        8,
        EGL_GREEN_SIZE,
        8,
        EGL_BLUE_SIZE,
        8,
        EGL_ALPHA_SIZE,
        8,
        EGL_DEPTH_SIZE,
        16,
        EGL_NONE,
    };

    EGLConfig config = nullptr;
    EGLint numConfigs = 0;
    if (!eglChooseConfig(eglDisplay, configAttrs, &config, 1, &numConfigs) || numConfigs == 0) {
      return false;
    }

    const EGLint contextAttrs[] = {
        EGL_CONTEXT_CLIENT_VERSION,
        3,
        EGL_NONE,
    };
    eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, contextAttrs);
    if (eglContext == EGL_NO_CONTEXT) {
      return false;
    }

    const EGLint surfaceAttrs[] = {
        EGL_WIDTH,
        1,
        EGL_HEIGHT,
        1,
        EGL_NONE,
    };
    eglSurface = eglCreatePbufferSurface(eglDisplay, config, surfaceAttrs);
    if (eglSurface == EGL_NO_SURFACE) {
      return false;
    }

    if (!eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      return false;
    }

    std::array<GLuint, kCameraTextureCount> ids = {};
    glGenTextures(static_cast<GLsizei>(ids.size()), ids.data());
    for (std::size_t i = 0; i < ids.size(); ++i) {
      if (ids[i] == 0) {
        continue;
      }
      glBindTexture(GL_TEXTURE_EXTERNAL_OES, ids[i]);
      glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
      glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
      glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
      cameraTextureIds[i] = ids[i];
    }

    return true;
  }

  void destroyAndroidGlInterop() {
    if (eglDisplay != EGL_NO_DISPLAY && eglContext != EGL_NO_CONTEXT && eglSurface != EGL_NO_SURFACE) {
      eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
      std::array<GLuint, kCameraTextureCount> ids = {};
      for (std::size_t i = 0; i < cameraTextureIds.size(); ++i) {
        ids[i] = static_cast<GLuint>(cameraTextureIds[i]);
      }
      glDeleteTextures(static_cast<GLsizei>(ids.size()), ids.data());
    }

    if (eglDisplay != EGL_NO_DISPLAY) {
      if (eglSurface != EGL_NO_SURFACE) {
        eglDestroySurface(eglDisplay, eglSurface);
      }
      if (eglContext != EGL_NO_CONTEXT) {
        eglDestroyContext(eglDisplay, eglContext);
      }
      eglTerminate(eglDisplay);
    }

    eglSurface = EGL_NO_SURFACE;
    eglContext = EGL_NO_CONTEXT;
    eglDisplay = EGL_NO_DISPLAY;
    cameraTextureIds.fill(0);
  }

  void makeCameraContextCurrent() {
    if (eglDisplay != EGL_NO_DISPLAY && eglSurface != EGL_NO_SURFACE && eglContext != EGL_NO_CONTEXT) {
      eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }
  }
#endif
};

FilamentRenderer::FilamentRenderer(RenderBackend backend) : impl_(new Impl(backend)) {}

FilamentRenderer::~FilamentRenderer() {
  delete impl_;
  impl_ = nullptr;
}

bool FilamentRenderer::initialize(void* nativeRenderTarget) {
  return impl_->initialize(nativeRenderTarget);
}

bool FilamentRenderer::setRenderTarget(void* nativeRenderTarget) {
  return impl_->setRenderTarget(nativeRenderTarget);
}

void FilamentRenderer::resize(int width, int height) {
  impl_->width = std::max(width, 1);
  impl_->height = std::max(height, 1);
  if (impl_->view != nullptr) {
    impl_->view->setViewport({0, 0, static_cast<uint32_t>(impl_->width), static_cast<uint32_t>(impl_->height)});
  }
#if defined(__ANDROID__)
  if (impl_->cameraStream != nullptr) {
    impl_->cameraStream->setDimensions(static_cast<uint32_t>(impl_->width), static_cast<uint32_t>(impl_->height));
  }
#endif
}

bool FilamentRenderer::setMaterialPackage(MaterialKind kind, const std::uint8_t* bytes, std::size_t size) {
  return impl_->setMaterialPackage(kind, bytes, size);
}

bool FilamentRenderer::setEnvironmentIblKtx(const std::uint8_t* bytes, std::size_t size) {
  return impl_->setEnvironmentIblKtx(bytes, size);
}

bool FilamentRenderer::setEnvironmentSkyboxKtx(const std::uint8_t* bytes, std::size_t size) {
  return impl_->setEnvironmentSkyboxKtx(bytes, size);
}

void FilamentRenderer::setEnvironmentSphericalHarmonics(const float* sh27) {
  impl_->setEnvironmentSh(sh27);
}

void FilamentRenderer::makeCameraContextCurrent() {
#if defined(__ANDROID__)
  impl_->makeCameraContextCurrent();
#endif
}

std::size_t FilamentRenderer::getCameraTextureIds(std::uint32_t* outTextureIds, std::size_t capacity) const {
#if defined(__ANDROID__)
  if (outTextureIds == nullptr || capacity == 0) {
    return impl_->cameraTextureIds.size();
  }
  const auto n = std::min(capacity, impl_->cameraTextureIds.size());
  for (std::size_t i = 0; i < n; ++i) {
    outTextureIds[i] = impl_->cameraTextureIds[i];
  }
  return n;
#else
  (void)outTextureIds;
  (void)capacity;
  return 0;
#endif
}

bool FilamentRenderer::setCameraStream(void* nativeStream, int width, int height) {
  return impl_->setCameraStream(nativeStream, width, height);
}

bool FilamentRenderer::setModelFromBytes(const std::uint8_t* bytes, std::size_t size) {
  return impl_->loadModel(bytes, size);
}

void FilamentRenderer::setCameraMatrices(const float* projection16, const float* model16) {
  if (projection16 == nullptr || model16 == nullptr) {
    impl_->hasCameraMatrices = false;
    return;
  }
  std::memcpy(impl_->projection, projection16, sizeof(float) * 16);
  std::memcpy(impl_->model, model16, sizeof(float) * 16);
  impl_->hasCameraMatrices = true;
}

void FilamentRenderer::setCameraFeed(const CameraFeedState& state) {
  impl_->cameraFeedHandle = state.handle;
  std::memcpy(impl_->cameraUvTransform, state.uvTransform3x3, sizeof(float) * 9);
  std::memcpy(impl_->cameraUvCoords8, state.uvCoords8, sizeof(float) * 8);
  impl_->cameraHasUvCoords = state.hasUvCoords;
}

void FilamentRenderer::setFaceRenderState(const FaceRenderState& state) {
  impl_->hasFace = state.hasFace;
  impl_->faceMeshOcclusionEnabled = state.faceMeshOcclusionEnabled;
  impl_->backPlaneOcclusionEnabled = state.backPlaneOcclusionEnabled;
  impl_->leftBackPlaneVisible = state.leftBackPlaneVisible;
  impl_->rightBackPlaneVisible = state.rightBackPlaneVisible;
  impl_->debugEnabled = state.debugEnabled;

  if (state.faceTransform16 != nullptr) {
    std::memcpy(impl_->faceTransform, state.faceTransform16, sizeof(float) * 16);
  }
  if (state.backPlaneTransform16 != nullptr) {
    std::memcpy(impl_->backPlaneTransform, state.backPlaneTransform16, sizeof(float) * 16);
  }

  if (state.vertices != nullptr && state.vertexCount > 0) {
    const auto safeVertexCount = std::min(state.vertexCount, kMaxFaceVertices);
    impl_->faceVertices.assign(state.vertices, state.vertices + safeVertexCount * 3);
  } else {
    impl_->faceVertices.clear();
  }

  if (state.indices != nullptr && state.indexCount > 0) {
    const auto safeIndexCount = std::min(state.indexCount, kMaxFaceIndices);
    impl_->faceIndices.assign(state.indices, state.indices + safeIndexCount);
  } else {
    impl_->faceIndices.clear();
  }
}

void FilamentRenderer::setLightIntensityScale(float intensityScale) {
  impl_->lightIntensityScale = intensityScale;
}

void FilamentRenderer::setGlassesTransform(const float* worldTransform16, bool visible) {
  impl_->glassesVisible = visible;
  if (worldTransform16 != nullptr) {
    std::memcpy(impl_->glassesTransform, worldTransform16, sizeof(float) * 16);
  }
}

void FilamentRenderer::render() {
  impl_->renderFrame();
}

void FilamentRenderer::destroy() {
  impl_->destroy();
}

} // namespace margelo::nitro::nitrovto::core::render
