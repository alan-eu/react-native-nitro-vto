import { execSync } from "child_process";
import { existsSync } from "fs";
import { resolve, basename } from "path";
import dotenv from "dotenv";

// Load .env file from package root
dotenv.config({ path: resolve(__dirname, "../.env"), quiet: true });

const IOS_MATERIAL_FOLDER = "ios/assets/materials";
const ANDROID_MATERIAL_FOLDER = "android/src/main/assets/materials";

const USAGE = `
Usage: npx tsx scripts/matc.ts <mat-file> <platform>

Arguments:
  mat-file   Path to the .mat file to compile
  platform   Target platform: "ios" or "android"

Examples:
  npx tsx scripts/matc.ts debug_material.mat ios
  npx tsx scripts/matc.ts debug_material.mat android
`;

const main = () => {
  const args = process.argv.slice(2);

  if (args.length !== 2) {
    console.error(USAGE);
    process.exit(1);
  }

  const [matFile, platform] = args;

  if (!matFile) {
    console.error("Error: mat-file is required");
    console.error(USAGE);
    process.exit(1);
  }

  if (platform !== "ios" && platform !== "android") {
    console.error(
      `Error: Invalid platform "${platform}". Must be "ios" or "android".`
    );
    console.error(USAGE);
    process.exit(1);
  }

  const matFilePath = resolve(
    platform === "ios" ? IOS_MATERIAL_FOLDER : ANDROID_MATERIAL_FOLDER,
    matFile
  );

  if (!existsSync(matFilePath)) {
    console.error(`Error: Material file not found: ${matFilePath}`);
    process.exit(1);
  }

  if (!matFilePath.endsWith(".mat")) {
    console.error(`Error: File must have .mat extension: ${matFilePath}`);
    process.exit(1);
  }

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

  // Output file: same name but .filamat extension
  const outputFile = matFilePath.replace(/\.mat$/, ".filamat");

  // Build command based on platform
  let command: string;
  if (platform === "ios") {
    // iOS: Metal backend only
    command = `"${matcPath}" --api metal --platform mobile -o "${outputFile}" "${matFilePath}"`;
  } else {
    // Android: OpenGL and Vulkan backends
    command = `"${matcPath}" --api opengl --api vulkan --platform mobile -o "${outputFile}" "${matFilePath}"`;
  }

  console.log(`Compiling ${basename(matFilePath)} for ${platform}...`);
  console.log(`Command: ${command}`);

  try {
    execSync(command, { stdio: "inherit" });
    console.log(`Output: ${outputFile}`);
  } catch (error) {
    console.error("Error: matc compilation failed");
    process.exit(1);
  }
};

main();
