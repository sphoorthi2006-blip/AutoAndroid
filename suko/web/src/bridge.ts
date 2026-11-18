// Suko host bridge. Inside the Suko Android app the page is served from the app's own files and
// the Kotlin side injects `window.SukoBridge`. In a normal browser it does not exist, and every
// caller falls back to the old behaviour (HTTP backend, <a download>).
//
// Protocol (all arguments are strings):
//   call(id, method, payload)         starts a job; the result is a JSON file fetched from /bridge/<id>
//   saveBegin / saveChunk / saveEnd   saves a file through Android (base64 chunks)
//   inputList()                       JSON list of the files the app handed over; bytes come from /input/<n>
//   info()                            JSON about the host
// Kotlin reports "job finished" by calling window.__sukoDone(id, ok, message).

type Host = {
  call(id: string, method: string, payload: string): void;
  saveBegin(id: string, name: string, mime: string): void;
  saveChunk(id: string, base64: string): void;
  saveEnd(id: string): void;
  inputList(): string;
  info(): string;
};

const host = (): Host | undefined => (globalThis as any).SukoBridge;
export const hasHost = (): boolean => !!host();

export const SAVE_CHUNK_BYTES = 3 * 1024 * 1024;

type Waiter = { ok: (message: string) => void; fail: (e: Error) => void };
const waiting = new Map<string, Waiter>();
let seq = 0;

(globalThis as any).__sukoDone = (id: string, ok: boolean, message: string) => {
  const w = waiting.get(id);
  if (!w) return;
  waiting.delete(id);
  if (ok) w.ok(message); else w.fail(new Error(message || "The app could not finish the job."));
};

function wait(id: string): Promise<string> {
  return new Promise((ok, fail) => waiting.set(id, { ok, fail }));
}

/** Bytes to base64 without FileReader (also works in Node tests). */
export async function toBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + 0x8000)));
  return btoa(s);
}

/** Runs a native method and returns its JSON result. Rejects with the native error message. */
export async function nativeCall<T = any>(method: string, payload = ""): Promise<T> {
  const h = host();
  if (!h) throw new Error("This page is not running inside the Suko app.");
  const id = `c${Date.now().toString(36)}${++seq}`;
  const done = wait(id);
  h.call(id, method, payload);
  await done;
  const res = await fetch(`/bridge/${id}`);
  if (!res.ok) throw new Error("The app's answer could not be read.");
  return (await res.json()) as T;
}

/** Saves a file through Android (Download/Suko). Resolves with where it was saved. */
export async function saveFile(name: string, blob: Blob, mime = blob.type || "application/octet-stream", chunk = SAVE_CHUNK_BYTES): Promise<string> {
  const h = host();
  if (!h) throw new Error("This page is not running inside the Suko app.");
  const id = `s${Date.now().toString(36)}${++seq}`;
  const done = wait(id);
  done.catch(() => { /* reported to the caller below; stops a "failed early" answer from looking unhandled */ });
  try {
    h.saveBegin(id, name, mime);
    for (let at = 0; at < blob.size; at += chunk) h.saveChunk(id, await toBase64(blob.slice(at, at + chunk)));
    h.saveEnd(id);
  } catch (e) {
    waiting.delete(id);
    throw e;
  }
  return done;
}

export type InputInfo = { name: string; mime: string; size: number };

/** The files the app handed over (from its own intake), as File objects. Empty in a browser. */
export async function inputFiles(): Promise<File[]> {
  const h = host();
  if (!h) return [];
  const list = JSON.parse(h.inputList() || "[]") as InputInfo[];
  const out: File[] = [];
  for (let i = 0; i < list.length; i++) {
    const res = await fetch(`/input/${i}`);
    if (!res.ok) throw new Error(`The app could not give out ${list[i].name}.`);
    out.push(new File([await res.blob()], list[i].name, { type: list[i].mime }));
  }
  return out;
}

export function hostInfo(): any {
  const h = host();
  return h ? JSON.parse(h.info() || "{}") : null;
}
