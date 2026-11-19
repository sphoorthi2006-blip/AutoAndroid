// Run: node tests/clips.test.mjs   (new workflow step 4: scene clip names)
import { build } from "esbuild";
const stub = { name: "stub", setup(b) {
  b.onResolve({ filter: /\?url$|^opentype\.js$/ }, a => ({ path: a.path, namespace: "stub" }));
  b.onLoad({ filter: /.*/, namespace: "stub" }, () => ({ contents: "export default {}", loader: "js" })); } };
const r = await build({ entryPoints: ["src/slides.ts"], bundle: true, format: "esm", platform: "node", write: false, plugins: [stub], logLevel: "silent" });
const mod = await import("data:text/javascript;base64," + Buffer.from(r.outputFiles[0].text).toString("base64"));
let fails = 0; const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };
const R = n => JSON.stringify(mod.clipRange(n));
ok(R("53.mp4") === '{"first":53,"last":53}', "53.mp4 covers cue 53");
ok(R("n53.mp4") === '{"first":53,"last":53}' && R("scene_53.mov") === '{"first":53,"last":53}', "n53.mp4 and scene_53.mov cover cue 53");
ok(R("12-14.mp4") === '{"first":12,"last":14}' && R("12 \u2013 14.mp4") === '{"first":12,"last":14}', "12-14.mp4 and 12 - 14.mp4 cover cues 12 to 14");
ok(R("take2_53.mp4") === '{"first":53,"last":53}', "several numbers: the LAST one wins");
ok(R("007.mp4") === '{"first":7,"last":7}', "leading zeros are ignored");
ok(R("intro.mp4") === "null", "no number: no guess");
ok(R("14-12.mp4") === "null", "a backwards range is refused");
ok(R("folder/sub/9.webm") === '{"first":9,"last":9}', "a path is reduced to the file name");
ok(mod.isVideoName("a.MP4") && mod.isVideoName("b.webm") && !mod.isVideoName("c.png") && !mod.isVideoName("d.srt"), "video names are told from pictures and subtitles");
const C = (name, first, last) => ({ name, first, last });
ok(mod.clipNotes([C("5.mp4", 5, 5), C("9-10.mp4", 9, 10)], [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]).length === 0, "clean clips: no notes");
ok(mod.clipNotes([C("5-7.mp4", 5, 7), C("7.mp4", 7, 7)]).some(n => /cover the same cue/.test(n)), "two clips on the same cue are reported");
ok(mod.clipNotes([C("9-11.mp4", 9, 11)], [1, 2, 9, 10]).some(n => /no image 11/.test(n)), "a clip beyond the timing file is reported");
console.log(fails ? `\n${fails} FAILED` : "\nall passed"); process.exit(fails ? 1 : 0);
