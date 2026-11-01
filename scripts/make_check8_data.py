"""Creates the input files Check8.kt needs in the folder given as argv[1] (real .docx, real images)."""
import sys, os, zipfile, struct, zlib
d = sys.argv[1]; os.makedirs(d + "/p", exist_ok=True)
def para(*runs): return "<w:p>" + "".join(runs) + "</w:p>"
def run(t): return f'<w:r><w:t xml:space="preserve">{t}</w:t></w:r>'
body = (para(run('Image 1 - Script line: "Tom &amp; Jerry &lt;meet&gt;"')) + para(run("Subject: a forest")) +
        para(run("Split run "), run("here"), "<w:r><w:tab/></w:r>", run("after tab")) +
        para(run("line1"), "<w:r><w:br/></w:r>", run("line2")) +
        "<w:tbl><w:tr><w:tc>" + para(run("Cell A")) + "</w:tc><w:tc>" + para(run("Cell B &#233;")) + "</w:tc></w:tr></w:tbl>")
def docx(path, b):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("[Content_Types].xml", "<Types/>")
        z.writestr("word/document.xml", f'<?xml version="1.0"?><w:document xmlns:w="x"><w:body>{b}</w:body></w:document>')
docx(d + "/test.docx", body); docx(d + "/empty.docx", para("")); docx(d + "/story.docx", para(run("The story begins here and goes on for a while.")))
with zipfile.ZipFile(d + "/notdocx.docx", "w") as z: z.writestr("a.txt", "x")
open(d + "/plain.txt", "w").write("hello\nworld")
def png(w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    ch = lambda t, x: struct.pack(">I", len(x)) + t + x + struct.pack(">I", zlib.crc32(t + x) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + ch(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + ch(b"IDAT", zlib.compress(raw)) + ch(b"IEND", b"")
open(d + "/p/a001.png", "wb").write(png(8, 8, (255, 0, 0)))
open(d + "/p/a002.png", "wb").write(png(8, 8, (0, 0, 255)))      # same size as a001, different bytes
open(d + "/p/a003.png", "wb").write(png(8, 8, (255, 0, 0)))      # byte-identical to a001
open(d + "/p/a004.jpg", "wb").write(b"\xff\xd8\xff\xe0" + b"\0" * 20)
open(d + "/p/a005.webp", "wb").write(b"RIFF\x10\0\0\0WEBPVP8 " + b"\0" * 8)
open(d + "/junk.png", "wb").write(b"this is not an image at all")
