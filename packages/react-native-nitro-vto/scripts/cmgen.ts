import { execSync } from "child_process";
import { copyFileSync, existsSync, mkdtempSync, readdirSync, rmSync } from "fs";
import { resolve, basename, join } from "path";
import { tmpdir } from "os";
import dotenv from "dotenv";

dotenv.config({ path: resolve(__dirname, "../.env"), quiet: true });

const SOURCE_ENVS_FOLDER = "assets/envs";
const TARGET_ENVS_FOLDERS = ["ios/assets/envs", "android/src/main/assets/envs"];

const USAGE = `
Usage: npm run cmgen

Processes all .hdr files in assets/envs and writes generated KTX/SH files to both platform env folders.
`;

const resolveCmgenPath = (): string | undefined => {
  return (
    process.env.CMGEN_PATH ??
    process.env.CMGEN_IOS_PATH ??
    process.env.CMGEN_ANDROID_PATH
  );
};

const main = () => {
  if (process.argv.length > 2) {
    console.error(USAGE);
    process.exit(1);
  }

  const cmgenPath = resolveCmgenPath();

  if (!cmgenPath) {
    console.error(
      "Error: no cmgen path configured. Set CMGEN_PATH (or CMGEN_IOS_PATH / CMGEN_ANDROID_PATH) in .env"
    );
    process.exit(1);
  }

  if (!existsSync(cmgenPath)) {
    console.error(`Error: cmgen binary not found at ${cmgenPath}`);
    process.exit(1);
  }

  const hdrFiles = readdirSync(SOURCE_ENVS_FOLDER).filter((f) => f.endsWith(".hdr"));

  if (hdrFiles.length === 0) {
    console.error(`No .hdr files found in ${SOURCE_ENVS_FOLDER}`);
    process.exit(1);
  }

  console.log(`Processing ${hdrFiles.length} environment(s) for all platforms...\n`);

  const tempDeployDir = mkdtempSync(join(tmpdir(), "nitro-vto-cmgen-"));
  const deployPrefix = basename(tempDeployDir);

  let failures = 0;

  try {
    for (const hdrFile of hdrFiles) {
      const hdrFilePath = resolve(SOURCE_ENVS_FOLDER, hdrFile);
      const sourceName = hdrFile.replace(/\.hdr$/, "");
      const command = `"${cmgenPath}" --format=ktx --size=256 --deploy="${tempDeployDir}" "${hdrFilePath}"`;

      console.log(`  ${hdrFile}`);

      try {
        execSync(command, { stdio: "pipe" });

        const generated = {
          ibl: join(tempDeployDir, `${deployPrefix}_ibl.ktx`),
          skybox: join(tempDeployDir, `${deployPrefix}_skybox.ktx`),
          sh: join(tempDeployDir, "sh.txt"),
        };

        if (!existsSync(generated.ibl) || !existsSync(generated.skybox) || !existsSync(generated.sh)) {
          throw new Error("cmgen did not produce expected outputs");
        }

        for (const targetFolder of TARGET_ENVS_FOLDERS) {
          const iblTarget = join(targetFolder, `${sourceName}_ibl.ktx`);
          const skyboxTarget = join(targetFolder, `${sourceName}_skybox.ktx`);
          const shTarget = join(targetFolder, `${sourceName}_sh.txt`);
          copyFileSync(generated.ibl, iblTarget);
          copyFileSync(generated.skybox, skyboxTarget);
          copyFileSync(generated.sh, shTarget);
          console.log(`    ${basename(iblTarget)}, ${basename(skyboxTarget)}, ${basename(shTarget)} → ${targetFolder}`);
        }
      } catch (error) {
        console.error(`  ✗ Failed to process ${hdrFile}`);
        if (error instanceof Error && "stderr" in error) {
          console.error(String((error as any).stderr));
        } else if (error instanceof Error) {
          console.error(error.message);
        }
        failures++;
      }
    }
  } finally {
    rmSync(tempDeployDir, { recursive: true, force: true });
  }

  console.log(`\nDone: ${hdrFiles.length - failures}/${hdrFiles.length} processed successfully.`);

  if (failures > 0) {
    process.exit(1);
  }
};

main();
