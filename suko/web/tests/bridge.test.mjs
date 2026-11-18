// Run: node tests/bridge.test.mjs   (bundles src/bridge.ts with esbuild and drives it with a fake Suko host)
import { build } from "esbuild";
const r = await build({ entryPoints: ["src/bridge.ts"], bundle: true, format: "esm", platform: "node", write: false, logLevel: "silent" });
let fails = 0; const ok = (c, m) => { console.log((c ? "PASS " : "FAIL ") + m); if (!c) fails++; };

const results = new Map();            // id -> json text served at /bridge/<id>
const saved = [];                     // what the fake Android received
const calls = [];
const files = [{ name: "a.png", mime: "image/png", bytes: new Uint8Array([1, 2, 3]) }, { name: "t.json", mime: "application/json", bytes: new TextEncoder().encode("{}") }];
globalThis.fetch = async (url) => {
  const u = String(url);
  if (u.startsWith("/bridge/")) { const t = results.get(u.slice(8)); return t === undefined ? new Response("", { status: 404 }) : new Response(t); }
  if (u.startsWith("/input/")) { const f = files[Number(u.slice(7))]; return f ? new Response(f.bytes) : new Response("", { status: 404 }); }
  return new Response("", { status: 404 });
};
globalThis.SukoBridge = {
  call(id, method, payload) {
    calls.push([method, payload.length]);
    setTimeout(() => {
      if (method === "boom") return globalThis.__sukoDone(id, false, "it broke");
      results.set(id, JSON.stringify({ method, chars: payload.length }));
      globalThis.__sukoDone(id, true, "");
    }, 1);
  },
  saveBegin(id, name, mime) { saved.push({ id, name, mime, chunks: [], ended: false }); },
  saveChunk(id, b64) { saved.find(s => s.id === id).chunks.push(Buffer.from(b64, "base64")); },
  saveEnd(id) { const s = saved.find(x => x.id === id); s.ended = true; setTimeout(() => globalThis.__sukoDone(id, true, "Download/Bezalel/" + s.name), 1); },
  inputList() { return JSON.stringify(files.map(f => ({ name: f.name, mime: f.mime, size: f.bytes.length }))); },
  info() { return JSON.stringify({ host: "suko", blocked: 2 }); },
};
const mod = await import("data:text/javascript;base64," + Buffer.from(r.outputFiles[0].text).toString("base64"));

ok(mod.hasHost() === true, "host is detected");
const a = await mod.nativeCall("health");
ok(a.method === "health" && a.chars === 0, "nativeCall returns the JSON result");
const big = await mod.nativeCall("size", "x".repeat(3_000_000));
ok(big.chars === 3_000_000, "a 3 MB payload is passed whole");
let msg = ""; try { await mod.nativeCall("boom"); } catch (e) { msg = e.message; }
ok(msg === "it broke", "a native error rejects with its message");
const parallel = await Promise.all([mod.nativeCall("p1"), mod.nativeCall("p2"), mod.nativeCall("p3")]);
ok(parallel.map(p => p.method).join() === "p1,p2,p3", "parallel calls do not mix their answers");

const data = new Uint8Array(2500).map((_, i) => i % 251);
const where = await mod.saveFile("out.mp4", new Blob([data]), "video/mp4", 1000);
const s = saved[0];
ok(where === "Download/Bezalel/out.mp4" && s.ended && s.mime === "video/mp4", "saveFile finishes and returns where it was saved");
ok(s.chunks.length === 3 && Buffer.concat(s.chunks).equals(Buffer.from(data)), "the file arrives in 3 chunks, byte for byte");
ok((await mod.toBase64(new Blob([new Uint8Array([0, 255, 128])]))) === Buffer.from([0, 255, 128]).toString("base64"), "base64 matches");

const inp = await mod.inputFiles();
ok(inp.length === 2 && inp[0].name === "a.png" && inp[0].type === "image/png" && inp[0].size === 3, "inputFiles gives named, typed files");
ok(mod.hostInfo().blocked === 2, "hostInfo is read");

delete globalThis.SukoBridge;
ok(mod.hasHost() === false && (await mod.inputFiles()).length === 0, "without a host: hasHost is false and there are no input files");
let m2 = ""; try { await mod.nativeCall("health"); } catch (e) { m2 = e.message; }
ok(m2.includes("not running inside the Bezalel app"), "without a host nativeCall explains itself");
process.exit(fails ? 1 : 0);
