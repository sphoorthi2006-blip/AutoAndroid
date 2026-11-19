import { compareFrames, type FrameDiff, type Region } from "./quality";
import { ArrayBufferTarget, Muxer } from "mp4-muxer";

/** One SMIL animation found in the drawing: <animate>, <animateTransform> or <set>. */
interface Track { el: Element; kind: "animate" | "transform" | "set"; attr: string; type: string; begin: number; dur: number; keyTimes: number[]; values: number[][]; raw: string[]; splines: number[][] | null; init: string | null; last: string }

const secs = (v: string | null) => (v ? parseFloat(v) || 0 : 0);
const nums = (v: string) => v.trim().split(/[\s,]+/).map(Number);

/** Cubic-bezier easing (the same as SMIL keySplines): progress q in 0..1 -> eased progress. */
function bezier(sp: number[], q: number): number {
  const [x1, y1, x2, y2] = sp;
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  let t = q;
  for (let i = 0; i < 8; i++) { const x = ((ax * t + bx) * t + cx) * t - q, d = (3 * ax * t + 2 * bx) * t + cx; if (Math.abs(x) < 1e-6 || Math.abs(d) < 1e-6) break; t -= x / d; }
  t = Math.min(1, Math.max(0, t));
  return ((ay * t + by) * t + cy) * t;
}

/** Turns the animated SVG into a "scrubbable" drawing: at any time t it sets every animated attribute to its value at t,
 *  so each video frame is exact (no real-time playback, no screen recording, no dropped frames).
 *  Supports <animate from/to or values/keyTimes/keySplines>, <animateTransform> (translate, scale, rotate) and <set>. */
export class Scrubber {
  private doc: Document;
  private tracks: Track[] = [];
  constructor(svgText: string) {
    this.doc = new DOMParser().parseFromString(svgText, "image/svg+xml");
    const all = Array.from(this.doc.querySelectorAll("animate, animateTransform, set"));
    for (const el of all) {
      const parent = el.parentElement, attr = el.getAttribute("attributeName"), tag = el.tagName;
      if (!parent || !attr) { el.remove(); continue; }
      const begin = secs(el.getAttribute("begin")), dur = Math.max(1e-6, secs(el.getAttribute("dur")));
      const init = parent.getAttribute(attr);
      if (tag === "set") {
        const to = el.getAttribute("to");
        if (to !== null) this.tracks.push({ el: parent, kind: "set", attr, type: "", begin, dur, keyTimes: [], values: [], raw: [to], splines: null, init, last: "\u0000" });
        el.remove(); continue;
      }
      const vals = el.getAttribute("values"), from = el.getAttribute("from"), to = el.getAttribute("to");
      const raw = vals !== null ? vals.split(";").map(x => x.trim()).filter(Boolean) : from !== null && to !== null ? [from, to] : [];
      if (raw.length < 2) { el.remove(); continue; }
      const kt = el.getAttribute("keyTimes");
      const keyTimes = kt ? kt.split(";").map(Number) : raw.map((_, i) => i / (raw.length - 1));
      const sp = el.getAttribute("calcMode") === "spline" && el.getAttribute("keySplines") ? el.getAttribute("keySplines")!.split(";").map(nums) : null;
      this.tracks.push({ el: parent, kind: tag === "animateTransform" ? "transform" : "animate", attr, type: el.getAttribute("type") ?? "translate", begin, dur, keyTimes, values: raw.map(nums), raw, splines: sp, init, last: "\u0000" });
      el.remove();
    }
  }
  private value(m: Track, t: number): string {
    if (m.kind === "set") return t >= m.begin ? m.raw[0] : (m.init ?? "");
    const p = Math.min(1, Math.max(0, (t - m.begin) / m.dur));
    const k = m.keyTimes;
    let i = 0; while (i < k.length - 2 && p > k[i + 1]) i++;
    let q = k[i + 1] > k[i] ? (p - k[i]) / (k[i + 1] - k[i]) : 1;
    q = Math.min(1, Math.max(0, q));
    if (m.splines && m.splines[i]) q = bezier(m.splines[i], q);
    const a = m.values[i], b = m.values[i + 1];
    const out = a.map((x, j) => +(x + ((b[j] ?? x) - x) * q).toFixed(4));
    return m.kind === "transform" ? `${m.type}(${out.join(" ")})` : String(out[0]);
  }
  /** SVG text of the drawing exactly as it looks at time t (seconds). */
  at(t: number): string {
    for (const m of this.tracks) {
      const v = this.value(m, t);
      if (v !== m.last) { if (m.kind === "set" && m.init === null && t < m.begin) m.el.removeAttribute(m.attr); else m.el.setAttribute(m.attr, v); m.last = v; }
    }
    return new XMLSerializer().serializeToString(this.doc);
  }
}

