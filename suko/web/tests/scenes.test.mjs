// Run: node tests/scenes.test.mjs   (new workflow steps 5 and 9: scene rows and report kinds)
import { build } from "esbuild";
const stub = { name: "stub", setup(b) {
  b.onResolve({ filter: /\?url$|^opentype\.js$/ }, a => ({ path: a.path, namespace: "stub" }));
  b.onLoad({ filter: /.*/, namespace: "stub" }, () => ({ contents: "export default {}", loader: "js" })); } };
const r = await build({ entryPoints: ["src/slides.ts"], bundle: true, format: "esm", platform: "node", write: false, plugins: [stub], logLevel: "silent" });
const mod = await import("data:text/javascript;base64," + Buffer.from(r.outputFiles[0].text).toString("base64"));
let fails = 0; const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };
const C = (name, first, last) => ({ name, first, last, file: null });
const J = x => JSON.stringify(x);
// step 5
let sp = mod.sceneSpans([C("12-14.mp4", 12, 14), C("5.mp4", 5, 5)]);
ok(sp.length === 2 && sp[0].clip.name === "5.mp4" && J(sp[1].cues) === "[12,13,14]", "clips are sorted and take their cues");
sp = mod.sceneSpans([C("5-7.mp4", 5, 7), C("7-8.mp4", 7, 8)]);
ok(J(sp[0].cues) === "[5,6,7]" && J(sp[1].cues) === "[8]", "an overlapping cue belongs to the first clip only");
sp = mod.sceneSpans([C("5-7.mp4", 5, 7), C("6.mp4", 6, 6)]);
ok(sp.length === 1, "a clip with no cue left is dropped");
ok(mod.sceneSpans([]).length === 0, "no clips, no scenes");
const specs = [{ n: 11, start: 40, end: 50 }, { n: 12, start: 50, end: 58 }, { n: 13, start: 58, end: 66 }, { n: 14, start: 66, end: 70 }];
let t = mod.sceneTimes([12, 13, 14], specs, 0, 10);
ok(t.start === 50 && t.end === 70 && t.fromFile, "scene times come from the JSON: first cue start to last cue end");
t = mod.sceneTimes([13], specs, 0, 10);
ok(t.start === 58 && t.end === 66, "a one-cue scene uses that cue's slot");
t = mod.sceneTimes([20, 21], specs, 66, 10);
ok(t.start === 66 && t.end === 86 && !t.fromFile, "no timing entry: follows the previous row, default seconds per cue");
t = mod.sceneTimes([14, 15], specs, 0, 10);
ok(t.start === 66 && t.end === 70, "only some cues in the file: the known ones give the times");
t = mod.sceneTimes([5], null, 12, 10);
ok(t.start === 12 && t.end === 22 && !t.fromFile, "no timing file at all");
// step 9
const rows = [
  { n: 1, start: 0, end: 5, status: "ok", kind: "heading", label: "Baking soda" },
  { n: 2, start: 5, end: 9, status: "error", kind: "subscribe", label: "Subscribe" },
  { n: 3, start: 9, end: 20, status: "ok", kind: "scene", last: 5, label: "3-5.mp4" },
];
const txt = mod.reportText(rows), csv = mod.reportCsv(rows);
ok(/Image 1 \(heading\): OK/.test(txt) && /Image 2 \(subscribe\): OK/.test(txt) && /Image 3-5 \(scene\): OK/.test(txt), "the summary names the kind of every row");
ok(/IMAGE 3-5 \(scene\)/.test(txt) && /IMAGE 1 \(heading\)/.test(txt), "each row's heading line shows its kind and cue range");
ok(!/not analysed/.test(txt), "a heading whose picture failed is not reported as 'not analysed'");
ok(csv.split("\r\n")[0] === "image,image_start,image_end,image_kind,step,kind,begin,end,script_time,diff,text,flag", "CSV has an image_kind column");
ok(/^3-5,9\.00,20\.00,scene,V,scene,9\.00,20\.00,,,"3-5\.mp4",""$/m.test(csv) && /^1,0\.00,5\.00,heading,H,heading,/m.test(csv), "CSV rows carry the kind and the clip name or words");
const pic = [{ n: 4, start: 0, end: 5, status: "wait" }];
ok(/not analysed/.test(mod.reportText(pic)) && /Image 4: CHECK/.test(mod.reportText(pic)), "a picture row is reported as before");
console.log(fails ? `\n${fails} FAILED` : "\nall passed"); process.exit(fails ? 1 : 0);
