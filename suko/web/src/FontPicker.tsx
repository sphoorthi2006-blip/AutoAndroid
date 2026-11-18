import { useEffect, useRef, useState } from "react";
import { allFonts, FONT_EXTENSIONS, type FontChoice } from "./textanim";
import { deleteFont, restoreFonts, uploadFont } from "./fontStore";

/** Step 24: the font list plus "Upload font". Used by the Text tab and the Video tab. */
export function FontPicker({ value, onChange, disabled }: { value: string; onChange: (id: string) => void; disabled?: boolean }) {
  const [fonts, setFonts] = useState<FontChoice[]>(allFonts());
  const [msg, setMsg] = useState("");
  const input = useRef<HTMLInputElement>(null);
  const refresh = () => setFonts(allFonts());

  useEffect(() => {
    let live = true;
    restoreFonts().then(() => { if (live) refresh(); });
    return () => { live = false; };
  }, []);

  async function pick(files: File[]) {
    setMsg("");
    let last: FontChoice | null = null;
    const bad: string[] = [];
    for (const f of files) {
      try { last = await uploadFont(f); } catch (e) { bad.push((e as Error).message); }
    }
    refresh();
    if (last) { onChange(last.id); setMsg(files.length > 1 ? `${files.length - bad.length} font(s) added.` : `Added ${last.label}.`); }
    if (bad.length) setMsg(m => [m, ...bad].filter(Boolean).join(" "));
  }
  async function remove() {
    const f = fonts.find(x => x.id === value);
    if (!f?.custom) return;
    await deleteFont(f.id);
    refresh(); onChange(allFonts()[0].id); setMsg(`Removed ${f.label}.`);
  }
  const cur = fonts.find(f => f.id === value);

  return (
    <div className="fontpick">
      <label>Font <select value={cur ? value : fonts[0].id} disabled={disabled} onChange={e => onChange(e.target.value)}>{fonts.map(f => <option key={f.id} value={f.id}>{f.label}</option>)}</select></label>
      <div className="row wrap">
        <button className="ghost" disabled={disabled} onClick={() => input.current?.click()}>Upload font…</button>
        {cur?.custom && <button className="ghost" disabled={disabled} onClick={remove}>Remove this font</button>}
      </div>
      <input ref={input} type="file" multiple accept={FONT_EXTENSIONS.join(",")} hidden onChange={e => { void pick(Array.from(e.target.files ?? [])); e.target.value = ""; }} />
      <p className="hint small">{msg || "Use your own .ttf, .otf or .woff file. It is kept on this phone for next time."}</p>
    </div>
  );
}
