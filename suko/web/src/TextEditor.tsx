import { useEffect, useMemo, useRef, useState, type PointerEvent as RPointerEvent } from "react";
import { bgMarkup, download } from "./compose";
import { goBack } from "./bridge";
import { allFonts, buildTextSvg, DEFAULT_TEXT, findFont, FONT_EXTENSIONS, SHAPES, type TextScene, type TextSettings } from "./textanim";
import { restoreFonts, uploadFont } from "./fontStore";
import type { Background, Project } from "./types";
import { estimateBytes, exportPlan, FPS_CHOICES, fpsFor, mp4Supported, renderMp4, RESOLUTIONS, safeRes, Scrubber, supportedResolutions } from "./video";
import "./TextEditor.css";

/** The video ends this long after the last letter is finished, so the finished text is on screen in the last frames. */
const HOLD = 0.1;
const TABS = [{ id: "text", label: "Text" }, { id: "style", label: "Style" }, { id: "motion", label: "Motion" }, { id: "export", label: "Export" }] as const;
type Tab = (typeof TABS)[number]["id"];
const TEXT_COLORS = ["#1b2a41", "#000000", "#ffffff", "#c0392b", "#1f4fd8", "#2e7d32", "#b7791f"];
const BG_COLORS = ["#fdf6e3", "#ffffff", "#0b0d12", "#1b2a41", "#fff3b0", "#dff3ff"];
const STEP = 5;   // one arrow press moves the text 5 % of the free room

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));
const clock = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`;
const mb = (b: number) => (b >= 1e9 ? `${(b / 1e9).toFixed(1)} GB` : `${Math.max(1, Math.round(b / 1e6))} MB`);
const sizeFmt = (n: number) => String(Math.round(n));

/** Where the text sits now, as 0..100 % of the free room (the same numbers posX / posY use). */
function posOf(sc: TextScene | null): { x: number; y: number } {
  if (!sc?.ink || !sc.area) return { x: 50, y: 50 };
  const fx = sc.area.w - sc.ink.w, fy = sc.area.h - sc.ink.h;
  return { x: fx > 1 ? clamp(((sc.ink.x - sc.area.x) / fx) * 100, 0, 100) : 50, y: fy > 1 ? clamp(((sc.ink.y - sc.area.y) / fy) * 100, 0, 100) : 50 };
}

const Chevron = ({ d }: { d: "l" | "r" | "u" | "d" }) => (
  <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d={{ l: "M15 5l-7 7 7 7", r: "M9 5l7 7-7 7", u: "M5 15l7-7 7 7", d: "M5 9l7 7 7-7" }[d]} />
  </svg>
);

/** The picture with its play bar. Owns the clock, so playing does not redraw the controls below. */
function Player({ scene, seconds, controls, boxes, badge, onMove, onSize }: {
  scene: TextScene | null; seconds: number; controls: boolean; boxes: boolean; badge: string;
  onMove: (x: number | undefined, y: number | undefined) => void; onSize: (px: number) => void;
}) {
  const [t, setT] = useState(seconds);
  const [playing, setPlaying] = useState(false);
  const tRef = useRef(t); tRef.current = t;
  const stage = useRef<HTMLDivElement>(null);
  const drag = useRef<{ kind: "move" | "size"; sx: number; sy: number; k: number; scene: TextScene } | null>(null);

  const scrub = useMemo(() => (scene ? new Scrubber(scene.svg) : null), [scene?.svg]);
  // A change to the text, style or position shows the finished picture, so you see the result of what you did.
  useEffect(() => { setPlaying(false); setT(seconds); }, [scene?.svg, seconds]);
  useEffect(() => {
    if (!playing) return;
    let raf = 0, last = performance.now();
    const tick = (now: number) => {
      const nt = tRef.current + (now - last) / 1000; last = now;
      if (nt >= seconds) { setT(seconds); setPlaying(false); return; }
      setT(nt); raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, seconds]);

  const frame = useMemo(() => (scrub ? scrub.at(Math.min(t, seconds)) : ""), [scrub, t, seconds]);
  const play = () => { if (playing) { setPlaying(false); return; } if (t >= seconds - 0.02) setT(0); setPlaying(true); };

  const begin = (kind: "move" | "size") => (e: RPointerEvent) => {
    if (!scene?.ink || !scene.area || !stage.current) return;
    e.preventDefault(); e.stopPropagation();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    drag.current = { kind, sx: e.clientX, sy: e.clientY, k: scene.width / stage.current.getBoundingClientRect().width, scene };
  };
  const during = (e: RPointerEvent) => {
    const d = drag.current; if (!d || !d.scene.ink || !d.scene.area) return;
    const dx = (e.clientX - d.sx) * d.k, dy = (e.clientY - d.sy) * d.k, { ink, area } = d.scene;
    if (d.kind === "move") {
      const fx = area.w - ink.w, fy = area.h - ink.h;
      onMove(fx > 1 ? Math.round(clamp(((ink.x + dx - area.x) / fx) * 100, 0, 100) * 10) / 10 : undefined, fy > 1 ? Math.round(clamp(((ink.y + dy - area.y) / fy) * 100, 0, 100) * 10) / 10 : undefined);
    } else onSize(clamp(d.scene.size * ((ink.w + dx) / Math.max(1, ink.w)), 20, d.scene.fit ?? 300));
  };
  const end = () => { drag.current = null; };

  const ratio = scene ? scene.width / scene.height : 16 / 9;
  const ink = scene?.ink;
  const pad = 10;
  return (
    <>
      <div className="tstage" ref={stage} style={{ aspectRatio: String(ratio), width: ratio < 1 ? `min(100%, calc(52dvh * ${ratio}))` : "100%" }}>
        <div className="frame" dangerouslySetInnerHTML={{ __html: frame }} />
        <span className="badge">{badge}</span>
        {boxes && !playing && scene && ink && (
          <div className="tbox" style={{ left: `${((ink.x - pad) / scene.width) * 100}%`, top: `${((ink.y - pad) / scene.height) * 100}%`, width: `${((ink.w + 2 * pad) / scene.width) * 100}%`, height: `${((ink.h + 2 * pad) / scene.height) * 100}%` }}
            onPointerDown={begin("move")} onPointerMove={during} onPointerUp={end} onPointerCancel={end}>
            <i className="dot tl" />
            <i className="dot br" onPointerDown={begin("size")} onPointerMove={during} onPointerUp={end} onPointerCancel={end} />
          </div>
        )}
      </div>
      {controls && (<>
        <p className="xhint">Drag the text on the picture to move it.</p>
        <div className="player">
          <button className="play" onClick={play} aria-label={playing ? "Pause" : "Play"}>
            {playing
              ? <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4.5" height="14" rx="1" /><rect x="13.5" y="5" width="4.5" height="14" rx="1" /></svg>
              : <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5.2v13.6a1 1 0 0 0 1.5.86l11-6.8a1 1 0 0 0 0-1.72l-11-6.8A1 1 0 0 0 8 5.2z" /></svg>}
          </button>
          <input className="range grow" type="range" min="0" max={seconds} step="0.01" value={Math.min(t, seconds)} aria-label="Position in the video"
            style={{ ["--fill" as string]: `${(Math.min(t, seconds) / Math.max(0.01, seconds)) * 100}%` }}
            onChange={e => { setPlaying(false); setT(+e.target.value); }} />
          <span className="time">{clock(Math.min(t, seconds))} / {clock(seconds)}</span>
        </div>
      </>)}
    </>
  );
}

export default function TextEditor() {
  const [tab, setTab] = useState<Tab>("text");
  const [tx, setTx] = useState<TextSettings>({ ...DEFAULT_TEXT, text: "Hello, world", px: 200 });
  const [bg, setBg] = useState<Background>({ kind: "color", color: "#fdf6e3" });
  const [scene, setScene] = useState<TextScene | null>(null);
  const [seconds, setSeconds] = useState(8);
  const [durText, setDurText] = useState("8");
  const [fps, setFps] = useState<number>(30);
  const [resId, setResId] = useState<string>("1080p");
  const [resOk, setResOk] = useState<Record<string, boolean> | null>(null);
  const [exporting, setExporting] = useState<{ done: number; total: number } | null>(null);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState("");
  const [fontName, setFontName] = useState(0);   // re-renders the font line after a font file is added
  const cancel = useRef(false);
  const fontFile = useRef<HTMLInputElement>(null);
  const bgFile = useRef<HTMLInputElement>(null);

  // The handwriting font is also used for the text box, so what you type looks like what is drawn.
  useEffect(() => {
    try { const f = new FontFace("SukoHand", `url(${allFonts()[0].url})`); f.load().then(() => document.fonts.add(f)).catch(() => {}); } catch { /* the box keeps the system font */ }
    document.body.style.background = "#0b0d12";
    restoreFonts().then(() => setFontName(n => n + 1));   // fonts the owner uploaded earlier
  }, []);

  // Which export sizes this phone's encoder can make (asked, not encoded).
  useEffect(() => {
    let live = true; setResOk(null);
    if (mp4Supported()) supportedResolutions(fpsFor(resId, fps)).then(r => { if (live) setResOk(r); }).catch(() => {});
    return () => { live = false; };
  }, [fps, resId]);

  // Typing "20" must not restart the preview at "2": the length is applied when typing stops.
  useEffect(() => {
    const x = parseFloat(durText);
    if (!Number.isFinite(x) || x < 1) return;
    const id = setTimeout(() => setSeconds(Math.min(1800, Math.round(x * 10) / 10)), 400);
    return () => clearTimeout(id);
  }, [durText]);

  useEffect(() => {
    let live = true;
    const id = setTimeout(() => {
      buildTextSvg(tx, (w, h) => bgMarkup({ width: w, height: h } as Project, bg), Math.max(0.5, seconds - HOLD))
        .then(r => { if (live) { setScene(r); setError(""); } })
        .catch(e => { if (live) setError(e.message ?? "Could not draw the text."); });
    }, 60);
    return () => { live = false; clearTimeout(id); };
  }, [tx, bg, seconds]);

  const pos = posOf(scene);
  const set = (p: Partial<TextSettings>) => setTx(t => ({ ...t, ...p }));
  const nudge = (dx: number, dy: number) => set({ posX: clamp(Math.round((tx.posX ?? pos.x) + dx), 0, 100), posY: clamp(Math.round((tx.posY ?? pos.y) + dy), 0, 100) });
  const fonts = allFonts(), font = findFont(tx.fontId);
  void fontName;

  async function pickFont(f: File | undefined) {
    if (!f) return;
    try { const c = await uploadFont(f); set({ fontId: c.id }); setFontName(n => n + 1); }
    catch (e: any) { setError(e.message ?? "Could not use this font file."); }
  }
  function pickBg(f: File | undefined) {
    if (!f) return;
    const r = new FileReader();
    r.onload = () => {
      const dataUrl = String(r.result), im = new Image();
      im.onload = () => setBg({ kind: "image", image: { dataUrl, w: im.naturalWidth || 1, h: im.naturalHeight || 1, zoom: 1, offX: 0, offY: 0 } });
      im.onerror = () => setError("Could not read this background image.");
      im.src = dataUrl;
    };
    r.readAsDataURL(f);
  }
  const setImg = (k: "zoom" | "offX" | "offY", v: number) => setBg(b => (b.kind === "image" ? { kind: "image", image: { ...b.image, [k]: v } } : b));

  async function exportMp4() {
    if (!scene) return;
    setError(""); setSaved(""); cancel.current = false; setExporting({ done: 0, total: exportPlan(seconds, fpsFor(resId, fps), resId).total });
    try {
      const blob = await renderMp4({ svg: scene.svg, width: scene.width, height: scene.height, seconds, fps: fpsFor(resId, fps), resolution: resId, onProgress: (done, total) => setExporting({ done, total }), cancelled: () => cancel.current });
      const name = `text-${resId}-${fpsFor(resId, fps)}fps-${Math.round(seconds * 10) / 10}s.mp4`;
      download(name, blob);
      setSaved(name);
    } catch (e: any) { if (!cancel.current) setError(e.message ?? "MP4 export failed."); }
    setExporting(null);
  }

  const pct = exporting ? Math.round((exporting.done / Math.max(1, exporting.total)) * 100) : 0;
  const durOk = Number.isFinite(parseFloat(durText)) && parseFloat(durText) >= 1 && Math.min(1800, Math.round(parseFloat(durText) * 10) / 10) === seconds;
  const sizeMax = Math.max(24, Math.floor(scene?.fit ?? 300)), sizeNow = scene?.size ?? tx.px ?? 96;
  const sizeSet = (px: number) => set({ px: Math.round(clamp(px, 20, sizeMax)) });
  const fill = (v: number, lo: number, hi: number) => ({ ["--fill" as string]: `${((v - lo) / Math.max(1e-6, hi - lo)) * 100}%` });
  const onExport = tab === "export";

  return (
    <div className="te">
      <div className="tbar">
        <button className="back" onClick={goBack} aria-label="Back"><Chevron d="l" /></button>
        <h1>Text Editor</h1>
        <button className="cta" onClick={() => { setTab("export"); window.scrollTo({ top: 0 }); }}>Export</button>
      </div>

      <div className="body">
        {error && <p className="xerr">{error}</p>}
        <Player scene={scene} seconds={seconds} controls={!onExport} boxes={!onExport} badge={resId === "1080p" ? "1080p" : resId.toUpperCase()} onMove={(x, y) => set({ posX: x ?? tx.posX, posY: y ?? tx.posY })} onSize={px => sizeSet(px)} />

        <div className="tabrow" role="tablist">
          {TABS.map(x => <button key={x.id} role="tab" aria-selected={tab === x.id} className={tab === x.id ? "pill on" : "pill"} onClick={() => setTab(x.id)}>{x.label}</button>)}
        </div>

        <section className="card">
          {tab === "text" && (<>
            <label className="lab" htmlFor="te-text">Your text (a new line makes a new line on the picture)</label>
            <textarea id="te-text" className="field hand" rows={3} value={tx.text} placeholder="Type or paste a verse, heading or formula" onChange={e => set({ text: e.target.value })} />
            <div className="row between">
              <div><span className="lab">Font</span><b className="val">{font.id === "patrick" ? "Patrick Hand (built in)" : font.label}</b></div>
              <button className="pill" onClick={() => fontFile.current?.click()}>Add font file</button>
              <input ref={fontFile} type="file" accept={FONT_EXTENSIONS.join(",")} hidden onChange={e => { pickFont(e.target.files?.[0]); e.target.value = ""; }} />
            </div>
            {fonts.length > 1 && (
              <select className="field" value={tx.fontId} onChange={e => set({ fontId: e.target.value })} aria-label="Font">{fonts.map(f => <option key={f.id} value={f.id}>{f.label}</option>)}</select>
            )}
            <div className="row between"><span className="lab">Size</span><span className="val">{sizeFmt(sizeNow)} px</span></div>
            <input className="range" type="range" min="20" max={sizeMax} step="1" value={clamp(sizeNow, 20, sizeMax)} style={fill(clamp(sizeNow, 20, sizeMax), 20, sizeMax)} aria-label="Size" onChange={e => sizeSet(+e.target.value)} />
            <span className="lab">Position</span>
            <div className="row pos">
              <button className="pill grow" onClick={() => set({ posX: undefined, posY: undefined })}>Center</button>
              <button className="sq" aria-label="Move left" onClick={() => nudge(-STEP, 0)}><Chevron d="l" /></button>
              <button className="sq" aria-label="Move up" onClick={() => nudge(0, -STEP)}><Chevron d="u" /></button>
              <button className="sq" aria-label="Move down" onClick={() => nudge(0, STEP)}><Chevron d="d" /></button>
              <button className="sq" aria-label="Move right" onClick={() => nudge(STEP, 0)}><Chevron d="r" /></button>
            </div>
            <p className="note">Formulas: <code>x^2</code> (small raised), <code>x^{"{n+1}"}</code>, <code>H_2O</code> (small lowered). Symbols like √ ∑ π ≠ ≤ ∞ × ÷ work too.</p>
          </>)}

          {tab === "style" && (<>
            <span className="lab">Text color</span>
            <div className="swatches">
              {TEXT_COLORS.map(c => <button key={c} className={tx.color.toLowerCase() === c ? "sw on" : "sw"} style={{ background: c }} aria-label={`Text color ${c}`} onClick={() => set({ color: c })} />)}
              <label className="sw custom" aria-label="Pick another text color"><input type="color" value={tx.color} onChange={e => set({ color: e.target.value })} /></label>
            </div>
            <span className="lab">Background</span>
            <div className="swatches">
              {BG_COLORS.map(c => <button key={c} className={bg.kind === "color" && bg.color.toLowerCase() === c ? "sw on" : "sw"} style={{ background: c }} aria-label={`Background ${c}`} onClick={() => setBg({ kind: "color", color: c })} />)}
              <label className="sw custom" aria-label="Pick another background color"><input type="color" value={bg.kind === "color" ? bg.color : "#ffffff"} onChange={e => setBg({ kind: "color", color: e.target.value })} /></label>
              <button className={bg.kind === "image" ? "pill on small" : "pill small"} onClick={() => bgFile.current?.click()}>Use an image</button>
              <input ref={bgFile} type="file" accept="image/*" hidden onChange={e => { pickBg(e.target.files?.[0]); e.target.value = ""; }} />
            </div>
            {bg.kind === "image" && (<>
              <div className="row between"><span className="lab">Zoom</span><span className="val">{bg.image.zoom.toFixed(2)}x</span></div>
              <input className="range" type="range" min="1" max="3" step="0.01" value={bg.image.zoom} style={fill(bg.image.zoom, 1, 3)} aria-label="Zoom" onChange={e => setImg("zoom", +e.target.value)} />
              <span className="lab">Left / right</span>
              <input className="range" type="range" min="-0.5" max="0.5" step="0.01" value={bg.image.offX} style={fill(bg.image.offX, -0.5, 0.5)} aria-label="Left / right" onChange={e => setImg("offX", +e.target.value)} />
              <span className="lab">Up / down</span>
              <input className="range" type="range" min="-0.5" max="0.5" step="0.01" value={bg.image.offY} style={fill(bg.image.offY, -0.5, 0.5)} aria-label="Up / down" onChange={e => setImg("offY", +e.target.value)} />
            </>)}
            <span className="lab">Picture shape</span>
            <div className="row wrap">{SHAPES.map(s => <button key={s.id} className={tx.shape === s.id ? "pill on" : "pill"} onClick={() => set({ shape: s.id })}>{s.label}</button>)}</div>
            <span className="lab">Align</span>
            <div className="row">
              <button className={tx.align === "center" ? "pill on" : "pill"} onClick={() => set({ align: "center" })}>Centre</button>
              <button className={tx.align === "left" ? "pill on" : "pill"} onClick={() => set({ align: "left" })}>Left</button>
            </div>
          </>)}

          {tab === "motion" && (<>
            <span className="lab">How the text appears</span>
            <button className={tx.style === "write" ? "opt on" : "opt"} onClick={() => set({ style: "write" })}><b>Handwritten</b><small>The pen follows each stroke of every letter.</small></button>
            <button className={tx.style === "trace" ? "opt on" : "opt"} onClick={() => set({ style: "trace" })}><b>Outline, then fill</b><small>Each letter is traced by its outline, then filled in.</small></button>
            <p className="note">All the text is written in exactly the video length (set it in Export). Press play above to watch it.</p>
          </>)}

          {tab === "export" && (<>
            <h2>Export video</h2>
            <div className="row between"><span className="lab">Size</span><b className="val">{safeRes(resId).w} x {safeRes(resId).h}</b></div>
            <div className="row wrap">
              {RESOLUTIONS.map(r => <button key={r.id} className={resId === r.id ? "pill on" : "pill"} disabled={!!exporting} onClick={() => { setResId(r.id); setFps(f => fpsFor(r.id, f)); }}>{r.id === "1080p" ? "1080p" : r.id.toUpperCase()}{resOk && !resOk[r.id] ? " ✕" : ""}</button>)}
            </div>
            <div className="row"><span className="lab">Frame rate</span>
              {FPS_CHOICES.map(f => <button key={f} className={fps === f ? "pill on" : "pill"} disabled={!!exporting || f !== fpsFor(resId, f)} onClick={() => setFps(f)}>{f} fps</button>)}
            </div>
            <div className="row between"><label className="lab" htmlFor="te-len">Length (seconds)</label>
              <input id="te-len" className="field numf" type="text" inputMode="decimal" value={durText} disabled={!!exporting} onChange={e => setDurText(e.target.value)} />
            </div>
            <p className="inset">No upscaling here: text is drawn sharp at every size, so the Upscale images switch is not shown in this tool. About {mb(estimateBytes(seconds, fpsFor(resId, fps), resId))} for {Math.round(seconds)} s.{resId === "4k" ? " 4K is 30 fps only." : ""}{resOk && !resOk[resId] ? " This phone's encoder may not support this size at this frame rate; try a smaller size." : ""}{estimateBytes(seconds, fpsFor(resId, fps), resId) > 400e6 ? " Big file: it is built in memory, so a phone with little RAM may fail." : ""}</p>
            <button className="go" disabled={!mp4Supported() || !durOk || !scene || !!exporting} onClick={exportMp4}>Export video</button>
            <p className="lab">The MP4 is saved to Download/Suko.</p>
            {!mp4Supported() && <p className="xwarn">This phone's web view cannot make MP4 (no WebCodecs). Update "Android System WebView" in the Play Store.</p>}
            {saved && !exporting && <p className="ok">Saved {saved}</p>}
            {exporting && (
              <div className="xbusy">
                <span className="cap">While exporting</span>
                <p className="xwarn">Rendering frame {exporting.done} of {exporting.total}</p>
                <div className="track" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}><i style={{ width: `${pct}%` }} /></div>
                <div className="row between">
                  <p className="lab m0">Keep the app open and the phone plugged in.</p>
                  <button className="pill" onClick={() => { cancel.current = true; }}>Cancel</button>
                </div>
              </div>
            )}
          </>)}
        </section>
      </div>
    </div>
  );
}
