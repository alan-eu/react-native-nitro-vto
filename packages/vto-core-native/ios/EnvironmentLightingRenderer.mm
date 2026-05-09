#import "EnvironmentLightingRenderer.h"
#import "LoaderUtils.h"
#import "LightingConstants.h"

#import <simd/simd.h>

#include <filament/Engine.h>
#include <filament/LightManager.h>
#include <filament/Scene.h>
#include <filament/IndirectLight.h>
#include <filament/Skybox.h>
#include <filament/Texture.h>
#include <ktxreader/Ktx1Reader.h>
#include <math/vec3.h>
#include <utils/Entity.h>
#include <utils/EntityManager.h>

using namespace filament;
using namespace utils;

static NSString *const TAG = @"EnvironmentLighting";

// IBL intensity tuning lives in LightingConstants.h.

@interface EnvironmentLightingRenderer ()

@property (nonatomic, assign) Engine *engine;
@property (nonatomic, assign) Scene *scene;
@property (nonatomic, assign) IndirectLight *indirectLight;
@property (nonatomic, assign) Skybox *skybox;
@property (nonatomic, assign) Texture *iblTexture;
@property (nonatomic, assign) Texture *skyboxTexture;

// Directional light driven per-frame from ARDirectionalLightEstimate.
@property (nonatomic, assign) Entity directionalLightEntity;
@property (nonatomic, assign) BOOL directionalLightAddedToScene;

@end

@implementation EnvironmentLightingRenderer

- (void)setupWithEngine:(Engine *)engine scene:(Scene *)scene {
    _engine = engine;
    _scene = scene;

    // Load IBL (indirect light) from ktx file
    NSData *iblData = [LoaderUtils loadAssetNamed:@"envs/studio_small_02_2k_ibl.ktx"];
    if (iblData) {
        // Create Ktx1Bundle from raw bytes
        auto iblBundle = new image::Ktx1Bundle(
            (const uint8_t *)iblData.bytes,
            (uint32_t)iblData.length
        );
        // Create texture from bundle (takes ownership and destroys bundle after upload)
        _iblTexture = ktxreader::Ktx1Reader::createTexture(
            engine,
            iblBundle,
            false // sRGB
        );

        if (_iblTexture) {
            auto builder = IndirectLight::Builder()
                .reflections(_iblTexture)
                .intensity(kStaticIblIntensity);

            // Load spherical harmonics for irradiance (diffuse IBL)
            NSData *shData = [LoaderUtils loadAssetNamed:@"envs/studio_small_02_2k_sh.txt"];
            if (shData) {
                NSString *shString = [[NSString alloc] initWithData:shData encoding:NSUTF8StringEncoding];
                filament::math::float3 sh[9];
                int idx = 0;
                for (NSString *line in [shString componentsSeparatedByString:@"\n"]) {
                    if (idx >= 9) break;
                    NSScanner *scanner = [NSScanner scannerWithString:line];
                    [scanner scanString:@"(" intoString:nil];
                    double r, g, b;
                    if ([scanner scanDouble:&r] &&
                        [scanner scanString:@"," intoString:nil] &&
                        [scanner scanDouble:&g] &&
                        [scanner scanString:@"," intoString:nil] &&
                        [scanner scanDouble:&b]) {
                        sh[idx] = filament::math::float3(r, g, b);
                        idx++;
                    }
                }
                if (idx == 9) {
                    builder.irradiance(3, sh);
                    NSLog(@"%@: Loaded SH irradiance (3 bands)", TAG);
                }
            }

            _indirectLight = builder.build(*engine);
            scene->setIndirectLight(_indirectLight);
            NSLog(@"%@: Loaded IBL", TAG);
        } else {
            NSLog(@"%@: Failed to create IBL texture", TAG);
        }
    } else {
        NSLog(@"%@: Failed to load IBL file", TAG);
    }

    // Load skybox from ktx file
    NSData *skyboxData = [LoaderUtils loadAssetNamed:@"envs/studio_small_02_2k_skybox.ktx"];
    if (skyboxData) {
        // Create Ktx1Bundle from raw bytes
        auto skyboxBundle = new image::Ktx1Bundle(
            (const uint8_t *)skyboxData.bytes,
            (uint32_t)skyboxData.length
        );
        // Create texture from bundle (takes ownership and destroys bundle after upload)
        _skyboxTexture = ktxreader::Ktx1Reader::createTexture(
            engine,
            skyboxBundle,
            false // sRGB
        );

        if (_skyboxTexture) {
            _skybox = Skybox::Builder()
                .environment(_skyboxTexture)
                .build(*engine);

            scene->setSkybox(_skybox);
            NSLog(@"%@: Loaded skybox", TAG);
        } else {
            NSLog(@"%@: Failed to create skybox texture", TAG);
        }
    } else {
        NSLog(@"%@: Failed to load skybox file", TAG);
    }

    // Create the directional ("sun") light entity. Driven per-frame from
    // ARDirectionalLightEstimate. Castles and shadows are off — face
    // occlusion handles "behind the head" depth, and shadow maps would
    // depend on a stable scene-coordinate ground plane we don't have.
    _directionalLightEntity = EntityManager::get().create();
    LightManager::Builder(LightManager::Type::DIRECTIONAL)
        .color({1.0f, 1.0f, 1.0f})
        .intensity(0.0f)               // overridden per-frame
        .direction({0.0f, 0.0f, -1.0f}) // overridden per-frame; -Z = away from camera
        .castShadows(false)
        .build(*engine, _directionalLightEntity);
    _scene->addEntity(_directionalLightEntity);
    _directionalLightAddedToScene = YES;

    NSLog(@"%@: Environment lighting setup complete", TAG);
}

- (void)updateDirectionalFromARKitWithLightEstimate:(ARLightEstimate *)lightEstimate {
    if (!_engine || !lightEstimate) return;
    if (![lightEstimate isKindOfClass:[ARDirectionalLightEstimate class]]) return;

    ARDirectionalLightEstimate *est = (ARDirectionalLightEstimate *)lightEstimate;

    // ARKit's primaryLightIntensity is in lumens; 1000 ≈ neutral indoor.
    // Normalize to a [0, ~2] factor, clamp at peak.
    float factor = (float)est.primaryLightIntensity / kArkitNeutralIntensity;
    if (factor < 0.0f) factor = 0.0f;
    if (factor > 2.0f) factor = 2.0f;
    float intensityLux = factor * kDirectionalIntensityMax;

    // primaryLightDirection is in face-anchor space, pointing FROM the
    // light source. Filament expects the direction the light is pointing
    // TO (away from the source), so negate.
    simd_float3 d = est.primaryLightDirection;
    LightManager &lm = _engine->getLightManager();
    LightManager::Instance instance = lm.getInstance(_directionalLightEntity);
    lm.setDirection(instance, {-d.x, -d.y, -d.z});
    lm.setIntensity(instance, intensityLux);
}

- (void)destroy {
    if (!_engine) return;

    if (_directionalLightAddedToScene && _scene) {
        _scene->remove(_directionalLightEntity);
        _directionalLightAddedToScene = NO;
    }
    if (!_directionalLightEntity.isNull()) {
        _engine->getLightManager().destroy(_directionalLightEntity);
        EntityManager::get().destroy(_directionalLightEntity);
    }
    if (_indirectLight) {
        _engine->destroy(_indirectLight);
    }
    if (_skybox) {
        _engine->destroy(_skybox);
    }
    if (_iblTexture) {
        _engine->destroy(_iblTexture);
    }
    if (_skyboxTexture) {
        _engine->destroy(_skyboxTexture);
    }
}

@end
