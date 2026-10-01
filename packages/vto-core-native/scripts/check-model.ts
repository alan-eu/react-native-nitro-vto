/**
 * check-model — automated QA gate for incoming glasses .glb models.
 *
 * Partner-produced (photogrammetry) models are parsed and checked against the
 * authoring conventions the renderer relies on, plus geometric norms derived
 * from the existing catalog. It flags anything anomalous for the manual render
 * review step; it does NOT modify the model.
 *
 *   npm run check-model <file.glb | directory> [--json]
 *
 * Exit code 0 if every model passes, 1 if any ERROR or WARN is raised — so it
 * can sit in the publish pipeline before the human render check.
 *
 * All geometry is measured in asset-root-local space (the same space the engine
 * articulates/anchors in: scene-graph transforms applied, before the per-frame
 * face transform). Lengths are millimetres, angles degrees.
 */

import { readdirSync, readFileSync, statSync } from "fs";
import { basename, extname, join, resolve } from "path";

// ─── Thresholds (derived from the current ~98-model catalog) ────────────────
// These bands pass the well-authored majority and flag the known outliers.
// Tune against the catalog if the convention shifts.
const TH = {
  // Root scene node should bake the FBX Z-up→Y-up convention: −90° about X.
  rootAngleDeg: 90,
  rootAngleTolDeg: 6, // |angle−90| beyond this → flag (878066 ≈103°, 878085 ≈93°)
  rootAxisDot: 0.97, // axis·(−1,0,0) below this → flag (identity-root 793026/942317)

  // Left/right mirror symmetry of lens centres.
  symXMismatchMm: 3, // | |Lx| − |Rx| |
  symRollMm: 3, // |Ly − Ry| (frame not level)

  // Lens-centre height above the model origin. The engine now anchors the
  // lens-centre to the nose bridge, so this is INFO (authoring quality), not a
  // user-facing defect. Norm ≈ 0 (83/98 within ±1mm).
  lensCenterInfoMm: 3,

  // Temple rake: hinge→tip vertical angle (+ = tip points up). Temples must
  // angle DOWN to reach the ear; catalog band ≈ −6° to −16° (median −9.4°).
  // Too horizontal → rides up above the ear (772092 ≈ −1.8°).
  templeRakeMaxDeg: -3, // rake above this (too horizontal) → flag

  // Overall frame width (X extent). Real frames ≈ 120–150 mm.
  widthMinMm: 100,
  widthMaxMm: 160,
};

const REQUIRED_NODES = [
  "HingeL_temple",
  "HingeR_temple",
  "TempleL_geometry",
  "TempleR_geometry",
  "LensL_geometry",
  "LensR_geometry",
];

// ─── glb parsing ─────────────────────────────────────────────────────────────
type Gltf = { json: any; buf: Buffer; binStart: number };

const parseGlb = (path: string): Gltf => {
  const buf = readFileSync(path);
  if (buf.readUInt32LE(0) !== 0x46546c67) throw new Error("not a glb (bad magic)");
  const jsonLen = buf.readUInt32LE(12);
  const json = JSON.parse(buf.subarray(20, 20 + jsonLen).toString("utf8"));
  // header(12) + json chunk header(8) + json + bin chunk header(8)
  return { json, buf, binStart: 20 + jsonLen + 8 };
};

// ─── small matrix / vector helpers (column-major 4×4) ───────────────────────
type Mat = number[];
type Vec3 = [number, number, number];

const IDENT: Mat = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];

const mul = (a: Mat, b: Mat): Mat => {
  const r = new Array(16).fill(0);
  for (let c = 0; c < 4; c++)
    for (let row = 0; row < 4; row++) {
      let s = 0;
      for (let k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
      r[c * 4 + row] = s;
    }
  return r;
};

const trs = (n: any): Mat => {
  if (n.matrix) return n.matrix.slice();
  const t = n.translation || [0, 0, 0];
  const [x, y, z, w] = n.rotation || [0, 0, 0, 1];
  const s = n.scale || [1, 1, 1];
  const R = [
    1 - 2 * (y * y + z * z), 2 * (x * y + w * z), 2 * (x * z - w * y), 0,
    2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x), 0,
    2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y), 0,
    0, 0, 0, 1,
  ];
  const S = [s[0], 0, 0, 0, 0, s[1], 0, 0, 0, 0, s[2], 0, 0, 0, 0, 1];
  const M = mul(R, S);
  M[12] = t[0]; M[13] = t[1]; M[14] = t[2];
  return M;
};

