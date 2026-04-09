import { execSync } from "child_process";
import { existsSync, readdirSync } from "fs";
import { resolve, basename } from "path";
import dotenv from "dotenv";

dotenv.config({ path: resolve(__dirname, "../.env"), quiet: true });

const SOURCE_MATERIAL_FOLDER = "assets/materials";

type Target = {
  name: "ios" | "android";
  outputFolder: string;
  apiArgs: string;
  matcPathEnvKeys: string[];
};

const TARGETS: Target[] = [
  {
    name: "ios",
    outputFolder: "ios/assets/materials",
    apiArgs: "--api metal",
    matcPathEnvKeys: ["MATC_IOS_PATH", "MATC_PATH"],
  },
  {
    name: "android",
    outputFolder: "android/src/main/assets/materials",
    apiArgs: "--api opengl --api vulkan",
    matcPathEnvKeys: ["MATC_ANDROID_PATH", "MATC_PATH"],
  },
];

const USAGE = `
Usage: npm run matc

Compiles all .mat files from assets/materials to platform-specific .filamat files.
`;

const resolveMatcPath = (keys: string[]): string | undefined => {
  for (const key of keys) {
    const value = process.env[key];
    if (value) {
      return value;
    }
  }
  return undefined;
};

const main = () => {
  if (process.argv.length > 2) {
    console.error(USAGE);
    process.exit(1);
  }

  const matFiles = readdirSync(SOURCE_MATERIAL_FOLDER).filter((f) =>
    f.endsWith(".mat")
  );

  if (matFiles.length === 0) {
    console.error(`No .mat files found in ${SOURCE_MATERIAL_FOLDER}`);
    process.exit(1);
  }

  const targetConfigs = TARGETS.map((target) => {
    const matcPath = resolveMatcPath(target.matcPathEnvKeys);
    if (!matcPath) {
      console.error(
        `Error: no matc path configured for ${target.name}. Set one of ${target.matcPathEnvKeys.join(
          ", "
        )} in .env`
      );
      process.exit(1);
    }
    if (!existsSync(matcPath)) {
      console.error(`Error: matc binary not found at ${matcPath}`);
      process.exit(1);
    }
    return { ...target, matcPath };
  });

  console.log(`Compiling ${matFiles.length} material(s) for all platforms...\n`);

  let failures = 0;

  for (const matFile of matFiles) {
    const matFilePath = resolve(SOURCE_MATERIAL_FOLDER, matFile);
    for (const target of targetConfigs) {
      const outputFile = resolve(
        target.outputFolder,
        matFile.replace(/\.mat$/, ".filamat")
      );
      const command = `"${target.matcPath}" ${target.apiArgs} --platform mobile -o "${outputFile}" "${matFilePath}"`;

      console.log(
        `  [${target.name}] ${basename(matFile)} → ${basename(outputFile)}`
      );

      try {
        execSync(command, { stdio: "pipe" });
      } catch (error) {
        console.error(`  ✗ Failed to compile ${matFile} for ${target.name}`);
        if (error instanceof Error && "stderr" in error) {
          console.error(String((error as any).stderr));
        }
        failures++;
      }
    }
  }

  const totalJobs = matFiles.length * targetConfigs.length;
  console.log(`\nDone: ${totalJobs - failures}/${totalJobs} compiled successfully.`);

  if (failures > 0) {
    process.exit(1);
  }
};

main();
