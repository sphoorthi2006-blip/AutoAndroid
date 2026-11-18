import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { detectPanels, panelsStatus, processImage } from "./api";
import { countNote, matchPanels, panelCheck, panelLayers, type FoundPanels } from "./panelmatch";
import { composeSvg, download, rasterizeIfSvg } from "./compose";
import { buildUnits, layerTimes, panelParts, parseSlidesFileChecked, planSlide, planTexts, readPics, reportCsv, reportText, slideReportText, type Pic, type ReportPart, type ReportRow, type SlidePlan, type SlideSpec, type TextFonts, type Unit, matchNotes } from "./slides";
import { findFont, loadFont, MATH_URL } from "./textanim";
import { FontPicker } from "./FontPicker";
import { ResolutionPicker } from "./ResolutionPicker";
import { DEFAULT_SKETCH, type Dir, type DrawStyle, type Entrance, type LinesMode, type Project, type SketchSettings } from "./types";
import { checkFinalFrame, mp4Supported, renderMp4Timeline } from "./video";
import { qualityVerdict } from "./quality";

const FPS = 30;

/** 0.15.3: the black frame (cut from the upload) is on the canvas from the start; it is not one of the animated units. */
const frameMarkup = (fr: { href: string; box: number[] }) => `<image href="${fr.href}" x="${fr.box[0]}" y="${fr.box[1]}" width="${fr.box[2]}" height="${fr.box[3]}" preserveAspectRatio="none"/>`;
interface Slide { key: string; n: number; name: string; file: File; thumb: string; status: "wait" | "busy" | "ok" | "error"; error?: string; panelNote?: string; project?: Project; units?: Unit[]; found?: FoundPanels | null }
interface Row { slide: Slide; spec?: SlideSpec; start: number; end: number; svg: string | null; width: number; height: number; plan?: SlidePlan; note: string; heading?: string; marks: number[]; parts?: ReportPart[] }

const fmt = (t: number) => { const s = Math.max(0, t), m = Math.floor(s / 60); return `${m}:${(s - m * 60).toFixed(1).padStart(4, "0")}`; };
const ENTRANCES: { id: Entrance; label: string }[] = [{ id: "draw", label: "Draw (hand-drawn)" }, { id: "fade", label: "Fade in" }, { id: "pop", label: "Pop in (bounce)" }, { id: "slide", label: "Move in" }, { id: "none", label: "Appear at once" }];

/* ------------------------------------------------------------------ the player: picture, play / pause, playhead ------------------------------------------------------------------ */