function loadSvg(text: string): Promise<HTMLImageElement> {
  return new Promise((ok, err) => {
    const url = URL.createObjectURL(new Blob([text], { type: "image/svg+xml;charset=utf-8" }));
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); ok(img); };
    img.onerror = () => { URL.revokeObjectURL(url); err(new Error("A frame could not be drawn.")); };
    img.src = url;
  });
}

export interface Mp4Options { svg: string; width: number; height: number; seconds: number; fps?: number; resolution?: string; onProgress?: (done: number, total: number) => void; cancelled?: () => boolean }
/** One image of a longer video: its animated drawing and where it sits on the video clock. */
/** `video` (new workflow step 7): a scene clip. It is drawn frame by frame (seek, then copy the frame), muted, letterboxed with black bars, in place of the SVG. */
export interface TimelineSlide { svg: string; width: number; height: number; start: number; end: number; video?: Blob }
/** One opened scene clip: `seek` moves to a time (seconds into the clip) and resolves when the frame is ready; `draw` copies that frame into a box. */
export interface SceneSource { duration: number; width: number; height: number; seek(t: number): Promise<void>; draw(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number): void; close(): void }
export type SceneOpener = (clip: Blob) => Promise<SceneSource>

/** The real opener: a hidden <video> element of the web view (muted; nothing is played, every frame is a seek). A seek that never answers is given up after 3 s so an export cannot hang. */
export const openSceneDom: SceneOpener = async clip => {
  const url = URL.createObjectURL(clip), v = document.createElement("video");
  v.muted = true; v.playsInline = true; v.preload = "auto"; v.src = url;
  await new Promise<void>((ok, no) => { v.onloadeddata = () => ok(); v.onerror = () => no(new Error("This phone's web view could not read a scene video. Try an MP4 (H.264) clip.")); });
  return {
    duration: Number.isFinite(v.duration) ? v.duration : 0, width: v.videoWidth || 1920, height: v.videoHeight || 1080,
    seek: t => new Promise<void>(ok => { if (Math.abs(v.currentTime - t) < 0.0005) return ok(); const done = () => { v.onseeked = null; clearTimeout(h); ok(); }; const h = setTimeout(done, 3000); v.onseeked = done; v.currentTime = t; }),
    draw: (c, x, y, w, h) => c.drawImage(v, x, y, w, h),
    close: () => { v.removeAttribute("src"); v.load(); URL.revokeObjectURL(url); },
  };
};
export interface SoundEvent { time: number }
export interface TimelineOptions { slides: TimelineSlide[]; fps?: number; resolution?: string; audio?: AudioBuffer | null; clickSound?: AudioBuffer | null; soundEvents?: SoundEvent[]; onProgress?: (done: number, total: number) => void; cancelled?: () => boolean; openScene?: SceneOpener }

export const mp4Supported = () => typeof VideoEncoder !== "undefined" && typeof VideoFrame !== "undefined";

const even = (v: number) => Math.max(2, Math.round(v / 2) * 2);

