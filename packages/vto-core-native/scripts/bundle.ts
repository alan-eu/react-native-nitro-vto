import {
  cpSync,
  existsSync,
  mkdirSync,
  readdirSync,
  rmSync,
  statSync,
} from "node:fs";
import { basename, join, resolve } from "node:path";

// Root of this (core) package.
const CORE_ROOT = resolve(__dirname, "..");
// Monorepo packages root.
const PACKAGES_ROOT = resolve(CORE_ROOT, "..");

// RN wrapper packages that should receive a bundled copy of core's native
// sources + assets. Add new wrappers here when they come online.
const WRAPPERS = ["react-native-nitro-vto", "react-native-vto"] as const;

// Copy rules. Each entry maps a path inside core → the same path inside the
// target wrapper. Files/dirs are wiped first in the destination to keep the
// copy hermetic. `optional` entries skip silently if the source doesn't exist
// (useful for the old-arch wrapper before it's scaffolded).
type Rule = {
  from: string; // relative to core root
  to: string; // relative to wrapper root
  kind: "file" | "dir";
};

const RULES: Rule[] = [
  // Kotlin renderers → Android Java source tree
  {
    from: "android/src/main/java/eu/alan/vto/core",
    to: "android/src/main/java/eu/alan/vto/core",
    kind: "dir",
  },
  // Compiled Android assets
  {
    from: "android/src/main/assets/materials",
    to: "android/src/main/assets/materials",
    kind: "dir",
  },
  {
    from: "android/src/main/assets/envs",
    to: "android/src/main/assets/envs",
    kind: "dir",
  },
  // iOS native sources — copy every file in core/ios that isn't the assets dir.
  // We enumerate dynamically below so adding new .swift/.mm/.h files doesn't
  // require editing this list.
  // (handled specially further down via enumerateIosSources)
  // Compiled iOS assets
  {
    from: "ios/assets/materials",
    to: "ios/assets/materials",
    kind: "dir",
  },
  { from: "ios/assets/envs", to: "ios/assets/envs", kind: "dir" },
  // Shared TS: Expo plugin + prop/method typedefs
  { from: "src/expo.ts", to: "src/expo.ts", kind: "file" },
  { from: "src/types.ts", to: "src/types.ts", kind: "file" },
];

const enumerateIosSources = (): string[] => {
  const dir = join(CORE_ROOT, "ios");
  return readdirSync(dir)
    .filter((name) => {
      const full = join(dir, name);
      if (!statSync(full).isFile()) return false;
      return /\.(swift|mm|m|h|hpp|cpp|c)$/.test(name);
    })
    .sort();
};

const wipe = (target: string, kind: Rule["kind"]) => {
  if (!existsSync(target)) return;
  if (kind === "dir") {
    rmSync(target, { recursive: true, force: true });
  } else {
    rmSync(target, { force: true });
  }
};

const copy = (sourcePath: string, targetPath: string, kind: Rule["kind"]) => {
  mkdirSync(resolve(targetPath, ".."), { recursive: true });
  if (kind === "dir") {
    cpSync(sourcePath, targetPath, { recursive: true });
  } else {
    cpSync(sourcePath, targetPath);
  }
};

const bundleToWrapper = (wrapperName: string) => {
  const wrapperRoot = join(PACKAGES_ROOT, wrapperName);
  if (!existsSync(wrapperRoot)) {
    console.log(`  ${wrapperName} → skipped (package doesn't exist yet)`);
    return { wrapper: wrapperName, fileCount: 0, skipped: true };
  }

  let fileCount = 0;

  for (const rule of RULES) {
    const source = join(CORE_ROOT, rule.from);
    if (!existsSync(source)) {
      console.warn(`  ${wrapperName} → source missing: ${rule.from}`);
      continue;
    }
    const target = join(wrapperRoot, rule.to);
    wipe(target, rule.kind);
    copy(source, target, rule.kind);

    if (rule.kind === "dir") {
      // Rough count of files in dir for the summary line.
      const count = countFiles(target);
      fileCount += count;
      console.log(`  ${wrapperName} → ${rule.to} (${count} files)`);
    } else {
      fileCount += 1;
      console.log(`  ${wrapperName} → ${rule.to}`);
    }
  }

  // iOS root-level native sources (enumerated per-run so additions pick up
  // without editing the rules table).
  const iosSources = enumerateIosSources();
  const iosTargetDir = join(wrapperRoot, "ios");
  mkdirSync(iosTargetDir, { recursive: true });

  // Wipe only the previously-bundled filenames, preserving any wrapper-owned
  // files (e.g. HybridNitroVtoView.swift, NitroVto.h, VtoViewManager.mm).
  // We keep a marker comment in each bundled file ... simpler: just rely on
  // the filename match — bundled files land with their core-side names.
  for (const name of iosSources) {
    const srcPath = join(CORE_ROOT, "ios", name);
    const dstPath = join(iosTargetDir, name);
    wipe(dstPath, "file");
    copy(srcPath, dstPath, "file");
    fileCount += 1;
  }
  console.log(
    `  ${wrapperName} → ios/*.{swift,mm,m,h,hpp} (${iosSources.length} files)`
  );

  return { wrapper: wrapperName, fileCount, skipped: false };
};

const countFiles = (dir: string): number => {
  if (!existsSync(dir)) return 0;
  const entries = readdirSync(dir, { withFileTypes: true });
  let count = 0;
  for (const e of entries) {
    const full = join(dir, e.name);
    if (e.isDirectory()) count += countFiles(full);
    else count += 1;
  }
  return count;
};

export const bundleAll = () => {
  console.log(
    `Bundling core sources from ${basename(CORE_ROOT)} into ${WRAPPERS.length} wrapper(s)...\n`
  );

  const results = WRAPPERS.map((w) => bundleToWrapper(w));

  console.log();
  for (const r of results) {
    const label = r.skipped ? "skipped" : `${r.fileCount} files`;
    console.log(`  ${r.wrapper}: ${label}`);
  }
  console.log("\nDone.");
};

export { CORE_ROOT };

// Only run when invoked directly (not when imported by watch.ts).
if (require.main === module) {
  bundleAll();
}
