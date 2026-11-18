import { useEffect, useState } from "react";
import { estimateBytes, mp4Supported, RESOLUTIONS, supportedResolutions } from "./video";

const mb = (b: number) => (b >= 1e9 ? `${(b / 1e9).toFixed(1)} GB` : `${Math.max(1, Math.round(b / 1e6))} MB`);

/** Step 24: 1080p / 2K / 4K buttons. Asks the phone's encoder which sizes it can make and warns about very big files. */
export function ResolutionPicker({ value, onChange, fps, seconds, disabled }: { value: string; onChange: (id: string) => void; fps: number; seconds: number; disabled?: boolean }) {
  const [ok, setOk] = useState<Record<string, boolean> | null>(null);
  useEffect(() => {
    let live = true;
    setOk(null);
    if (mp4Supported()) supportedResolutions(fps).then(r => { if (live) setOk(r); }).catch(() => { if (live) setOk(null); });
    return () => { live = false; };
  }, [fps]);
  const size = estimateBytes(seconds, fps, value);
  const cur = RESOLUTIONS.find(r => r.id === value) ?? RESOLUTIONS[0];
  return (
    <div>
      <div className="row wrap">
        {RESOLUTIONS.map(r => <button key={r.id} disabled={disabled} className={value === r.id ? "on" : "ghost"} onClick={() => onChange(r.id)}>{r.id === "1080p" ? "1080p" : r.id.toUpperCase()}{ok && !ok[r.id] ? " ✕" : ""}</button>)}
      </div>
      <p className="hint small">{cur.w} x {cur.h}{value === "4k" ? ", 30 fps only" : ""}. About {mb(size)} for {Math.round(seconds)} s.{ok && !ok[value] ? " This phone's encoder does not support this size at this frame rate; the export will say so. Try a smaller size." : ""}{size > 400e6 ? " Big file: the video is built in memory, so a phone with little RAM may fail. A smaller size or 30 fps is safer." : ""}</p>
    </div>
  );
}
