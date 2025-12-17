const { getDefaultConfig, mergeConfig } = require("@react-native/metro-config");
const path = require("path");

const root = path.resolve(__dirname, "..");

const defaultConfig = getDefaultConfig(__dirname);

const config = {
  watchFolders: [root],
  resolver: {
    // Ensure JSON files are resolved
    sourceExts: [...defaultConfig.resolver.sourceExts, 'json'],
    // Make sure nitrogen generated files are accessible
    nodeModulesPaths: [
      path.resolve(__dirname, "node_modules"),
      path.resolve(root, "node_modules"),
    ],
  },
  transformer: {
    getTransformOptions: async () => ({
      transform: {
        experimentalImportSupport: false,
        inlineRequires: true,
      },
    }),
  },
};

module.exports = mergeConfig(defaultConfig, config);
