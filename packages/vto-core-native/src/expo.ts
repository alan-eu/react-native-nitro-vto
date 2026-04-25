import {
  createRunOncePlugin,
  withAndroidManifest,
  withPodfile,
  type AndroidConfig,
  type ConfigPlugin,
} from "@expo/config-plugins";
import { getMainApplication } from "@expo/config-plugins/build/android/Manifest.js";
import { mergeContents } from "@expo/config-plugins/build/utils/generateCode.js";
import fs from "node:fs";
import path from "node:path";

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

// Filament 1.69.3's upstream podspec sets EXCLUDED_ARCHS[sdk=iphonesimulator*]
// = arm64, which breaks arm64 simulator builds on Apple silicon. Override the
// pod source by injecting a `pod 'Filament', :podspec => URL` line into the
// consumer's Podfile (right after `use_native_modules!`, so it lands inside
// the main target block). The URL points to a forked Filament.podspec hosted
// alongside this package at the matching git tag, with the exclusion lines
// removed.
const FILAMENT_ARCH_FIX_TAG = "react-native-nitro-vto-filament-arch-fix";
const FILAMENT_FORK_REPO = "alan-eu/react-native-nitro-vto";
const FILAMENT_FORK_PATH = "packages/vto-core-native/Filament.podspec";

// Walk up from this file to the nearest package.json so the URL pins to the
// installed package's version, regardless of bob's output directory layout.
const findOwnPackageVersion = (): string => {
  let dir = __dirname;
  while (true) {
    const candidate = path.join(dir, "package.json");
    if (fs.existsSync(candidate)) {
      try {
        const pkg = JSON.parse(fs.readFileSync(candidate, "utf8"));
        if (pkg.version) return pkg.version as string;
      } catch {
        // fall through to walk up
      }
    }
    const parent = path.dirname(dir);
    if (parent === dir) return "main";
    dir = parent;
  }
};

const filamentPodspecUrl = (): string => {
  const version = findOwnPackageVersion();
  const ref = version === "main" ? "main" : `v${version}`;
  return `https://raw.githubusercontent.com/${FILAMENT_FORK_REPO}/${ref}/${FILAMENT_FORK_PATH}`;
};

const withFilamentArchFix: ConfigPlugin = (config) => {
  return withPodfile(config, (config) => {
    const snippet = `  pod 'Filament', '1.69.3', :podspec => '${filamentPodspecUrl()}'`;

    const result = mergeContents({
      tag: FILAMENT_ARCH_FIX_TAG,
      src: config.modResults.contents,
      newSrc: snippet,
      anchor: /use_native_modules!/,
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
