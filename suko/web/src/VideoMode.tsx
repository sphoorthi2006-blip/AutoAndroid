import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { analyseImage } from "./api";
import { countNote, matchPanels, panelCheck, panelLayers, type FoundPanels } from "./panelmatch";
import { composeSvg, download, rasterizeIfSvg } from "./compose";
import { buildUnits, layerTimes, panelParts, parseSlidesFileChecked, planSlide, planTexts, readClips, readPics, clipRange, clipNotes, sceneSpans, sceneTimes, reportCsv, reportText, slideReportText, type Clip, type Pic, type ReportPart, type ReportRow, type SlidePlan, type SlideSpec, type TextFonts, type Unit, matchNotes } from "./slides";
import { findFont, loadFont, MATH_URL } from "./textanim";
import { buildTextSlide, resolveTextSlide, type KindChoice, type KindOverride, type TextSlideKind } from "./textslide";
import { ResolutionPicker } from "./ResolutionPicker";
import { DEFAULT_SKETCH, type Dir, type DrawStyle, type Entrance, type LinesMode, type Project, type SketchSettings } from "./types";
import { checkFinalFrame, mp4Supported, renderMp4Timeline } from "./video";
import { qualityVerdict } from "./quality";
import { cancelNative, goBack, hasHost, keepAlive, nativeCall, toBase64 } from "./bridge";
import { clearProject, deleteAssets, getAsset, getMeta, listAssetIds, putAsset, putMeta, storeAvailable, type ProjectMeta } from "./projectStore";
import "./VideoMode.css";

const FPS = 30;

/** 0.15.3: the black frame (cut from the upload) is on the canvas from the start; it is not one of the animated units. */
const frameMarkup = (fr: { href: string; box: number[] }) => `<image href="${fr.href}" x="${fr.box[0]}" y="${fr.box[1]}" width="${fr.box[2]}" height="${fr.box[3]}" preserveAspectRatio="none"/>`;
interface Slide { key: string; n: number; name: string; file: File; thumb: string; status: "wait" | "busy" | "ok" | "error"; error?: string; panelNote?: string; upscaled?: boolean; project?: Project; units?: Unit[]; found?: FoundPanels | null }
interface Row { slide: Slide; spec?: SlideSpec; start: number; end: number; svg: string | null; width: number; height: number; plan?: SlidePlan; note: string; heading?: string; marks: number[]; parts?: ReportPart[];
  /** New workflow: a heading or subscribe slide is a white screen with handwritten black Patrick Hand words (no picture, no zoom). */
  textKind?: TextSlideKind; words?: string;
  /** New workflow step 5: a scene row. The clip replaces the pictures of `cues`; it stays a video (no SVG). */
  scene?: { clip: Clip; cues: number[] } }

/** The moments the click sound plays: at the start of every heading / subscribe / scene row and at every drawn unit of a picture. Used by the preview and by the export, so they agree. */
const clickEvents = (R: Row[], on: boolean): { time: number }[] => on ? R.flatMap(r => r.textKind || r.scene ? [{ time: r.start }] : (r.plan?.items ?? []).filter(item => item.dur > 0).map(item => ({ time: r.start + item.begin }))) : [];

const fmt = (t: number) => { const s = Math.max(0, t), m = Math.floor(s / 60); return `${m}:${(s - m * 60).toFixed(1).padStart(4, "0")}`; };
const ENTRANCES: { id: Entrance; label: string }[] = [{ id: "draw", label: "Draw (hand-drawn)" }, { id: "fade", label: "Fade in" }, { id: "pop", label: "Pop in (bounce)" }, { id: "slide", label: "Move in" }, { id: "none", label: "Appear at once" }];


