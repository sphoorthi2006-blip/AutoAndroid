// Run: node tests/video-basics.test.mjs
// Kept from the old units test (2026-11-21): picture fitting into 1080p, backdrop colour and the last-frame check. The rest of that file tested the deleted SVG Editor.
import { build } from "esbuild";
let fails = 0; const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };
// ---- v2 step 9: 1080p 30 fps only, and the last-frame check
{
  const q = await build({ entryPoints: ["src/quality.ts"], bundle: true, format: "esm", platform: "node", write: false, logLevel: "silent" });
  const qm = await import("data:text/javascript;base64," + Buffer.from(q.outputFiles[0].text).toString("base64"));
  const stub2 = { name: "stub2", setup(b) { b.onResolve({ filter: /^mp4-muxer$/ }, a => ({ path: a.path, namespace: "stub2" })); b.onLoad({ filter: /.*/, namespace: "stub2" }, () => ({ contents: "export class ArrayBufferTarget {} export class Muxer {}", loader: "js" })); } };
  const vb = await build({ entryPoints: ["src/video.ts"], bundle: true, format: "esm", platform: "node", write: false, plugins: [stub2], logLevel: "silent" });
  const vm = await import("data:text/javascript;base64," + Buffer.from(vb.outputFiles[0].text).toString("base64"));
  ok(vm.EXPORT_W === 1920 && vm.EXPORT_H === 1080 && vm.EXPORT_FPS === 30, "step 9: the export is 1920x1080 at 30 fps");
  const f1 = vm.fitInto(1376, 768), f2 = vm.fitInto(2752, 1536), f3 = vm.fitInto(1000, 1000), f4 = vm.fitInto(960, 540);
  ok(f1.w <= 1920 + 1e-6 && f1.h <= 1080 + 1e-6 && Math.abs(f1.w / f1.h - 1376 / 768) < 1e-9 && Math.abs(f1.x * 2 + f1.w - 1920) < 1e-6 && Math.abs(f1.y * 2 + f1.h - 1080) < 1e-6, "step 9: a 1376x768 picture is fitted and centred in 1080p, not stretched");
  ok(Math.abs(f2.w / f2.h - 2752 / 1536) < 1e-9 && f2.w <= 1920 + 1e-6, "step 9: a 4K-size picture is scaled down into 1080p");
  ok(f3.w === 1080 && f3.h === 1080 && f3.x === 420 && f3.y === 0, "step 9: a square picture gets bars left and right");
  ok(f4.w === 1920 && f4.h === 1080 && f4.x === 0 && f4.y === 0, "step 9: a small 16:9 picture is scaled up to fill 1080p");
  ok(vm.svgBackground('<svg><defs/><g><rect width="10" height="10" fill="#fdf6e3"/></g></svg>') === "#fdf6e3" && vm.svgBackground("<svg/>") === "#ffffff", "step 9: the bars take the picture's background colour");
  const W = 20, H = 10, a = new Uint8ClampedArray(W * H * 4).fill(255), b = new Uint8ClampedArray(a);
  ok(qm.compareFrames(a, b, W, H).mean === 0 && qm.qualityVerdict(qm.compareFrames(a, b, W, H)).ok, "step 9: identical frames are OK");
  for (let x = 0; x < 10; x++) for (let y = 0; y < H; y++) { const i = (y * W + x) * 4; b[i] = b[i + 1] = b[i + 2] = 0; }   // left half black
  const dAll = qm.compareFrames(a, b, W, H), dRight = qm.compareFrames(a, b, W, H, [{ x: 10, y: 0, w: 10, h: 10 }]), dLeft = qm.compareFrames(a, b, W, H, [{ x: 0, y: 0, w: 10, h: 10 }]);
  ok(Math.abs(dAll.bad - 0.5) < 1e-9 && !qm.qualityVerdict(dAll).ok && /CHECK/.test(qm.qualityVerdict(dAll).text), "step 9: half the picture wrong is flagged CHECK");
  ok(dRight.bad === 0 && qm.qualityVerdict(dRight).ok && dLeft.bad === 1, "step 9: only the chosen regions count (panels, not lettering)");
  b.set(a); b[0] = 0; ok(qm.qualityVerdict(qm.compareFrames(a, b, W, H)).ok, "step 9: a few edge pixels may differ");
}
console.log(fails ? `${fails} FAILED` : "all passed"); process.exit(fails ? 1 : 0);