/** Renders one animated SVG to an MP4 (H.264) that lasts exactly `seconds`, entirely in the browser. */
export function renderMp4(o: Mp4Options): Promise<Blob> {
  return renderMp4Timeline({ slides: [{ svg: o.svg, width: o.width, height: o.height, start: 0, end: o.seconds }], fps: o.fps, resolution: o.resolution, onProgress: o.onProgress, cancelled: o.cancelled });
}

/** v2 step 9: the default export is 1920x1080 at 30 frames per second, whatever the size of the pictures (they are fitted inside, never stretched). Step 24 adds 2K and 4K (RESOLUTIONS). */
export const EXPORT_W = 1920, EXPORT_H = 1080, EXPORT_FPS = 30;
/** Step 23: the frame rates the owner can choose. */
export const FPS_CHOICES = [30, 60] as const;
export const safeFps = (v: unknown): number => (v === 60 ? 60 : EXPORT_FPS);

/** Step 24: the export sizes the owner can choose. All are 16:9; pictures are fitted inside (never stretched). */
export interface Resolution { id: string; label: string; w: number; h: number }
export const RESOLUTIONS: Resolution[] = [
  { id: "1080p", label: "1080p (1920 x 1080)", w: 1920, h: 1080 },
  { id: "2k", label: "2K (2560 x 1440)", w: 2560, h: 1440 },
  { id: "4k", label: "4K (3840 x 2160)", w: 3840, h: 2160 },
];
export const DEFAULT_RESOLUTION = "1080p";
/** Any unknown value falls back to 1080p. */
export const safeRes = (v: unknown): Resolution => RESOLUTIONS.find(r => r.id === v) ?? RESOLUTIONS[0];
/** 4K is limited to 30 frames per second (phones cannot encode or hold 4K at 60). */
export const MAX_FPS: Record<string, number> = { "4k": 30 };
export const fpsFor = (res: unknown, fps: unknown): number => Math.min(safeFps(fps), MAX_FPS[safeRes(res).id] ?? 60);

/** Frame count, bitrate and key-frame spacing of an export at 1080p, 2K or 4K, 30 or 60 frames per second. */
export function exportPlan(seconds: number, fpsIn?: number, resIn?: string) {
  const res = safeRes(resIn), fps = fpsFor(res.id, fpsIn);
  return { fps, res, width: res.w, height: res.h, total: Math.max(1, Math.round(seconds * fps)), bitrate: Math.round(res.w * res.h * fps * 0.15), keyEvery: fps * 2,
    stamp: (i: number) => Math.round((i * 1e6) / fps), duration: Math.round(1e6 / fps) };
}
/** Rough size of the finished MP4 in bytes (the muxer keeps the whole file in memory, so very big ones can fail on a phone). */
export const estimateBytes = (seconds: number, fpsIn?: number, resIn?: string): number => Math.round((exportPlan(seconds, fpsIn, resIn).bitrate * Math.max(0, seconds)) / 8);

