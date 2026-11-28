# Kokoro Narrator - notes for Claude

Static web app (no build step) that runs Kokoro-82M in the browser. Files are at the repo root: `index.html`, `app.js`, `lib.js`, `files.js`, `sw.js`, `manifest.json`.

- The repo used to hold other projects (Bezalel, Sukko image upscaler, an old native voice app). All were removed on the owner's request; they remain in git history.
- Hosting: GitHub Pages is currently set to build from the `gh-pages` branch (root). After changing `main`, copy the app files onto `gh-pages` and push both. The sandbox proxy blocks the Pages API, so only the owner can change this setting.
- Speed: `sw.js` adds COOP/COEP (credentialless) headers to page loads so onnxruntime can use several threads; the app reloads once to pick this up. `index.html?safe=1` turns it off, and a "safe mode" button appears only if the model fails to load while isolated.
- The owner is not a developer and uses a phone. Keep instructions as short tap-by-tap steps.
- Never write the owner's GitHub token anywhere (files, git config, remote URLs). A token was pasted into chat earlier and should be revoked.
- The sandbox cannot reach the npm registry, jsDelivr or Hugging Face, so the model, blending and PDF/zip reading have not been run in a real browser. Pure helpers in `lib.js` are tested with Node.
- Blending depends on how `kokoro-js@1.2.1` loads voices (a fetch to `.../voices/<name>.bin`, voice list at `tts.voices`). If the library version changes, re-check this. The app falls back to Voice A if blending fails.
- Bump the cache name in `sw.js` when the list of files or their contents change, so phones pick up updates.
- Model: always `fp32` (326 MB), WebGPU when available, else wasm. It loads automatically when the app opens (no Load button, no quality choice, no live playback: the owner asked for no options). Generate/Preview are always enabled and wait for the model if needed. Phonemes come from the same espeak-based phonemizer on every device, so desktop/mobile differences come from model precision/device, not the text.
