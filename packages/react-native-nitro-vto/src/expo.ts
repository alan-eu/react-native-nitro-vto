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
    config.modResults = addHighSamplingRateSensorsPermission(config.modResults);
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

  const existing = mainApplication["meta-data"].find(
    (m) => m["$"]["android:name"] === "com.google.ar.core"
  );
  if (existing) {
    existing["$"]["android:value"] = "optional";
  } else {
    mainApplication["meta-data"].push({
      $: {
        "android:name": "com.google.ar.core",
        "android:value": "optional",
      },
    });
  }

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
        "android:required": "false",
      },
    });
  }

  return androidManifest;
};

const addHighSamplingRateSensorsPermission = (
  androidManifest: AndroidConfig.Manifest.AndroidManifest
) => {
  if (!Array.isArray(androidManifest.manifest["uses-permission"])) {
    androidManifest.manifest["uses-permission"] = [];
  }

  const permission = "android.permission.HIGH_SAMPLING_RATE_SENSORS";
  if (
    androidManifest.manifest["uses-permission"].some(
      (p) => p["$"]["android:name"] === permission
    )
  ) {
    return androidManifest;
  }

  androidManifest.manifest["uses-permission"].push({
    $: { "android:name": permission },
  });

  return androidManifest;
};

export default createRunOncePlugin(
  withNitroVto,
  "@alaneu/react-native-nitro-vto"
);
