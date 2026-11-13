// Tiny ZIP reader / writer (no libraries). Reads stored + deflated entries; writes stored entries
// (PNG is already compressed, so storing is the right choice).

const te = new TextEncoder();
const td = new TextDecoder('utf-8');

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    t[n] = c >>> 0;
  }
  return t;
})();

export function crc32(bytes) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

/** Lists the entries of a ZIP (Blob/File). Each entry has name, isDir and an async blob() that unpacks it. */
export async function readZip(blob) {
  const size = blob.size;
  if (size < 22) throw new Error('not a ZIP file');
  const tailLen = Math.min(size, 65557);
  const tail = new DataView(await blob.slice(size - tailLen).arrayBuffer());
  let eocd = -1;
  for (let i = tailLen - 22; i >= 0; i--) {
    if (tail.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('not a ZIP file');
  const count = tail.getUint16(eocd + 10, true);
  const cdSize = tail.getUint32(eocd + 12, true);
  const cdOff = tail.getUint32(eocd + 16, true);
  if (count === 0xFFFF || cdOff === 0xFFFFFFFF || cdSize === 0xFFFFFFFF) throw new Error('ZIP64 archives are not supported');

  const cd = new DataView(await blob.slice(cdOff, cdOff + cdSize).arrayBuffer());
  const cdBytes = new Uint8Array(cd.buffer);
  const entries = [];
  let p = 0;
  while (p + 46 <= cd.byteLength && cd.getUint32(p, true) === 0x02014b50) {
    const flags = cd.getUint16(p + 8, true);
    const method = cd.getUint16(p + 10, true);
    const csize = cd.getUint32(p + 20, true);
    const nlen = cd.getUint16(p + 28, true);
    const elen = cd.getUint16(p + 30, true);
    const clen = cd.getUint16(p + 32, true);
    const lho = cd.getUint32(p + 42, true);
    const name = td.decode(cdBytes.subarray(p + 46, p + 46 + nlen)).replace(/\\/g, '/');
    p += 46 + nlen + elen + clen;
    const isDir = name.endsWith('/');
    entries.push({
      name,
      isDir,
      async blob() {
        if (flags & 1) throw new Error('encrypted ZIP entries are not supported');
        const lh = new DataView(await blob.slice(lho, lho + 30).arrayBuffer());
        if (lh.getUint32(0, true) !== 0x04034b50) throw new Error('damaged ZIP entry');
        const start = lho + 30 + lh.getUint16(26, true) + lh.getUint16(28, true);
        const raw = blob.slice(start, start + csize);
        if (method === 0) return raw;
        if (method === 8) return await new Response(raw.stream().pipeThrough(new DecompressionStream('deflate-raw'))).blob();
        throw new Error('unsupported ZIP compression method ' + method);
      },
    });
  }
  return entries;
}

/** Builds a ZIP Blob from [{name, blob}]. */
export async function buildZip(files) {
  const parts = [];
  const central = [];
  let offset = 0;
  const now = new Date();
  const dosTime = (now.getHours() << 11) | (now.getMinutes() << 5) | (now.getSeconds() >> 1);
  const dosDate = ((now.getFullYear() - 1980) << 9) | ((now.getMonth() + 1) << 5) | now.getDate();

  for (const f of files) {
    const data = new Uint8Array(await f.blob.arrayBuffer());
    if (data.length > 0xFFFFFFFE || offset > 0xFFFFFFFE) throw new Error('ZIP is too large (4 GB limit)');
    const name = te.encode(f.name);
    const crc = crc32(data);

    const lh = new DataView(new ArrayBuffer(30));
    lh.setUint32(0, 0x04034b50, true);
    lh.setUint16(4, 20, true);
    lh.setUint16(6, 0x0800, true);            // UTF-8 names
    lh.setUint16(8, 0, true);                 // stored
    lh.setUint16(10, dosTime, true);
    lh.setUint16(12, dosDate, true);
    lh.setUint32(14, crc, true);
    lh.setUint32(18, data.length, true);
    lh.setUint32(22, data.length, true);
    lh.setUint16(26, name.length, true);
    lh.setUint16(28, 0, true);
    parts.push(lh.buffer, name, data);

    const ch = new DataView(new ArrayBuffer(46));
    ch.setUint32(0, 0x02014b50, true);
    ch.setUint16(4, 20, true);
    ch.setUint16(6, 20, true);
    ch.setUint16(8, 0x0800, true);
    ch.setUint16(10, 0, true);
    ch.setUint16(12, dosTime, true);
    ch.setUint16(14, dosDate, true);
    ch.setUint32(16, crc, true);
    ch.setUint32(20, data.length, true);
    ch.setUint32(24, data.length, true);
    ch.setUint16(28, name.length, true);
    ch.setUint32(42, offset, true);
    central.push(ch.buffer, name);

    offset += 30 + name.length + data.length;
  }
  let cdSize = 0;
  for (const c of central) cdSize += c.byteLength;
  if (files.length > 0xFFFE) throw new Error('too many files for one ZIP');
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, files.length, true);
  end.setUint16(10, files.length, true);
  end.setUint32(12, cdSize, true);
  end.setUint32(16, offset, true);
  return new Blob([...parts, ...central, end.buffer], { type: 'application/zip' });
}