/** H.264 levels to try, best first. 1080p at 60 fps needs level 4.2 (2a); 2K at 30 fps needs 5.0 (32), 2K at 60 and 4K at 30 need 5.1 (33). 4K is 30 fps only. */
export const H264_CODECS = ["avc1.640034", "avc1.4d0034", "avc1.640033", "avc1.4d0033", "avc1.640032", "avc1.4d0032", "avc1.64002a", "avc1.4d002a", "avc1.42002a", "avc1.42003e", "avc1.640028", "avc1.42001f"];
/** [level_idc, max macroblocks per second, max macroblocks per frame] from the H.264 level table. */
const LEVELS: [number, number, number][] = [[0x1f, 108000, 3600], [0x28, 245760, 8192], [0x2a, 522240, 8704], [0x32, 589824, 22080], [0x33, 983040, 36864], [0x34, 2073600, 36864], [0x3e, 4177920, 139264]];
/** The codec strings (from H264_CODECS, same order) whose level is big enough for this size and frame rate. */
export function codecsFor(w: number, h: number, fps: number): string[] {
  const mbs = Math.ceil(w / 16) * Math.ceil(h / 16);
  return H264_CODECS.filter(c => {
    const lv = parseInt(c.slice(-2), 16), row = LEVELS.find(l => l[0] === lv);
    return !!row && mbs <= row[2] && mbs * fps <= row[1];
  });
}
/** Which of the export sizes this phone's encoder can make at this frame rate (nothing is encoded, only asked). */
export async function supportedResolutions(fpsIn?: number): Promise<Record<string, boolean>> {
  const out: Record<string, boolean> = {};
  for (const r of RESOLUTIONS) {
    out[r.id] = false;
    const fps = fpsFor(r.id, fpsIn);
    if (!mp4Supported()) continue;
    const p = exportPlan(1, fps, r.id);
    for (const codec of codecsFor(r.w, r.h, fps)) {
      try { if ((await VideoEncoder.isConfigSupported({ width: r.w, height: r.h, bitrate: p.bitrate, framerate: fps, codec, avc: { format: "avc" } } as VideoEncoderConfig)).supported) { out[r.id] = true; break; } } catch { /* try the next one */ }
    }
  }
  return out;
}
/** Where a w x h picture goes inside a W x H frame: as big as possible, centred, same proportions. */
export function fitInto(w: number, h: number, W = EXPORT_W, H = EXPORT_H): { x: number; y: number; w: number; h: number } {
  const f = Math.min(W / w, H / h);
  return { x: (W - w * f) / 2, y: (H - h * f) / 2, w: w * f, h: h * f };
}
/** The background colour of a composed slide SVG (its first full-size rect), white when there is none. */
export function svgBackground(svg: string): string {
  const m = svg.match(/<rect[^>]*\bfill="(#[0-9a-fA-F]{3,8})"/);
  return m ? m[1] : "#ffffff";
}

/** v2 step 9: the LAST frame of a slide's animation compared with the picture that was uploaded, at most 960 px wide. `regions` are boxes in the slide's own pixels
 *  (for example the panels); without them the whole picture counts. */
export async function checkFinalFrame(sl: { svg: string; width: number; height: number; start: number; end: number }, file: Blob, regions?: Region[]): Promise<FrameDiff> {
  const k = Math.min(1, 960 / sl.width), w = Math.max(1, Math.round(sl.width * k)), h = Math.max(1, Math.round(sl.height * k));
  const mk = () => { const c = document.createElement("canvas"); c.width = w; c.height = h; const x = c.getContext("2d", { willReadFrequently: true })!; x.fillStyle = "#ffffff"; x.fillRect(0, 0, w, h); return x; };
  const A = mk(), B = mk();
  const img = await loadSvg(new Scrubber(sl.svg).at(sl.end - sl.start + 0.05));
  A.drawImage(img, 0, 0, w, h);
  const bmp = await createImageBitmap(file);
  B.drawImage(bmp, 0, 0, w, h);
  return compareFrames(A.getImageData(0, 0, w, h).data, B.getImageData(0, 0, w, h).data, w, h, regions?.map(r => ({ x: r.x * k, y: r.y * k, w: r.w * k, h: r.h * k })));
}

