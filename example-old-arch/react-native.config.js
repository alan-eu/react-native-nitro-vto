// This example targets React Native's old architecture. Reanimated 4.x (hoisted
// from the new-arch example in this monorepo) requires the new architecture, so
// we skip autolinking it here. Gesture handler is similarly unused in this
// screen and excluded to keep the native build minimal.
module.exports = {
  dependencies: {
    "react-native-reanimated": {
      platforms: { android: null, ios: null },
    },
    "react-native-gesture-handler": {
      platforms: { android: null, ios: null },
    },
    "react-native-worklets": {
      platforms: { android: null, ios: null },
    },
  },
};
