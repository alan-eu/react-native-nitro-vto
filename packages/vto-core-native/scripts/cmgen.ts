import { execSync } from "child_process";
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readdirSync,
  renameSync,
  rmSync,
} from "fs";
import { resolve, basename, join } from "path";
import { tmpdir } from "os";
import dotenv from "dotenv";

// `.env` lives at the monorepo root (see `.env.example`).
dotenv.config({ path: resolve(__dirname, "../../../.env"), quiet: true });

const SOURCE_FOLDER = resolve(__dirname, "../assets/envs");
const IOS_OUTPUT_FOLDER = resolve(__dirname, "../ios/assets/envs");
const ANDROID_OUTPUT_FOLDER = resolve(
  __dirname,
  "../android/src/main/assets/envs"
);

const TARGETS: { label: string; outputDir: string }[] = [
  { label: "ios", outputDir: IOS_OUTPUT_FOLDER },
  { label: "android", outputDir: ANDROID_OUTPUT_FOLDER },
];

const USAGE = `
Usage: npm run cmgen

Processes every .hdr in packages/react-native-nitro-vto/assets/envs/ with cmgen
and copies the outputs (_ibl.ktx, _skybox.ktx, _sh.txt) to:
  - ios/assets/envs/
  - android/src/main/assets/envs/

KTX outputs are platform-agnostic, so cmgen runs once per .hdr then we copy.

Requires CMGEN_PATH in .env (path to a Filament cmgen binary).
`;

const main = () => {
  if (process.argv[2]) {
    console.error(USAGE);
    console.error(`Unexpected argument "${process.argv[2]}"`);
    process.exit(1);
  }

  const cmgenPath = process.env.CMGEN_PATH;
  if (!cmgenPath) {
    console.error(USAGE);
    console.error("Error: CMGEN_PATH not defined in .env");
    process.exit(1);
  }
  if (!existsSync(cmgenPath)) {
    console.error(`Error: cmgen binary not found at ${cmgenPath}`);
    process.exit(1);
  }

  if (!existsSync(SOURCE_FOLDER)) {
    console.error(`Error: source folder not found: ${SOURCE_FOLDER}`);
    process.exit(1);
  }

  const hdrFiles = readdirSync(SOURCE_FOLDER).filter((f) => f.endsWith(".hdr"));
  if (hdrFiles.length === 0) {
    console.error(`No .hdr files found in ${SOURCE_FOLDER}`);
    process.exit(1);
  }

  for (const { outputDir } of TARGETS) {
    mkdirSync(outputDir, { recursive: true });
  }

  console.log(
    `Compiling ${hdrFiles.length} environment(s) for ${TARGETS.length} target(s)...\n`
  );

  // cmgen's --deploy flag names outputs using the deploy folder's basename as the
  // prefix (deploy=/tmp/foo/envs → envs_ibl.ktx, envs_skybox.ktx, sh.txt). We run
  // into a temporary "envs" deploy folder per .hdr, rename, then copy to every
  // platform output directory.
  const scratchRoot = join(tmpdir(), `nitrovto-cmgen-${process.pid}`);
  mkdirSync(scratchRoot, { recursive: true });

  let failures = 0;

  try {
    for (const hdrFile of hdrFiles) {
      const hdrPath = join(SOURCE_FOLDER, hdrFile);
      const sourceName = hdrFile.replace(/\.hdr$/, "");
      const deployDir = join(scratchRoot, "envs");
      mkdirSync(deployDir, { recursive: true });

      const command = `"${cmgenPath}" --format=ktx --size=256 --deploy="${deployDir}" "${hdrPath}"`;
      console.log(`  ${hdrFile}`);

      try {
        execSync(command, { stdio: "pipe" });

        const renames: [string, string][] = [
          [`envs_ibl.ktx`, `${sourceName}_ibl.ktx`],
          [`envs_skybox.ktx`, `${sourceName}_skybox.ktx`],
          [`sh.txt`, `${sourceName}_sh.txt`],
        ];

        for (const [from, to] of renames) {
          const fromPath = join(deployDir, from);
          const toPath = join(deployDir, to);
          if (existsSync(fromPath)) renameSync(fromPath, toPath);
        }

        const artifacts = [
          `${sourceName}_ibl.ktx`,
          `${sourceName}_skybox.ktx`,
          `${sourceName}_sh.txt`,
        ];

        for (const { label, outputDir } of TARGETS) {
          for (const artifact of artifacts) {
            const srcPath = join(deployDir, artifact);
            if (!existsSync(srcPath)) continue;
            copyFileSync(srcPath, join(outputDir, artifact));
            console.log(`    → ${label}/${artifact}`);
          }
        }
      } catch (error) {
        console.error(`    ✗ failed`);
        if (error instanceof Error && "stderr" in error) {
          console.error(String((error as any).stderr));
        }
        failures++;
      } finally {
        rmSync(deployDir, { recursive: true, force: true });
      }
    }
  } finally {
    rmSync(scratchRoot, { recursive: true, force: true });
  }

  console.log(
    `\nDone: ${hdrFiles.length - failures}/${hdrFiles.length} compiled successfully.`
  );
  if (failures > 0) process.exit(1);
};

main();
