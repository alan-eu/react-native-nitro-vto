const { getDefaultConfig, mergeConfig } = require("@react-native/metro-config");
const path = require("node:path");

// Monorepo root
const root = path.resolve(__dirname, "..");
// Library package
const libraryPackage = path.resolve(root, "packages/react-native-nitro-vto");

/**
 * Metro configuration
 * https://facebook.github.io/metro/docs/configuration
 *
 * @type {import('@react-native/metro-config').MetroConfig}
 */
const config = {
  watchFolders: [root, libraryPackage],

  resolver: {
    // Ensure we use the root node_modules
    nodeModulesPaths: [path.resolve(root, "node_modules")],
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

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
