// Run: node tests/text.test.mjs   (step 19: text position. Bundles src/textanim.ts with esbuild, loads the real bundled fonts)
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import opentype from "opentype.js";
const stub = { name: "stub", setup(b) {
  b.onResolve({ filter: /\?url$/ }, a => ({ path: a.path, namespace: "stub" }));
  b.onLoad({ filter: /.*/, namespace: "stub" }, () => ({ contents: "export default ''", loader: "js" })); } };
const r = await build({ entryPoints: ["src/textanim.ts"], bundle: true, format: "esm", platform: "node", write: false, plugins: [stub], logLevel: "silent" });
const mod = await import("data:text/javascript;base64," + Buffer.from(r.outputFiles[0].text).toString("base64"));
const load = f => { const b = readFileSync(f); return opentype.parse(b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength)); };
const main = load("src/fonts/PatrickHand-Regular.ttf");
const math = load("node_modules/@fontsource/noto-sans-math/files/noto-sans-math-latin-400-normal.woff");
let fails = 0; const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };
const cv = { w: 1920, h: 1080 };
const base = { ...mod.DEFAULT_TEXT, text: "Peace be still", size: 40, align: "center" };
const ink = laid => { let l = 1e9, rt = -1e9, t = 1e9, b = -1e9;
  for (const g of laid.glyphs) { const bb = g.font.charToGlyph(g.ch).getPath(g.x, g.y, g.sz).getBoundingBox(); l = Math.min(l, bb.x1); rt = Math.max(rt, bb.x2); t = Math.min(t, bb.y1); b = Math.max(b, bb.y2); }
  return { l, r: rt, t, b, cx: (l + rt) / 2, cy: (t + b) / 2 }; };
