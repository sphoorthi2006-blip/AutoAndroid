/** Save progress for the SVG Editor: the project (pictures, timing file, scene videos, audio and every setting) is kept on the phone in the web view's own
 *  database (IndexedDB), so closing the app or leaving the screen does not start the work again from nothing.
 *  Big files are stored one by one ("asset" records) and only when they change; the small settings live in ONE "meta" record, written last, so a save that is
 *  cut short never leaves a meta record that points at missing files. Every call answers null / false instead of throwing (private mode, no storage, full disk). */

const DB_NAME = "bezalel-project", STORE = "kv", VERSION = 1;

export interface AssetRec { blob: Blob; name: string; type: string }

/** What the screen keeps besides the files. Plain JSON only. */
export interface ProjectMeta {
  v: 1;
  savedAt: number;
  settings: Record<string, unknown>;
  specText: string | null; specName: string;
  kinds: Record<string, unknown>;
  unnamedClips: string[];
  cur: number;
  pics: { id: string; key: string; n: number; name: string; upscaled: boolean }[];
  clips: { id: string; name: string }[];
  audio: { id: string; name: string } | null;
}

function open(): Promise<IDBDatabase | null> {
  return new Promise(ok => {
    try {
      if (typeof indexedDB === "undefined") return ok(null);
      const rq = indexedDB.open(DB_NAME, VERSION);
      rq.onupgradeneeded = () => { if (!rq.result.objectStoreNames.contains(STORE)) rq.result.createObjectStore(STORE); };
      rq.onsuccess = () => ok(rq.result);
      rq.onerror = () => ok(null);
      rq.onblocked = () => ok(null);
    } catch { ok(null); }
  });
}

async function tx<T>(mode: IDBTransactionMode, run: (s: IDBObjectStore) => IDBRequest<T> | void): Promise<T | null | undefined> {
  const db = await open();
  if (!db) return null;
  return new Promise(ok => {
    try {
      const t = db.transaction(STORE, mode);
      const rq = run(t.objectStore(STORE));
      t.oncomplete = () => { db.close(); ok(rq ? (rq.result as T) : undefined); };
      t.onerror = t.onabort = () => { db.close(); ok(null); };
    } catch { try { db.close(); } catch { /* already closed */ } ok(null); }
  });
}

/** Is saving possible at all on this phone? */
export async function storeAvailable(): Promise<boolean> { return (await open().then(d => { d?.close(); return !!d; })); }

export async function getMeta(): Promise<ProjectMeta | null> {
  const m = await tx<ProjectMeta>("readonly", s => s.get("meta"));
  return m && (m as ProjectMeta).v === 1 ? (m as ProjectMeta) : null;
}
export async function getAsset(id: string): Promise<AssetRec | null> {
  const a = await tx<AssetRec>("readonly", s => s.get("asset:" + id));
  return a && (a as AssetRec).blob ? (a as AssetRec) : null;
}
export async function putAsset(id: string, rec: AssetRec): Promise<boolean> { return (await tx("readwrite", s => s.put(rec, "asset:" + id))) !== null; }
export async function putMeta(meta: ProjectMeta): Promise<boolean> { return (await tx("readwrite", s => s.put(meta, "meta"))) !== null; }
export async function deleteAssets(ids: string[]): Promise<void> { if (ids.length) await tx("readwrite", s => { ids.forEach(i => s.delete("asset:" + i)); }); }
/** Every file id currently stored (to remove the ones the project no longer uses). */
export async function listAssetIds(): Promise<string[]> {
  const keys = await tx<IDBValidKey[]>("readonly", s => s.getAllKeys());
  return (keys ?? []).map(String).filter(k => k.startsWith("asset:")).map(k => k.slice(6));
}
export async function clearProject(): Promise<boolean> { return (await tx("readwrite", s => s.clear())) !== null; }
