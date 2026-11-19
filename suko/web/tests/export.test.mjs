// Run: node tests/export.test.mjs   (step 23: 1080p MP4 at 30 and 60 fps; step 24: 2K and 4K. Runs the real export loop in Node with a fake encoder and fake canvas.)
import { build } from "esbuild";
import { JSDOM } from "jsdom";
const dom = new JSDOM("<!doctype html><body></body>");
globalThis.DOMParser = dom.window.DOMParser; globalThis.XMLSerializer = dom.window.XMLSerializer;
const calls = { configs: [], encoded: [], flushed: 0, drawn: 0, finalized: 0, chunks: 0 };
globalThis.VideoFrame = class { constructor(_c, init) { this.timestamp = init.timestamp; this.duration = init.duration; } close() {} };
globalThis.VideoEncoder = class {
  static async isConfigSupported(c) { return { supported: !(c.framerate > 30 && c.codec.endsWith("28")) }; }   // like a phone without level 4.0 at 60 fps is not the point; any codec works
  constructor(o) { this.o = o; this.encodeQueueSize = 0; }
  configure(c) { calls.configs.push(c); }
  encode(f, opts) { calls.encoded.push({ ts: f.timestamp, dur: f.duration, key: !!opts.keyFrame }); this.o.output({ byteLength: 10 }, {}); }
  async flush() { calls.flushed++; } close() {}
};
globalThis.Image = class { set src(_v) { setTimeout(() => this.onload && this.onload(), 0); } };
globalThis.URL.createObjectURL = () => "blob:fake"; globalThis.URL.revokeObjectURL = () => {};
const ctx = { fillRect() {}, drawImage() { calls.drawn++; }, set fillStyle(_v) {} };
globalThis.document = { createElement: () => ({ width: 0, height: 0, getContext: () => ctx }) };
const fakeMuxer = { name: "fake", setup(b) {
  b.onResolve({ filter: /^mp4-muxer$/ }, a => ({ path: a.path, namespace: "fake" }));
  b.onLoad({ filter: /.*/, namespace: "fake" }, () => ({ loader: "js", contents: `
    export class ArrayBufferTarget { get buffer() { return new ArrayBuffer(8); } }
    export class Muxer { constructor(o) { globalThis.__mux = o; } addVideoChunk() { globalThis.__chunks = (globalThis.__chunks || 0) + 1; } finalize() { globalThis.__final = true; } }` })); } };
const r = await build({ entryPoints: ["src/video.ts"], bundle: true, format: "esm", platform: "node", write: false, plugins: [fakeMuxer], logLevel: "silent" });
const mod = await import("data:text/javascript;base64," + Buffer.from(r.outputFiles[0].text).toString("base64"));
let fails = 0; const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };
// a tiny animated drawing: one bar that grows for 2 seconds (so every frame differs)
const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1080" viewBox="0 0 1920 1080"><rect width="1920" height="1080" fill="#fdf6e3"/><rect x="0" y="0" width="0" height="50" fill="#000"><animate attributeName="width" from="0" to="1000" begin="0s" dur="2s" fill="freeze"/></rect></svg>`;

ok(JSON.stringify(mod.FPS_CHOICES) === "[30,60]", "the choices are 30 and 60");
ok(mod.safeFps(60) === 60 && mod.safeFps(30) === 30 && mod.safeFps(24) === 30 && mod.safeFps(undefined) === 30 && mod.safeFps("60") === 30, "any other value falls back to 30");
const p30 = mod.exportPlan(10, 30), p60 = mod.exportPlan(10, 60);
ok(p30.total === 300 && p60.total === 600, "10 s = 300 frames at 30 fps, 600 frames at 60 fps");
ok(p60.bitrate === 2 * p30.bitrate, "bitrate scales with the frame rate");
ok(p60.stamp(60) === 1e6 && p30.stamp(30) === 1e6, "frame 60 (at 60 fps) and frame 30 (at 30 fps) are both at exactly 1 s");
ok(mod.exportPlan(0, 60).total === 1, "an empty length still gives one frame");