function Player({ rows, total, seekReq, onIndex }: { rows: Row[]; total: number; seekReq: { t: number; id: number } | null; onIndex: (i: number) => void }) {
  const [t, setT] = useState(0);
  const [playing, setPlaying] = useState(false);
  const tRef = useRef(0);
  const host = useRef<HTMLDivElement>(null);
  const track = useRef<HTMLDivElement>(null);
  const resume = useRef(false);

  const idx = useMemo(() => { let i = 0; for (let k = 0; k < rows.length; k++) if (rows[k].start <= t + 1e-6) i = k; return i; }, [rows, t]);
  const row = rows[idx];
  useEffect(() => { onIndex(idx); }, [idx]);

  const seek = (x: number) => { const v = Math.min(total, Math.max(0, x)); tRef.current = v; setT(v); };
  useEffect(() => { if (seekReq) seek(seekReq.t); }, [seekReq?.id]);
  useEffect(() => { if (tRef.current > total) seek(total); }, [total]);

  // the clock
  useEffect(() => {
    if (!playing) return;
    let raf = 0, last = performance.now();
    const tick = (now: number) => {
      const nt = tRef.current + (now - last) / 1000; last = now;
      if (nt >= total) { tRef.current = total; setT(total); setPlaying(false); return; }
      tRef.current = nt; setT(nt); raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [playing, total]);

  // put the drawing at time t: the animation is paused and moved to the exact moment (so pause and scrubbing are exact)
  useLayoutEffect(() => {
    const svg = host.current?.querySelector("svg") as SVGSVGElement | null;
    if (!svg || !row) return;
    svg.removeAttribute("width"); svg.removeAttribute("height");
    svg.style.width = "100%"; svg.style.height = "100%"; svg.style.display = "block";
    try { svg.pauseAnimations(); svg.setCurrentTime(Math.max(0, t - row.start)); } catch { /* not ready yet */ }
  });

  const toggle = () => { if (!playing && tRef.current >= total - 0.01) seek(0); setPlaying(p => !p); };
  const goSlide = (d: number) => {
    if (!rows.length) return;
    if (d < 0) seek(t - rows[idx].start > 1 ? rows[idx].start : rows[Math.max(0, idx - 1)].start);
    else seek(idx + 1 < rows.length ? rows[idx + 1].start : total);
  };

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement;
      if (el && /^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName)) return;
      if (e.code === "Space") { e.preventDefault(); toggle(); }
      else if (e.key === "ArrowLeft") { e.preventDefault(); seek(tRef.current - (e.shiftKey ? 5 : 1)); }
      else if (e.key === "ArrowRight") { e.preventDefault(); seek(tRef.current + (e.shiftKey ? 5 : 1)); }
      else if (e.key === "Home") seek(0);
      else if (e.key === "End") seek(total);
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  });

  const fromPointer = (e: React.PointerEvent) => { const r = track.current!.getBoundingClientRect(); seek(((e.clientX - r.left) / r.width) * total); };
  const pct = (x: number) => `${(x / Math.max(0.001, total)) * 100}%`;

  return (
    <>
      <div className="vview">
        {row && (row.svg
          ? <div ref={host} className="vscene" dangerouslySetInnerHTML={{ __html: row.svg }} />
          : <div className="vscene"><img src={row.slide.thumb} alt="" style={{ width: "100%", height: "100%", objectFit: "contain", opacity: .55 }} /><p className="busy">{row.slide.status === "error" ? "This image could not be read" : "Finding the elements…"}</p></div>)}
      </div>
      <div className="vbar">
        <button className="icon big" title="Previous image" aria-label="Previous image" onClick={() => goSlide(-1)}>⏮</button>
        <button className="big" title={playing ? "Pause (Space)" : "Play (Space)"} aria-label={playing ? "Pause" : "Play"} onClick={toggle}>{playing ? "⏸ Pause" : "▶ Play"}</button>
        <button className="icon big" title="Next image" aria-label="Next image" onClick={() => goSlide(1)}>⏭</button>
        <span className="vtime" aria-label="Time">{fmt(t)} / {fmt(total)}</span>
        <span className="vwhere">Image {row?.slide.n ?? "-"} of {rows.length}</span>
      </div>
      <div className="vtrack" ref={track} role="slider" aria-label="Playhead" aria-valuemin={0} aria-valuemax={Math.round(total)} aria-valuenow={Math.round(t)}
        onPointerDown={e => { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); resume.current = playing; setPlaying(false); fromPointer(e); }}
        onPointerMove={e => { if (e.buttons) fromPointer(e); }}
        onPointerUp={() => { if (resume.current && tRef.current < total) setPlaying(true); resume.current = false; }}>
        {rows.map((r, i) => <div key={r.slide.key} className={"vseg" + (i === idx ? " on" : "") + (r.slide.status === "error" ? " bad" : "")} style={{ left: pct(r.start), width: pct(r.end - r.start) }}><span>{r.slide.n}</span></div>)}
        {rows.flatMap(r => r.marks.map((m, j) => <i key={r.slide.key + "-" + j} className="vtick" style={{ left: pct(m) }} />))}
        <div className="vhead" style={{ left: pct(t) }} />
      </div>
    </>
  );
}

/* ------------------------------------------------------------------ the whole video mode ------------------------------------------------------------------ */

