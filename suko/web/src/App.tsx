import { useEffect, useMemo, useRef, useState } from "react";
import { backendVersion, detectPanels, panelsStatus, processImage } from "./api";
import { matchPanels, panelLayers } from "./panelmatch";
import { BACKEND_VERSION, bgMarkup, composeSvg, download, drawOrder, effDir, effEntrance, effStyle, finalArt, planSketch, rasterizeIfSvg, sketchReady, svgToPngBlob } from "./compose";
import { DEFAULT_ANIM, DEFAULT_SKETCH, type Background, type Dir, type DrawStyle, type Entrance, type Layer, type LinesMode, type Project, type SketchSettings } from "./types";
import { exportPlan, FPS_CHOICES, mp4Supported, fpsFor, renderMp4, safeRes, Scrubber } from "./video";
import { FontPicker } from "./FontPicker";
import { ResolutionPicker } from "./ResolutionPicker";
import { buildTextSvg, DEFAULT_TEXT, SHAPES, type TextSettings } from "./textanim";
import VideoMode from "./VideoMode";
import TextEditor from "./TextEditor";

/** The video ends this long after the last element is finished, so the final artwork is on screen in the last frames. */
const HOLD = 0.1;

/** Defaults for every element that has no choice of its own (each element can override them in the Elements list). */
interface Defaults { entrance: Entrance; style: DrawStyle; dir: Dir; enterTime: number; draw: number; pause: number; pen: number; lines: LinesMode }
const DEFAULTS: Defaults = { entrance: "draw", style: "illustrate", dir: "left", enterTime: 0.6, draw: 3, pause: 0, pen: 0.5, lines: "auto" };
type TimingMode = "fit" | "manual";
/** fit    = the whole picture is finished exactly at the video length; elements without their own time share what is left by drawing effort.
 *  manual = every element takes the draw time you set (default for all, or its own), plus its pause. The video is as long as all of that together. */
const settingsFor = (mode: TimingMode, seconds: number, d: Defaults): SketchSettings => mode === "manual"
  ? { ...DEFAULT_SKETCH, ...d, min: d.draw, max: d.draw, fit: false, target: 0 }
  : { ...DEFAULT_SKETCH, ...d, min: 0.02, max: 600, fit: true, target: Math.max(0.5, seconds - HOLD) };

const ENTRANCES: { id: Entrance; label: string }[] = [
  { id: "draw", label: "Draw (hand-drawn)" }, { id: "fade", label: "Fade in" }, { id: "pop", label: "Pop in (bounce)" }, { id: "slide", label: "Move in" }, { id: "none", label: "Appear at once" },
];
const DIRS: { id: Dir; label: string }[] = [{ id: "left", label: "from left" }, { id: "right", label: "from right" }, { id: "top", label: "from top" }, { id: "bottom", label: "from bottom" }];

/** A number box that changes the animation only when you press Enter or leave the box (so typing "12" does not restart the preview at "1"). Empty = automatic. */
function NumBox({ value, placeholder, onCommit, width = 64 }: { value: number | undefined; placeholder: string; onCommit: (v: number | undefined) => void; width?: number }) {
  const [t, setT] = useState(value === undefined ? "" : String(value));
  useEffect(() => { setT(value === undefined ? "" : String(value)); }, [value]);
  const commit = () => { const x = parseFloat(t); onCommit(t.trim() === "" || !Number.isFinite(x) || x < 0 ? undefined : Math.round(x * 100) / 100); };
  return <input className="num" type="text" inputMode="decimal" style={{ width }} value={t} placeholder={placeholder} onChange={e => setT(e.target.value)} onBlur={commit} onKeyDown={e => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }} />;
}

/** Small picture of one element for the list. Shown as an image so its internal ids cannot clash with the main drawing. */
function thumbOf(l: Layer): string {
  const pad = Math.max(l.bbox.w, l.bbox.h) * 0.06 + 2, b = l.bbox;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${b.x - pad} ${b.y - pad} ${b.w + 2 * pad} ${b.h + 2 * pad}">${finalArt(l)}</svg>`;
  return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
}

