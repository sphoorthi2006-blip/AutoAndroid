// Run: node tests/textslide.test.mjs   (new workflow steps 1-2: heading / subscribe detection and the white handwritten text slide)
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import opentype from "opentype.js";
const stub = { name: "stub", setup(b) {
  b.onResolve({ filter: /\?url$/ }, a => ({ path: a.path, namespace: "stub" }));
  b.onLoad({ filter: /.*/, namespace: "stub" }, () => ({ contents: "export default ''", loader: "js" })); } };
const r = await build({ stdin: { contents: 'export * from "./src/textslide.ts"; export { setRasterizer } from "./src/textanim.ts";', resolveDir: process.cwd(), loader: "ts" },
  bundle: true, format: "esm", platform: "node", write: false, plugins: [stub], logLevel: "silent" });
const mod = await import("data:text/javascript;base64," + Buffer.from(r.outputFiles[0].text).toString("base64"));
const load = f => { const b = readFileSync(f); return opentype.parse(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength)); };
const fonts = { main: load("src/fonts/PatrickHand-Regular.ttf"), math: load("node_modules/@fontsource/noto-sans-math/files/noto-sans-math-latin-400-normal.woff") };
let fails = 0; const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };

// a tiny even-odd scanline rasterizer so the handwriting engine runs without a browser canvas
mod.setRasterizer((d, b, scale, pad) => {
  const w = Math.ceil((b.x2 - b.x1) * scale) + 2 * pad, h = Math.ceil((b.y2 - b.y1) * scale) + 2 * pad;
  const polys = []; let cur = [], x = 0, y = 0;
  const tok = d.match(/[MLQCZ]|-?\d*\.?\d+(?:e-?\d+)?/gi) ?? []; let i = 0; const num = () => parseFloat(tok[i++]);
  const px = (X, Y) => [(X - b.x1) * scale + pad, (Y - b.y1) * scale + pad];
  while (i < tok.length) { const c = tok[i++];
    if (c === "M") { if (cur.length) polys.push(cur); x = num(); y = num(); cur = [px(x, y)]; }
    else if (c === "L") { x = num(); y = num(); cur.push(px(x, y)); }
    else if (c === "Q") { const x1 = num(), y1 = num(), x2 = num(), y2 = num(); for (let k = 1; k <= 8; k++) { const t = k / 8, u = 1 - t; cur.push(px(u * u * x + 2 * u * t * x1 + t * t * x2, u * u * y + 2 * u * t * y1 + t * t * y2)); } x = x2; y = y2; }
    else if (c === "C") { const x1 = num(), y1 = num(), x2 = num(), y2 = num(), x3 = num(), y3 = num(); for (let k = 1; k <= 8; k++) { const t = k / 8, u = 1 - t; cur.push(px(u ** 3 * x + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t ** 3 * x3, u ** 3 * y + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t ** 3 * y3)); } x = x3; y = y3; }
  }
  if (cur.length) polys.push(cur);
  const bits = new Uint8Array(w * h);
  for (let row = 0; row < h; row++) { const yy = row + 0.5, xs = [];
    for (const p of polys) for (let k = 0; k < p.length; k++) { const a = p[k], c2 = p[(k + 1) % p.length]; if ((a[1] <= yy) !== (c2[1] <= yy)) xs.push(a[0] + ((yy - a[1]) / (c2[1] - a[1])) * (c2[0] - a[0])); }
    xs.sort((m, n) => m - n); for (let k = 0; k + 1 < xs.length; k += 2) for (let col = Math.ceil(xs[k] - 0.5); col < xs[k + 1] - 0.5; col++) if (col >= 0 && col < w) bits[row * w + col] = 1; }
  return { bits, w, h };
});

const P = (label, verse, extra = {}) => ({ shape: "parchment", label, verse, start: 0, ...extra });
const S = (o) => ({ n: 1, start: 0, end: 8, elements: [], ...o });
// ---- step 1: detection
const h1 = mod.classifyTextSlide(S({ panels: [P("", "1. Ordered Words")] }));
ok(h1?.kind === "heading" && h1.words === "1. Ordered Words", "svg-bh heading canvas (one parchment panel, empty label, verse) is a heading");
const sub = mod.classifyTextSlide(S({ panels: [P("", "LIKE AND SUBSCRIBE")] }));
ok(sub?.kind === "subscribe" && sub.words === "LIKE AND SUBSCRIBE", "like-and-subscribe canvas is a subscribe slide");
ok(mod.classifyTextSlide(S({ text: "Please like and subscribe for more.", panels: [{ shape: "rectangle", label: "", start: 0 }] }))?.kind === "subscribe", "a one-panel canvas whose text says subscribe is a subscribe slide");
ok(mod.classifyTextSlide(S({ text: "If you enjoy this, subscribe.", panels: [{ label: "A", start: 0 }, { label: "B", start: 2 }, { label: "C", start: 4 }] })) === null, "a normal 3-panel canvas that only mentions subscribe stays a picture");
ok(mod.classifyTextSlide(S({ panels: [P("PSALM 33:6", "By the word of the Lord the heavens were made")] })) === null, "a verse canvas WITH a label stays a picture");
ok(mod.classifyTextSlide(S({ system: "comic", panels: [{ shape: "rectangle", label: "THE CROWD", start: 0 }] })) === null, "a scene canvas stays a picture");
ok(mod.classifyTextSlide(S({ text: "3. Living Blessing" }))?.words === "3. Living Blessing", "a short numbered line with no panels is a heading");
ok(mod.classifyTextSlide(S({ heading: "2. Separated Spaces", panels: [P("", "2. Separated Spaces")] }))?.words === "2. Separated Spaces", "heading words come from the verse");
ok(mod.classifyTextSlide(undefined) === null, "no timing entry: nothing is guessed");
ok(mod.classifyTextSlide(S({ system: "heading", heading: "Intro" }))?.words === "Intro", "a canvas the file marks as heading is a heading");