type IconName = "back" | "play" | "pause" | "prev" | "next" | "image" | "folder" | "clock" | "music" | "film";
/** Small line icons (no emoji, so the buttons look the same on every phone). */
function Icon({ n, size = 22 }: { n: IconName; size?: number }) {
  const p = { width: size, height: size, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round" as const, strokeLinejoin: "round" as const, "aria-hidden": true };
  switch (n) {
    case "back": return <svg {...p}><path d="M15 5l-7 7 7 7" /></svg>;
    case "play": return <svg {...p}><path d="M8 5v14l11-7z" fill="currentColor" /></svg>;
    case "pause": return <svg {...p}><rect x="6" y="5" width="4" height="14" rx="1.2" fill="currentColor" /><rect x="14" y="5" width="4" height="14" rx="1.2" fill="currentColor" /></svg>;
    case "prev": return <svg {...p}><path d="M6 5v14" /><path d="M19 5L9 12l10 7z" fill="currentColor" /></svg>;
    case "next": return <svg {...p}><path d="M18 5v14" /><path d="M5 5l10 7-10 7z" fill="currentColor" /></svg>;
    case "image": return <svg {...p}><rect x="3" y="4" width="18" height="16" rx="3" /><circle cx="9" cy="10" r="1.7" /><path d="M4 18l5-5 4 4 3-3 4 4" /></svg>;
    case "folder": return <svg {...p}><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /></svg>;
    case "clock": return <svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></svg>;
    case "music": return <svg {...p}><path d="M9 18V6l10-2v12" /><circle cx="7" cy="18" r="2.5" /><circle cx="17" cy="16" r="2.5" /></svg>;
    case "film": return <svg {...p}><rect x="3" y="5" width="18" height="14" rx="2.5" /><path d="M10 9.5l5 2.5-5 2.5z" fill="currentColor" /></svg>;
  }
}

/* ------------------------------------------------------------------ the player: picture, play / pause, playhead ------------------------------------------------------------------ */

/** New workflow step 6: the scene clip plays muted inside the preview, letterboxed (the whole clip is visible, black bars), in step with the playhead.
 *  `lt` is the time inside the slot (playhead minus the row's start). Past the end of the clip the last frame stays; a clip longer than its slot is simply cut. */
function SceneVideo({ file, lt, playing }: { file: File; lt: number; playing: boolean }) {
  const ref = useRef<HTMLVideoElement>(null);
  const url = useMemo(() => URL.createObjectURL(file), [file]);
  useEffect(() => () => URL.revokeObjectURL(url), [url]);
  useEffect(() => {
    const v = ref.current; if (!v) return;
    if (playing) { void v.play().catch(() => { /* the browser may refuse; the picture still follows the playhead */ }); } else v.pause();
  }, [playing, url]);
  useEffect(() => {
    const v = ref.current; if (!v) return;
    const d = Number.isFinite(v.duration) ? v.duration : Infinity, target = Math.max(0, Math.min(lt, d - 0.04));
    if (playing ? Math.abs(v.currentTime - target) > 0.3 : Math.abs(v.currentTime - target) > 0.02) { try { v.currentTime = target; } catch { /* not ready yet */ } }
  }, [lt, playing, url]);
  return <video ref={ref} src={url} muted playsInline preload="auto" onLoadedMetadata={e => { try { (e.currentTarget).currentTime = Math.max(0, lt); } catch { /* ignore */ } }}
    style={{ width: "100%", height: "100%", objectFit: "contain", background: "#000", display: "block" }} />;
}

function Player({ rows, total, seekReq, onIndex, audio, clicks }: { rows: Row[]; total: number; seekReq: { t: number; id: number } | null; onIndex: (i: number) => void; audio: AudioBuffer | null; clicks: number[] }) {
  const [t, setT] = useState(0);
  const [seekVer, setSeekVer] = useState(0);   // bumps on every jump of the playhead, so the sound restarts from the new place
  const [playing, setPlaying] = useState(false);
  const tRef = useRef(0);
  const host = useRef<HTMLDivElement>(null);
  const track = useRef<HTMLDivElement>(null);
  const resume = useRef(false);

  const idx = useMemo(() => { let i = 0; for (let k = 0; k < rows.length; k++) if (rows[k].start <= t + 1e-6) i = k; return i; }, [rows, t]);
  const row = rows[idx];
  useEffect(() => { onIndex(idx); }, [idx]);

  const seek = (x: number) => { const v = Math.min(total, Math.max(0, x)); tRef.current = v; setT(v); setSeekVer(n => n + 1); };
  useEffect(() => { if (seekReq) seek(seekReq.t); }, [seekReq?.id]);
  useEffect(() => { if (tRef.current > total) seek(total); }, [total]);

  // sound in the preview: the voice-over and the click sounds play from the playhead while it runs (before this, audio was only used by the MP4 export)
  const ctxRef = useRef<AudioContext | null>(null);
  const clickBuf = useRef<AudioBuffer | null>(null);
  const clicksRef = useRef<number[]>(clicks); clicksRef.current = clicks;   // read when playing starts; a changing list must not restart the voice
  useEffect(() => () => { void ctxRef.current?.close().catch(() => { /* already closed */ }); ctxRef.current = null; }, []);
  useEffect(() => {
    if (!playing) return;
    const Ctx = window.AudioContext || (window as any).webkitAudioContext;
    if (!Ctx) return;
    if (!ctxRef.current) { try { ctxRef.current = new Ctx(); } catch { return; } }
    const ctx = ctxRef.current!;
    let live = true;
    const nodes: AudioBufferSourceNode[] = [];
    const play = (buf: AudioBuffer, when: number, offset = 0) => { const n = ctx.createBufferSource(); n.buffer = buf; n.connect(ctx.destination); n.start(when, offset); nodes.push(n); };
    (async () => {
      try { await ctx.resume(); } catch { /* the page may not be allowed to start sound yet */ }
      if (!live || ctx.state !== "running") return;
      let at = tRef.current;                           // where the picture is NOW (resuming can take a moment), so voice and picture start together
      if (audio && at < audio.duration) play(audio, ctx.currentTime, at);
      if (clicksRef.current.length) {
        if (!clickBuf.current) { try { const r = await fetch(`${import.meta.env.BASE_URL}click.wav`); clickBuf.current = await ctx.decodeAudioData(await r.arrayBuffer()); } catch { return; } }
        if (!live || !clickBuf.current) return;
        at = tRef.current; const now = ctx.currentTime;
        for (const c of clicksRef.current) if (c >= at) play(clickBuf.current, now + (c - at));
      }
    })();
    return () => { live = false; nodes.forEach(n => { try { n.stop(); } catch { /* not started */ } }); };
  }, [playing, audio, seekVer]);

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
      <div className="se-view">
        {row && (row.scene
          ? <div className="se-scene"><SceneVideo file={row.scene.clip.file} lt={t - row.start} playing={playing} /></div>
          : row.svg
          ? <div ref={host} className="se-scene" dangerouslySetInnerHTML={{ __html: row.svg }} />
          : <div className="se-scene"><img src={row.slide.thumb} alt="" style={{ width: "100%", height: "100%", objectFit: "contain", opacity: .55 }} /><p className="busy">{row.slide.status === "error" ? "This image could not be read" : row.slide.thumb ? "Finding the elements…" : "No picture for this image"}</p></div>)}
      </div>
      <div className="se-ctl">
        <button className="se-round" title="Previous image" aria-label="Previous image" onClick={() => goSlide(-1)}><Icon n="prev" size={18} /></button>
        <button className="se-play" title={playing ? "Pause (Space)" : "Play (Space)"} aria-label={playing ? "Pause" : "Play"} onClick={toggle}><Icon n={playing ? "pause" : "play"} size={24} /></button>
        <button className="se-round" title="Next image" aria-label="Next image" onClick={() => goSlide(1)}><Icon n="next" size={18} /></button>
        <span className="se-time" aria-label="Time">{fmt(t)} <i>/ {fmt(total)}</i></span>
        <span className="se-chip" title="Current image" aria-label={`Image ${row?.slide.n ?? "-"} of ${rows.length}`}>{row?.slide.n ?? "-"} / {rows.length}</span>
      </div>
      <div className="se-track" ref={track} role="slider" aria-label="Playhead" aria-valuemin={0} aria-valuemax={Math.round(total)} aria-valuenow={Math.round(t)}
        onPointerDown={e => { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); resume.current = playing; setPlaying(false); fromPointer(e); }}
        onPointerMove={e => { if (e.buttons) fromPointer(e); }}
        onPointerUp={() => { if (resume.current && tRef.current < total) setPlaying(true); resume.current = false; }}>
        {rows.map((r, i) => <div key={r.slide.key} className={"se-seg" + (i === idx ? " on" : "") + (r.slide.status === "error" ? " bad" : "")} style={{ left: pct(r.start), width: pct(r.end - r.start) }}><span>{r.slide.n}</span></div>)}
        {rows.flatMap(r => r.marks.map((m, j) => <i key={r.slide.key + "-" + j} className="se-tick" style={{ left: pct(m) }} />))}
        <div className="se-head" style={{ left: pct(t) }} />
      </div>
    </>
  );
}

/* ------------------------------------------------------------------ the whole video mode ------------------------------------------------------------------ */

type Tab = "import" | "style" | "audio" | "images" | "export";
const TABS: { id: Tab; label: string }[] = [{ id: "import", label: "Import" }, { id: "style", label: "Style" }, { id: "audio", label: "Audio" }, { id: "images", label: "Images" }, { id: "export", label: "Export" }];

