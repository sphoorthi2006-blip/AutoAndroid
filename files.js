// Reads a user's file and returns plain text ready for narration.
// Libraries for zipped formats and PDF are loaded only when needed.
import {
  fileKind, looksLikeText, normalizeText, joinWrappedLines,
  htmlToText, markdownToText, subtitleToText, rtfToText, xmlToText,
} from "./lib.js";

const JSZIP_URL = "https://cdn.jsdelivr.net/npm/jszip@3.10.1/+esm";
const PDFJS_BASE = "https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/build/";

let jszipPromise = null;
let pdfPromise = null;

function getJSZip() {
  if (!jszipPromise) jszipPromise = import(JSZIP_URL).then((m) => m.default || m);
  return jszipPromise;
}
function getPdfjs() {
  if (!pdfPromise) {
    pdfPromise = import(PDFJS_BASE + "pdf.min.mjs").then((m) => {
      m.GlobalWorkerOptions.workerSrc = PDFJS_BASE + "pdf.worker.min.mjs";
      return m;
    });
  }
  return pdfPromise;
}

function decodeBytes(bytes) {
  if (bytes[0] === 0xFF && bytes[1] === 0xFE) return new TextDecoder("utf-16le").decode(bytes.subarray(2));
  if (bytes[0] === 0xFE && bytes[1] === 0xFF) return new TextDecoder("utf-16be").decode(bytes.subarray(2));
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
  } catch (e) {
    return new TextDecoder("windows-1252").decode(bytes);
  }
}

async function zipText(file, JSZip) {
  return JSZip.loadAsync(await file.arrayBuffer());
}

async function readDocx(file) {
  const zip = await zipText(file, await getJSZip());
  const entry = zip.file("word/document.xml");
  if (!entry) throw new Error("This does not look like a Word (.docx) file.");
  return xmlToText(await entry.async("string"));
}

async function readOdt(file) {
  const zip = await zipText(file, await getJSZip());
  const entry = zip.file("content.xml");
  if (!entry) throw new Error("This does not look like an OpenDocument file.");
  return xmlToText(await entry.async("string"));
}

async function readPptx(file) {
  const zip = await zipText(file, await getJSZip());
  const names = Object.keys(zip.files)
    .filter((n) => /^ppt\/slides\/slide\d+\.xml$/.test(n))
    .sort((a, b) => parseInt(a.match(/(\d+)\.xml$/)[1], 10) - parseInt(b.match(/(\d+)\.xml$/)[1], 10));
  if (!names.length) throw new Error("No slides found in this PowerPoint file.");
  const parts = [];
  for (const n of names) parts.push(xmlToText(await zip.file(n).async("string")));
  return parts.join("\n\n");
}

async function readEpub(file) {
  const zip = await zipText(file, await getJSZip());
  let order = [];
  try {
    const container = await zip.file("META-INF/container.xml").async("string");
    const opfPath = container.match(/full-path="([^"]+)"/)[1];
    const opf = await zip.file(opfPath).async("string");
    const base = opfPath.includes("/") ? opfPath.slice(0, opfPath.lastIndexOf("/") + 1) : "";
    const manifest = {};
    for (const m of opf.matchAll(/<item\b[^>]*>/g)) {
      const id = (m[0].match(/\bid="([^"]+)"/) || [])[1];
      const href = (m[0].match(/\bhref="([^"]+)"/) || [])[1];
      if (id && href) manifest[id] = base + decodeURIComponent(href);
    }
    for (const m of opf.matchAll(/<itemref\b[^>]*\bidref="([^"]+)"/g)) {
      if (manifest[m[1]]) order.push(manifest[m[1]]);
    }
  } catch (e) { order = []; }
  if (!order.length) {
    order = Object.keys(zip.files).filter((n) => /\.(xhtml|html|htm)$/i.test(n)).sort();
  }
  const parts = [];
  for (const n of order) {
    const f = zip.file(n);
    if (f) parts.push(htmlToText(await f.async("string")));
  }
  return parts.filter(Boolean).join("\n\n");
}

async function readPdf(file) {
  const pdfjs = await getPdfjs();
  const doc = await pdfjs.getDocument({ data: new Uint8Array(await file.arrayBuffer()) }).promise;
  const pages = [];
  for (let p = 1; p <= doc.numPages; p++) {
    const page = await doc.getPage(p);
    const content = await page.getTextContent();
    pages.push(content.items.map((i) => i.str + (i.hasEOL ? "\n" : " ")).join(""));
  }
  const text = joinWrappedLines(normalizeText(pages.join("\n\n")));
  if (!text) throw new Error("This PDF has no selectable text (it may be a scan or pictures).");
  return text;
}

// Main entry: returns the readable text of one file
export async function fileToText(file) {
  const kind = fileKind(file.name);
  let text;
  switch (kind) {
    case "docx": text = await readDocx(file); break;
    case "odt": text = await readOdt(file); break;
    case "pptx": text = await readPptx(file); break;
    case "epub": text = await readEpub(file); break;
    case "pdf": text = await readPdf(file); break;
    case "unsupported":
      throw new Error("This old format can't be read. Save it as .docx, .pdf or .txt and try again.");
    default: {
      const raw = decodeBytes(new Uint8Array(await file.arrayBuffer()));
      if (kind === "unknown" && !looksLikeText(raw)) {
        throw new Error("This type of file isn't readable text. Try .txt, .docx, .pdf, .epub, .md, .html or .srt.");
      }
      if (kind === "markdown") text = markdownToText(raw);
      else if (kind === "html") text = htmlToText(raw);
      else if (kind === "subtitle") text = subtitleToText(raw);
      else if (kind === "rtf" || /^\{\\rtf/.test(raw)) text = rtfToText(raw);
      else text = normalizeText(raw);
    }
  }
  if (!text || !text.trim()) throw new Error("No readable text found in this file.");
  return text.trim();
}
