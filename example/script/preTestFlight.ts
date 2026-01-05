import { readFileSync, writeFileSync } from "fs";
import { join } from "path";

const packagePath = join(__dirname, "../package.json");
const libraryPackagePath = join(
  __dirname,
  "../../packages/react-native-nitro-vto/package.json"
);

const packageJson = JSON.parse(readFileSync(packagePath, "utf-8"));
const libraryPackageJson = JSON.parse(
  readFileSync(libraryPackagePath, "utf-8")
);

const version = libraryPackageJson.version;

packageJson.dependencies["@alaneu/react-native-nitro-vto"] = version;

writeFileSync(packagePath, JSON.stringify(packageJson, null, 2) + "\n");

console.log(
  `Updated @alaneu/react-native-nitro-vto version to ${version} in example/package.json`
);