/** Inside the Bezalel app the Text Editor card opens this page as ...index.html?app=text: the Text Editor screen (TextEditor.tsx), no Image/Video tabs. */
const isTextApp = (): boolean => { try { return new URLSearchParams(window.location.search).get("app") === "text"; } catch { return false; } };

export default function App() {
  return isTextApp() ? <TextEditor /> : <Studio />;
}

function Studio() {
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<string>("");
  const [project, setProject] = useState<Project | null>(null);
  // Inside the Bezalel app the Text Editor card opens this page as ...index.html?app=text: text mode only, no Image/Video tabs.
  const textOnly = useMemo(() => { try { return new URLSearchParams(window.location.search).get("app") === "text"; } catch { return false; } }, []);
  // Inside the Bezalel app the SVG Editor card opens this page as ...index.html?app=video: the Video screen only (VideoMode), no Image/Text tabs.
  const videoOnly = useMemo(() => { try { return new URLSearchParams(window.location.search).get("app") === "video"; } catch { return false; } }, []);
  const [mode, setModeRaw] = useState<"image" | "text" | "video">(textOnly ? "text" : "video");
  const setMode = setModeRaw;
  const [imgBg, setImgBg] = useState<Background>({ kind: "color", color: "#ffffff" });
  const [textBg, setTextBg] = useState<Background>({ kind: "color", color: "#fdf6e3" });
  const [tx, setTx] = useState<TextSettings>({ ...DEFAULT_TEXT });
  const [textScene, setTextScene] = useState<{ svg: string; width: number; height: number } | null>(null);
  const bg = mode === "text" ? textBg : imgBg;
  const setBg = mode === "text" ? setTextBg : setImgBg;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [durText, setDurText] = useState("20");
  const [seconds, setSeconds] = useState(20);
  const [tmode, setTmode] = useState<TimingMode>("fit");
  const [defs, setDefs] = useState<Defaults>(DEFAULTS);
  const [replay, setReplay] = useState(0);
  const [backend, setBackend] = useState<string | null | undefined>(undefined);
  const [fps, setFps] = useState<number>(30);
  const [resId, setResId] = useState<string>("1080p");
  const [exporting, setExporting] = useState<{ done: number; total: number } | null>(null);
  const cancel = useRef(false);
  const fileRef = useRef<HTMLInputElement>(null);
  useEffect(() => { backendVersion().then(setBackend); }, []);

  // Typing "20" must not restart the preview at "2": the duration is applied when typing stops.
  useEffect(() => {
    const x = parseFloat(durText);
    if (!Number.isFinite(x) || x < 1) return;
    const id = setTimeout(() => setSeconds(Math.min(1800, x)), 450);
    return () => clearTimeout(id);
  }, [durText]);

  const sk = useMemo(() => settingsFor(tmode, seconds, defs), [tmode, seconds, defs]);
  const animSvg = useMemo(() => (project ? composeSvg(project, bg, { sketch: sk }) : ""), [project, bg, sk]);
  // Text mode: the animated SVG is rebuilt a moment after you stop typing.
  useEffect(() => {
    if (mode !== "text") return;
    let live = true;
    const id = setTimeout(() => {
      buildTextSvg(tx, (w, h) => bgMarkup({ width: w, height: h } as Project, textBg), Math.max(0.5, seconds - HOLD))
        .then(r => { if (live) { setTextScene(r); setError(""); } })
        .catch(e => { if (live) setError(e.message ?? "Could not draw the text."); });
    }, 250);
    return () => { live = false; clearTimeout(id); };
  }, [mode, tx, textBg, seconds]);
  const scene = mode === "image" ? (project ? { svg: animSvg, width: project.width, height: project.height } : null) : mode === "text" ? textScene : null;
  const plan = useMemo(() => (project ? planSketch(project.layers.filter(l => l.visible), sk, project.order) : null), [project, sk]);
  // Length of the video. Image, manual timing: everything you set added up. Image, fit: the length you typed, unless your own element times need more.
  const outSeconds = useMemo(() => {
    if (mode !== "image" || !plan) return seconds;
    const need = Math.ceil((plan.total + HOLD) * 10) / 10;
    return tmode === "manual" ? Math.max(1, need) : Math.max(seconds, need);
  }, [mode, plan, tmode, seconds]);
  const thumbs = useMemo(() => new Map((project?.layers ?? []).map(l => [l.id, thumbOf(l)])), [project?.layers.length, file]);
  const upd = (id: string, patch: Partial<Layer>) => setProject(p => (p ? { ...p, layers: p.layers.map(l => (l.id === id ? { ...l, ...patch } : l)) } : p));
  const updTime = (id: string, key: "draw" | "pause", v: number | undefined) => setProject(p => (p ? { ...p, layers: p.layers.map(l => { if (l.id !== id) return l; const timing = { ...l.timing, [key]: v }; if (timing.draw === undefined && timing.pause === undefined) { const { timing: _t, ...rest } = l; return rest as Layer; } return { ...l, timing }; }) } : p));
  const move = (id: string, by: number) => setProject(p => {
    if (!p) return p;
    const ids = drawOrder(p.layers, p.order).map(l => l.id), i = ids.indexOf(id), j = i + by;
    if (i < 0 || j < 0 || j >= ids.length) return p;
    [ids[i], ids[j]] = [ids[j], ids[i]];
    return { ...p, order: ids };
  });
  const resetAll = () => setProject(p => (p ? { ...p, layers: p.layers.map(({ timing, entrance, style, dir, ...rest }) => rest as Layer) } : p));

  async function pick(f: File | undefined) {
    if (!f || busy || exporting) return;
    setProject(null); setError("");
    try {
      const ready = await rasterizeIfSvg(f);   // SVG -> PNG so it can be separated
      setFile(ready);
      setPreview(URL.createObjectURL(ready));
      setBusy(true);
      let p = await processImage(ready);        // elements are found automatically, there is nothing to click
      // 0.15.2: when the panels are found, every panel (with its label, caption box and arrow) is one element made of the ORIGINAL pixels, finished before the next starts
      const found = await detectPanels(ready);
      const pl = found?.panels.length ? panelLayers(found, matchPanels(found)) : null;
      if (found && pl) {
        const fr = found.frameRaster, c = fr?.box;
        const frame: Layer[] = fr && c ? [{ id: "frame", name: "Frame", group: "", visible: true, kind: "connector", bbox: { x: c[0], y: c[1], w: c[2], h: c[3] }, tx: 0, ty: 0, scale: 1, rot: 0, anim: { ...DEFAULT_ANIM }, rasterHref: fr.href, rasterBox: c }] : [];
        const layers = [...frame, ...pl];
        p = { ...p, layers, order: layers.map(l => l.id), bgRasterHref: undefined };
      }
      if (!pl) setError(`Panels not used: ${panelsStatus || "the panels have no cut-outs"}. The old element path is shown instead.`);
      setProject(p); setImgBg({ kind: "color", color: p.backgroundColor }); setReplay(n => n + 1);
    } catch (e: any) { setError(e.message ?? "Could not open this image."); }
    setBusy(false);
  }

  async function pickBgImage(f: File | undefined) {
    if (!f) return;
    try {
      const ready = await rasterizeIfSvg(f);
      const r = new FileReader();
      r.onload = () => {
        const dataUrl = String(r.result), im = new Image();
        im.onload = () => setBg({ kind: "image", image: { dataUrl, w: im.naturalWidth || im.width || 1, h: im.naturalHeight || im.height || 1, zoom: 1, offX: 0, offY: 0 } });
        im.onerror = () => setError("Could not read this background image.");
        im.src = dataUrl;
      };
      r.readAsDataURL(ready);
    } catch (e: any) { setError(e.message ?? "Could not read this background image."); }
  }
  const setImg = (k: "zoom" | "offX" | "offY", v: number) =>
    setBg(b => (b.kind === "image" ? { kind: "image", image: { ...b.image, [k]: v } } : b));

  async function exportMp4() {
    if (!scene) return;
    setError(""); cancel.current = false; setExporting({ done: 0, total: exportPlan(outSeconds, fpsFor(resId, fps), resId).total });
    try {
      const blob = await renderMp4({ svg: scene.svg, width: scene.width, height: scene.height, seconds: outSeconds, fps: fpsFor(resId, fps), resolution: resId, onProgress: (done, total) => setExporting({ done, total }), cancelled: () => cancel.current });
      download(`${mode === "text" ? "text" : "animation"}-${resId}-${fpsFor(resId, fps)}fps-${Math.round(outSeconds * 10) / 10}s.mp4`, blob);
    } catch (e: any) { if (!cancel.current) setError(e.message ?? "MP4 export failed."); }
    setExporting(null);
  }


  const pct = exporting ? Math.round((exporting.done / Math.max(1, exporting.total)) * 100) : 0;

  const durOk = (mode === "image" && tmode === "manual") || (Number.isFinite(parseFloat(durText)) && parseFloat(durText) >= 1 && Math.min(1800, parseFloat(durText)) === seconds);
  const busyAny = busy || !!exporting;

  return (
    <div className="app">
      <header>
        <h1>{textOnly ? "Text Editor" : videoOnly ? "SVG Editor" : "Image Layer Studio"}</h1>
        {!textOnly && !videoOnly && <div className="tabs">
          <button className={mode === "image" ? "tab on" : "tab"} disabled={busyAny} onClick={() => setMode("image")}>Image</button>
          <button className={mode === "text" ? "tab on" : "tab"} disabled={busyAny} onClick={() => setMode("text")}>Text</button>
          <button className={mode === "video" ? "tab on" : "tab"} disabled={busyAny} onClick={() => setMode("video")}>Video</button>
        </div>}
        <div className="actions">
          {mode === "image" && <>
            <button className="ghost" disabled={busyAny} onClick={() => fileRef.current?.click()}>{file ? "Change image" : "Upload image"}</button>
            <input ref={fileRef} type="file" accept="image/*,.svg" hidden onChange={e => { pick(e.target.files?.[0]); e.target.value = ""; }} />
          </>}
          {scene && <button className="ghost" onClick={() => setReplay(n => n + 1)}>↻ Replay</button>}
        </div>
      </header>

      {error && <p className="error">{error}</p>}
      {mode !== "text" && backend !== undefined && backend !== BACKEND_VERSION && <p className="warn">{backend === null ? "The backend is not running, so images cannot be separated." : `An OLD backend (${backend || "older than 0.10.2"}) is still running. Close this window, then open the app again from the Desktop icon. If this message comes back, double-click restart-backend.bat in the backend folder.`}</p>}
      {mode === "image" && project && !sketchReady(project, sk) && <p className="warn">This project was made by an older backend, so the drawing looks plain. Restart the app and upload the image again.</p>}

      {mode === "video" && <VideoMode onError={setError} />}
      {mode !== "video" && <main>
        <section className="stage">
          {mode === "image" && !file && (
            <div className="empty" onClick={() => fileRef.current?.click()}
              onDragOver={e => e.preventDefault()} onDrop={e => { e.preventDefault(); pick(e.dataTransfer.files[0]); }}>
              <p>Drop a poster, logo or illustration here</p><span>PNG, JPG, WebP or SVG</span>
            </div>
          )}
          {mode === "image" && file && !project && (
            <div className="busywrap">
              <img className="canvas" src={preview} alt="Uploaded" style={{ opacity: busy ? 0.5 : 1 }} />
              {busy && <p className="busy">Finding the elements…</p>}
            </div>
          )}
          {scene && <div className="canvas anim" key={scene.svg + replay} style={{ aspectRatio: `${scene.width} / ${scene.height}`, maxWidth: scene.height > scene.width ? "min(100%, 360px)" : undefined }} dangerouslySetInnerHTML={{ __html: scene.svg }} />}
        </section>

        <aside>
          {mode === "image" && !project && <p className="hint">Upload an image. The elements are found automatically, then you choose how long the drawing should take and export an MP4.</p>}

          {mode === "text" && (<>
            <h2>Text</h2>
            <textarea className="textbox" rows={6} value={tx.text} placeholder="Type or paste a verse, heading or formula" onChange={e => setTx(t => ({ ...t, text: e.target.value }))} />
            <p className="hint small">Enter starts a new line. Make the size smaller to have room to move the text. Formulas: <code>x^2</code> (small raised), <code>x^{"{n+1}"}</code>, <code>H_2O</code> (small lowered); symbols like √ ∑ π ≠ ≤ ∞ × ÷ work too.</p>
            <div className="sliders">
              <FontPicker value={tx.fontId} onChange={id => setTx(t => ({ ...t, fontId: id }))} disabled={!!exporting} />
              <label>Text color <input type="color" value={tx.color} onChange={e => setTx(t => ({ ...t, color: e.target.value }))} /></label>
              <label>Align <select value={tx.align} onChange={e => setTx(t => ({ ...t, align: e.target.value as TextSettings["align"] }))}><option value="center">Centre</option><option value="left">Left</option></select></label>
              <label>Size <input type="range" min="30" max="100" step="1" value={tx.size} onChange={e => setTx(t => ({ ...t, size: +e.target.value }))} /></label>
              <label>Position across <input type="range" min="0" max="100" step="1" value={tx.posX ?? 50} onChange={e => setTx(t => ({ ...t, posX: +e.target.value }))} /> <span className="inl">{tx.posX === undefined ? "auto" : tx.posX + "%"}</span></label>
              <label>Position up/down <input type="range" min="0" max="100" step="1" value={tx.posY ?? 50} onChange={e => setTx(t => ({ ...t, posY: +e.target.value }))} /> <span className="inl">{tx.posY === undefined ? "centre" : tx.posY + "%"}</span></label>
              <button disabled={tx.posX === undefined && tx.posY === undefined} onClick={() => setTx(t => ({ ...t, posX: undefined, posY: undefined }))}>Centre the text</button>
              <label>Writing <select value={tx.style} onChange={e => setTx(t => ({ ...t, style: e.target.value as TextSettings["style"] }))}><option value="write">Handwritten (pen follows each stroke)</option><option value="trace">Outline, then fill</option></select></label>
              <label>Shape <select value={tx.shape} onChange={e => setTx(t => ({ ...t, shape: e.target.value }))}>{SHAPES.map(f => <option key={f.id} value={f.id}>{f.label}</option>)}</select></label>
            </div>
          </>)}

          {scene && (<>
            <h2>Duration</h2>
            {mode === "image" && (
              <div className="seg" role="radiogroup" aria-label="How the length is set">
                <button className={tmode === "fit" ? "on" : ""} disabled={!!exporting} onClick={() => setTmode("fit")}>Fit to a length</button>
                <button className={tmode === "manual" ? "on" : ""} disabled={!!exporting} onClick={() => setTmode("manual")}>Set each element</button>
              </div>
            )}
            {(mode === "text" || tmode === "fit") && (<>
              <p className="hint small">{mode === "image" ? "The whole image is finished in exactly this time (use the length of the voice-over). Elements whose time you set yourself keep it; the rest share what is left." : "All the text is written in exactly this time. Use the length of the voice-over for this part of the script."}</p>
              <div className="row">
                <input type="number" min="1" max="1800" step="1" style={{ width: 90 }} value={durText} onChange={e => setDurText(e.target.value)} disabled={!!exporting} /> seconds
              </div>
            </>)}
            {mode === "image" && tmode === "manual" && <p className="hint small">Every element takes the draw time you give it (default below, or its own time in the list). The video is as long as everything added up: <b>{Math.round(outSeconds * 10) / 10} s</b>.</p>}
            {mode === "image" && plan && tmode === "fit" && outSeconds > seconds + 0.05 && <p className="hint small" style={{ color: "var(--bad)" }}>The times you set add up to more than {seconds} s, so the video is {Math.round(outSeconds * 10) / 10} s.</p>}

            {mode === "image" && project && (<>
              <h2>Animation <small>for all elements</small></h2>
              <div className="sliders">
                <label>Entrance <select value={defs.entrance} onChange={e => setDefs(d => ({ ...d, entrance: e.target.value as Entrance }))}>{ENTRANCES.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
                {defs.entrance === "draw" && <label>Draw style <select value={defs.style} onChange={e => setDefs(d => ({ ...d, style: e.target.value as DrawStyle }))}><option value="illustrate">Follow the lines</option><option value="reveal">Reveal top to bottom</option></select></label>}
                {defs.entrance === "draw" && <label>Lines then colour <select value={defs.lines} onChange={e => setDefs(d => ({ ...d, lines: e.target.value as LinesMode }))}><option value="auto">Auto (panels one by one, other pictures all lines first)</option><option value="each">One piece at a time: its lines, then its colour</option><option value="all">All lines first, then all colour</option><option value="off">Off (outline and colour together)</option></select></label>}
                {defs.entrance === "draw" && <label>{defs.lines !== "off" ? "Lines share" : "Pen share"} <span className="inl"><input type="range" min="10" max="90" step="5" value={Math.round(defs.pen * 100)} onChange={e => setDefs(d => ({ ...d, pen: +e.target.value / 100 }))} /> {Math.round(defs.pen * 100)}% pen, {100 - Math.round(defs.pen * 100)}% colour</span></label>}
                {defs.entrance === "slide" && <label>Move in <select value={defs.dir} onChange={e => setDefs(d => ({ ...d, dir: e.target.value as Dir }))}>{DIRS.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>}
                {tmode === "manual" && <label>Draw time <span className="inl"><NumBox value={defs.draw} placeholder="3" onCommit={v => setDefs(d => ({ ...d, draw: v && v > 0 ? v : d.draw }))} /> s</span></label>}
                {defs.entrance !== "draw" && defs.entrance !== "none" && <label>Enter time <span className="inl"><NumBox value={defs.enterTime} placeholder="0.6" onCommit={v => setDefs(d => ({ ...d, enterTime: v && v > 0 ? v : d.enterTime }))} /> s</span></label>}
                <label>Pause after <span className="inl"><NumBox value={defs.pause} placeholder="0" onCommit={v => setDefs(d => ({ ...d, pause: v ?? 0 }))} /> s</span></label>
              </div>

              <h2>Elements <small>{plan ? `${plan.order.length} shown, drawn in this order` : ""}</small></h2>
              <p className="hint small">Each element can have its own entrance, draw time and pause. Leave a box empty to use the default above.</p>
              <ul className="elems">
                {drawOrder(project.layers, project.order).map((l, i, all) => {
                  const ent = effEntrance(l, sk), tm = plan?.times.get(l.id);
                  return (
                    <li key={l.id} className={l.visible ? "" : "off"}>
                      <div className="top">
                        <span className="num">{i + 1}</span>
                        <img className="thumb" src={thumbs.get(l.id)} alt="" />
                        <span className="meta"><b title={l.name}>{l.name}</b><small>{!l.visible ? "hidden" : tm ? `${Math.round(tm.begin * 10) / 10}s to ${Math.round((tm.begin + tm.dur) * 10) / 10}s` : ""}</small></span>
                        <button className="icon" title="Earlier" disabled={i === 0 || !!exporting} onClick={() => move(l.id, -1)}>↑</button>
                        <button className="icon" title="Later" disabled={i === all.length - 1 || !!exporting} onClick={() => move(l.id, 1)}>↓</button>
                        <button className="icon" title={l.visible ? "Hide this element" : "Show this element"} disabled={!!exporting} onClick={() => upd(l.id, { visible: !l.visible })}>{l.visible ? "👁" : "—"}</button>
                      </div>
                      {l.visible && (
                        <div className="ctl">
                          <label>Entrance <select value={l.entrance ?? ""} onChange={e => upd(l.id, { entrance: (e.target.value || undefined) as Entrance | undefined })}><option value="">Default ({ENTRANCES.find(o => o.id === defs.entrance)?.label})</option>{ENTRANCES.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
                          {ent === "draw" && <label>Draw style <select value={l.style ?? ""} onChange={e => upd(l.id, { style: (e.target.value || undefined) as DrawStyle | undefined })}><option value="">Default</option><option value="illustrate">Follow the lines</option><option value="reveal">Reveal top to bottom</option></select></label>}
                          {ent === "slide" && <label>Move in <select value={l.dir ?? ""} onChange={e => upd(l.id, { dir: (e.target.value || undefined) as Dir | undefined })}><option value="">Default</option>{DIRS.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>}
                          {ent !== "none" && <label>{ent === "draw" ? "Draw time" : "Enter time"} <span className="inl"><NumBox value={l.timing?.draw} placeholder={tm ? String(Math.round(tm.dur * 10) / 10) : "auto"} onCommit={v => updTime(l.id, "draw", v !== undefined && v < 0.1 ? 0.1 : v)} /> s</span></label>}
                          <label>Pause after <span className="inl"><NumBox value={l.timing?.pause} placeholder={String(defs.pause)} onCommit={v => updTime(l.id, "pause", v)} /> s</span></label>
                        </div>
                      )}
                    </li>
                  );
                })}
              </ul>
              <div className="row wrap"><button className="ghost" disabled={!!exporting} onClick={resetAll}>Reset all to default</button><button className="ghost" disabled={!!exporting} onClick={() => setProject(p => (p ? { ...p, order: undefined } : p))}>Reading order</button></div>
            </>)}

            <h2>Background</h2>
            <div className="row">
              <label>Color <input type="color" value={bg.kind === "color" ? bg.color : "#ffffff"} onChange={e => setBg({ kind: "color", color: e.target.value })} /></label>
              <label className="btn ghost">Use an image<input type="file" accept="image/*" hidden onChange={e => pickBgImage(e.target.files?.[0])} /></label>
            </div>
            {bg.kind === "image" && (
              <div className="sliders">
                <label>Zoom <input type="range" min="1" max="3" step="0.01" value={bg.image.zoom} onChange={e => setImg("zoom", +e.target.value)} /></label>
                <label>Left / right <input type="range" min="-0.5" max="0.5" step="0.01" value={bg.image.offX} onChange={e => setImg("offX", +e.target.value)} /></label>
                <label>Up / down <input type="range" min="-0.5" max="0.5" step="0.01" value={bg.image.offY} onChange={e => setImg("offY", +e.target.value)} /></label>
              </div>
            )}

            <h2>Export</h2>
            {!exporting && (
              <div className="row wrap">
                {FPS_CHOICES.map(f => <button key={f} disabled={f !== fpsFor(resId, f)} className={fpsFor(resId, fps) === f ? "on" : "ghost"} onClick={() => setFps(f)}>{f} fps</button>)}
              </div>
            )}
            {!exporting && <ResolutionPicker value={resId} onChange={id => { setResId(id); setFps(f => fpsFor(id, f)); }} fps={fpsFor(resId, fps)} seconds={outSeconds} />}
            {!exporting && (
              <div className="row wrap" style={{ marginTop: 8 }}>
                <button onClick={exportMp4} disabled={!mp4Supported() || !durOk}>Export {safeRes(resId).id === "1080p" ? "1080p" : safeRes(resId).id.toUpperCase()} MP4, {fps} fps ({Math.round(outSeconds * 10) / 10} s)</button>
              </div>
            )}
            {exporting && (
              <div>
                <div className="bar"><div style={{ width: `${pct}%` }} /></div>
                <p className="hint small">Making the video: frame {exporting.done} of {exporting.total} ({pct}%). Keep this window open.</p>
                <button className="ghost" onClick={() => { cancel.current = true; }}>Cancel</button>
              </div>
            )}
            {!mp4Supported() && <p className="hint small">This phone's web view cannot make MP4 (no WebCodecs). Update "Android System WebView" in the Play Store.</p>}
            <p className="hint small" style={{ marginTop: 10 }}>{safeRes(resId).w} x {safeRes(resId).h}, {fps} frames per second, H.264. {fps === 60 ? "60 fps has twice as many frames, so it takes about twice as long to make as 30 fps." : "A 20 s video at 1080p takes about a minute to make."}{resId !== "1080p" ? " 2K takes about twice and 4K about four times as long as 1080p." : ""}</p>
            <div className="row wrap" style={{ marginTop: 8 }}>
              <button className="ghost" disabled={!!exporting} onClick={() => download("composition-sketch.svg", new Blob([scene.svg], { type: "image/svg+xml" }))}>Animated SVG</button>
              <button className="ghost" disabled={!!exporting} onClick={async () => download("composition.png", await svgToPngBlob(new Scrubber(scene.svg).at(1e6), scene.width, scene.height))}>PNG</button>
            </div>
          </>)}
        </aside>
      </main>}
    </div>
  );
}
