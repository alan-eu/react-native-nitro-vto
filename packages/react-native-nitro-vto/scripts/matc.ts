import { execSync } from "child_process";
import { existsSync, readdirSync } from "fs";
import { resolve, basename, join } from "path";
import dotenv from "dotenv";

dotenv.config({ path: resolve(__dirname, "../.env"), quiet: true });

const SOURCE_FOLDER = resolve(__dirname, "../assets/materials");
const IOS_OUTPUT_FOLDER = resolve(__dirname, "../ios/assets/materials");
const ANDROID_OUTPUT_FOLDER = resolve(
  __dirname,
  "../android/src/main/assets/materials"
);

type TargetLabel = "ios" | "android";

const TARGETS: { label: TargetLabel; outputDir: string; apiFlags: string }[] = [
  { label: "ios", outputDir: IOS_OUTPUT_FOLDER, apiFlags: "--api metal" },
  {
    label: "android",
    outputDir: ANDROID_OUTPUT_FOLDER,
    apiFlags: "--api opengl --api vulkan",
  },
];

// A .mat is considered shared unless it carries a .ios.mat or .android.mat
// suffix — in which case it's compiled only for the matching platform and
// the suffix is stripped from the output filename.
const PLATFORM_SUFFIX: Record<string, TargetLabel> = {
  ".ios.mat": "ios",
  ".android.mat": "android",
};

const USAGE = `
Usage: npm run matc

Compiles every .mat in packages/react-native-nitro-vto/assets/materials/ to
.filamat. Shared sources (e.g. face_occlusion.mat) are compiled for both
platforms; platform-specific sources (e.g. camera_background.ios.mat) are
compiled only for the matching platform. Outputs:
  - Metal → ios/assets/materials/<name>.filamat
  - OpenGL + Vulkan → android/src/main/assets/materials/<name>.filamat

Requires MATC_PATH in .env (path to a Filament matc binary).
`;

const main = () => {
  if (process.argv[2]) {
    console.error(USAGE);
    console.error(`Unexpected argument "${process.argv[2]}"`);
    process.exit(1);
  }

  const matcPath = process.env.MATC_PATH;
  if (!matcPath) {
    console.error(USAGE);
    console.error("Error: MATC_PATH not defined in .env");
    process.exit(1);
  }
  if (!existsSync(matcPath)) {
    console.error(`Error: matc binary not found at ${matcPath}`);
    process.exit(1);
  }

  if (!existsSync(SOURCE_FOLDER)) {
    console.error(`Error: source folder not found: ${SOURCE_FOLDER}`);
    process.exit(1);
  }

  const matFiles = readdirSync(SOURCE_FOLDER).filter((f) => f.endsWith(".mat"));
  if (matFiles.length === 0) {
    console.error(`No .mat files found in ${SOURCE_FOLDER}`);
    process.exit(1);
  }

  console.log(
    `Compiling ${matFiles.length} material(s) for ${TARGETS.length} target(s)...\n`
  );

  let failures = 0;
  let totalBuilds = 0;

  for (const matFile of matFiles) {
    const sourcePath = join(SOURCE_FOLDER, matFile);
    const platformLock = (Object.keys(PLATFORM_SUFFIX) as Array<keyof typeof PLATFORM_SUFFIX>)
      .find((suffix) => matFile.endsWith(suffix));
    const strippedBase = platformLock
      ? matFile.slice(0, -platformLock.length)
      : matFile.replace(/\.mat$/, "");
    const outputName = `${strippedBase}.filamat`;
    console.log(`  ${basename(matFile)}`);

    for (const { label, outputDir, apiFlags } of TARGETS) {
      if (platformLock && PLATFORM_SUFFIX[platformLock] !== label) continue;

      const outputPath = join(outputDir, outputName);
      const command = `"${matcPath}" ${apiFlags} --platform mobile -o "${outputPath}" "${sourcePath}"`;
      totalBuilds++;

      try {
        execSync(command, { stdio: "pipe" });
        console.log(`    → ${label}/${outputName}`);
      } catch (error) {
        console.error(`    ✗ ${label} failed`);
        if (error instanceof Error && "stderr" in error) {
          console.error(String((error as any).stderr));
        }
        failures++;
      }
    }
  }

  console.log(`\nDone: ${totalBuilds - failures}/${totalBuilds} compiled successfully.`);
  if (failures > 0) process.exit(1);
};

main();