ok(mod.classifyTextSlide(S({ panels: [P("", "By the word of the Lord the heavens were made, and all their host by the breath of his mouth.")] })) === null, "a long verse with no label is NOT a heading (verse panels keep their own layout)");
ok(mod.classifyTextSlide(S({ panels: [P("", "4. Image Bearers")], elements: [] }))?.kind === "heading", "heading works whether or not a picture exists (it only reads the timing file)");
// ---- step 2: the slide itself
const t = mod.buildTextSlide("1. Ordered Words", 8, fonts);
ok(t.width === 1920 && t.height === 1080, "the slide is 1920 x 1080");
ok(/<rect width="1920" height="1080" fill="#ffffff"\/>/.test(t.svg), "white from edge to edge");
ok(!/animateTransform/.test(t.svg) && !/scale\(1\.\d/.test(t.svg), "NO zoom: nothing in the slide scales or moves");
ok(!/<animate attributeName="opacity"/.test(t.svg), "no fade: the only animation is the pen");
ok(/stroke-dashoffset/.test(t.svg) && /<mask /.test(t.svg), "the words are written by a pen that follows the letter strokes (same engine as the Text Editor)");
ok(/fill="#000000"/.test(t.svg) && !/#fdf6e3|#fff4b3/i.test(t.svg), "text is black, no parchment or label colour");
ok(!/<image|<rect(?![^>]*width="1920")/.test(t.svg), "no frame, box or picture: only the white sheet and the letters");
const begins = [...t.svg.matchAll(/begin="([\d.]+)s"/g)].map(m => +m[1]), ends = [...t.svg.matchAll(/begin="([\d.]+)s" dur="([\d.]+)s"/g)].map(m => +m[1] + +m[2]);
ok(Math.max(...ends) <= 8 - 0.45, "the pen is finished before the slide ends (last at " + Math.max(...ends).toFixed(2) + " s of 8 s)");
ok(Math.min(...begins) >= 0.1, "the pen starts a moment after the cut");
ok(mod.writeSeconds("A", 8) >= 0.8 && mod.writeSeconds("x".repeat(200), 30) <= 4 && mod.writeSeconds("1. Ordered Words", 1.2) <= 0.7, "writing time: at least 0.8 s, at most 4 s, never longer than the slide allows");
const sb = mod.buildTextSlide("LIKE AND SUBSCRIBE", 5, fonts);
ok(/<mask /.test(sb.svg) && sb.writeSeconds <= 4.5, "the subscribe slide is written the same way");
ok(mod.buildTextSlide("Hi", 4, fonts, { width: 3840, height: 2160 }).width === 3840, "other sizes work");
// ---- step 3: overrides
const sp = S({ panels: [P("", "1. Ordered Words")] }), pic = S({ panels: [{ shape: "rectangle", label: "THE CROWD", start: 0 }], heading: "Chapter 2" });
ok(mod.resolveTextSlide(sp, { kind: "picture" }) === null, "step 3: Picture keeps the picture even when the file says heading");
ok(mod.resolveTextSlide(sp, { kind: "auto", words: "  My   own words " })?.words === "My own words", "step 3: Auto with typed words uses the typed words, tidied");
ok(mod.resolveTextSlide(sp, { kind: "subscribe" })?.words === "LIKE AND SUBSCRIBE", "step 3: forcing Subscribe on a heading gives the subscribe words");
ok(mod.resolveTextSlide(pic, { kind: "heading" })?.words === "Chapter 2", "step 3: forcing Heading on a scene uses the file's heading");
ok(mod.resolveTextSlide(undefined, { kind: "heading" }) === null, "step 3: Heading with no words anywhere stays a picture (nothing to write)");
ok(mod.resolveTextSlide(undefined, { kind: "heading", words: "Intro" })?.words === "Intro", "step 3: Heading with typed words works with no timing entry at all");
ok(mod.resolveTextSlide(undefined, { kind: "subscribe" })?.kind === "subscribe", "step 3: Subscribe works with no timing entry at all");
ok(mod.resolveTextSlide(pic) === null && mod.resolveTextSlide(sp)?.kind === "heading", "step 3: no override = the automatic decision");
console.log(fails ? `\n${fails} FAILED` : "\nall passed"); process.exit(fails ? 1 : 0);
