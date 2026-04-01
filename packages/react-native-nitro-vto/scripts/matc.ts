import { execSync } from "child_process";
import { existsSync, readdirSync } from "fs";
import { resolve, basename } from "path";
import dotenv from "dotenv";

dotenv.config({ path: resolve(__dirname, "../.env"), quiet: true });

const IOS_MATERIAL_FOLDER = "ios/assets/materials";
const ANDROID_MATERIAL_FOLDER = "android/src/main/assets/materials";

const USAGE = `
Usage: npm run matc <platform>

Compiles all .mat files in the platform's material folder to .filamat.

Arguments:
  platform   Target platform: "ios" or "android"

Examples:
  npm run matc ios
  npm run matc android
`;

const main = () => {
  const platform = process.argv[2];

  if (!platform || (platform !== "ios" && platform !== "android")) {
    console.error(USAGE);
    process.exit(1);
  }

  const materialFolder =
    platform === "ios" ? IOS_MATERIAL_FOLDER : ANDROID_MATERIAL_FOLDER;

  const matcPathKey =
    platform === "ios" ? "MATC_IOS_PATH" : "MATC_ANDROID_PATH";
  const matcPath = process.env[matcPathKey];

  if (!matcPath) {
    console.error(`Error: ${matcPathKey} not defined in .env file`);
    process.exit(1);
  }

  if (!existsSync(matcPath)) {
    console.error(`Error: matc binary not found at ${matcPath}`);
    process.exit(1);
  }

  const matFiles = readdirSync(materialFolder).filter((f) =>
    f.endsWith(".mat")
  );

  if (matFiles.length === 0) {
    console.error(`No .mat files found in ${materialFolder}`);
    process.exit(1);
  }

  console.log(
    `Compiling ${matFiles.length} material(s) for ${platform}...\n`
  );

  let failures = 0;

  for (const matFile of matFiles) {
    const matFilePath = resolve(materialFolder, matFile);
    const outputFile = matFilePath.replace(/\.mat$/, ".filamat");

    let command: string;
    if (platform === "ios") {
      command = `"${matcPath}" --api metal --platform mobile -o "${outputFile}" "${matFilePath}"`;
    } else {
      command = `"${matcPath}" --api opengl --api vulkan --platform mobile -o "${outputFile}" "${matFilePath}"`;
    }

    console.log(`  ${basename(matFile)} → ${basename(outputFile)}`);

    try {
      execSync(command, { stdio: "pipe" });
    } catch (error) {
      console.error(`  ✗ Failed to compile ${matFile}`);
      if (error instanceof Error && "stderr" in error) {
        console.error(String((error as any).stderr));
      }
      failures++;
    }
  }

  console.log(
    `\nDone: ${matFiles.length - failures}/${matFiles.length} compiled successfully.`
  );

  if (failures > 0) {
    process.exit(1);
  }
};

main();
