import { readFileSync, writeFileSync } from "fs";
import { join } from "path";

const packagePath = join(__dirname, "../package.json");

const packageJson = JSON.parse(readFileSync(packagePath, "utf-8"));

packageJson.dependencies["@alaneu/react-native-nitro-vto"] = "*";

writeFileSync(packagePath, JSON.stringify(packageJson, null, 2) + "\n");

console.log(
  `Reverted @alaneu/react-native-nitro-vto version to "*" in example/package.json`
);
