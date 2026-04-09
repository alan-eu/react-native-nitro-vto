#!/usr/bin/env node

import { execFileSync } from "child_process";
import { createHash } from "crypto";
import { createReadStream } from "fs";
import { access, mkdir, mkdtemp, readFile, rename, rm, writeFile } from "fs/promises";
import { basename, dirname, join, resolve } from "path";
import { fileURLToPath } from "url";

type Platform = "android" | "ios";
type PlatformArg = Platform | "all";

type Lockfile = {
  version: string;
  artifacts: Record<Platform, { url: string; sha256: string }>;
};

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const rootDir = resolve(__dirname, "..");

const lockfilePath = process.env.FILAMENT_LOCKFILE ?? join(rootDir, "filament.lock.json");
const cacheDir = process.env.FILAMENT_CACHE_DIR ?? join(rootDir, ".cache", "filament");
const sdkDir = process.env.FILAMENT_SDK_DIR ?? join(rootDir, "third_party", "filament", "sdk");
const offline = process.env.FILAMENT_OFFLINE === "1";
const platformArg = (process.argv[2] ?? "all") as PlatformArg;

const usage = "Usage: fetch-filament-native.ts [android|ios|all]";

const delay = (ms: number) => new Promise((resolveDelay) => setTimeout(resolveDelay, ms));

const fileExists = async (path: string): Promise<boolean> => {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
};

const sha256File = async (path: string): Promise<string> => {
  const hash = createHash("sha256");
  await new Promise<void>((resolvePromise, rejectPromise) => {
    const stream = createReadStream(path);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("error", rejectPromise);
    stream.on("end", () => resolvePromise());
  });
  return hash.digest("hex");
};

const verifyArchive = async (archivePath: string, expectedSha: string): Promise<void> => {
  const actualSha = await sha256File(archivePath);
  if (actualSha !== expectedSha) {
    throw new Error(
      `Checksum mismatch for ${archivePath}\n  expected: ${expectedSha}\n  actual:   ${actualSha}`
    );
  }
};

const downloadArchive = async (url: string, archivePath: string): Promise<void> => {
  if (offline) {
    throw new Error(`Offline mode enabled and archive missing: ${archivePath}`);
  }
  if (!url.startsWith("https://")) {
    throw new Error(`Only HTTPS URLs are allowed: ${url}`);
  }

  const tmpPath = `${archivePath}.tmp`;
  let lastError: unknown;

  for (let attempt = 1; attempt <= 5; attempt += 1) {
    try {
      console.log(`Downloading ${url} (attempt ${attempt}/5)`);
      const response = await fetch(url, {
        redirect: "follow",
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const buffer = Buffer.from(await response.arrayBuffer());
      await writeFile(tmpPath, buffer);
      await rename(tmpPath, archivePath);
      return;
    } catch (error) {
      lastError = error;
      await rm(tmpPath, { force: true });
      if (attempt < 5) {
        await delay(2000);
      }
    }
  }

  throw new Error(`Failed to download ${url}: ${String(lastError)}`);
};

const installPlatform = async (platform: Platform, lock: Lockfile): Promise<void> => {
  const version = lock.version;
  const lockUrl = lock.artifacts[platform].url;
  const lockSha = lock.artifacts[platform].sha256;

  const envUrl =
    platform === "android"
      ? process.env.FILAMENT_ANDROID_URL ?? ""
      : process.env.FILAMENT_IOS_URL ?? "";
  const envSha =
    platform === "android"
      ? process.env.FILAMENT_ANDROID_SHA256 ?? ""
      : process.env.FILAMENT_IOS_SHA256 ?? "";

  let url = lockUrl;
  let expectedSha = lockSha;
  if (envUrl) {
    if (!envSha) {
      throw new Error(`${platform} URL override requires matching SHA256 override`);
    }
    url = envUrl;
    expectedSha = envSha;
  }

  const archiveName = basename(new URL(url).pathname);
  const archiveDir = join(cacheDir, `v${version}`, platform);
  const archivePath = join(archiveDir, archiveName);

  await mkdir(archiveDir, { recursive: true });

  if (await fileExists(archivePath)) {
    try {
      await verifyArchive(archivePath, expectedSha);
    } catch {
      console.error(`Cached archive is invalid, removing: ${archivePath}`);
      await rm(archivePath, { force: true });
    }
  }

  if (!(await fileExists(archivePath))) {
    await downloadArchive(url, archivePath);
  }

  await verifyArchive(archivePath, expectedSha);

  const targetDir = join(sdkDir, platform);
  const markerFile = join(targetDir, ".filament-sha256");
  if (await fileExists(markerFile)) {
    const installedSha = (await readFile(markerFile, "utf-8")).trim();
    if (installedSha === expectedSha) {
      console.log(`Filament ${platform} SDK already installed (${version})`);
      return;
    }
  }

  const extractRoot = await mkdtemp(join(cacheDir, `.extract-${platform}-`));
  try {
    execFileSync("tar", ["-xzf", archivePath, "-C", extractRoot], { stdio: "inherit" });
    const extracted = join(extractRoot, "filament");
    const markerHeader = join(extracted, "include", "filament", "Engine.h");
    if (!(await fileExists(markerHeader))) {
      throw new Error(`Invalid Filament archive layout for ${platform}`);
    }

    await rm(targetDir, { recursive: true, force: true });
    await rename(extracted, targetDir);

    await writeFile(join(targetDir, ".filament-sha256"), expectedSha);
    await writeFile(join(targetDir, ".filament-version"), version);
    console.log(`Installed Filament ${platform} SDK to ${targetDir}`);
  } finally {
    await rm(extractRoot, { recursive: true, force: true });
  }
};

const main = async () => {
  if (!["android", "ios", "all"].includes(platformArg)) {
    throw new Error(usage);
  }

  if (!(await fileExists(lockfilePath))) {
    throw new Error(`Lockfile not found: ${lockfilePath}`);
  }

  await mkdir(cacheDir, { recursive: true });
  await mkdir(sdkDir, { recursive: true });

  const lock = JSON.parse(await readFile(lockfilePath, "utf-8")) as Lockfile;

  if (platformArg === "all") {
    await installPlatform("android", lock);
    await installPlatform("ios", lock);
  } else {
    await installPlatform(platformArg, lock);
  }
};

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
