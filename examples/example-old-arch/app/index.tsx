import {
  VtoView,
  type VtoRef,
  vtoVersion,
} from "@alaneu/react-native-vto";
import React, { useCallback, useEffect, useRef, useState } from "react";
import {
  Alert,
  PermissionsAndroid,
  Platform,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from "react-native";

const MODELS = [
  "https://github.com/alan-eu/react-native-nitro-vto/raw/main/misc/models/878082.glb",
  "https://github.com/alan-eu/react-native-nitro-vto/raw/main/misc/models/680048.glb",
];

const App = () => {
  const [hasPermission, setHasPermission] = useState(false);
  const [currentModelIndex, setCurrentModelIndex] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [faceMeshOcclusionEnabled, setFaceMeshOcclusionEnabled] =
    useState(true);
  const [backPlaneOcclusionEnabled, setBackPlaneOcclusionEnabled] =
    useState(true);
  const [debugEnabled, setDebugEnabled] = useState(false);
  const [glassesHidden, setGlassesHidden] = useState(false);

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

  const handleModelLoaded = useCallback((url: string) => {
    console.log(`[perf] modelLoaded ${url} in ${msSinceModelRequested()}ms`);
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

  const handleFaceMeshOcclusion = useCallback(() => {
    setFaceMeshOcclusionEnabled((prev) => !prev);
  }, []);

  const handleBackPlaneOcclusion = useCallback(() => {
    setBackPlaneOcclusionEnabled((prev) => !prev);
  }, []);

  const handleDebug = useCallback(() => {
    setDebugEnabled((prev) => !prev);
  }, []);

  const handleToggleGlasses = useCallback(() => {
    setGlassesHidden((prev) => {
      const next = !prev;
      if (next) {
        vtoRef.current?.hideGlasses();
      } else {
        vtoRef.current?.showGlasses();
      }
      return next;
    });
  }, []);

  const currentModel = MODELS[currentModelIndex];

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
      <VtoView
        ref={vtoRef}
        style={styles.vtoView}
        modelUrl={currentModel}
        isActive={true}
        faceMeshOcclusion={faceMeshOcclusionEnabled}
        backPlaneOcclusion={backPlaneOcclusionEnabled}
        forwardOffset={0.0035}
        debug={debugEnabled}
        showNativeFPS={true}
        onModelLoaded={handleModelLoaded}
        onFaceTracked={handleFaceTracked}
        onGlassesDisplayed={handleGlassesDisplayed}
      />
      {isLoading && (
        <View style={styles.loadingOverlay}>
          <Text style={styles.loadingText}>Loading model...</Text>
        </View>
      )}
      <View style={styles.controls}>
        <TouchableOpacity
          style={[styles.button, isLoading && styles.buttonLoading]}
          onPress={handleNextModel}
          disabled={isLoading}
        >
          <Text style={styles.buttonText}>Next Model</Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[
            styles.button,
            faceMeshOcclusionEnabled
              ? styles.buttonEnabled
              : styles.buttonDisabled,
          ]}
          onPress={handleFaceMeshOcclusion}
          disabled={isLoading}
        >
          <Text style={styles.buttonText}>
            Face Mesh Occlusion{" "}
            {faceMeshOcclusionEnabled ? "Enabled" : "Disabled"}
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[
            styles.button,
            backPlaneOcclusionEnabled
              ? styles.buttonEnabled
              : styles.buttonDisabled,
          ]}
          onPress={handleBackPlaneOcclusion}
          disabled={isLoading}
        >
          <Text style={styles.buttonText}>
            Back Plane Occlusion{" "}
            {backPlaneOcclusionEnabled ? "Enabled" : "Disabled"}
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[
            styles.button,
            debugEnabled ? styles.buttonEnabled : styles.buttonDisabled,
          ]}
          onPress={handleDebug}
          disabled={isLoading}
        >
          <Text style={styles.buttonText}>
            Debug: {debugEnabled ? "Enabled" : "Disabled"}
          </Text>
        </TouchableOpacity>
        <TouchableOpacity
          style={[
            styles.button,
            glassesHidden ? styles.buttonDisabled : styles.buttonEnabled,
          ]}
          onPress={handleToggleGlasses}
          disabled={isLoading}
        >
          <Text style={styles.buttonText}>
            Glasses: {glassesHidden ? "Hidden" : "Visible"}
          </Text>
        </TouchableOpacity>
        <Text style={styles.text}>VTO (old arch) Version: {vtoVersion}</Text>
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
    bottom: 48,
    left: 0,
    right: 0,
    alignItems: "center",
    gap: 10,
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
  buttonEnabled: {
    backgroundColor: "#060",
  },
  buttonDisabled: {
    backgroundColor: "#600",
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
