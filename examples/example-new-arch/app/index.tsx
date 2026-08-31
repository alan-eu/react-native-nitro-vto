import {
  NitroVtoView,
  type HybridRef,
  type NitroVtoViewMethods,
  type NitroVtoViewProps,
} from "@alaneu/react-native-nitro-vto";
import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  Alert,
  Image,
  PermissionsAndroid,
  Platform,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
  Dimensions
} from "react-native";
import { callback } from "react-native-nitro-modules";

const MODELS = [
  { code: "ALAN161", name: "Calypso", url: "https://static.alan.com/shop/vto/878082.glb", isClipOn: false },
  { code: "ALAN105", name: "Nénuphar", url: "https://static.alan.com/shop/vto/680048.glb", isClipOn: false },
];

type VtoRef = HybridRef<NitroVtoViewProps, NitroVtoViewMethods>;

const App = () => {
  const [hasPermission, setHasPermission] = useState(false);
  const [currentModelIndex, setCurrentModelIndex] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [mode, setMode] = useState<"ar" | "preview">(
    "ar"
  );

  const vtoRef = useRef<VtoRef | null>(null);

  const requestCameraPermission = useCallback(async () => {
    if (Platform.OS === "android") {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: "Camera Permission",
            message:
              "This app needs camera access for the virtual try-on feature.",
            buttonNeutral: "Ask Me Later",
            buttonNegative: "Cancel",
            buttonPositive: "OK",
          }
        );
        if (granted === PermissionsAndroid.RESULTS.GRANTED) {
          setHasPermission(true);
        } else {
          Alert.alert(
            "Permission Denied",
            "Camera permission is required for this feature."
          );
        }
      } catch (err) {
        console.warn(err);
      }
    } else {
      setHasPermission(true);
    }
  }, []);

  useEffect(() => {
    requestCameraPermission();
  }, [requestCameraPermission]);

  // Perf instrumentation — all three events are timed from the same baseline
  // (the last model change) so their durations are directly comparable.
  // `tModelRequested` is set at mount and re-armed whenever the selected model
  // changes.
  const tModelRequested = useRef(performance.now());

  useEffect(() => {
    tModelRequested.current = performance.now();
  }, [currentModelIndex]);

  const msSinceModelRequested = () =>
    Math.round(performance.now() - tModelRequested.current);

  const handleNextModel = useCallback(() => {
    setIsLoading(true);
    setCurrentModelIndex((prev) => (prev + 1) % MODELS.length);
  }, []);

  const handlePrevModel = useCallback(() => {
    setIsLoading(true);
    setCurrentModelIndex((prev) => (prev - 1 + MODELS.length) % MODELS.length);
  }, []);

  const handleModelLoaded = useCallback((url: string) => {
    console.log(`[perf] modelLoaded ${url} in ${msSinceModelRequested()}ms`);
    // add a timeout to avoid loading overlay flickering
    const timeout = setTimeout(() => {
      setIsLoading(false);
    }, 300);
    return () => clearTimeout(timeout);
  }, []);

  const handleFaceTracked = useCallback(() => {
    console.log(`[perf] faceTracked in ${msSinceModelRequested()}ms`);
  }, []);

  const handleGlassesDisplayed = useCallback((url: string) => {
    console.log(
      `[perf] glassesDisplayed ${url} in ${msSinceModelRequested()}ms`
    );
  }, []);

  const handleArUnavailable = useCallback((reason: string) => {
    console.log(`[vto] AR unavailable: ${reason}`);
  }, []);

  const currentModel = MODELS[currentModelIndex];
  const photoBase = `https://static.alan.com/fr-web/eyewear/frames/photoshoot/large/${currentModel.code}`;

  if (!hasPermission) {
    return (
      <View style={styles.container}>
        <Text style={styles.text}>Camera permission is required</Text>
        <TouchableOpacity
          style={styles.button}
          onPress={requestCameraPermission}
        >
          <Text style={styles.buttonText}>Grant Permission</Text>
        </TouchableOpacity>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <NitroVtoView
        style={styles.vtoView}
        modelUrl={currentModel.url}
        isActive={true}
        isClipOn={currentModel.isClipOn}
        mode={mode}
        previewBackgroundColor="#FBF3E4"
        forwardOffset={0.005}
        debug={false}
        showNativeFPS={true}
        onModelLoaded={callback(handleModelLoaded)}
        onFaceTracked={callback(handleFaceTracked)}
        onGlassesDisplayed={callback(handleGlassesDisplayed)}
        onArUnavailable={callback(handleArUnavailable)}
        hybridRef={callback((ref: VtoRef) => {
          vtoRef.current = ref;
        })}
      />
      {isLoading && (
        <View style={styles.loadingOverlay}>
          <Text style={styles.loadingText}>Loading model...</Text>
        </View>
      )}
      <View style={styles.modelLabel}>
        <Text style={styles.modelLabelCode}>
          {currentModel.code}
          {currentModel.isClipOn ? " (clip-on)" : ""}
        </Text>
        <Text style={styles.modelLabelName}>
          {currentModel.name} · {currentModelIndex + 1}/{MODELS.length}
        </Text>
      </View>
      <View style={styles.controls}>
        <View style={styles.navRow}>
          <TouchableOpacity
            style={[styles.button, isLoading && styles.buttonLoading]}
            onPress={handlePrevModel}
            disabled={isLoading}
          >
            <Text style={styles.buttonText}>← Prev</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={[styles.button, isLoading && styles.buttonLoading]}
            onPress={handleNextModel}
            disabled={isLoading}
          >
            <Text style={styles.buttonText}>Next →</Text>
          </TouchableOpacity>
        </View>
        <TouchableOpacity
          style={styles.button}
          onPress={() =>
            setMode((prev) =>
              prev === "ar" ? "preview" : "ar"
            )
          }
        >
          <Text style={styles.buttonText}>Mode: {mode}</Text>
        </TouchableOpacity>
      </View>
      <View style={styles.photoStrip}>
        <Image
          source={{ uri: `${photoBase}-front.jpg` }}
          style={styles.photo}
          resizeMode="cover"
        />
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#000",
  },
  vtoView: {
    flex: 1,
  },
  controls: {
    position: "absolute",
    bottom: Dimensions.get("screen").width / 2 + 20,
    left: 0,
    right: 0,
    alignItems: "center",
    gap: 10,
  },
  navRow: {
    flexDirection: "row",
    gap: 10,
  },
  photoStrip: {
    position: "absolute",
    bottom: 0,
    left: 0,
    right: 0,
    height: Dimensions.get("screen").width /2,
    flexDirection: "row",
    backgroundColor: "#FBF3E4",
  },
  photo: {
    flex: 1,
  },
  modelLabel: {
    position: "absolute",
    top: 64,
    left: 0,
    right: 0,
    alignItems: "center",
  },
  modelLabelCode: {
    color: "#fff",
    fontSize: 18,
    fontWeight: "700",
    textShadowColor: "rgba(0, 0, 0, 0.6)",
    textShadowRadius: 4,
  },
  modelLabelName: {
    color: "#fff",
    fontSize: 13,
    textShadowColor: "rgba(0, 0, 0, 0.6)",
    textShadowRadius: 4,
  },
  button: {
    backgroundColor: "#007AFF",
    paddingHorizontal: 24,
    paddingVertical: 8,
    borderRadius: 8,
  },
  buttonLoading: {
    backgroundColor: "#666",
  },
  buttonText: {
    color: "#fff",
    fontSize: 14,
    fontWeight: "600",
  },
  text: {
    color: "#fff",
    fontSize: 16,
    textAlign: "center",
    marginBottom: 20,
  },
  loadingOverlay: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: "rgba(0, 0, 0, 0.5)",
    justifyContent: "center",
    alignItems: "center",
  },
  loadingText: {
    color: "#fff",
    fontSize: 18,
    fontWeight: "600",
  },
});

export default App;