const A = ink(mod.layoutText(main, math, base, cv));
ok(Math.abs(A.cy - cv.h / 2) < 1, "automatic: text is centred up/down (as before)");
ok(Math.abs(A.cx - cv.w / 2) < cv.w * 0.03, "automatic: centred line is near the middle across");
const same = ink(mod.layoutText(main, math, { ...base, posX: undefined, posY: undefined }, cv));
ok(same.l === A.l && same.t === A.t, "no position chosen = identical to the old layout");
const mx = cv.w * 0.08, my = cv.h * 0.1;
const TL = ink(mod.layoutText(main, math, { ...base, posX: 0, posY: 0 }, cv));
ok(Math.abs(TL.l - mx) < 1.5 && Math.abs(TL.t - my) < 1.5, "0% / 0%: ink touches the top-left of the safe area");
const BR = ink(mod.layoutText(main, math, { ...base, posX: 100, posY: 100 }, cv));
ok(Math.abs(BR.r - (cv.w - mx)) < 1.5 && Math.abs(BR.b - (cv.h - my)) < 1.5, "100% / 100%: ink touches the bottom-right of the safe area");
const M = ink(mod.layoutText(main, math, { ...base, posX: 50, posY: 50 }, cv));
ok(Math.abs(M.cx - cv.w / 2) < 1.5 && Math.abs(M.cy - cv.h / 2) < 1.5, "50% / 50%: exactly in the middle");
const L = ink(mod.layoutText(main, math, { ...base, align: "left", posX: 50, posY: 50 }, cv));
ok(Math.abs(L.cx - cv.w / 2) < 1.5, "left-aligned lines can also be moved to the middle");
const Y = ink(mod.layoutText(main, math, { ...base, posY: 25 }, cv));
ok(Math.abs(Y.l - A.l) < 0.01 && Y.t < A.t, "only posY set: moves up/down, not sideways");
const clamp = ink(mod.layoutText(main, math, { ...base, posX: -50, posY: 999 }, cv));
ok(clamp.l >= mx - 1.5 && clamp.b <= cv.h - my + 1.5, "out-of-range positions are kept inside the safe area");
const full = ink(mod.layoutText(main, math, { ...base, size: 100, posX: 0, posY: 0 }, cv));
const full2 = ink(mod.layoutText(main, math, { ...base, size: 100, posX: 100, posY: 100 }, cv));
ok(full2.r - full.r < cv.w * 0.2, "size 100%: little room left to move (hint in the screen says so)");
const wide = mod.layoutText(main, math, base, cv), moved = mod.layoutText(main, math, { ...base, posX: 10, posY: 80 }, cv);
ok(wide.glyphs.length === moved.glyphs.length && wide.size === moved.size, "moving does not change the letters or the size");
const box = mod.layoutInBox(main, math, "Heading", { x: 100, y: 100, w: 600, h: 120 }, cv);
ok(box.glyphs.length === 7, "Video-tab box layout still works");
// ---- step 24: uploaded fonts
{
  const buf = f => { const b = readFileSync(f); return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength); };
  ok(mod.allFonts().length === 1 && mod.allFonts()[0].id === "patrick", "step 24: at first only the bundled font is listed");
  const c = mod.addCustomFont("My Hand.ttf", buf("src/fonts/PatrickHand-Regular.ttf"));
  ok(c.id === "custom-my-hand" && c.custom === true && /\(uploaded\)$/.test(c.label), "step 24: an uploaded .ttf is listed as an uploaded font (" + c.label + ")");
  ok(mod.allFonts().length === 2 && mod.allFonts()[1].id === c.id && mod.findFont(c.id).custom === true, "step 24: it is added after the bundled font and can be found by id");
  const f = await mod.loadFont(c.url);
  ok(f.charToGlyphIndex("A") > 0 && f.glyphs.length > 50, "step 24: loadFont returns the uploaded font without any network");
  mod.addCustomFont("My Hand.ttf", buf("src/fonts/PatrickHand-Regular.ttf"));
  ok(mod.allFonts().length === 2, "step 24: the same file name again replaces, not duplicates");
  const w = mod.addCustomFont("Math.woff", buf("node_modules/@fontsource/noto-sans-math/files/noto-sans-math-latin-400-normal.woff"));
  ok(w.id === "custom-math" && mod.allFonts().length === 3, "step 24: a .woff file works too");
  let msg = ""; try { mod.addCustomFont("notes.ttf", new TextEncoder().encode("this is not a font").buffer); } catch (e) { msg = e.message; }
  ok(/not a font/.test(msg) && mod.allFonts().length === 3, "step 24: a file that is not a font is refused with a plain message and not listed");
  ok(mod.findFont("custom-gone").id === "patrick", "step 24: an unknown font id falls back to the bundled font");
  const laid = mod.layoutText(f, math, { ...mod.DEFAULT_TEXT, text: "Hello", size: 50 }, cv);
  ok(laid.glyphs.length === 5, "step 24: text can be laid out with the uploaded font");
  mod.removeCustomFont(c.id); mod.removeCustomFont(w.id);
  ok(mod.allFonts().length === 1 && mod.findFont(c.id).id === "patrick", "step 24: removing the uploaded fonts leaves only the bundled one");
}
const P = mod.layoutText(main, math, { ...base, px: 90 }, cv);
ok(Math.abs(P.size - 90) < 0.01 && P.fit > 90, "exact px size: used as given, and the biggest fitting size is reported");
const P2 = mod.layoutText(main, math, { ...base, px: 99999 }, cv);
ok(Math.abs(P2.size - P2.fit) < 0.01, "exact px size is limited to what fits");
const IA = mod.layoutText(main, math, { ...base, posX: 20, posY: 70 }, cv);
ok(IA.ink && IA.area && Math.abs(IA.ink.x - (IA.area.x + (IA.area.w - IA.ink.w) * 0.2)) < 1.5 && Math.abs(IA.ink.y - (IA.area.y + (IA.area.h - IA.ink.h) * 0.7)) < 1.5, "reported ink box matches the chosen position (used by drag on the picture)");
console.log(fails ? `${fails} FAILED` : "all passed"); process.exit(fails ? 1 : 0);
