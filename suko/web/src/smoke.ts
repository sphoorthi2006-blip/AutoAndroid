// Step 18 smoke test: runs inside the Bezalel app and checks every part of the web host. It uses the same bridge.ts as the Studio.
import { hasHost, hostInfo, inputFiles, nativeCall, saveFile } from "./bridge";

type Level = "pass" | "fail" | "info";
const out = document.getElementById("out")!;
const sum = document.getElementById("sum")!;
let pass = 0, fail = 0;

function row(level: Level, title: string, detail = "") {
  const d = document.createElement("div");
  d.className = `row ${level}`;
  d.innerHTML = `<b>${level === "pass" ? "PASS" : level === "fail" ? "FAIL" : "INFO"}: ${title}</b><span></span>`;
  (d.querySelector("span") as HTMLElement).textContent = detail;
  out.appendChild(d);
  if (level === "pass") pass++;
  if (level === "fail") fail++;
}
async function check(title: string, fn: () => Promise<string | void>) {
  try { row("pass", title, (await fn()) || ""); } catch (e) { row("fail", title, (e as Error).message); }
}

async function main() {
  await check("Page comes from the app's own files", async () => {
    if (location.origin !== "https://appassets.androidplatform.net") throw new Error(`origin is ${location.origin}`);
    return location.href;
  });
  await check("Bridge is present", async () => {
    if (!hasHost()) throw new Error("window.SukoBridge is missing");
    return JSON.stringify(hostInfo());
  });
  await check("Native call round trip (health)", async () => {
    const j = await nativeCall<any>("health");
    if (j.engine !== "suko-native") throw new Error(JSON.stringify(j));
    return `engine=${j.engine}, ready=${j.ready}`;
  });
  await check("Big payload (3 MB) reaches Kotlin intact", async () => {
    const text = "abcdefghij".repeat(300_000);
    const j = await nativeCall<any>("size", text);
    if (j.chars !== text.length) throw new Error(`sent ${text.length}, Kotlin got ${j.chars}`);
    return `${j.chars} characters`;
  });
  await check("Unknown native method is refused cleanly", async () => {
    try { await nativeCall("nope"); } catch (e) { return (e as Error).message; }
    throw new Error("an unknown method was accepted");
  });
  await check("Files handed over by the app can be read", async () => {
    const files = await inputFiles();
    const f = files.find(x => x.name === "hello.txt");
    if (!f) throw new Error(`hello.txt not found (got ${files.map(x => x.name).join(", ") || "nothing"})`);
    const t = await f.text();
    if (t !== "hello suko") throw new Error(`wrong content: ${t}`);
    return `${files.length} file(s), hello.txt ok`;
  });
  await check("Internet is blocked (offline rule)", async () => {
    let reached = false;
    try { const r = await fetch("https://example.com/", { cache: "no-store" }); reached = r.ok; } catch { /* expected */ }
    if (reached) throw new Error("a request to example.com went through");
    const blocked = hostInfo()?.blocked;
    return `blocked requests counted by the app: ${blocked}`;
  });
  await check("Save a file through Android (5 chunks)", async () => {
    const body = "Bezalel web test file. ".repeat(250);   // 5000 bytes
    const where = await saveFile("suko-web-test.txt", new Blob([body], { type: "text/plain" }), "text/plain", 1000);
    return where;
  });

  // information for step 23 (Text Editor export): can this phone encode H.264 in the page?
  const enc = (globalThis as any).VideoEncoder;
  row("info", "WebCodecs VideoEncoder", enc ? "present" : "NOT present (step 23 would need the native GL encoder)");
  if (enc) {
    for (const fps of [30, 60]) {
      try {
        const r = await enc.isConfigSupported({ codec: "avc1.640028", width: 1920, height: 1080, framerate: fps, bitrate: 12_000_000 });
        row("info", `H.264 1080p at ${fps} fps`, r.supported ? "supported" : "not supported");
      } catch (e) { row("info", `H.264 1080p at ${fps} fps`, `check failed: ${(e as Error).message}`); }
    }
  }
  row("info", "Fonts API / canvas", `fonts=${!!document.fonts}, OffscreenCanvas=${typeof OffscreenCanvas !== "undefined"}`);

  sum.textContent = fail === 0 ? `All ${pass} checks passed.` : `${fail} check(s) FAILED, ${pass} passed.`;
}
main();