/** `standalone` = the SVG Editor screen inside the app (own top bar, own error line, fills the window). Without it (Image Layer Studio in a browser) the old page header is used. */
export default function VideoMode({ onError, standalone = false }: { onError?: (m: string) => void; standalone?: boolean }) {
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
  const [replaceText, setReplaceText] = useState(false);   // OFF by default: the labels and captions stay exactly as the picture has them. ON = redraw them in Patrick Hand (Style tab)
  const [resId, setResId] = useState("1080p");   // step 24: export size
  const [textColor, setTextColor] = useState("#111111");
  const [patrickFonts, setPatrickFonts] = useState<TextFonts | null>(null);   // step 8: ALL lettering the editor writes (headings, subscribe, labels, captions) is Patrick Hand, locked
  const [repMenu, setRepMenu] = useState(false);   // the single "Download report" button opens a small menu (.txt or .csv)
  const [bgColor, setBgColor] = useState("#ffffff");   // the video background: white by default, whatever colour the pictures had
  const [kinds, setKinds] = useState<Record<number, KindOverride>>({});   // step 3: the owner's choice per image number
  const [clips, setClips] = useState<Clip[]>([]);   // step 4: scene videos
  const [unnamedClips, setUnnamedClips] = useState<string[]>([]);
  const [cur, setCur] = useState(0);
  const [seekReq, setSeekReq] = useState<{ t: number; id: number } | null>(null);
  const [exporting, setExporting] = useState<{ done: number; total: number; phase?: string } | null>(null);
  const [upscale, setUpscale] = useState(true);   // step 29: inside the app, make every picture sharper (AI 2x) at Export, before the video is drawn
  const rowsRef = useRef<Row[]>([]);
  const [quality, setQuality] = useState<Record<string, string>>({});   // step 9: result of the last-frame check, by slide key
  const [audioFile, setAudioFile] = useState<File | null>(null);
  const [audioBuffer, setAudioBuffer] = useState<AudioBuffer | null>(null);
  const [audioLoading, setAudioLoading] = useState(false);
  const [elementSounds, setElementSounds] = useState(true);
  const audioRef = useRef<HTMLInputElement>(null);
  const cancel = useRef(false);
  const run = useRef(0);
  const coveredRef = useRef<Set<number>>(new Set());
  const picRef = useRef<HTMLInputElement>(null);
  const dirRef = useRef<HTMLInputElement>(null);
  const specRef = useRef<HTMLInputElement>(null);
  const [tab, setTab] = useState<Tab>("import");
  const [err, setErr] = useState("");
  const [saved, setSaved] = useState("");
  const tabsRef = useRef<HTMLDivElement>(null);
  const report = (m: string) => { setErr(m); onError?.(m); };
  const specTextRef = useRef<string | null>(null);   // the timing file's own text, kept so the project can be saved and rebuilt
  const savedBlobs = useRef(new Map<string, Blob>());   // file id -> the file last written to the saved project (to write only what changed)
  const [loaded, setLoaded] = useState(false);   // the saved project has been looked for; auto-save never runs before this (it would wipe the saved one)
  const [saveInfo, setSaveInfo] = useState<{ state: "idle" | "saving" | "saved" | "error" | "off"; at?: number; msg?: string }>({ state: "idle" });
  const [restoredNote, setRestoredNote] = useState("");

  useEffect(() => {
    let live = true;
    Promise.all([loadFont(findFont("patrick").url), loadFont(MATH_URL)]).then(([main, math]) => { if (live) setPatrickFonts({ main, math }); }).catch(() => { if (live) setPatrickFonts(null); });
    return () => { live = false; };
  }, []);

  const setSlide = (key: string, patch: Partial<Slide>) => setSlides(s => s.map(x => (x.key === key ? { ...x, ...patch } : x)));

  async function processAll(list: Slide[]) {
    const id = ++run.current;
    for (const s of list) {
      if (coveredRef.current.has(s.n)) continue;   // step 5: a scene clip takes this cue; its picture is never turned into SVG
      if (run.current !== id) return;
      setSlide(s.key, { status: "busy" });
      try {
        const ready = await rasterizeIfSvg(s.file);
        const a = await analyseImage(ready);   // panels first; the slow full separation only runs when the picture has no panels (see api.ts)
        if (run.current !== id) return;
        setSlide(s.key, { status: "ok", project: a.project, units: buildUnits(a.project), found: a.found, panelNote: a.panelNote });
      } catch (e: any) {
        if (run.current !== id) return;
        setSlide(s.key, { status: "error", error: e?.message ?? "Could not read this image." });
      }
    }
  }


  /* ------------------------------------------------------------------ save progress (auto-save + the Save progress button) ------------------------------------------------------------------ */

  async function loadAudioFile(file: File) {
    setAudioLoading(true);
    try {
      const Ctx = window.AudioContext || (window as any).webkitAudioContext;
      if (!Ctx) throw new Error("Audio decoding is not available in this browser.");
      const ctx = new Ctx();
      try { const decoded = await ctx.decodeAudioData(await file.arrayBuffer()); setAudioFile(file); setAudioBuffer(decoded); }
      finally { await ctx.close(); }
    } catch (err: any) { report(`Could not read ${file.name}: ${err?.message ?? "unsupported audio format"}`); }
    finally { setAudioLoading(false); }
  }

  const settingsNow = () => ({ defSec, entrance, style, lines, pen, writeAll, maxIdle, replaceText, resId, textColor, bgColor, upscale, elementSounds });
  const saving = useRef(false), saveAgain = useRef(false);
  const saveRef = useRef<(manual?: boolean) => Promise<void>>(async () => { /* set below */ });

  /** Writes the project to the phone: only files that changed since the last save, then the small settings record, then removes files the project no longer uses. */
  async function saveNow(manual = false) {
    if (saving.current) { saveAgain.current = true; return; }
    saving.current = true;
    try {
      if (!slides.length && !specs && !clips.length && !audioFile) { if (manual) setSaveInfo({ state: "saved", at: Date.now(), msg: "Nothing to save yet: add pictures first." }); return; }
      setSaveInfo(i => ({ ...i, state: "saving" }));
      const files = new Map<string, File>();
      slides.forEach(x => files.set("pic:" + x.key, x.file));
      clips.forEach(c => files.set("clip:" + c.name, c.file));
      if (audioFile) files.set("audio", audioFile);
      let ok = true;
      for (const [id, f] of files) {
        if (savedBlobs.current.get(id) === f) continue;
        if (await putAsset(id, { blob: f, name: f.name, type: f.type })) savedBlobs.current.set(id, f); else { ok = false; break; }
      }
      if (ok) {
        const meta: ProjectMeta = {
          v: 1, savedAt: Date.now(), settings: settingsNow(), specText: specTextRef.current, specName, kinds: kinds as Record<string, unknown>, unnamedClips, cur,
          pics: slides.map(x => ({ id: "pic:" + x.key, key: x.key, n: x.n, name: x.name, upscaled: !!x.upscaled })),
          clips: clips.map(c => ({ id: "clip:" + c.name, name: c.name })),
          audio: audioFile ? { id: "audio", name: audioFile.name } : null,
        };
        ok = await putMeta(meta);
        if (ok) { const stale = (await listAssetIds()).filter(i => !files.has(i)); await deleteAssets(stale); stale.forEach(i => savedBlobs.current.delete(i)); }
      }
      setSaveInfo(ok ? { state: "saved", at: Date.now() } : { state: "error", msg: "Could not save your progress. The phone may be out of space." });
    } catch { setSaveInfo({ state: "error", msg: "Could not save your progress." }); }
    finally { saving.current = false; if (saveAgain.current) { saveAgain.current = false; void saveRef.current(); } }
  }
  saveRef.current = saveNow;

  /** The saved project comes back by itself when the screen opens, so a closed app does not mean starting again. Pictures are analysed again (about a second or two each). */
  useEffect(() => {
    let live = true;
    (async () => {
      try {
        if (!(await storeAvailable())) { if (live) setSaveInfo({ state: "off" }); return; }
        const meta = await getMeta();
        if (!meta || !live) return;
        const st = (meta.settings ?? {}) as Record<string, any>;
        const secs = typeof st.defSec === "number" && st.defSec >= 1 ? st.defSec : 10;
        setDefSec(secs);
        if (typeof st.entrance === "string") setEntrance(st.entrance as Entrance);
        if (typeof st.style === "string") setStyle(st.style as DrawStyle);
        if (typeof st.lines === "string") setLines(st.lines as LinesMode);
        if (typeof st.pen === "number") setPen(st.pen);
        if (typeof st.writeAll === "boolean") setWriteAll(st.writeAll);
        if (typeof st.maxIdle === "number") setMaxIdle(st.maxIdle);
        if (typeof st.replaceText === "boolean") setReplaceText(st.replaceText);
        if (typeof st.resId === "string") setResId(st.resId);
        if (typeof st.textColor === "string") setTextColor(st.textColor);
        if (typeof st.bgColor === "string") setBgColor(st.bgColor);
        if (typeof st.upscale === "boolean") setUpscale(st.upscale);
        if (typeof st.elementSounds === "boolean") setElementSounds(st.elementSounds);

        const list: Slide[] = [];
        for (const pc of meta.pics) {
          const a = await getAsset(pc.id); if (!a) continue;
          const f = new File([a.blob], a.name || pc.name, { type: a.type });
          savedBlobs.current.set(pc.id, f);
          list.push({ key: pc.key, n: pc.n, name: pc.name, file: f, thumb: URL.createObjectURL(f), status: "wait", upscaled: pc.upscaled });
        }
        const cl: Clip[] = [];
        for (const c of meta.clips) {
          const a = await getAsset(c.id), r = clipRange(c.name); if (!a || !r) continue;
          const f = new File([a.blob], c.name, { type: a.type }); savedBlobs.current.set(c.id, f);
          cl.push({ name: c.name, first: r.first, last: r.last, file: f });
        }
        if (!live) return;
        if (meta.specText) {
          try { const r = parseSlidesFileChecked(meta.specText, secs); specTextRef.current = meta.specText; setSpecs(r.specs); setSpecNotes(r.notes); setSpecName(meta.specName || "timing.json"); } catch { /* a damaged timing record is skipped */ }
        }
        setKinds((meta.kinds ?? {}) as Record<number, KindOverride>);
        setUnnamedClips(meta.unnamedClips ?? []);
        setClips(cl);
        coveredRef.current = new Set(sceneSpans(cl).flatMap(sp => sp.cues));
        setSlides(list);
        setSeekReq({ t: 0, id: Date.now() });
        if (meta.audio) { const a = await getAsset(meta.audio.id); if (a && live) { const f = new File([a.blob], a.name || meta.audio.name, { type: a.type }); savedBlobs.current.set(meta.audio.id, f); void loadAudioFile(f); } }
        if (list.length || meta.specText || cl.length) {
          const t = new Date(meta.savedAt);
          setRestoredNote(`Your last project is back: ${list.length} picture${list.length === 1 ? "" : "s"}${meta.specText ? ", timing file" : ""}${cl.length ? `, ${cl.length} scene video${cl.length === 1 ? "" : "s"}` : ""}${meta.audio ? ", audio" : ""} (saved ${t.toLocaleDateString()} ${t.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}).`);
          setSaveInfo({ state: "saved", at: meta.savedAt });
        }
        if (list.length) void processAll(list);
      } catch (e: any) { if (live) report("Could not open your saved project: " + (e?.message ?? "unknown problem")); }
      finally { if (live) setLoaded(true); }
    })();
    return () => { live = false; };
  }, []);

  /** Auto-save: a moment after anything that matters changes, and at once when the app goes to the background. */
  const saveSig = useMemo(() => JSON.stringify([slides.map(x => [x.key, x.n, x.upscaled ? 1 : 0]), settingsNow(), specName, kinds, clips.map(c => c.name), unnamedClips, audioFile?.name, audioFile?.size]),
    [slides, defSec, entrance, style, lines, pen, writeAll, maxIdle, replaceText, resId, textColor, bgColor, upscale, elementSounds, specName, kinds, clips, unnamedClips, audioFile]);
  useEffect(() => {
    if (!loaded) return;
    const t = setTimeout(() => void saveRef.current(), 1500);
    return () => clearTimeout(t);
  }, [saveSig, specs, loaded]);
  useEffect(() => {
    const hide = () => { if (loaded && document.visibilityState !== "visible") void saveRef.current(); };
    document.addEventListener("visibilitychange", hide); window.addEventListener("pagehide", hide);
    return () => { document.removeEventListener("visibilitychange", hide); window.removeEventListener("pagehide", hide); };
  }, [loaded]);

  async function startNew() {
    if (!window.confirm("Start a new project? The saved project on this phone will be deleted.")) return;
    run.current++;   // stops any analysis still running
    slides.forEach(x => URL.revokeObjectURL(x.thumb));
    setSlides([]); setSpecs(null); setSpecNotes([]); setSpecName(""); specTextRef.current = null; setKinds({}); setClips([]); setUnnamedClips([]);
    setAudioFile(null); setAudioBuffer(null); setCur(0); setRestoredNote(""); coveredRef.current = new Set(); savedBlobs.current.clear();
    await clearProject();
    setSaveInfo({ state: "idle" });
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
    report("");
    const timing = files.filter(f => /\.(json|txt)$/i.test(f.name));
    const pics = files.filter(f => !timing.includes(f));
    let gotClips = false;
    try {
      for (const f of timing) { { const text = await f.text(); const r = parseSlidesFileChecked(text, defSec); specTextRef.current = text; setSpecs(r.specs); setSpecNotes(r.notes); } setSpecName(f.name); }
      if (pics.length) {
        const cl = await readClips(pics);   // step 4: scene videos (loose, in a ZIP or in a folder); the clip name gives the cues it covers
        if (cl.clips.length || cl.unnamed.length) { setClips(cl.clips); setUnnamedClips(cl.unnamed); gotClips = true; coveredRef.current = new Set(sceneSpans(cl.clips).flatMap(sp => sp.cues)); }
        const found: Pic[] = await readPics(pics);
        if (!found.length && gotClips) return;   // only videos were added: the pictures stay as they are
        if (!found.length) throw new Error("No pictures or scene videos found. Use a ZIP of PNG, JPG or WebP files, scene videos named like 53.mp4 or 12-14.mp4, or pick the files themselves.");
        slides.forEach(s => URL.revokeObjectURL(s.thumb));
        const list: Slide[] = found.map((p, i) => ({ key: `${Date.now()}-${i}`, n: p.n ?? i + 1, name: p.name, file: p.file, thumb: URL.createObjectURL(p.file), status: "wait" }));
        setSlides(list); setCur(0); setSeekReq({ t: 0, id: Date.now() });
        void processAll(list);
      }
    } catch (e: any) { report(e?.message ?? "Could not open these files."); }
  }

  const spans = useMemo(() => sceneSpans(clips), [clips]);
  const covered = useMemo(() => new Set(spans.flatMap(sp => sp.cues)), [spans]);
  coveredRef.current = covered;   // cues shown as scene videos: their pictures are not processed

  const sk: SketchSettings = useMemo(() => ({ ...DEFAULT_SKETCH, entrance, style, pen, lines, dir: "left" as Dir, enterTime: 0.6, min: 0.02, max: 600, fit: false, target: 0 }), [entrance, style, pen, lines]);

  // batches: pictures and timing file are matched by number; say what does not line up (for example pictures 1-20 with a file for 21-40)
  const batchNotes = useMemo(() => (specs ? matchNotes([...slides.map(s => s.n), ...specs.filter(sp => resolveTextSlide(sp, kinds[sp.n])).map(sp => sp.n), ...covered], specs) : []), [slides, specs, kinds, covered]);

  const rows: Row[] = useMemo(() => {
    const out: Row[] = [];
    let prevEnd = 0;
    // headings and subscribe slides come from the timing file, not from pictures: when no picture has their number they still get a row (white screen, handwriting)
    const have = new Set(slides.map(x => x.n));
    const pics = slides.filter(x => !covered.has(x.n));   // step 5: cues under a scene clip have no picture row
    const textOnly: Slide[] = (specs ?? []).filter(sp => !have.has(sp.n) && !covered.has(sp.n) && (resolveTextSlide(sp) || resolveTextSlide(sp, kinds[sp.n]))).map(sp => ({ key: `text-${sp.n}`, n: sp.n, name: "", file: new File([], "text"), thumb: "", status: "ok" as const }));
    const forced: Slide[] = Object.entries(kinds).map(([n, ov]) => ({ n: +n, ov })).filter(x => !have.has(x.n) && !covered.has(x.n) && !(specs ?? []).some(sp => sp.n === x.n) && (x.ov.kind === "heading" || x.ov.kind === "subscribe")).map(x => ({ key: `text-${x.n}`, n: x.n, name: "", file: new File([], "text"), thumb: "", status: "ok" as const }));
    const sceneSlides: Slide[] = spans.map(sp => ({ key: `scene-${sp.clip.name}`, n: sp.cues[0], name: sp.clip.name, file: sp.clip.file, thumb: "", status: "ok" as const }));
    const all = [...pics, ...textOnly, ...forced, ...sceneSlides].sort((a, b) => a.n - b.n);
    for (const s of all) {
      const sc = s.key.startsWith("scene-") ? spans.find(x => `scene-${x.clip.name}` === s.key) : undefined;
      if (sc) {
        // new workflow step 5: a scene row. Start and end come from the timing file (first to last covered cue); the clip stays a video.
        const tm = sceneTimes(sc.cues, specs, prevEnd, defSec);
        prevEnd = tm.end;
        out.push({ slide: s, start: tm.start, end: tm.end, svg: null, width: 1920, height: 1080, note: tm.fromFile ? "" : `No timing in the file for cue ${sc.cues[0]}${sc.cues.length > 1 ? `-${sc.cues[sc.cues.length - 1]}` : ""}, ${defSec * sc.cues.length} s used.`, marks: [tm.start], scene: { clip: sc.clip, cues: sc.cues } });
        continue;
      }
      const spec = specs?.find(x => x.n === s.n);
      const start = spec ? spec.start : prevEnd;
      const end = spec ? spec.end : start + defSec;
      prevEnd = end;
      const heading = spec?.heading;
      const prevHeading = out.length ? out[out.length - 1].heading : undefined;
      let svg: string | null = null, plan: SlidePlan | undefined, note = "", marks: number[] = [], parts: ReportPart[] | undefined;
      let width = 1920, height = 1080;
      if (specs && !spec) note = `No timing for image ${s.n} in the file, ${defSec} s used.`;
      const tinfo = resolveTextSlide(spec, kinds[s.n]);
      let textKind: TextSlideKind | undefined, words: string | undefined;
      if (tinfo) {
        // new workflow step 2: heading / subscribe = white screen + handwritten black words, whatever the AI picture looks like. No zoom, no fade.
        textKind = tinfo.kind; words = tinfo.words; marks = [start];
        if (patrickFonts) { const t = buildTextSlide(tinfo.words, end - start, patrickFonts); svg = t.svg; width = t.width; height = t.height; }
        else note = "Loading the Patrick Hand font…";
      } else if (s.status === "ok" && s.project && s.units) {
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
        const tx = replaceText && patrickFonts ? planTexts(plan, p, times, spec ? { heading: pl ? undefined : spec.heading, captions: spec.captions, panels: pl ? (spec.panels ?? spec.captions?.map(c => ({ label: c, start: 0 }))) : undefined } : undefined, end - start, patrickFonts, textColor, "write") : null;
        svg = composeSvg(p, { kind: "color", color: bgColor }, { sketch: sk, times, skip: tx?.skip, extra: (tx?.markup ?? "") + (pl && s.found?.frameRaster ? frameMarkup(s.found.frameRaster) : "") });
        marks = plan.items.filter(i => i.unit.kind === "item").map(i => start + i.begin);
        const want = spec?.elements.length || spec?.expected;
        const pm = spec?.panels && s.found?.panels.length ? matchPanels(s.found, spec.panels) : null;   // version 2 file + panels found: count the panels
        const cnt = pm ? pm.notes.join(" ") : countNote(plan.main, want);
        if (cnt) note = cnt;
      }
      if (!tinfo && s.key.startsWith("text-")) note = "No picture for this image. Choose Heading or Subscribe (and type the words), or add the picture.";
      if (!tinfo && s.status === "ok" && s.panelNote) note = `Panels not used: ${s.panelNote}. The old element path is used instead.`;
      if (s.status === "error" && !tinfo) note = s.error ?? "";
      out.push({ slide: s, spec, start, end, svg, width, height, plan, note, heading, marks, parts, textKind, words });
    }
    return out;
  }, [slides, specs, defSec, writeAll, maxIdle, sk, bgColor, replaceText, textColor, patrickFonts, kinds, spans, covered]);

  rowsRef.current = rows;
  const clickTimes = useMemo(() => clickEvents(rows, elementSounds).map(e => e.time), [rows, elementSounds]);
  const total = rows.length ? rows[rows.length - 1].end : 0;
  const procSlides = slides.filter(s => !covered.has(s.n));   // pictures under a scene clip are not processed, so they are not waited for
  const done = procSlides.filter(s => s.status === "ok" || s.status === "error").length;
  const failed = procSlides.filter(s => s.status === "error").length;
  

  async function exportMp4() {
    report(""); setSaved(""); cancel.current = false;
    const frames = Math.round(total * FPS);
    setExporting({ done: 0, total: frames });
    void keepAlive(true);
    try {
      // New workflow step 29: ONE progress bar for two phases. Phase 1 (inside the app only): every picture is upscaled by the phone's AI upscaler, then analysed again
      // (so the pen plan is the same and the original-pixel crops are cut from the sharper picture). A picture that fails keeps its normal version. Phase 2: the video.
      const todo = hasHost() && upscale ? rowsRef.current.filter(r => !r.textKind && !r.scene && r.slide.status === "ok" && !r.slide.upscaled).map(r => r.slide) : [];
      const W_UP = todo.length ? Math.max(todo.length, Math.round(frames * 0.5)) : 0;   // upscaling gets a third of the bar
      let failedUp = 0;
      for (let i = 0; i < todo.length; i++) {
        if (cancel.current) throw new Error("Export cancelled.");
        setExporting({ done: Math.round((i / todo.length) * W_UP), total: frames + W_UP, phase: `Making picture ${i + 1} of ${todo.length} sharper` });
        try {
          const s = todo[i];
          const r = await nativeCall<any>("upscale", await toBase64(s.file));
          if (r?.error === "cancelled") throw new Error("Export cancelled.");
          if (r?.error || !r?.png) throw new Error(r?.detail ?? "upscale failed");
          const bin = atob(r.png), bytes = new Uint8Array(bin.length); for (let k = 0; k < bin.length; k++) bytes[k] = bin.charCodeAt(k);
          const up = new File([bytes], s.name.replace(/\.[^.]+$/, "") + "_x2.png", { type: "image/png" });
          const a = await analyseImage(up);
          setSlide(s.key, { file: up, upscaled: true, project: a.project, units: buildUnits(a.project), found: a.found, panelNote: a.panelNote });
        } catch { failedUp++; }
      }
      if (cancel.current) throw new Error("Export cancelled.");
      if (todo.length) await new Promise(r => setTimeout(r, 150));   // let the page rebuild its rows from the sharper pictures
      if (failedUp) report(`${failedUp} picture${failedUp > 1 ? "s" : ""} could not be made sharper and ${failedUp > 1 ? "were" : "was"} used as it is.`);
      const R = rowsRef.current;
      const soundEvents = clickEvents(R, elementSounds);
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
        slides: R.map(r => ({ svg: r.svg ?? "", width: r.width, height: r.height, start: r.start, end: r.end, video: r.scene?.clip.file })),   // step 7: a scene row is drawn from its clip
        audio: audioBuffer,
        clickSound,
        soundEvents,
        onProgress: (d, t) => setExporting({ done: W_UP + d, total: W_UP + t, phase: todo.length ? "Drawing the video" : undefined }), cancelled: () => cancel.current,
      });
      const outName = `video-${resId}-${Math.round(total)}s.mp4`;
      download(outName, blob);
      setSaved(outName);
    } catch (e: any) { if (!cancel.current) report(e?.message ?? "MP4 export failed."); }
    void keepAlive(false);
    setExporting(null);
  }

  const pct = exporting ? Math.round((exporting.done / Math.max(1, exporting.total)) * 100) : 0;
  const busy = procSlides.length > 0 && done < procSlides.length;
  // a heading or subscribe slide never needs its picture, so a picture that could not be read does not block the export
  // a heading, subscribe or scene row never needs a picture; every other row needs its analysed picture
  const ready = rows.length > 0 && rows.every(r => r.scene || (!!r.svg && (r.textKind || r.slide.status === "ok")));

  const resLabel = resId === "1080p" ? "1080p" : resId.toUpperCase();
  const sub = rows.length ? `${rows.length} ${rows.length === 1 ? "image" : "images"} · ${fmt(total)}` : "Pictures + timing → video";
  const openExport = () => { setTab("export"); requestAnimationFrame(() => tabsRef.current?.scrollIntoView({ block: "nearest", behavior: "smooth" })); };

  return (
    <div className={"se" + (standalone ? "" : " embedded")}>
      {standalone && (
        <div className="se-bar">
          <button className="se-back" onClick={goBack} aria-label="Back"><Icon n="back" /></button>
          <div className="se-title"><h1>SVG Editor</h1><span>{sub}</span></div>
          <button className={"se-save" + (saveInfo.state === "saved" ? " ok" : saveInfo.state === "error" ? " bad" : "")} disabled={saveInfo.state === "saving" || saveInfo.state === "off"} onClick={() => void saveNow(true)} aria-label="Save progress">{saveInfo.state === "saving" ? "Saving…" : saveInfo.state === "saved" ? "Saved" : "Save"}</button>
          <button className="se-cta" onClick={openExport}>Export</button>
        </div>
      )}
      {standalone && err && <div className="se-err" role="alert"><span>{err}</span><button onClick={() => setErr("")}>Dismiss</button></div>}

      <div className="se-main">
        <section className="se-stage" onDragOver={e => e.preventDefault()} onDrop={e => { e.preventDefault(); void dropFiles(e.dataTransfer); }}>
          {rows.length > 0
            ? <Player rows={rows} total={total} seekReq={seekReq} onIndex={setCur} audio={audioBuffer} clicks={clickTimes} />
            : (
              <div className="se-empty" onClick={() => picRef.current?.click()}>
                <div className="orb"><Icon n="image" size={26} /></div>
                <b>Add your pictures</b>
                <span>{specs ? `Timing loaded (${specs.length} images). Now add the images.` : "Pictures (ZIP or folder), scene videos and the timing JSON. Drop them here or tap."}</span>
              </div>
            )}
        </section>

        <div className="se-panel">
          <div className="se-tabs" role="tablist" ref={tabsRef}>
            {TABS.map(x => <button key={x.id} role="tab" aria-selected={tab === x.id} className={tab === x.id ? "se-tab on" : "se-tab"} onClick={() => setTab(x.id)}>{x.label}{x.id === "import" && ready && <i className="dot" />}</button>)}
          </div>

          <section className="se-card">
            {tab === "import" && (<>
              <h2>Import <small>pictures, timing and scene videos</small></h2>
              {restoredNote && <p className="hint small se-restored">{restoredNote}</p>}
              <div className="se-tiles">
                <button className={"se-tile" + (slides.length ? " done" : "")} disabled={!!exporting} onClick={() => picRef.current?.click()}><Icon n="image" /><span>{slides.length ? "Change images" : "Images (ZIP)"}</span><small>{slides.length ? `${slides.length} added` : "pictures or ZIP"}</small></button>
                <button className="se-tile" disabled={!!exporting} onClick={() => dirRef.current?.click()}><Icon n="folder" /><span>Images (folder)</span><small>a whole folder</small></button>
                <button className={"se-tile" + (specs ? " done" : "")} disabled={!!exporting} onClick={() => specRef.current?.click()}><Icon n="clock" /><span>{specs ? "Change JSON" : "Timing (JSON)"}</span><small>{specs ? specName : "cue times"}</small></button>
              </div>
              <div className="se-stats">
                <span className={slides.length ? "ok" : ""}>{slides.length ? `${slides.length} images${covered.size ? ` (${slides.filter(s => covered.has(s.n)).length} replaced by scene videos)` : ""}` : "No images yet"}</span>
                <span className={specs ? "ok" : ""}>{specs ? `Timing for ${specs.length} images` : "No timing file"}</span>
                {busy && <span>Finding elements: {done} of {procSlides.length}</span>}
                {failed > 0 && <span className="bad">{failed} could not be read</span>}
              </div>
              <div className="se-saverow">
                <button className="ghost" disabled={!!exporting || saveInfo.state === "saving" || saveInfo.state === "off"} onClick={() => void saveNow(true)}>Save progress</button>
                <button className="ghost" disabled={!!exporting || (!slides.length && !specs && !clips.length && !audioFile)} onClick={() => void startNew()}>Start a new project</button>
              </div>
              <p className={"hint small" + (saveInfo.state === "error" || saveInfo.state === "off" ? " se-warn" : "")}>{
                saveInfo.state === "off" ? "Saving is not available on this phone, so the project is lost when the app closes."
                : saveInfo.state === "error" ? saveInfo.msg
                : saveInfo.state === "saving" ? "Saving…"
                : saveInfo.state === "saved" ? `${saveInfo.msg ? saveInfo.msg + " " : ""}Saved at ${new Date(saveInfo.at ?? Date.now()).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}. Your work is also saved automatically.`
                : "Your work is saved automatically a moment after every change."}</p>
        {specs && specNotes.length + batchNotes.length > 0 && (
          <div className="hint small se-warn">
            <b>Timing file notes:</b>
            <ul style={{ margin: "4px 0 0 16px", padding: 0 }}>{[...batchNotes, ...specNotes].slice(0, 6).map((n, i) => <li key={i}>{n}</li>)}{specNotes.length + batchNotes.length > 6 ? <li>and {specNotes.length + batchNotes.length - 6} more</li> : null}</ul>
          </div>
        )}
        {!specs && slides.length > 0 && (
          <label className="se-field">Seconds per image <input className="num" type="number" min="1" max="300" style={{ width: 70 }} value={defSec} onChange={e => setDefSec(Math.min(300, Math.max(1, +e.target.value || 10)))} /></label>
        )}
        {(clips.length > 0 || unnamedClips.length > 0) && (<>
          <h2>Scene videos <small>{clips.length} clip{clips.length === 1 ? "" : "s"}</small></h2>
          <ul className="se-list">
            {clips.map(c => <li key={c.name}><span className="meta"><b>{c.name}</b><small>{c.first === c.last ? `replaces cue ${c.first}` : `replaces cues ${c.first} to ${c.last}`}</small></span></li>)}
          </ul>
          {unnamedClips.length > 0 && <p className="hint small se-warn">No number in the name, so not used: {unnamedClips.join(", ")}. Rename like 53.mp4 or 12-14.mp4.</p>}
          {clipNotes(clips, specs?.map(sp => sp.n)).map((n, i) => <p key={i} className="hint small se-warn">{n}</p>)}
          <p className="hint small">A scene stays a video (it is never turned into SVG). Each scene is a row on the timeline (times from the JSON) and plays muted in the preview. Its pictures are skipped. The MP4 draws each scene frame by frame (muted, black bars, one click at its start).</p>
        </>)}
            </>)}

            {tab === "style" && (<>
        <h2>Animation</h2>
        <div className="row"><label className="inl">Background <input type="color" value={bgColor} onChange={e => setBgColor(e.target.value)} /></label>
          {bgColor.toLowerCase() !== "#ffffff" && <button className="ghost" onClick={() => setBgColor("#ffffff")}>White</button>}</div>
        <div className="sliders">
          <label>Entrance <select value={entrance} onChange={e => setEntrance(e.target.value as Entrance)}>{ENTRANCES.map(o => <option key={o.id} value={o.id}>{o.label}</option>)}</select></label>
          {entrance === "draw" && <label>Draw style <select value={style} onChange={e => setStyle(e.target.value as DrawStyle)}><option value="illustrate">Follow the lines</option><option value="reveal">Reveal top to bottom</option></select></label>}
          {entrance === "draw" && <label>Lines then colour <select value={lines} onChange={e => setLines(e.target.value as LinesMode)}><option value="auto">Auto (panels one by one, other pictures all lines first)</option><option value="each">One piece at a time: its lines, then its colour</option><option value="all">All lines first, then all colour</option><option value="off">Off (outline and colour together)</option></select></label>}
          {entrance === "draw" && <label>{lines !== "off" ? "Lines share" : "Pen share"} <span className="inl"><input type="range" min="10" max="90" step="5" style={{ ["--fill" as string]: `${((Math.round(pen * 100) - 10) / 80) * 100}%` }} value={Math.round(pen * 100)} onChange={e => setPen(+e.target.value / 100)} /> {Math.round(pen * 100)}% pen, {100 - Math.round(pen * 100)}% colour</span></label>}
          <label>Longest idle <span className="inl"><input className="num" type="number" min="1" max="30" style={{ width: 56 }} value={maxIdle} onChange={e => setMaxIdle(Math.min(30, Math.max(1, +e.target.value || 5)))} /> s</span></label>
          <label className="chk"><input type="checkbox" checked={replaceText} onChange={e => setReplaceText(e.target.checked)} /> Rewrite the picture's labels and captions in Patrick Hand</label>
          <p className="hint small">{replaceText ? "The labels and captions are taken out of the picture and written again in Patrick Hand, in the same place and box, in the colour below. Heading and subscribe slides are always Patrick Hand." : "Off: the labels and captions stay exactly as they are in the picture. Heading and subscribe slides are still written in Patrick Hand."}</p>
          {replaceText && <label className="inl">Text colour <input type="color" value={textColor} onChange={e => setTextColor(e.target.value)} /></label>}
          <label className="chk"><input type="checkbox" checked={writeAll} onChange={e => setWriteAll(e.target.checked)} /> Write the heading again on every image</label>
        </div>
            </>)}

            {tab === "audio" && (<>
        <h2>Audio</h2>
        <div className="row wrap">
          <button className="ghost" disabled={!!exporting || audioLoading} onClick={() => audioRef.current?.click()}>{audioFile ? "Change audio" : "Add audio"}</button>
          {audioFile && <button className="ghost" disabled={!!exporting || audioLoading} onClick={() => { setAudioFile(null); setAudioBuffer(null); }}>Remove audio</button>}
        </div>
        <input ref={audioRef} type="file" accept="audio/*,.mp3,.wav,.m4a,.aac,.ogg,.flac,.opus" hidden onChange={e => { const file = e.target.files?.[0]; e.target.value = ""; if (file) void loadAudioFile(file); }} />
        <p className="hint small">{audioLoading ? "Reading audio…" : audioFile ? `${audioFile.name} — audio will be synchronized to the video, trimmed if longer, or padded with silence if shorter.` : "Add a voice-over, music track, or other audio file. It plays in the preview and in the exported video. Common formats are supported when your device can decode them."}</p>
        <label className="chk"><input type="checkbox" checked={elementSounds} disabled={!!exporting} onChange={e => setElementSounds(e.target.checked)} /> Use the bundled click.wav for every element (headings, arrows and illustrations)</label>
            </>)}

            {tab === "images" && (<>
        <h2>Images <small>{rows.length ? `${fmt(total)} in total` : ""}</small></h2>
        <ul className="se-list">
          {rows.map((r, i) => (
            <li key={r.slide.key} className={(i === cur ? "sel " : "") + r.slide.status} onClick={() => setSeekReq({ t: r.start, id: Date.now() })}>
              {r.scene ? <span className="thumb scene"><Icon n="film" size={20} /></span> : r.slide.thumb ? <img className="thumb" src={r.slide.thumb} alt="" /> : <span className="thumb blank" />}
              <span className="meta">
                <b>{r.scene ? (r.scene.cues.length > 1 ? `Scene ${r.scene.cues[0]}-${r.scene.cues[r.scene.cues.length - 1]}` : `Scene ${r.scene.cues[0]}`) : `Image ${r.slide.n}`} <small>{fmt(r.start)} to {fmt(r.end)}</small></b>
                <small>{r.scene ? `Video: ${r.scene.clip.name} (muted, kept as a video)` : r.textKind === "subscribe" ? `Subscribe slide: "${r.words}"` : r.textKind === "heading" ? `Heading slide: "${r.words}"` : (r.heading ?? r.slide.name)}</small>
                {r.textKind && <small>white screen, handwritten, no zoom</small>}
                {!r.scene && <span className="kindbox" onClick={e => e.stopPropagation()} style={{ display: "flex", gap: 6, flexWrap: "wrap", marginTop: 4 }}>
                  <select aria-label={`What image ${r.slide.n} is`} value={kinds[r.slide.n]?.kind ?? "auto"} disabled={!!exporting}
                    onChange={e => { const kind = e.target.value as KindChoice; setKinds(k => { const n = { ...k }; if (kind === "auto" && !k[r.slide.n]?.words) delete n[r.slide.n]; else n[r.slide.n] = { ...k[r.slide.n], kind }; return n; }); }}>
                    <option value="auto">Auto</option><option value="picture">Picture</option><option value="heading">Heading</option><option value="subscribe">Subscribe</option>
                  </select>
                  {(r.textKind || (kinds[r.slide.n] && kinds[r.slide.n].kind !== "picture" && kinds[r.slide.n].kind !== "auto")) && (
                    <input type="text" aria-label={`Words for image ${r.slide.n}`} placeholder={r.words ?? "type the words"} value={kinds[r.slide.n]?.words ?? ""} disabled={!!exporting}
                      onChange={e => { const words = e.target.value; setKinds(k => ({ ...k, [r.slide.n]: { kind: k[r.slide.n]?.kind ?? "auto", words } })); }} style={{ width: 150 }} />
                  )}
                </span>}
                {(kinds[r.slide.n]?.kind === "heading" || kinds[r.slide.n]?.kind === "subscribe") && !r.textKind && <small className="warnline">Type the words to write.</small>}
                {r.spec?.text && <small title={r.spec.text} style={{ opacity: 0.75, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", maxWidth: 220 }}>{r.spec.text}</small>}
                {r.spec?.layout && <small>{r.spec.system ? r.spec.system + " " : ""}{r.spec.layout}{r.spec.panels ? `, ${r.spec.panels.length} panels` : ""}</small>}
                {!r.scene && r.slide.status === "busy" && <small>finding elements…</small>}
                {!r.scene && r.slide.status === "wait" && <small>waiting</small>}
                {!r.textKind && r.slide.status === "ok" && r.plan && <small>{r.plan.main} elements{r.plan.fromJson ? ", times from the file" : ", spread evenly"}</small>}
                {r.note && <small className="warnline">{r.note}</small>}
              </span>
            </li>
          ))}
        </ul>

        {(() => {   // v2 step 13: numbered outlines over the upload, layout name, panels found vs in the file
          const r = rows[cur];
          if (!r || r.scene || r.textKind || !r.slide.found?.panels.length) return null;   // step 9: only picture rows have a Panel check
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
          const reps: ReportRow[] = rows.map(r => ({ n: r.slide.n, start: r.start, end: r.end, heading: r.heading, status: r.slide.status, elements: r.spec?.elements, captions: r.spec?.captions, plan: r.plan, note: r.note, parts: r.parts, kind: r.scene ? "scene" : r.textKind ?? "picture", last: r.scene ? r.scene.cues[r.scene.cues.length - 1] : undefined, label: r.scene ? r.scene.clip.name : r.words }));
          return (<>
            <div className="se-dl">
              <button className="go se-dl-main" disabled={!rows.length} aria-expanded={repMenu} onClick={() => setRepMenu(v => !v)}><Icon n="clock" size={18} /><span>Download report</span><span className="se-dl-caret">{repMenu ? "▴" : "▾"}</span></button>
              {repMenu && <div className="se-dl-menu">
                <button className="ghost" onClick={() => { download("timing-report.txt", new Blob([reportText(reps)], { type: "text/plain" })); setRepMenu(false); }}><b>Text</b><small>.txt, easy to read</small></button>
                <button className="ghost" onClick={() => { download("timing-report.csv", new Blob(["\ufeff" + reportCsv(reps)], { type: "text/csv" })); setRepMenu(false); }}><b>Spreadsheet</b><small>.csv, opens in Excel</small></button>
              </div>}
            </div>
            {reps[cur] && <pre className="report">{slideReportText(reps[cur])}</pre>}
            {rows[cur]?.svg && !rows[cur].textKind && !rows[cur].scene && (<div className="row wrap">
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
            </>)}

            {tab === "export" && (<>
              <h2>Export <small>MP4 video</small></h2>
              <div className="se-sum">
                <div><span>Images</span><b>{rows.length}</b></div>
                <div><span>Length</span><b>{fmt(total)}</b></div>
                <div><span>Audio</span><b>{audioFile ? audioFile.name : "none"}</b></div>
              </div>
        {hasHost() && !exporting && <label className="chk"><input type="checkbox" checked={upscale} onChange={e => setUpscale(e.target.checked)} /> Make every picture sharper (AI upscale) when exporting. Takes longer; the preview stays on the normal pictures.</label>}
        {!exporting && <ResolutionPicker value={resId} onChange={setResId} fps={FPS} seconds={total} />}
              {!exporting && <button className="go" onClick={exportMp4} disabled={!mp4Supported() || !ready || !rows.length || audioLoading}>Export {resLabel} MP4 ({fmt(total)})</button>}
              {!exporting && !ready && <p className="hint small">{rows.length ? "Wait until every picture has been read." : "Add pictures first (Import tab)."}</p>}
              {hasHost() && !exporting && <p className="hint small">The MP4 is saved to Download/Bezalel.</p>}
              {saved && !exporting && <p className="se-ok">Saved {saved}</p>}
              {exporting && (
                <div className="se-busy">
                  <span className="cap">While exporting</span>
                  <p className="lab">{exporting.phase ? `${exporting.phase}` : "Making the video"}: {pct}%</p>
                  <div className="bar" role="progressbar" aria-valuenow={pct} aria-valuemin={0} aria-valuemax={100}><div style={{ width: `${pct}%` }} /></div>
                  <div className="row"><p className="hint small" style={{ flex: 1 }}>Keep the app open and the phone plugged in.</p><button className="ghost" onClick={() => { cancel.current = true; cancelNative(); }}>Cancel</button></div>
                </div>
              )}
        {!mp4Supported() && <p className="hint small">This browser cannot make MP4. Use Chrome or Edge.</p>}
        <p className="hint small" style={{ marginTop: 8 }}>Exports one MP4 with all images, the selected audio track, and optional element-specific sound effects, synchronized to the slide timeline.</p>
            </>)}
          </section>
        </div>
      </div>

      <input ref={picRef} type="file" multiple accept=".zip,image/*,.svg,video/*,.mp4,.mov,.webm,.m4v" hidden onChange={e => { void addFiles(Array.from(e.target.files ?? [])); e.target.value = ""; }} />
      <input ref={dirRef} type="file" hidden {...({ webkitdirectory: "", directory: "" } as any)} onChange={e => { void addFiles(Array.from(e.target.files ?? [])); e.target.value = ""; }} />
      <input ref={specRef} type="file" accept=".json,.txt" hidden onChange={e => { void addFiles(Array.from(e.target.files ?? [])); e.target.value = ""; }} />
    </div>
  );
}
