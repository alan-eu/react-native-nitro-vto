import {
  createRunOncePlugin,
  withAndroidManifest,
  type AndroidConfig,
  type ConfigPlugin,
} from "@expo/config-plugins";
import { getMainApplication } from "@expo/config-plugins/build/android/Manifest.js";

const withNitroVto: ConfigPlugin = (config) => {
  config = withAndroidManifest(config, (config) => {
    config.modResults = addARCoreMetadataToAndroidManifest(config.modResults);
    config.modResults = addCameraARFeatureToAndroidManifestManifest(
      config.modResults
    );
    return config;
  });

  return config;
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