/** Builds stereo PCM at 48 kHz and encodes it as the MP4 AAC track. */
async function encodeTimelineAudio(muxer: any, seconds: number, source: AudioBuffer | null, clickSound: AudioBuffer | null, events: SoundEvent[], cancelled?: () => boolean): Promise<void> {
  if (typeof AudioEncoder === "undefined" || typeof AudioData === "undefined") throw new Error("This device cannot encode AAC audio. Update Android System WebView and try again.");
  const rate = 48000, frames = Math.max(1, Math.ceil(seconds * rate));
  const pcm = new Float32Array(frames * 2);
  if (source) {
    const left = source.getChannelData(0), right = source.numberOfChannels > 1 ? source.getChannelData(1) : left;
    for (let i = 0; i < frames; i++) {
      const at = i * source.sampleRate / rate, a = Math.floor(at), q = at - a;
      if (a < left.length) {
        const b = Math.min(a + 1, left.length - 1);
        pcm[i * 2] = (left[a] + (left[b] - left[a]) * q) * 0.88;
        pcm[i * 2 + 1] = (right[a] + (right[b] - right[a]) * q) * 0.88;
      }
    }
  }
  // Reuse the exact click.wav bundled with the Android app for EVERY element entrance.
  // Resample it onto the same 48 kHz timeline as the video and optional narration/music.
  if (clickSound) {
    const clickL = clickSound.getChannelData(0);
    const clickR = clickSound.numberOfChannels > 1 ? clickSound.getChannelData(1) : clickL;
    const clickFrames = Math.max(1, Math.ceil(clickSound.length * rate / clickSound.sampleRate));
    for (const event of events) {
      if (cancelled?.()) throw new Error("Export cancelled.");
      const start = Math.max(0, Math.round(event.time * rate));
      for (let n = 0; n < clickFrames && start + n < frames; n++) {
        const at = n * clickSound.sampleRate / rate;
        const a = Math.floor(at), q = at - a;
        const b = Math.min(a + 1, clickL.length - 1);
        const l = clickL[a] + (clickL[b] - clickL[a]) * q;
        const r = clickR[a] + (clickR[b] - clickR[a]) * q;
        const ix = (start + n) * 2;
        pcm[ix] += l; pcm[ix + 1] += r;
      }
    }
  }
  // Gentle saturation prevents clipping when a cue lands over loud source audio.
  for (let i = 0; i < pcm.length; i++) pcm[i] = Math.tanh(pcm[i] * 1.08) * 0.94;

  const cfg: AudioEncoderConfig = { codec: "mp4a.40.2", sampleRate: rate, numberOfChannels: 2, bitrate: 160000 };
  const supported = await AudioEncoder.isConfigSupported(cfg);
  if (!supported.supported) throw new Error("This device cannot encode AAC audio for MP4. Try updating Android System WebView.");
  let encErr: Error | null = null;
  const encoder = new AudioEncoder({ output: (chunk, meta) => muxer.addAudioChunk(chunk, meta), error: e => { encErr = e as Error; } });
  encoder.configure(cfg);
  const block = 1024;
  try {
    for (let start = 0; start < frames; start += block) {
      if (cancelled?.()) throw new Error("Export cancelled.");
      if (encErr) throw encErr;
      const count = Math.min(block, frames - start);
      const data = pcm.subarray(start * 2, (start + count) * 2);
      const audio = new AudioData({ format: "f32", sampleRate: rate, numberOfFrames: count, numberOfChannels: 2, timestamp: Math.round(start * 1e6 / rate), data });
      encoder.encode(audio); audio.close();
      while (encoder.encodeQueueSize > 8) await new Promise(resolve => setTimeout(resolve, 5));
    }
    await encoder.flush();
    if (encErr) throw encErr;
  } finally { encoder.close(); }
}

