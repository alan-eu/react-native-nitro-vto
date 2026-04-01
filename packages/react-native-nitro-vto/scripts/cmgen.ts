import { execSync } from "child_process";
import { existsSync, readdirSync, renameSync } from "fs";
import { resolve, basename, join } from "path";
import dotenv from "dotenv";

dotenv.config({ path: resolve(__dirname, "../.env"), quiet: true });

const IOS_ENVS_FOLDER = "ios/assets/envs";
const ANDROID_ENVS_FOLDER = "android/src/main/assets/envs";

const USAGE = `
Usage: npm run cmgen <platform>

Processes all .hdr files in the platform's envs folder using cmgen.
Generates IBL and skybox KTX files.

Arguments:
  platform   Target platform: "ios" or "android"

Examples:
  npm run cmgen ios
  npm run cmgen android
`;

const main = () => {
  const platform = process.argv[2];

  if (!platform || (platform !== "ios" && platform !== "android")) {
    console.error(USAGE);
    process.exit(1);
  }

  const envsFolder =
    platform === "ios" ? IOS_ENVS_FOLDER : ANDROID_ENVS_FOLDER;

  const cmgenPathKey =
    platform === "ios" ? "CMGEN_IOS_PATH" : "CMGEN_ANDROID_PATH";
  const cmgenPath = process.env[cmgenPathKey];

  if (!cmgenPath) {
    console.error(`Error: ${cmgenPathKey} not defined in .env file`);
    process.exit(1);
  }

  if (!existsSync(cmgenPath)) {
    console.error(`Error: cmgen binary not found at ${cmgenPath}`);
    process.exit(1);
  }

  const hdrFiles = readdirSync(envsFolder).filter((f) =>
    f.endsWith(".hdr")
  );

  if (hdrFiles.length === 0) {
    console.error(`No .hdr files found in ${envsFolder}`);
    process.exit(1);
  }

  console.log(
    `Processing ${hdrFiles.length} environment(s) for ${platform}...\n`
  );

  // cmgen --deploy names output using the deploy folder's basename as prefix.
  // e.g. --deploy=ios/assets/envs produces envs_ibl.ktx, envs_skybox.ktx, sh.txt
  // We rename them to <source_name>_ibl.ktx, <source_name>_skybox.ktx, <source_name>_sh.txt
  const deployPrefix = basename(envsFolder);

  let failures = 0;

  for (const hdrFile of hdrFiles) {
    const hdrFilePath = resolve(envsFolder, hdrFile);
    const sourceName = hdrFile.replace(/\.hdr$/, "");
    const command = `"${cmgenPath}" --format=ktx --size=256 --deploy="${envsFolder}" "${hdrFilePath}"`;

    console.log(`  ${hdrFile}`);

    try {
      execSync(command, { stdio: "pipe" });

      // Rename cmgen output to use source filename as prefix
      const renames: [string, string][] = [
        [`${deployPrefix}_ibl.ktx`, `${sourceName}_ibl.ktx`],
        [`${deployPrefix}_skybox.ktx`, `${sourceName}_skybox.ktx`],
        [`sh.txt`, `${sourceName}_sh.txt`],
      ];

      for (const [from, to] of renames) {
        const fromPath = join(envsFolder, from);
        const toPath = join(envsFolder, to);
        if (existsSync(fromPath)) {
          renameSync(fromPath, toPath);
          console.log(`    ${from} → ${to}`);
        }
      }
    } catch (error) {
      console.error(`  ✗ Failed to process ${hdrFile}`);
      if (error instanceof Error && "stderr" in error) {
        console.error(String((error as any).stderr));
      }
      failures++;
    }
  }

  console.log(
    `\nDone: ${hdrFiles.length - failures}/${hdrFiles.length} processed successfully.`
  );

  if (failures > 0) {
    process.exit(1);
  }
};

main();
