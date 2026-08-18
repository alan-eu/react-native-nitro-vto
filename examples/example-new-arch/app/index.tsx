import {
  NitroVtoView,
  nitroVtoVersion,
  type HybridRef,
  type NitroVtoViewMethods,
  type NitroVtoViewProps,
} from "@alaneu/react-native-nitro-vto";
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
import { callback } from "react-native-nitro-modules";

const MODELS = [
  "https://github.com/alan-eu/react-native-nitro-vto/raw/main/misc/models/878082.glb",
  "https://github.com/alan-eu/react-native-nitro-vto/raw/main/misc/models/680048.glb",
];

type VtoRef = HybridRef<NitroVtoViewProps, NitroVtoViewMethods>;

const App = () => {
  const [hasPermission, setHasPermission] = useState(false);
  const [currentModelIndex, setCurrentModelIndex] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [debugEnabled, setDebugEnabled] = useState(false);
  const [glassesHidden, setGlassesHidden] = useState(false);
  const [previewMode, setPreviewMode] = useState(false);
  const [arUnavailable, setArUnavailable] = useState<string | null>(null);

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

  const handleTogglePreview = useCallback(() => {
    setPreviewMode((prev) => !prev);
  }, []);

  const handleArUnavailable = useCallback((reason: string) => {
    console.log(`[vto] AR unavailable: ${reason}`);
    setArUnavailable(reason);
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
      <NitroVtoView
        style={styles.vtoView}
        modelUrl={currentModel}
        isActive={true}
        isClipOn={false}
        mode={previewMode ? "preview" : "ar"}
        previewBackgroundColor="#FBF3E4"
        forwardOffset={0.005}
        debug={debugEnabled}
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
      <View style={styles.controls}>
        {MODELS.length > 1 && (
          <TouchableOpacity
            style={[styles.button, isLoading && styles.buttonLoading]}
            onPress={handleNextModel}
            disabled={isLoading}
          >
            <Text style={styles.buttonText}>Next Model</Text>
          </TouchableOpacity>
        )}
        <TouchableOpacity
          style={[
            styles.button,
            previewMode ? styles.buttonEnabled : styles.buttonDisabled,
          ]}
          onPress={handleTogglePreview}
        >
          <Text style={styles.buttonText}>
            Mode: {previewMode || arUnavailable ? "Preview" : "AR"}
            {arUnavailable ? ` (${arUnavailable})` : ""}
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
        <Text style={styles.text}>Nitro VTO Version: {nitroVtoVersion}</Text>
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