for (const fps of [30, 60]) {
  Object.assign(calls, { configs: [], encoded: [], flushed: 0, drawn: 0 }); globalThis.__chunks = 0; globalThis.__final = false;
  let last = [0, 0];
  const blob = await mod.renderMp4({ svg, width: 1920, height: 1080, seconds: 2, fps, onProgress: (d, t) => { last = [d, t]; } });
  const c = calls.configs[0], e = calls.encoded;
  ok(c.width === 1920 && c.height === 1080 && c.framerate === fps, `${fps} fps: encoder set to 1920x1080 at ${fps} fps`);
  ok(e.length === 2 * fps, `${fps} fps: 2 s gives ${2 * fps} frames (got ${e.length})`);
  ok(e[0].ts === 0 && e[e.length - 1].ts === Math.round(((2 * fps - 1) * 1e6) / fps), `${fps} fps: first frame at 0, last frame just before 2 s`);
  ok(e.every((f, i) => i === 0 || f.ts > e[i - 1].ts), `${fps} fps: timestamps strictly increase`);
  ok(e.filter(f => f.key).length === 1 && e[0].key, `${fps} fps: one key frame at the start (next one is 2 s later)`);
  ok(calls.drawn === 2 * fps, `${fps} fps: every frame of the growing bar was drawn once (${calls.drawn})`);
  ok(globalThis.__mux.video.frameRate === fps && globalThis.__chunks === 2 * fps && globalThis.__final, `${fps} fps: muxer told ${fps} fps, got every frame, finished`);
  ok(last[0] === last[1] && last[1] === 2 * fps && blob.type === "video/mp4", `${fps} fps: progress reaches ${2 * fps} of ${2 * fps}; result is video/mp4`);
}
// default (no fps given) stays 30
Object.assign(calls, { configs: [], encoded: [] });
await mod.renderMp4({ svg, width: 1920, height: 1080, seconds: 1 });
ok(calls.configs[0].framerate === 30 && calls.encoded.length === 30, "no fps given = 30 fps (as before)");
// cancel stops the export
let n = 0;
let msg = ""; try { await mod.renderMp4({ svg, width: 1920, height: 1080, seconds: 5, fps: 60, cancelled: () => ++n > 20 }); } catch (e) { msg = e.message; }
ok(/cancel/i.test(msg), "cancel stops a 60 fps export with a clear message");
// ---- step 24: 1080p / 2K / 4K
ok(JSON.stringify(mod.RESOLUTIONS.map(r => [r.id, r.w, r.h])) === '[["1080p",1920,1080],["2k",2560,1440],["4k",3840,2160]]', "step 24: the sizes are 1080p, 2K (2560x1440) and 4K (3840x2160)");
ok(mod.safeRes("4k").w === 3840 && mod.safeRes("2k").h === 1440 && mod.safeRes("8k").id === "1080p" && mod.safeRes(undefined).id === "1080p", "step 24: any unknown size falls back to 1080p");
ok(mod.exportPlan(10, 30).width === 1920 && mod.exportPlan(10, 30).bitrate === Math.round(1920 * 1080 * 30 * 0.15), "step 24: no size given = 1080p with the old bitrate");
const q4 = mod.exportPlan(10, 30, "4k"), q2 = mod.exportPlan(10, 60, "2k");
ok(q4.width === 3840 && q4.height === 2160 && q4.bitrate === Math.round(3840 * 2160 * 30 * 0.15) && q4.total === 300, "step 24: 4K plan (size, bitrate, frames)");
ok(q2.width === 2560 && q2.total === 600 && q2.bitrate === Math.round(2560 * 1440 * 60 * 0.15), "step 24: 2K at 60 fps plan");
ok(mod.estimateBytes(10, 30, "4k") === Math.round(q4.bitrate * 10 / 8) && mod.estimateBytes(10, 30, "4k") > mod.estimateBytes(10, 30, "2k") && mod.estimateBytes(10, 30, "2k") > mod.estimateBytes(10, 30, "1080p"), "step 24: bigger size = bigger estimated file");
const lv = c => parseInt(c.slice(-2), 16);
ok(mod.codecsFor(1920, 1080, 30).some(c => lv(c) === 0x28) && mod.codecsFor(1920, 1080, 30).length > 0, "step 24: 1080p at 30 fps may use level 4.0");
ok(mod.codecsFor(1920, 1080, 60).every(c => lv(c) >= 0x2a), "step 24: 1080p at 60 fps needs level 4.2 or higher");
ok(mod.codecsFor(2560, 1440, 30).every(c => lv(c) >= 0x32) && mod.codecsFor(2560, 1440, 30).length > 0, "step 24: 2K at 30 fps needs level 5.0 or higher");
ok(mod.codecsFor(2560, 1440, 60).every(c => lv(c) >= 0x33) && mod.codecsFor(3840, 2160, 30).every(c => lv(c) >= 0x33) && mod.codecsFor(3840, 2160, 30).length > 0, "step 24: 2K at 60 fps and 4K at 30 fps need level 5.1 or higher");
ok(mod.fpsFor("4k", 60) === 30 && mod.fpsFor("4k", 30) === 30 && mod.fpsFor("2k", 60) === 60 && mod.fpsFor("1080p", 60) === 60, "step 24: 4K is 30 fps only; 1080p and 2K keep 30 or 60");
ok(mod.exportPlan(10, 60, "4k").fps === 30 && mod.exportPlan(10, 60, "4k").total === 300, "step 24: asking 4K at 60 fps gives 30 fps");
Object.assign(calls, { configs: [], encoded: [] });
await mod.renderMp4({ svg, width: 1920, height: 1080, seconds: 1, fps: 60, resolution: "4k" });
ok(calls.configs[0].framerate === 30 && calls.encoded.length === 30, "step 24: a 4K export asked for 60 fps is made at 30 fps");
for (const [id, W, H] of [["2k", 2560, 1440], ["4k", 3840, 2160], ["1080p", 1920, 1080]]) {
  Object.assign(calls, { configs: [], encoded: [] }); globalThis.__chunks = 0;
  const made = []; const keep = globalThis.document.createElement;
  globalThis.document.createElement = () => { const c = { width: 0, height: 0, getContext: () => ctx }; made.push(c); return c; };
  await mod.renderMp4({ svg, width: 1920, height: 1080, seconds: 1, fps: 30, resolution: id });
  globalThis.document.createElement = keep;
  ok(calls.configs[0].width === W && calls.configs[0].height === H && calls.configs[0].framerate === 30, `step 24: ${id}: encoder set to ${W}x${H}`);
  ok(globalThis.__mux.video.width === W && globalThis.__mux.video.height === H, `step 24: ${id}: muxer told ${W}x${H}`);
  ok(made.some(c => c.width === W && c.height === H) && calls.encoded.length === 30, `step 24: ${id}: frames are drawn on a ${W}x${H} canvas, 30 frames for 1 s`);
}
{
  const keepSupport = globalThis.VideoEncoder.isConfigSupported;
  globalThis.VideoEncoder.isConfigSupported = async c => ({ supported: c.width < 3000 });   // a phone that cannot do 4K
  const sup = await mod.supportedResolutions(30);
  ok(sup["1080p"] === true && sup["2k"] === true && sup["4k"] === false, "step 24: the phone check says yes to 1080p and 2K, no to 4K");
  let m4 = ""; try { await mod.renderMp4({ svg, width: 1920, height: 1080, seconds: 1, resolution: "4k" }); } catch (e) { m4 = e.message; }
  ok(/4K/.test(m4) && /smaller size/.test(m4), "step 24: an unsupported size gives a clear message (" + m4 + ")");
  globalThis.VideoEncoder.isConfigSupported = keepSupport;
}
// ---- new workflow step 7: a scene clip in the timeline
{
  Object.assign(calls, { configs: [], encoded: [], flushed: 0, drawn: 0 }); globalThis.__chunks = 0;
  const log = { opened: 0, seeks: [], draws: 0, closed: 0 };
  const opener = async () => { log.opened++; return { duration: 1.0, width: 1280, height: 720, seek: async t => { log.seeks.push(+t.toFixed(3)); }, draw: () => { log.draws++; }, close: () => { log.closed++; } }; };
  const blob = await mod.renderMp4Timeline({ slides: [{ svg, width: 1920, height: 1080, start: 0, end: 1 }, { svg: "", width: 1920, height: 1080, start: 1, end: 3, video: new Blob(["x"]) }], fps: 30, openScene: opener });
  ok(calls.encoded.length === 90 && blob.type === "video/mp4", "step 7: a 1 s picture + a 2 s scene = 90 frames");
  ok(log.opened === 1 && log.closed === 1, "step 7: the clip is opened once and closed at the end");
  ok(log.draws === 60 && log.seeks.length === 60, "step 7: every scene frame is a seek plus a draw (60)");
  ok(log.seeks[0] === 0 && log.seeks[1] === 0.033, "step 7: the first scene frame is at 0 s of the clip, the next 1/30 s later");
  ok(Math.max(...log.seeks) <= 0.96 + 1e-9 && log.seeks[log.seeks.length - 1] === 0.96, "step 7: a slot longer than the clip holds the last frame (never seeks past the end)");
  let n2 = 0, closed2 = 0, m2 = ""; try { await mod.renderMp4Timeline({ slides: [{ svg: "", width: 1920, height: 1080, start: 0, end: 5, video: new Blob(["x"]) }], fps: 30, openScene: async () => ({ duration: 5, width: 1920, height: 1080, seek: async () => {}, draw() {}, close: () => closed2++ }), cancelled: () => ++n2 > 10 }); } catch (e) { m2 = e.message; }
  ok(/cancel/i.test(m2) && closed2 === 1, "step 7: cancel during a scene closes the clip");
}
console.log(fails ? `${fails} FAILED` : "all passed"); process.exit(fails ? 1 : 0);
