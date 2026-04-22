import { execFileSync, execSync, spawnSync } from "child_process";
import { existsSync } from "node:fs";
import { join, resolve } from "node:path";

// The dev-client deep-link scheme is derived from the Expo `slug` in app.config.ts
// (slug "vto-demo" → "exp+vto-demo://…"). Update here if the slug ever changes.
const DEV_CLIENT_SCHEME = "exp+vto-demo";

const USAGE = `
Usage: npm run androidAll -- --host <ip> [--port <port>]

Builds the Android debug APK once, installs it on every connected adb device,
and opens the Expo dev-client deep link so each device connects to Metro.

Arguments:
  --host <ip>      (required) The LAN IP of the machine running Metro.
  --port <port>    (optional) Metro port (default 8081).

Example:
  npm run androidAll -- --host 192.168.1.26
`;

const parseArgs = (argv: string[]): { host: string; port: string } => {
  let host: string | undefined;
  let port = "8081";

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--host") {
      host = argv[++i];
    } else if (arg === "--port") {
      port = argv[++i];
    } else {
      console.error(`Unknown argument: ${arg}`);
      console.error(USAGE);
      process.exit(1);
    }
  }

  if (!host) {
    console.error("Error: --host is required");
    console.error(USAGE);
    process.exit(1);
  }

  return { host, port };
};

const listConnectedDevices = (): string[] => {
  const output = execSync("adb devices", { encoding: "utf-8" });
  return output
    .split("\n")
    .slice(1) // drop "List of devices attached" header
    .map((line) => line.trim())
    .filter((line) => line.length > 0)
    .map((line) => line.split(/\s+/))
    .filter(([, status]) => status === "device")
    .map(([serial]) => serial);
};

const main = () => {
  const { host, port } = parseArgs(process.argv.slice(2));

  const exampleRoot = resolve(__dirname, "..");
  const androidDir = join(exampleRoot, "android");
  const apkPath = join(
    androidDir,
    "app/build/outputs/apk/debug/app-debug.apk"
  );

  // Step 1: build the APK once.
  console.log("Building debug APK...");
  execSync("./gradlew assembleDebug", {
    cwd: androidDir,
    stdio: "inherit",
  });

  if (!existsSync(apkPath)) {
    console.error(`Error: expected APK not found at ${apkPath}`);
    process.exit(1);
  }

  // Step 2: enumerate connected devices.
  const devices = listConnectedDevices();
  if (devices.length === 0) {
    console.error("Error: no connected adb devices");
    process.exit(1);
  }

  console.log(
    `\nFound ${devices.length} device(s): ${devices.join(", ")}\n`
  );

  // Step 3: install + launch on each device.
  const deepLink = `${DEV_CLIENT_SCHEME}://expo-development-client/?url=${encodeURIComponent(
    `http://${host}:${port}`
  )}`;

  type Result = { serial: string; ok: boolean; reason?: string };
  const results: Result[] = [];

  for (const serial of devices) {
    console.log(`  ${serial}`);

    try {
      execFileSync("adb", ["-s", serial, "install", "-r", apkPath], {
        stdio: "inherit",
      });
    } catch (error) {
      const reason =
        error instanceof Error ? error.message : String(error);
      console.error(`    ✗ install failed`);
      results.push({ serial, ok: false, reason });
      continue;
    }

    const launch = spawnSync(
      "adb",
      [
        "-s",
        serial,
        "shell",
        "am",
        "start",
        "-a",
        "android.intent.action.VIEW",
        "-d",
        deepLink,
      ],
      { stdio: "inherit" }
    );

    if (launch.status !== 0) {
      console.error(`    ✗ launch failed`);
      results.push({
        serial,
        ok: false,
        reason: `am start exited with status ${launch.status}`,
      });
      continue;
    }

    console.log(`    → installed + launched`);
    results.push({ serial, ok: true });
  }

  // Step 4: summary + exit code.
  const failures = results.filter((r) => !r.ok);
  const successes = results.length - failures.length;

  console.log(
    `\nDone: ${successes}/${results.length} device(s) succeeded.`
  );

  if (failures.length > 0) {
    for (const f of failures) {
      console.error(`  ✗ ${f.serial}${f.reason ? ` — ${f.reason}` : ""}`);
    }
    process.exit(1);
  }
};

main();