const apply = (m: Mat, v: Vec3): Vec3 => [
  m[0] * v[0] + m[4] * v[1] + m[8] * v[2] + m[12],
  m[1] * v[0] + m[5] * v[1] + m[9] * v[2] + m[13],
  m[2] * v[0] + m[6] * v[1] + m[10] * v[2] + m[14],
];

const hypot = (a: number, b: number) => Math.sqrt(a * a + b * b);
const norm = (v: Vec3): Vec3 => {
  const l = Math.hypot(...v) || 1;
  return [v[0] / l, v[1] / l, v[2] / l];
};

// ─── scene-graph traversal (asset-root-local space) ─────────────────────────
type Scene = {
  nodes: any[];
  parent: number[];
  byName: Record<string, number>;
  rootIdx: number;
};

const buildScene = (g: Gltf): Scene => {
  const nodes = g.json.nodes || [];
  const parent = new Array(nodes.length).fill(-1);
  nodes.forEach((n: any, i: number) =>
    (n.children || []).forEach((c: number) => (parent[c] = i))
  );
  const byName: Record<string, number> = {};
  nodes.forEach((n: any, i: number) => {
    if (n.name) byName[n.name] = i;
  });
  const rootIdx = (g.json.scenes?.[g.json.scene || 0]?.nodes || [0])[0];
  return { nodes, parent, byName, rootIdx };
};

const worldOf = (s: Scene, idx: number): Mat => {
  const chain: number[] = [];
  for (let c = idx; c !== -1; c = s.parent[c]) chain.unshift(c);
  let w = IDENT;
  for (const c of chain) w = mul(w, trs(s.nodes[c]));
  return w;
};

const readPositions = (g: Gltf, accessorIdx: number): Vec3[] => {
  const a = g.json.accessors[accessorIdx];
  const bv = g.json.bufferViews[a.bufferView];
  const stride = bv.byteStride || 12;
  const base = g.binStart + (bv.byteOffset || 0) + (a.byteOffset || 0);
  const out: Vec3[] = [];
  for (let i = 0; i < a.count; i++) {
    const o = base + i * stride;
    out.push([g.buf.readFloatLE(o), g.buf.readFloatLE(o + 4), g.buf.readFloatLE(o + 8)]);
  }
  return out;
};

// World-space vertices of a node's mesh (empty if the node has no mesh).
const nodeWorldVerts = (g: Gltf, s: Scene, idx: number): Vec3[] => {
  const mesh = s.nodes[idx]?.mesh;
  if (mesh === undefined) return [];
  const w = worldOf(s, idx);
  const out: Vec3[] = [];
  for (const prim of g.json.meshes[mesh].primitives)
    for (const v of readPositions(g, prim.attributes.POSITION)) out.push(apply(w, v));
  return out;
};

const centroid = (pts: Vec3[]): Vec3 => {
  const c: Vec3 = [0, 0, 0];
  for (const p of pts) { c[0] += p[0]; c[1] += p[1]; c[2] += p[2]; }
  return [c[0] / pts.length, c[1] / pts.length, c[2] / pts.length];
};

