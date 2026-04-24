import {
  createRunOncePlugin,
  withAndroidManifest,
  withPodfile,
  type AndroidConfig,
  type ConfigPlugin,
} from "@expo/config-plugins";
import { getMainApplication } from "@expo/config-plugins/build/android/Manifest.js";
import { mergeContents } from "@expo/config-plugins/build/utils/generateCode.js";

const withNitroVto: ConfigPlugin = (config) => {
  config = withAndroidManifest(config, (config) => {
    config.modResults = addARCoreMetadataToAndroidManifest(config.modResults);
    config.modResults = addCameraARFeatureToAndroidManifestManifest(
      config.modResults
    );
    return config;
  });

  config = withFilamentArchFix(config);

  return config;
};

// Filament 1.69.3's podspec sets EXCLUDED_ARCHS[sdk=iphonesimulator*]=arm64
// on the user target, which breaks arm64 simulator builds on Apple silicon.
// Inject a snippet into the Podfile's post_install that removes the setting
// from every pod + aggregate target after Pod resolution.
const FILAMENT_ARCH_FIX_TAG = "react-native-nitro-vto-filament-arch-fix";

const FILAMENT_ARCH_FIX_SNIPPET = `    installer.pods_project.targets.each do |target|
      target.build_configurations.each do |config|
        config.build_settings.delete('EXCLUDED_ARCHS[sdk=iphonesimulator*]')
      end
    end
    installer.aggregate_targets.each do |aggregate_target|
      aggregate_target.user_project.native_targets.each do |target|
        target.build_configurations.each do |config|
          config.build_settings.delete('EXCLUDED_ARCHS[sdk=iphonesimulator*]')
        end
      end
      aggregate_target.user_project.save
    end`;

const withFilamentArchFix: ConfigPlugin = (config) => {
  return withPodfile(config, (config) => {
    const result = mergeContents({
      tag: FILAMENT_ARCH_FIX_TAG,
      src: config.modResults.contents,
      newSrc: FILAMENT_ARCH_FIX_SNIPPET,
      anchor: /post_install do \|installer\|/,
      offset: 1,
      comment: "#",
    });

    if (result.didMerge || result.didClear) {
      config.modResults.contents = result.contents;
    }

    return config;
  });
};

const addARCoreMetadataToAndroidManifest = (
  androidManifest: AndroidConfig.Manifest.AndroidManifest
) => {
  const mainApplication = getMainApplication(androidManifest);
  if (!mainApplication) {
    return androidManifest;
  }
  mainApplication["meta-data"] = mainApplication["meta-data"] || [];

  mainApplication["meta-data"].push({
    $: {
      "android:name": "com.google.ar.core",
      "android:value": "required",
    },
  });
  return androidManifest;
};

const addCameraARFeatureToAndroidManifestManifest = (
  androidManifest: AndroidConfig.Manifest.AndroidManifest
) => {
  if (!Array.isArray(androidManifest.manifest["uses-feature"])) {
    androidManifest.manifest["uses-feature"] = [];
  }

  const features = ["android.hardware.camera", "android.hardware.camera.ar"];
  for (const feature of features) {
    if (
      androidManifest.manifest["uses-feature"].some(
        (f) => f["$"]["android:name"] === feature
      )
    ) {
      continue;
    }
    androidManifest.manifest["uses-feature"].push({
      $: {
        "android:name": feature,
        "android:required": "true",
      },
    });
  }

  return androidManifest;
};

export default createRunOncePlugin(
  withNitroVto,
  "@alaneu/react-native-nitro-vto"
);
