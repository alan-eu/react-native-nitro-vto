#import "EnvironmentLightingRenderer.h"
#import "LoaderUtils.h"

#include <filament/Engine.h>
#include <filament/Scene.h>
#include <filament/IndirectLight.h>
#include <filament/Skybox.h>
#include <filament/Texture.h>
#include <ktxreader/Ktx1Reader.h>

using namespace filament;

static NSString *const TAG = @"EnvironmentLighting";

// Intensity will range from 30_000 to 90_000 based on pixel intensity
static const float BASE_INTENSITY = 30000.0f;
static const float INTENSITY_FACTOR = 60000.0f;

@interface EnvironmentLightingRenderer ()

@property (nonatomic, assign) Engine *engine;
@property (nonatomic, assign) IndirectLight *indirectLight;
@property (nonatomic, assign) Skybox *skybox;
@property (nonatomic, assign) Texture *iblTexture;
@property (nonatomic, assign) Texture *skyboxTexture;

@end

@implementation EnvironmentLightingRenderer

- (void)setupWithEngine:(Engine *)engine scene:(Scene *)scene {
    _engine = engine;

    // Load IBL (indirect light) from ktx file
    NSData *iblData = [LoaderUtils loadAssetNamed:@"envs/studio_small_02_ibl.ktx"];
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
            _indirectLight = IndirectLight::Builder()
                .reflections(_iblTexture)
                .intensity(BASE_INTENSITY)
                .build(*engine);

            scene->setIndirectLight(_indirectLight);
            NSLog(@"%@: Loaded IBL", TAG);
        } else {
            NSLog(@"%@: Failed to create IBL texture", TAG);
        }
    } else {
        NSLog(@"%@: Failed to load IBL file", TAG);
    }

    // Load skybox from ktx file
    NSData *skyboxData = [LoaderUtils loadAssetNamed:@"envs/studio_small_02_skybox.ktx"];
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

    NSLog(@"%@: Environment lighting setup complete", TAG);
}

- (void)updateFromARKitWithLightEstimate:(ARLightEstimate *)lightEstimate {
    if (!_indirectLight) return;

    // ARKit ambientIntensity is in lumens, typically ranges 0-2000
    // Normalize to 0-1 range for our intensity calculation
    float normalizedIntensity = fminf((float)lightEstimate.ambientIntensity / 1000.0f, 1.0f);
    _indirectLight->setIntensity(BASE_INTENSITY + normalizedIntensity * INTENSITY_FACTOR);
}

- (void)destroy {
    if (!_engine) return;

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