export default function VideoMode({ onError }: { onError: (m: string) => void }) {
  const [slides, setSlides] = useState<Slide[]>([]);
  const [specs, setSpecs] = useState<SlideSpec[] | null>(null);
  const [specNotes, setSpecNotes] = useState<string[]>([]);
  const [specName, setSpecName] = useState("");
  const [defSec, setDefSec] = useState(10);
  const [entrance, setEntrance] = useState<Entrance>("draw");
  const [style, setStyle] = useState<DrawStyle>("illustrate");
  const [lines, setLines] = useState<LinesMode>("auto");   // auto: panels one by one (lines, then colour), other pictures: all lines first, then all colour
  const [pen, setPen] = useState(0.5);   // share of each piece's time spent on the pen outline (the rest is colour)
  const [writeAll, setWriteAll] = useState(false);
  const [maxIdle, setMaxIdle] = useState(5);
  const [replaceText, setReplaceText] = useState(true);   // write headings and captions with handwriting (font replacement)
  const [fontId, setFontId] = useState("patrick");
  const [resId, setResId] = useState("1080p");   // step 24: export size
  const [textColor, setTextColor] = useState("#111111");
  const [fonts, setFonts] = useState<TextFonts | null>(null);
  const [bgColor, setBgColor] = useState("#ffffff");   // the video background: white by default, whatever colour the pictures had
  const [cur, setCur] = useState(0);
  const [seekReq, setSeekReq] = useState<{ t: number; id: number } | null>(null);
  const [exporting, setExporting] = useState<{ done: number; total: number } | null>(null);
  const [quality, setQuality] = useState<Record<string, string>>({});   // step 9: result of the last-frame check, by slide key
  const [audioFile, setAudioFile] = useState<File | null>(null);
  const [audioBuffer, setAudioBuffer] = useState<AudioBuffer | null>(null);
  const [audioLoading, setAudioLoading] = useState(false);
  const [elementSounds, setElementSounds] = useState(true);
  const audioRef = useRef<HTMLInputElement>(null);
  const cancel = useRef(false);
  const run = useRef(0);
  const picRef = useRef<HTMLInputElement>(null);
  const dirRef = useRef<HTMLInputElement>(null);
  const specRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let live = true;
    const f = findFont(fontId);
    Promise.all([loadFont(f.url), loadFont(MATH_URL)]).then(([main, math]) => { if (live) setFonts({ main, math }); }).catch(() => { if (live) setFonts(null); });
    return () => { live = false; };
  }, [fontId]);

  const setSlide = (key: string, patch: Partial<Slide>) => setSlides(s => s.map(x => (x.key === key ? { ...x, ...patch } : x)));

  async function processAll(list: Slide[]) {
    const id = ++run.current;
    for (const s of list) {
      if (run.current !== id) return;
      setSlide(s.key, { status: "busy" });
      try {
        const ready = await rasterizeIfSvg(s.file);
        const p = await processImage(ready);
        const found = await detectPanels(ready);   // v2: the panels, matched to the file's panels by order (null with an older backend)
        if (run.current !== id) return;
        setSlide(s.key, { status: "ok", project: p, units: buildUnits(p), found, panelNote: found?.panels.length ? "" : panelsStatus || "no panels were found in this picture" });
      } catch (e: any) {
        if (run.current !== id) return;
        setSlide(s.key, { status: "error", error: e?.message ?? "Could not read this image." });
      }
    }
  }

  /** Files dropped from Explorer: loose files, ZIPs, and whole folders (read folder by folder). */
  async function dropFiles(dt: DataTransfer) {
    const entries = Array.from(dt.items ?? []).map(i => (i as any).webkitGetAsEntry?.()).filter(Boolean);
    if (!entries.some((en: any) => en.isDirectory)) { void addFiles(Array.from(dt.files)); return; }
    const out: File[] = [];
    const walk = async (en: any): Promise<void> => {
      if (en.isFile) { out.push(await new Promise<File>((ok, no) => en.file(ok, no))); return; }
      const rd = en.createReader();
      for (;;) {
        const batch: any[] = await new Promise((ok, no) => rd.readEntries(ok, no));
        if (!batch.length) break;
        for (const c of batch) await walk(c);
      }
    };
    try { for (const en of entries) await walk(en); } catch { /* fall through */ }
    void addFiles(out.length ? out : Array.from(dt.files));
  }

  async function addFiles(files: File[]) {
    onError("");
    const timing = files.filter(f => /\.(json|txt)$/i.test(f.name));
    const pics = files.filter(f => !timing.includes(f));
    try {
      for (const f of timing) { { const r = parseSlidesFileChecked(await f.text(), defSec); setSpecs(r.specs); setSpecNotes(r.notes); } setSpecName(f.name); }
      if (pics.length) {
        const found: Pic[] = await readPics(pics);
        if (!found.length) throw new Error("No pictures found. Use a ZIP of PNG, JPG or WebP files, or pick the pictures themselves.");
        slides.forEach(s => URL.revokeObjectURL(s.thumb));
        const list: Slide[] = found.map((p, i) => ({ key: `${Date.now()}-${i}`, n: p.n ?? i + 1, name: p.name, file: p.file, thumb: URL.createObjectURL(p.file), status: "wait" }));
        setSlides(list); setCur(0); setSeekReq({ t: 0, id: Date.now() });
        void processAll(list);
      }
    } catch (e: any) { onError(e?.message ?? "Could not open these files."); }
  }

  const sk: SketchSettings = useMemo(() => ({ ...DEFAULT_SKETCH, entrance, style, pen, lines, dir: "left" as Dir, enterTime: 0.6, min: 0.02, max: 600, fit: false, target: 0 }), [entrance, style, pen, lines]);

  // batches: pictures and timing file are matched by number; say what does not line up (for example pictures 1-20 with a file for 21-40)
  const batchNotes = useMemo(() => (specs ? matchNotes(slides.map(s => s.n), specs) : []), [slides, specs]);

  const rows: Row[] = useMemo(() => {
    const out: Row[] = [];
    let prevEnd = 0;
    for (const s of slides) {
      const spec = specs?.find(x => x.n === s.n);
      const start = spec ? spec.start : prevEnd;
      const end = spec ? spec.end : start + defSec;
      prevEnd = end;
      const heading = spec?.heading;
      const prevHeading = out.length ? out[out.length - 1].heading : undefined;
      let svg: string | null = null, plan: SlidePlan | undefined, note = "", marks: number[] = [], parts: ReportPart[] | undefined;
      let width = 1920, height = 1080;
      if (specs && !spec) note = `No timing for image ${s.n} in the file, ${defSec} s used.`;
      if (s.status === "ok" && s.project && s.units) {
        // v2 step 10: with panels found (and their raster crops) every panel is ONE unit; otherwise the old element logic (units from processImage) is used
        const pmatch = s.found?.panels.length ? matchPanels(s.found, spec?.panels) : null;
        const pl = s.found && pmatch ? panelLayers(s.found, pmatch) : null;
        const p = pl ? { ...s.project, layers: pl, order: undefined, bgRasterHref: undefined } : s.project, units = pl ? buildUnits(p) : s.units;
        width = p.width; height = p.height;
        const writeHeading = writeAll || !(heading !== undefined && heading === prevHeading);
        const local = spec && spec.elements.length ? spec.elements.map(e => e - start) : null;
        plan = planSlide(units, p.width, p.height, end - start, local, { writeHeading, maxIdle });
        const times = layerTimes(plan, p);
        if (pl) parts = panelParts(plan, p, times, spec?.panels, start);
        const tx = replaceText && fonts ? planTexts(plan, p, times, spec ? { heading: pl ? undefined : spec.heading, captions: spec.captions, panels: pl ? (spec.panels ?? spec.captions?.map(c => ({ label: c, start: 0 }))) : undefined } : undefined, end - start, fonts, textColor, "write") : null;
        svg = composeSvg(p, { kind: "color", color: bgColor }, { sketch: sk, times, skip: tx?.skip, extra: (tx?.markup ?? "") + (pl && s.found?.frameRaster ? frameMarkup(s.found.frameRaster) : "") });
        marks = plan.items.filter(i => i.unit.kind === "item").map(i => start + i.begin);
        const want = spec?.elements.length || spec?.expected;
        const pm = spec?.panels && s.found?.panels.length ? matchPanels(s.found, spec.panels) : null;   // version 2 file + panels found: count the panels
        const cnt = pm ? pm.notes.join(" ") : countNote(plan.main, want);
        if (cnt) note = cnt;
      }
      if (s.status === "ok" && s.panelNote) note = `Panels not used: ${s.panelNote}. The old element path is used instead.`;
      if (s.status === "error") note = s.error ?? "";
      out.push({ slide: s, spec, start, end, svg, width, height, plan, note, heading, marks, parts });
    }
    return out;
  }, [slides, specs, defSec, writeAll, maxIdle, sk, bgColor, replaceText, fonts, textColor]);

  const total = rows.length ? rows[rows.length - 1].end : 0;
  const done = slides.filter(s => s.status === "ok" || s.status === "error").length;
  const failed = slides.filter(s => s.status === "error").length;
  const ready = slides.length > 0 && done === slides.length && failed === 0;

  async function exportMp4() {
    onError(""); cancel.current = false;
    const frames = Math.round(total * FPS);
    setExporting({ done: 0, total: frames });
    try {
      const soundEvents = elementSounds ? rows.flatMap(r => (r.plan?.items ?? [])
        .filter(item => item.dur > 0)
        .map(item => ({ time: r.start + item.begin }))) : [];
      let clickSound: AudioBuffer | null = null;
      if (soundEvents.length) {
        const Ctx = window.AudioContext || (window as any).webkitAudioContext;
        if (!Ctx) throw new Error("Audio playback is not available in this browser.");
        const clickContext = new Ctx();
        try {
          const response = await fetch(`${import.meta.env.BASE_URL}click.wav`);
          if (!response.ok) throw new Error("The bundled click.wav sound could not be loaded.");
          clickSound = await clickContext.decodeAudioData(await response.arrayBuffer());
        } finally { await clickContext.close(); }
      }
      const blob = await renderMp4Timeline({
        resolution: resId,
        slides: rows.map(r => ({ svg: r.svg!, width: r.width, height: r.height, start: r.start, end: r.end })),
        audio: audioBuffer,
        clickSound,
        soundEvents,
        onProgress: (d, t) => setExporting({ done: d, total: t }), cancelled: () => cancel.current,
      });
      download(`video-${resId}-${Math.round(total)}s.mp4`, blob);
    } catch (e: any) { if (!cancel.current) onError(e?.message ?? "MP4 export failed."); }
    setExporting(null);
  }

  const pct = exporting ? Math.round((exporting.done / Math.max(1, exporting.total)) * 100) : 0;
  const busy = slides.length > 0 && done < slides.length;

  return (
    <main className="vmain">
      <section className="vstage" onDragOver={e => e.preventDefault()} onDrop={e => { e.preventDefault(); void dropFiles(e.dataTransfer); }}>
        {rows.length > 0
          ? <Player rows={rows} total={total} seekReq={seekReq} onIndex={setCur} />
          : (
            <div className="vview"><div className="empty vempty" onClick={() => picRef.current?.click()}>
              <p>Drop the images (ZIP, a folder, or the pictures) and the timing JSON here</p>
              <span>{specs ? `Timing loaded (${specs.length} images). Now add the images.` : "Or use the two buttons on the right"}</span>
            </div></div>
          )}
      </section>

      <aside className="vside">
        <h2>Import</h2>
        <div className="row wrap">
          <button className="ghost" disabled={!!exporting} onClick={() => picRef.current?.click()}>{slides.length ? "Change images" : "Images (ZIP)"}</button>
          <button className="ghost" disabled={!!exporting} onClick={() => dirRef.current?.click()}>Images (folder)</button>
          <button className="ghost" disabled={!!exporting} onClick={() => specRef.current?.click()}>{specs ? "Change JSON" : "Timing (JSON)"}</button>
        </div>
        <input ref={picRef} type="file" multiple accept=".zip,image/*,.svg" hidden onChange={e => { void addFiles(Array.from(e.target.files ?? [])); e.target.value = ""; }} />
        <input ref={dirRef} type="file" hidden {...({ webkitdirectory: "", directory: "" } as any)} onChange={e => { void addFiles(Array.from(e.target.files ?? [])); e.target.value = ""; }} />
        <input ref={specRef} type="file" accept=".json,.txt" hidden onChange={e => { void addFiles(Array.from(e.target.files ?? [])); e.target.value = ""; }} />
        <p className="hint small">
          {slides.length ? `${slides.length} images` : "No images yet"}{specs ? `, timing for ${specs.length} images (${specName})` : ", no timing file"}
          {busy ? ` . Finding elements: ${done} of ${slides.length}` : ""}{failed ? ` . ${failed} could not be read` : ""}
        </p>
        {specs && specNotes.length + batchNotes.length > 0 && (
          <div className="hint small" style={{ color: "#b45309" }}>
            <b>Timing file notes:</b>
            <ul style={{ margin: "4px 0 0 16px", padding: 0 }}>{[...batchNotes, ...specNotes].slice(0, 6).map((n, i) => <li key={i}>{n}</li>)}{specNotes.length + batchNotes.length > 6 ? <li>and {specNotes.length + batchNotes.length - 6} more</li> : null}</ul>
          </div>
        )}
        {!specs && slides.length > 0 && (
          <div className="row"><label className="inl">Seconds per image <input className="num" type="number" min="1" max="300" style={{ width: 70 }} value={defSec} onChange={e => setDefSec(Math.min(300, Math.max(1, +e.target.value || 10)))} /></label></div>
        )}

        <h2>Audio</h2>
        <div className="row wrap">
          <button className="ghost" disabled={!!exporting || audioLoading} onClick={() => audioRef.current?.click()}>{audioFile ? "Change audio" : "Add audio"}</button>
          {audioFile && <button className="ghost" disabled={!!exporting || audioLoading} onClick={() => { setAudioFile(null); setAudioBuffer(null); }}>Remove audio</button>}
        </div>
        <input ref={audioRef} type="file" accept="audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac,.opus" hidden onChange={async e => {
          const file = e.target.files?.[0]; e.target.value = ""; if (!file) return;
          setAudioLoading(true);
          try {
            const Ctx = window.AudioContext || (window as any).webkitAudioContext;
            if (!Ctx) throw new Error("Audio decoding is not available in this browser.");
            const ctx = new Ctx();
            try { const decoded = await ctx.decodeAudioData(await file.arrayBuffer()); setAudioFile(file); setAudioBuffer(decoded); }
            finally { await ctx.close(); }
          } catch (err: any) { onError(`Could not read ${file.name}: ${err?.message ?? "unsupported audio format"}`); }
          finally { setAudioLoading(false); }
        }} />
        <p className="hint small">{audioLoading ? "Reading audio…" : audioFile ? `${audioFile.name} — audio will be synchronized to the video, trimmed if longer, or padded with silence if shorter.` : "Add a voice-over, music track, or other audio file. Common formats are supported when your device can decode them."}</p>
        <label className="chk"><input type="checkbox" checked={elementSounds} disabled={!!exporting} onChange={e => setElementSounds(e.target.checked)} /> Use the bundled click.wav for every element (headings, arrows and illustrations)</label>

        <h2>Animation</h2>
        <div className="row"><label className="inl">Background <input type="color" value={bgColor} onChange={e => setBgColor(e.target.value)} style={{ width: 44, height: 26, padding: 0, border: "1px solid #bbb" }} /></label>
          {bgColor.toLowerCase() !== "#ffffff" && <button className="ghost" onClick={() => setBgColor("#ffffff")}>White</button>}</div>
        <div className="sliders">
          <label>Entrance <select value={entrance} onChange={e => setEntrance(e.target.value as Entrance)}>{ENTRANCES.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
          {entrance === "draw" && <label>Draw style <select value={style} onChange={e => setStyle(e.target.value as DrawStyle)}><option value="illustrate">Follow the lines</option><option value="reveal">Reveal top to bottom</option></select></label>}
          {entrance === "draw" && <label>Lines then colour <select value={lines} onChange={e => setLines(e.target.value as LinesMode)}><option value="auto">Auto (panels one by one, other pictures all lines first)</option><option value="each">One piece at a time: its lines, then its colour</option><option value="all">All lines first, then all colour</option><option value="off">Off (outline and colour together)</option></select></label>}
          {entrance === "draw" && <label>{lines !== "off" ? "Lines share" : "Pen share"} <span className="inl"><input type="range" min="10" max="90" step="5" value={Math.round(pen * 100)} onChange={e => setPen(+e.target.value / 100)} /> {Math.round(pen * 100)}% pen, {100 - Math.round(pen * 100)}% colour</span></label>}
          <label>Longest idle <span className="inl"><input className="num" type="number" min="1" max="30" style={{ width: 56 }} value={maxIdle} onChange={e => setMaxIdle(Math.min(30, Math.max(1, +e.target.value || 5)))} /> s</span></label>
          <label className="chk"><input type="checkbox" checked={replaceText} onChange={e => setReplaceText(e.target.checked)} /> Write headings and captions with handwriting</label>
          {replaceText && <FontPicker value={fontId} onChange={setFontId} disabled={!!exporting} />}
          {replaceText && <label className="inl">Text colour <input type="color" value={textColor} onChange={e => setTextColor(e.target.value)} style={{ width: 44, height: 26, padding: 0, border: "1px solid #bbb" }} /></label>}
          <label className="chk"><input type="checkbox" checked={writeAll} onChange={e => setWriteAll(e.target.checked)} /> Write the heading again on every image</label>
        </div>

        <h2>Images <small>{rows.length ? `${fmt(total)} in total` : ""}</small></h2>
        <ul className="vlist">
          {rows.map((r, i) => (
            <li key={r.slide.key} className={(i === cur ? "sel " : "") + r.slide.status} onClick={() => setSeekReq({ t: r.start, id: Date.now() })}>
              <img className="thumb" src={r.slide.thumb} alt="" />
              <span className="meta">
                <b>Image {r.slide.n} <small>{fmt(r.start)} to {fmt(r.end)}</small></b>
                <small>{r.heading ?? r.slide.name}</small>
                {r.spec?.text && <small title={r.spec.text} style={{ opacity: 0.75, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: 220 }}>{r.spec.text}</small>}
                {r.spec?.layout && <small>{r.spec.system ? r.spec.system + " " : ""}{r.spec.layout}{r.spec.panels ? `, ${r.spec.panels.length} panels` : ""}</small>}
                {r.slide.status === "busy" && <small>finding elements…</small>}
                {r.slide.status === "wait" && <small>waiting</small>}
                {r.slide.status === "ok" && r.plan && <small>{r.plan.main} elements{r.plan.fromJson ? ", times from the file" : ", spread evenly"}</small>}
                {r.note && <small className="warnline">{r.note}</small>}
              </span>
            </li>
          ))}
        </ul>

        {(() => {   // v2 step 13: numbered outlines over the upload, layout name, panels found vs in the file
          const r = rows[cur];
          if (!r?.slide.found?.panels.length) return null;
          const chk = panelCheck(r.slide.found, matchPanels(r.slide.found, r.spec?.panels), r.spec);
          const col = (o: { listed: boolean; mismatch: boolean }) => o.mismatch ? "#d97706" : o.listed ? "#16a34a" : "#dc2626";
          const fs = Math.max(chk.width, chk.height) / 28;
          return (<>
            <h2>Panel check <small>image {r.slide.n}</small></h2>
            <div className="pcheck" style={{ aspectRatio: `${chk.width} / ${chk.height}` }}>
              <img src={r.slide.thumb} alt="" />
              <svg viewBox={`0 0 ${chk.width} ${chk.height}`} preserveAspectRatio="none">
                {chk.outlines.map(o => (<g key={o.k}>
                  <rect x={o.bbox[0]} y={o.bbox[1]} width={o.bbox[2]} height={o.bbox[3]} fill="none" stroke={col(o)} strokeWidth={fs / 8} strokeDasharray={o.listed ? undefined : `${fs / 3} ${fs / 4}`} />
                  <circle cx={o.bbox[0] + fs * 0.7} cy={o.bbox[1] + fs * 0.7} r={fs * 0.62} fill={col(o)} />
                  <text x={o.bbox[0] + fs * 0.7} y={o.bbox[1] + fs * 0.7} fontSize={fs * 0.8} fill="#fff" textAnchor="middle" dominantBaseline="central" fontWeight="700">{o.k}</text>
                </g>))}
                {chk.arrows.map((a, i) => <rect key={"a" + i} x={a.bbox[0]} y={a.bbox[1]} width={a.bbox[2]} height={a.bbox[3]} fill="none" stroke="#2563eb" strokeWidth={fs / 12} strokeDasharray={`${fs / 4} ${fs / 4}`} />)}
              </svg>
            </div>
            <small className={chk.level === "warn" ? "warnline" : undefined}>{chk.layoutText && <>Layout: {chk.layoutText}. </>}{chk.countText}</small>
            <small style={{ display: "block" }}>Green = listed in the file, amber = shape differs from the file, red dashed = not in the file. Blue dashes = arrows.</small>
            {chk.notes.filter(n => n !== chk.countText).map((n, i) => <small key={i} className="warnline" style={{ display: "block" }}>{n}</small>)}
          </>);
        })()}

        <h2>Report <small>order and times per image</small></h2>
        {(() => {
          const reps: ReportRow[] = rows.map(r => ({ n: r.slide.n, start: r.start, end: r.end, heading: r.heading, status: r.slide.status, elements: r.spec?.elements, captions: r.spec?.captions, plan: r.plan, note: r.note, parts: r.parts }));
          return (<>
            <div className="row">
              <button className="ghost" disabled={!rows.length} onClick={() => download("timing-report.txt", new Blob([reportText(reps)], { type: "text/plain" }))}>Download report (.txt)</button>
              <button className="ghost" disabled={!rows.length} onClick={() => download("timing-report.csv", new Blob(["\ufeff" + reportCsv(reps)], { type: "text/csv" }))}>Download (.csv)</button>
            </div>
            {reps[cur] && <pre className="report">{slideReportText(reps[cur])}</pre>}
            {rows[cur]?.svg && (<div className="row">
              <button className="ghost" onClick={async () => {
                const r = rows[cur]; const key = r.slide.key;
                setQuality(q => ({ ...q, [key]: "Checking..." }));
                try {
                  const regions = r.slide.found?.panels.length ? r.slide.found.panels.map(p => ({ x: p.bbox[0], y: p.bbox[1], w: p.bbox[2], h: p.bbox[3] })) : undefined;
                  const d = await checkFinalFrame({ svg: r.svg!, width: r.width, height: r.height, start: r.start, end: r.end }, r.slide.file, regions);
                  setQuality(q => ({ ...q, [key]: qualityVerdict(d, regions ? "the panels of the upload" : "the upload").text }));
                } catch (e) { setQuality(q => ({ ...q, [key]: "Could not check: " + (e as Error).message })); }
              }}>Check last frame against the picture</button>
              {quality[rows[cur].slide.key] && <span className="hint small">{quality[rows[cur].slide.key]}</span>}
            </div>)}
          </>);
        })()}

        <h2>Export</h2>
        {!exporting && <ResolutionPicker value={resId} onChange={setResId} fps={FPS} seconds={total} />}
        {!exporting && <button style={{ marginTop: 8 }} onClick={exportMp4} disabled={!mp4Supported() || !ready || !rows.length || audioLoading}>Export {resId === "1080p" ? "1080p" : resId.toUpperCase()} MP4 ({fmt(total)})</button>}
        {exporting && (
          <div>
            <div className="bar"><div style={{ width: `${pct}%` }} /></div>
            <p className="hint small">Making the video: frame {exporting.done} of {exporting.total} ({pct}%). Keep this window open.</p>
            <button className="ghost" onClick={() => { cancel.current = true; }}>Cancel</button>
          </div>
        )}
        {!mp4Supported() && <p className="hint small">This browser cannot make MP4. Use Chrome or Edge.</p>}
        <p className="hint small" style={{ marginTop: 8 }}>Exports one MP4 with all images, the selected audio track, and optional element-specific sound effects, synchronized to the slide timeline.</p>
      </aside>
    </main>
  );
}
