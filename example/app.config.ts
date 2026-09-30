import "tsx/cjs";

import { ConfigContext, ExpoConfig } from "expo/config";
import withNitroVto from "@alaneu/react-native-nitro-vto/app.plugin";

export default ({ config }: ConfigContext): ExpoConfig => {
  let newConfig: ExpoConfig = {
    ...config,
    name: "vto-demo",
    slug: "vto-demo",
    version: "1.0.0",
    extra: {
      eas: {
        projectId: "b39f1bdf-bef0-4d9a-966d-9fd1e3654774",
      },
    },
    orientation: "portrait",
    icon: "./assets/images/icon.png",
    scheme: "vtodemo",
    userInterfaceStyle: "automatic",
    newArchEnabled: true,
    ios: {
      bundleIdentifier: "eu.alan.vto.demo",
      supportsTablet: true,
      infoPlist: {
        NSCameraUsageDescription:
          "This app uses the camera for AR glasses try-on",
        ITSAppUsesNonExemptEncryption: false,
      },
    },
    android: {
      package: "eu.alan.vto.demo",
      adaptiveIcon: {
        backgroundColor: "#E6F4FE",
        foregroundImage: "./assets/images/android-icon-foreground.png",
        backgroundImage: "./assets/images/android-icon-background.png",
        monochromeImage: "./assets/images/android-icon-monochrome.png",
      },
      edgeToEdgeEnabled: true,
      predictiveBackGestureEnabled: false,
      permissions: ["android.permission.CAMERA"],
    },
    plugins: [
      "expo-router",
      [
        "expo-splash-screen",
        {
          image: "./assets/images/splash-icon.png",
          imageWidth: 200,
          resizeMode: "contain",
          backgroundColor: "#ffffff",
          dark: {
            backgroundColor: "#000000",
          },
        },
      ],
      [
        "expo-dev-client",
        {
          launchMode: "most-recent",
        },
      ],
    ],
    experiments: {
      typedRoutes: true,
      reactCompiler: true,
    },
  };
  newConfig = withNitroVto(newConfig);
  return newConfig;
};
