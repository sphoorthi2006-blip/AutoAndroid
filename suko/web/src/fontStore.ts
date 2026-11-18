// Step 24: uploaded fonts are kept on the phone (IndexedDB) so they are still there the next time the app opens.
// Every call is wrapped: when storage is blocked or missing (private window, tests) the fonts simply last until the app closes.
import { addCustomFont, removeCustomFont, type FontChoice } from "./textanim";

const DB = "suko-fonts", STORE = "fonts";
type Saved = { id: string; name: string; bytes: ArrayBuffer };

function open(): Promise<IDBDatabase | null> {
  return new Promise(ok => {
    try {
      if (typeof indexedDB === "undefined") return ok(null);
      const rq = indexedDB.open(DB, 1);
      rq.onupgradeneeded = () => { rq.result.createObjectStore(STORE, { keyPath: "id" }); };
      rq.onsuccess = () => ok(rq.result);
      rq.onerror = () => ok(null);
    } catch { ok(null); }
  });
}
function run<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>): Promise<T | null> {
  return open().then(db => new Promise<T | null>(ok => {
    if (!db) return ok(null);
    try {
      const rq = f(db.transaction(STORE, mode).objectStore(STORE));
      rq.onsuccess = () => { db.close(); ok(rq.result); };
      rq.onerror = () => { db.close(); ok(null); };
    } catch { try { db.close(); } catch { /* ignore */ } ok(null); }
  }));
}

/** Reads a font file, adds it to the font list and remembers it. Rejects with a plain message when the file is not a font. */
export async function uploadFont(file: File): Promise<FontChoice> {
  const bytes = await file.arrayBuffer();
  const choice = addCustomFont(file.name, bytes);
  await run("readwrite", s => s.put({ id: choice.id, name: file.name, bytes } as Saved));
  return choice;
}
export async function deleteFont(id: string): Promise<void> {
  removeCustomFont(id);
  await run("readwrite", s => s.delete(id));
}
let restored: Promise<FontChoice[]> | null = null;
/** Puts the remembered fonts back into the list (once per app start). */
export function restoreFonts(): Promise<FontChoice[]> {
  if (!restored) restored = run<Saved[]>("readonly", s => s.getAll() as IDBRequest<Saved[]>).then(all => {
    const out: FontChoice[] = [];
    for (const f of all ?? []) { try { out.push(addCustomFont(f.name, f.bytes)); } catch { /* a damaged entry is skipped */ } }
    return out;
  });
  return restored;
}
