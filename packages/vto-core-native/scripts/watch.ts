import chokidar from "chokidar";
import { join } from "node:path";
import { bundleAll, CORE_ROOT } from "./bundle";

// Paths to watch, relative to CORE_ROOT. Mirror the copy rules in bundle.ts:
// if the bundler copies it, we watch it.
const WATCH_GLOBS = [
  "android/src/main/java/eu/alan/vto/core/**",
  "android/src/main/assets/materials/**",
  "android/src/main/assets/envs/**",
  "ios/*.swift",
  "ios/*.mm",
  "ios/*.m",
  "ios/*.h",
  "ios/*.hpp",
  "ios/*.cpp",
  "ios/*.c",
  "ios/assets/materials/**",
  "ios/assets/envs/**",
  "src/expo.ts",
  "src/types.ts",
].map((p) => join(CORE_ROOT, p));

// Debounce: editors often emit several change events for a single save
// (rename-then-write on atomic saves), so collapse bursts before re-bundling.
const DEBOUNCE_MS = 150;

let pending: NodeJS.Timeout | null = null;
let bundling = false;

const scheduleBundle = (reason: string) => {
  if (pending) clearTimeout(pending);
  pending = setTimeout(() => {
    pending = null;
    if (bundling) {
      // Another bundle is already in flight; kick off a fresh one right after
      // so we don't miss the latest change.
      scheduleBundle(reason);
      return;
    }
    bundling = true;
    console.log(`\n[watch] ${reason} — re-bundling...`);
    try {
      bundleAll();
    } catch (err) {
      console.error("[watch] bundle failed:", err);
    } finally {
      bundling = false;
    }
  }, DEBOUNCE_MS);
};

// Initial bundle so wrappers are up-to-date on start.
bundleAll();

console.log("\n[watch] watching core sources; Ctrl-C to stop.");

const watcher = chokidar.watch(WATCH_GLOBS, {
  ignoreInitial: true,
  // Don't follow into .git, node_modules, etc. (none of our globs touch them,
  // but set as defense-in-depth).
  ignored: (path) => /(\/node_modules\/|\/\.git\/)/.test(path),
});

watcher.on("add", (p) => scheduleBundle(`added ${p}`));
watcher.on("change", (p) => scheduleBundle(`changed ${p}`));
watcher.on("unlink", (p) => scheduleBundle(`removed ${p}`));
watcher.on("error", (err) => console.error("[watch] watcher error:", err));

// Graceful shutdown — make Ctrl-C exit cleanly without a stack trace.
const stop = () => {
  console.log("\n[watch] stopping.");
  watcher.close().finally(() => process.exit(0));
};
process.on("SIGINT", stop);
process.on("SIGTERM", stop);
