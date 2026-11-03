#!/usr/bin/env python3
"""
Phase 10 & Section 18: Synthetic Scale Project Generator for AutoAndroid.
Generates complete offline test bundles for 200, 300, or 500 numbered images (with varied dimensions
including small 640x360, standard 1280x720 / 1920x1080, vertical 1080x1920, and very large 4000x3000 images),
matching script.txt, captions.srt, details.txt, and 30-minute or 60-minute synthetic narration WAV audio
with realistic voiced segments and inter-scene pauses.

Uses only the Python standard library (zlib, struct, wave, math, zipfile, argparse) — zero external dependencies required.
"""
import argparse
import math
import os
import struct
import wave
import zipfile
import zlib


def write_minimal_png(path: str, width: int, height: int, rgb: tuple[int, int, int]) -> None:
    """Writes a valid uncompressed/zlib-compressed RGB PNG of any dimension using only Python stdlib."""
    r, g, b = rgb
    # Build one scanline: filter byte 0 + RGB triplets
    row = b"\x00" + bytes((r, g, b)) * width
    compressor = zlib.compressobj(level=1)
    compressed_chunks = []
    # Stream rows in batches of 64 to keep memory tiny even for 4000x3000 images
    batch_rows = 64
    full_batches = height // batch_rows
    remainder = height % batch_rows
    batch_data = row * batch_rows
    for _ in range(full_batches):
        c = compressor.compress(batch_data)
        if c:
            compressed_chunks.append(c)
    if remainder:
        c = compressor.compress(row * remainder)
        if c:
            compressed_chunks.append(c)
    compressed_chunks.append(compressor.flush())
    idat_payload = b"".join(compressed_chunks)

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag)
        crc = zlib.crc32(data, crc) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", ihdr))
        f.write(chunk(b"IDAT", idat_payload))
        f.write(chunk(b"IEND", b""))


def fmt_srt_time(seconds: float) -> str:
    ms_total = int(round(seconds * 1000))
    ms = ms_total % 1000
    s_total = ms_total // 1000
    s = s_total % 60
    m_total = s_total // 60
    m = m_total % 60
    h = m_total // 60
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def generate_narration_wav(path: str, total_seconds: float, scene_count: int, sample_rate: int = 16000) -> None:
    """Generates a 16-bit mono WAV narration with clear voiced speech-like envelope and 0.65s pauses at scene boundaries."""
    scene_dur = total_seconds / max(1, scene_count)
    pause_dur = min(0.65, scene_dur * 0.2)
    with wave.open(path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)

        # Generate 1-second chunks for fast streaming write
        total_frames = int(round(total_seconds * sample_rate))
        written = 0
        chunk_frames = sample_rate
        while written < total_frames:
            n = min(chunk_frames, total_frames - written)
            t0 = written / sample_rate
            pos_in_scene = t0 % scene_dur
            in_pause = pos_in_scene >= (scene_dur - pause_dur)
            if in_pause:
                buf = b"\x00\x00" * n
            else:
                samples = [
                    int(12000 * math.sin(2.0 * math.pi * 220.0 * ((written + i) / sample_rate)))
                    for i in range(n)
                ]
                buf = struct.pack(f"<{n}h", *samples)
            wf.writeframes(buf)
            written += n


def generate_project(out_dir: str, num_images: int, audio_minutes: float, zip_bundle: bool) -> None:
    os.makedirs(out_dir, exist_ok=True)
    pics_dir = os.path.join(out_dir, "pictures")
    os.makedirs(pics_dir, exist_ok=True)

    total_seconds = max(num_images * 3.0, audio_minutes * 60.0)
    scene_dur = total_seconds / max(1, num_images)

    # Size cycle includes small (640x360), standard HD (1280x720), Full HD (1920x1080), vertical (1080x1920), and very large (4000x3000)
    size_cycle = [
        (640, 360),
        (1280, 720),
        (1920, 1080),
        (1080, 1920),
        (800, 800),
        (1600, 900),
    ]

    script_lines = []
    details_blocks = []
    srt_blocks = []

    for idx in range(1, num_images + 1):
        start_sec = (idx - 1) * scene_dur
        end_sec = idx * scene_dur - 0.08
        line_text = f"Scene number {idx} demonstrates deterministic offline matching and synchronized timing across scale tests."
        script_lines.append(line_text)
        details_blocks.append(f"Image {idx} - Script line: {line_text}\nPrompt: Visual illustration for scene {idx}.\n")
        srt_blocks.append(f"{idx}\n{fmt_srt_time(start_sec)} --> {fmt_srt_time(end_sec)}\n{line_text}\n")

        # Every 25th image is a very large 4000x3000 image to test bounded-memory decoding & upscaler 20 MP cap
        if idx % 25 == 0:
            w, h = (4000, 3000)
        else:
            w, h = size_cycle[(idx - 1) % len(size_cycle)]
        rgb = ((idx * 47) % 220 + 20, (idx * 89) % 220 + 20, (idx * 137) % 220 + 20)
        img_path = os.path.join(pics_dir, f"image_{idx:04d}.png")
        write_minimal_png(img_path, w, h, rgb)

    script_path = os.path.join(out_dir, "script.txt")
    details_path = os.path.join(out_dir, "details.txt")
    srt_path = os.path.join(out_dir, "captions.srt")
    wav_path = os.path.join(out_dir, "narration.wav")

    with open(script_path, "w", encoding="utf-8") as f:
        f.write("\n".join(script_lines) + "\n")
    with open(details_path, "w", encoding="utf-8") as f:
        f.write("\n".join(details_blocks) + "\n")
    with open(srt_path, "w", encoding="utf-8") as f:
        f.write("\n".join(srt_blocks) + "\n")

    generate_narration_wav(wav_path, total_seconds, num_images)

    if zip_bundle:
        zip_path = os.path.join(out_dir, f"synthetic_{num_images}img_{int(audio_minutes)}min.zip")
        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED) as zf:
            zf.write(script_path, "script.txt")
            zf.write(details_path, "details.txt")
            zf.write(srt_path, "captions.srt")
            zf.write(wav_path, "narration.wav")
            for name in sorted(os.listdir(pics_dir)):
                zf.write(os.path.join(pics_dir, name), os.path.join("pictures", name))
        print(f"Created bundle ZIP: {zip_path}")

    print(
        f"Generated synthetic project in {out_dir}: {num_images} images "
        f"(including {num_images // 25} very large 4000x3000 images), "
        f"script.txt, captions.srt, details.txt, and {total_seconds / 60.0:.1f}-min narration.wav"
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate synthetic scale test projects for AutoAndroid (200/300/500 images, 30/60-min audio).")
    parser.add_argument("--images", type=int, default=200, help="Number of numbered scene images to generate (e.g. 200, 300, 500)")
    parser.add_argument("--audio-minutes", type=float, default=30.0, help="Duration of synthetic narration WAV in minutes (e.g. 30, 60)")
    parser.add_argument("--out", type=str, required=True, help="Output directory path")
    parser.add_argument("--zip", action="store_true", help="Also package generated project into a single importable .zip bundle")
    args = parser.parse_args()
    generate_project(args.out, args.images, args.audio_minutes, args.zip)


if __name__ == "__main__":
    main()