const aabb = (pts: Vec3[]) => {
  const mn: Vec3 = [Infinity, Infinity, Infinity];
  const mx: Vec3 = [-Infinity, -Infinity, -Infinity];
  for (const p of pts) for (let i = 0; i < 3; i++) { mn[i] = Math.min(mn[i], p[i]); mx[i] = Math.max(mx[i], p[i]); }
  return { mn, mx, center: [(mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2] as Vec3 };
};

// Orthonormalised rotation of a transform, as axis-angle (degrees).
const axisAngle = (m: Mat) => {
  const x = norm([m[0], m[1], m[2]]);
  let y: Vec3 = [m[4], m[5], m[6]];
  const d = x[0] * y[0] + x[1] * y[1] + x[2] * y[2];
  y = norm([y[0] - d * x[0], y[1] - d * x[1], y[2] - d * x[2]]);
  const z: Vec3 = [
    x[1] * y[2] - x[2] * y[1],
    x[2] * y[0] - x[0] * y[2],
    x[0] * y[1] - x[1] * y[0],
  ];
  const trace = x[0] + y[1] + z[2];
  const angle = (Math.acos(Math.max(-1, Math.min(1, (trace - 1) / 2))) * 180) / Math.PI;
  let axis: Vec3 = [y[2] - z[1], z[0] - x[2], x[1] - y[0]];
  const s = Math.hypot(...axis);
  axis = s > 1e-6 ? [axis[0] / s, axis[1] / s, axis[2] / s] : [0, 0, 0];
  return { angle, axis };
};

// ─── checks ──────────────────────────────────────────────────────────────────
type Severity = "ERROR" | "WARN" | "INFO";
type Finding = { severity: Severity; check: string; message: string };

const checkModel = (g: Gltf): Finding[] => {
  const s = buildScene(g);
  const f: Finding[] = [];

  // 1. Required nodes — the renderer keys articulation/anchoring off these names.
  const missing = REQUIRED_NODES.filter((n) => s.byName[n] === undefined);
  if (missing.length) {
    f.push({ severity: "ERROR", check: "nodes", message: `missing node(s): ${missing.join(", ")}` });
  }

  // 2. Root orientation — expect the −90°-about-X (Z-up→Y-up) convention.
  const rootAA = axisAngle(worldOf(s, s.rootIdx));
  const axisDotNegX = -rootAA.axis[0];
  if (
    Math.abs(rootAA.angle - TH.rootAngleDeg) > TH.rootAngleTolDeg ||
    axisDotNegX < TH.rootAxisDot
  ) {
    f.push({
      severity: "WARN",
      check: "root",
      message: `non-standard root rotation: ${rootAA.angle.toFixed(0)}° about [${rootAA.axis.map((v) => v.toFixed(2)).join(",")}] (expected ~90° about [-1,0,0])`,
    });
  }

  const hasLenses = s.byName.LensL_geometry !== undefined && s.byName.LensR_geometry !== undefined;
  if (hasLenses) {
    const lL = aabb(nodeWorldVerts(g, s, s.byName.LensL_geometry));
    const lR = aabb(nodeWorldVerts(g, s, s.byName.LensR_geometry));

    // 3. Symmetry + roll.
    const xMismatch = Math.abs(Math.abs(lL.center[0]) - Math.abs(lR.center[0])) * 1000;
    const roll = Math.abs(lL.center[1] - lR.center[1]) * 1000;
    if (xMismatch > TH.symXMismatchMm)
      f.push({ severity: "WARN", check: "symmetry", message: `lenses asymmetric in X by ${xMismatch.toFixed(1)}mm` });
    if (roll > TH.symRollMm)
      f.push({ severity: "WARN", check: "roll", message: `lenses not level: L/R height differs ${roll.toFixed(1)}mm` });

    // 4. Lens-centre vertical offset (engine-compensated → INFO).
    const lensCenterY = ((lL.center[1] + lR.center[1]) / 2) * 1000;
    if (Math.abs(lensCenterY) > TH.lensCenterInfoMm)
      f.push({ severity: "INFO", check: "lens-center", message: `lens-centre ${lensCenterY.toFixed(1)}mm off origin (engine anchors this, but non-ideal authoring)` });

    // 7. Frame width sanity.
    const widthMm = (lL.mn[0] < lR.mn[0]
      ? lR.mx[0] - lL.mn[0]
      : lL.mx[0] - lR.mn[0]) * 1000;
    if (widthMm < TH.widthMinMm || widthMm > TH.widthMaxMm)
      f.push({ severity: "WARN", check: "scale", message: `frame width ${widthMm.toFixed(0)}mm out of plausible range (${TH.widthMinMm}–${TH.widthMaxMm}mm) — check units/scale` });

    // 6. Lens material alphaMode consistency (L vs R).
    const lensMat = (nodeIdx: number): string | null => {
      const mesh = s.nodes[nodeIdx].mesh;
      if (mesh === undefined) return null;
      const matIdx = g.json.meshes[mesh].primitives[0]?.material;
      return matIdx === undefined ? null : g.json.materials?.[matIdx]?.alphaMode || "OPAQUE";
    };
    const aL = lensMat(s.byName.LensL_geometry);
    const aR = lensMat(s.byName.LensR_geometry);
    if (aL && aR && aL !== aR)
      f.push({ severity: "WARN", check: "lens-material", message: `left/right lens alphaMode differ (${aL} vs ${aR})` });
  }

  // 5. Temple rake — hinge→tip vertical angle from the rear-most vertices
  // (robust; the engine uses the AABB-centre proxy at runtime).
  const rake = (hinge: string, temple: string): number | null => {
    if (s.byName[hinge] === undefined || s.byName[temple] === undefined) return null;
    const H = worldOf(s, s.byName[hinge]);
    const pts = nodeWorldVerts(g, s, s.byName[temple]);
    if (!pts.length) return null;
    const rearCount = Math.max(1, Math.floor(pts.length * 0.15));
    const rear = [...pts].sort((a, b) => a[2] - b[2]).slice(0, rearCount);
    const rc = centroid(rear);
    return (Math.atan2(rc[1] - H[13], hypot(rc[0] - H[12], rc[2] - H[14])) * 180) / Math.PI;
  };
  const rakeL = rake("HingeL_temple", "TempleL_geometry");
  const rakeR = rake("HingeR_temple", "TempleR_geometry");
  if (rakeL !== null && rakeR !== null) {
    const r = (rakeL + rakeR) / 2;
    if (r > TH.templeRakeMaxDeg)
      f.push({ severity: "WARN", check: "temple-rake", message: `temples too horizontal (rake ${r.toFixed(1)}°, expected ≤ ${TH.templeRakeMaxDeg}° down) — will ride up above the ear` });
  }

  return f;
};

// ─── CLI ─────────────────────────────────────────────────────────────────────
const USAGE = `
Usage: npm run check-model <file.glb | directory> [--json]

Checks incoming glasses .glb models against the renderer's authoring conventions
and catalog-derived geometric norms. Exit 0 if all pass, 1 if any ERROR/WARN.
`;

const SEV_RANK: Record<Severity, number> = { ERROR: 0, WARN: 1, INFO: 2 };

const main = () => {
  const args = process.argv.slice(2);
  const asJson = args.includes("--json");
  const target = args.find((a) => !a.startsWith("--"));
  if (!target) { console.error(USAGE); process.exit(1); }

  const path = resolve(target);
  let files: string[];
  try {
    files = statSync(path).isDirectory()
      ? readdirSync(path).filter((f) => extname(f).toLowerCase() === ".glb").sort().map((f) => join(path, f))
      : [path];
  } catch {
    console.error(`Path not found: ${path}`);
    process.exit(1);
  }
  if (!files.length) { console.error(`No .glb files in ${path}`); process.exit(1); }

  const results = files.map((file) => {
    try {
      return { file, name: basename(file, ".glb"), findings: checkModel(parseGlb(file)) };
    } catch (e) {
      return { file, name: basename(file, ".glb"), findings: [{ severity: "ERROR" as Severity, check: "parse", message: (e as Error).message }] };
    }
  });

  if (asJson) {
    console.log(JSON.stringify(results, null, 2));
  } else {
    const SYM: Record<Severity, string> = { ERROR: "✗", WARN: "⚠", INFO: "·" };
    for (const r of results) {
      const flags = r.findings.filter((x) => x.severity !== "INFO");
      if (!flags.length && !r.findings.length) { console.log(`✓ ${r.name}`); continue; }
      console.log(`${flags.length ? "⚠" : "✓"} ${r.name}`);
      for (const x of [...r.findings].sort((a, b) => SEV_RANK[a.severity] - SEV_RANK[b.severity]))
        console.log(`    ${SYM[x.severity]} [${x.check}] ${x.message}`);
    }
  }

  const passed = results.filter((r) => !r.findings.some((x) => x.severity !== "INFO")).length;
  const flagged = results.length - passed;
  console.error(`\n${passed}/${results.length} passed; ${flagged} flagged for review.`);
  process.exit(flagged > 0 ? 1 : 0);
};

main();
