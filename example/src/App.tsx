import React, { useState, useEffect, useCallback } from "react";
import {
  StyleSheet,
  View,
  Text,
  TouchableOpacity,
  PermissionsAndroid,
  Platform,
  Alert,
} from "react-native";
import { NitroVtoView } from "@alaneu/react-native-nitro-vto";

const MODELS = [
  {
    url: "https://github.com/alan-eu/react-native-nitro-vto/raw/main/packages/react-native-nitro-vto/android/src/main/assets/models/680048.glb",
    width: 0.138,
  },
  {
    url: "https://github.com/alan-eu/react-native-nitro-vto/raw/main/packages/react-native-nitro-vto/android/src/main/assets/models/878082.glb",
    width: 0.135,
  },
];

function App(): React.JSX.Element {
  const [hasPermission, setHasPermission] = useState(false);
  const [currentModelIndex, setCurrentModelIndex] = useState(0);

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

  const handleNextModel = useCallback(() => {
    setCurrentModelIndex((prev) => (prev + 1) % MODELS.length);
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
        modelUrl={currentModel.url}
        modelWidthMeters={currentModel.width}
        isActive={true}
      />
      <View style={styles.controls}>
        <Text style={styles.modelText}>Model: {currentModel.url}</Text>
        <TouchableOpacity style={styles.button} onPress={handleNextModel}>
          <Text style={styles.buttonText}>Next Model</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
}

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
    bottom: 40,
    left: 0,
    right: 0,
    alignItems: "center",
  },
  modelText: {
    color: "#fff",
    fontSize: 16,
    marginBottom: 10,
  },
  button: {
    backgroundColor: "#007AFF",
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: 8,
  },
  buttonText: {
    color: "#fff",
    fontSize: 16,
    fontWeight: "600",
  },
  text: {
    color: "#fff",
    fontSize: 16,
    textAlign: "center",
    marginBottom: 20,
  },
});

export default App;