/** Renders a whole video: every image in turn on one clock (frame by frame, so the timing is exact), as one MP4. */
export async function renderMp4Timeline(o: TimelineOptions): Promise<Blob> {
  if (!mp4Supported()) throw new Error("This phone's web view cannot make MP4 files (no WebCodecs). Update \"Android System WebView\" in the Play Store and try again.");
  if (!o.slides.length) throw new Error("There is nothing to export yet.");
  const slides = o.slides;
  const seconds = slides[slides.length - 1].end;
  const plan = exportPlan(seconds, o.fps, o.resolution);   // step 23/24: 1080p, 2K or 4K at 30 or 60 fps
  const fps = plan.fps, total = plan.total;
  const W = plan.width, H = plan.height;

  const base = { width: W, height: H, bitrate: plan.bitrate, framerate: fps };
  let config: VideoEncoderConfig | null = null;
  for (const codec of codecsFor(W, H, fps)) {
    const c: VideoEncoderConfig = { ...base, codec, avc: { format: "avc" } } as VideoEncoderConfig;
    try { if ((await VideoEncoder.isConfigSupported(c)).supported) { config = c; break; } } catch { /* try the next one */ }
  }
  if (!config) throw new Error(`This phone has no H.264 encoder for ${plan.res.id === "1080p" ? "1080p" : plan.res.id.toUpperCase()} at ${fps} frames per second. Try a smaller size${fps > 30 ? " or 30 frames per second" : ""}.`);

  const target = new ArrayBufferTarget();
  const wantsAudio = !!o.audio || !!o.soundEvents?.length;
  const muxer: any = new Muxer({ target, video: { codec: "avc", width: W, height: H, frameRate: fps },
    ...(wantsAudio ? { audio: { codec: "aac", sampleRate: 48000, numberOfChannels: 2 } } : {}), fastStart: "in-memory" });
  let encErr: Error | null = null;
  const enc = new VideoEncoder({ output: (chunk, meta) => muxer.addVideoChunk(chunk, meta), error: e => { encErr = e as Error; } });
  enc.configure(config);

  const canvas = document.createElement("canvas");
  canvas.width = W; canvas.height = H;
  const ctx = canvas.getContext("2d", { alpha: false })!;
  let si = -1, scrub: Scrubber | null = null, lastSvg = "", scene: SceneSource | null = null;

  for (let i = 0; i < total; i++) {
    if (o.cancelled?.()) { scene?.close(); enc.close(); throw new Error("Export cancelled."); }
    if (encErr) throw encErr;
    const t = i / fps;
    let j = si < 0 ? 0 : si;
    while (j + 1 < slides.length && t >= slides[j].end - 1e-9) j++;
    if (j !== si) {
      si = j; lastSvg = ""; scene?.close(); scene = null;
      scrub = slides[j].video ? null : new Scrubber(slides[j].svg);
      if (slides[j].video) scene = await (o.openScene ?? openSceneDom)(slides[j].video!);
    }
    const sl = slides[si];
    if (scene) {   // new workflow step 7: a scene clip: seek to this frame's moment (the last frame is held if the slot is longer than the clip), black bars around it
      await scene.seek(Math.min(Math.max(0, t - sl.start), Math.max(0, scene.duration - 0.04)));
      ctx.fillStyle = "#000000"; ctx.fillRect(0, 0, W, H);
      const fit = fitInto(scene.width, scene.height, W, H);
      scene.draw(ctx, fit.x, fit.y, fit.w, fit.h);
    } else {
    const svg = scrub!.at(Math.max(0, t - sl.start));
    if (svg !== lastSvg) {            // a frame that looks like the one before is not drawn again (pauses and holds are fast)
      const img = await loadSvg(svg);
      ctx.fillStyle = svgBackground(sl.svg); ctx.fillRect(0, 0, W, H);   // the bars (if the picture is not exactly 16:9) have the picture's own background
      const fit = fitInto(sl.width, sl.height, W, H);
      ctx.drawImage(img, fit.x, fit.y, fit.w, fit.h);
      lastSvg = svg;
    }
    }
    const frame = new VideoFrame(canvas, { timestamp: plan.stamp(i), duration: plan.duration });
    enc.encode(frame, { keyFrame: i % plan.keyEvery === 0 });
    frame.close();
    while (enc.encodeQueueSize > 8) await new Promise(r => setTimeout(r, 5));   // do not pile up frames in memory
    if (i % 5 === 0) { o.onProgress?.(i + 1, total); await new Promise(r => setTimeout(r, 0)); }
  }
  scene?.close();
  await enc.flush();
  enc.close();
  if (encErr) throw encErr;

  // Audio is rendered on the same video clock: uploaded music/voice is trimmed or padded,
  // and small element-specific cues are mixed at each element's exact entrance time.
  if (wantsAudio) await encodeTimelineAudio(muxer, seconds, o.audio ?? null, o.clickSound ?? null, o.soundEvents ?? [], o.cancelled);
  muxer.finalize();
  o.onProgress?.(total, total);
  return new Blob([target.buffer], { type: "video/mp4" });
}
