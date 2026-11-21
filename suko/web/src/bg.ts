import { hasHost, saveFile } from "./bridge";
import type { Background } from "./types";

/** The background of a text picture: a colour, or a picture covering the whole canvas (zoom and offsets from the Style tab). */
export function bgMarkup(p: { width: number; height: number }, bg: Background): string {
  if (bg.kind === "color") return `<rect width="${p.width}" height="${p.height}" fill="${bg.color}"/>`;
  const i = bg.image;
  const s = Math.max(p.width / i.w, p.height / i.h) * i.zoom;
  const w = i.w * s, h = i.h * s;
  const x = (p.width - w) / 2 + i.offX * p.width, y = (p.height - h) / 2 + i.offY * p.height;
  return `<image href="${i.dataUrl}" x="${x}" y="${y}" width="${w}" height="${h}" preserveAspectRatio="none"/>`;
}

/** Saves a file: inside the Bezalel app through Android (Download/Bezalel), in a plain browser as a normal download. */
export function download(name: string, blob: Blob) {
  if (hasHost()) {   // inside the Bezalel app a blob link does nothing
    saveFile(name, blob).catch(e => alert(`Could not save ${name}: ${(e as Error).message}`));
    return;
  }
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob); a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}
