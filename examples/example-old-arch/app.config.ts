import "tsx/cjs";

import { ConfigContext, ExpoConfig } from "expo/config";
import withVto from "@alaneu/react-native-vto/app.plugin";

export default ({ config }: ConfigContext): ExpoConfig => {
  let newConfig: ExpoConfig = {
    ...config,
    name: "vto-demo-old-arch",
    slug: "vto-demo-old-arch",
    version: "1.0.0",
    orientation: "portrait",
    icon: "./assets/images/icon.png",
    scheme: "vtodemooldarch",
    userInterfaceStyle: "automatic",
    newArchEnabled: false,
    ios: {
      bundleIdentifier: "eu.alan.vto.demo.oldarch",
      supportsTablet: true,
      infoPlist: {
        NSCameraUsageDescription:
          "This app uses the camera for AR glasses try-on",
        ITSAppUsesNonExemptEncryption: false,
      },
    },
    android: {
      package: "eu.alan.vto.demo.oldarch",
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
      "expo-dev-client",
    ],
    experiments: {
      typedRoutes: true,
      reactCompiler: true,
    },
  };
  newConfig = withVto(newConfig);
  return newConfig;
};
