import { DEFAULT_ANIM, type Project } from "./types";
import type { FoundPanels } from "./panelmatch";
import { BACKEND_VERSION } from "./compose";
import { hasHost, nativeCall, toBase64 } from "./bridge";
const API = import.meta.env.VITE_API_URL ?? "http://localhost:8000";

export async function processImage(file: File): Promise<Project> {
  let data: any;
  if (hasHost()) {   // inside the Bezalel app: the Kotlin engine answers instead of the HTTP backend
    data = await nativeCall("process", await toBase64(file));
    if (data?.error) throw new Error(data.detail ?? "Processing failed.");
  } else {
    const body = new FormData();
    body.append("file", file);
    const res = await fetch(`${API}/api/process`, { method: "POST", body });
    if (!res.ok) {
      const msg = await res.json().catch(() => null);
      throw new Error(msg?.detail ?? "Processing failed. Check that the backend is running.");
    }
    data = await res.json();
  }
  return { ...data, layers: data.layers.map((l: any) => ({ ...l, visible: true, tx: 0, ty: 0, scale: 1, rot: 0, anim: { ...DEFAULT_ANIM } })) };
}

/** Which backend is answering? Returns its version ("" for a backend older than 0.10) or null when nothing answers. */
export async function backendVersion(): Promise<string | null> {
  if (hasHost()) { try { await nativeCall("health"); return BACKEND_VERSION; } catch { return null; } }
  try { const r = await fetch(`${API}/api/health`); const j = await r.json(); return j.version ?? ""; } catch { return null; }
}

/** v0.15: the panels (thick black outlines) of a picture, with their labels, caption boxes, arrows and the ribbon. An empty `panels` list means none were found
 *  (use processImage / segment instead). Returns null when the backend is older than 0.15 or cannot be reached, so callers can simply carry on without panels. */
export async function detectPanels(file: File): Promise<FoundPanels | null> {
  panelsStatus = "";
  if (hasHost()) {
    try {
      const j = await nativeCall<any>("panels", await toBase64(file));
      if (j?.error) { panelsStatus = j.detail ?? "the app could not find panels"; return null; }
      if (!j.panels?.length) panelsStatus = "no panels were found in this picture";
      return j;
    } catch (e) { panelsStatus = (e as Error).message; return null; }
  }
  try {
    const body = new FormData();
    body.append("file", file);
    const res = await fetch(`${API}/api/panels`, { method: "POST", body });
    if (!res.ok) { panelsStatus = res.status === 404 ? "the backend is older than 0.15 (restart with START.bat from the newest folder)" : `the backend answered an error (${res.status}): ${(await res.text().catch(() => "")).slice(0, 120)}`; return null; }
    const j = await res.json();
    if (!j.panels?.length) panelsStatus = "no panels were found in this picture";
    return j;
  } catch { panelsStatus = "the backend could not be reached"; return null; }
}
/** Why the last detectPanels call gave no panels ("" when it did). Shown to the person, so the old element path is never used silently. */
export let panelsStatus = "";
