# Kokoro Narrator - notes for Claude

Static web app (no build step) that runs Kokoro-82M in the browser. Files are at the repo root: `index.html`, `app.js`, `lib.js`, `files.js`, `sw.js`, `manifest.json`.

- The repo used to hold other projects (Bezalel, Sukko image upscaler, an old native voice app). All were removed on the owner's request; they remain in git history.
- Hosting is GitHub Pages from `main` at `/ (root)`. The owner turns Pages on in the GitHub website; the sandbox proxy blocks the Pages API.
- The owner is not a developer and uses a phone. Keep instructions as short tap-by-tap steps.
- Never write the owner's GitHub token anywhere (files, git config, remote URLs). A token was pasted into chat earlier and should be revoked.
- The sandbox cannot reach the npm registry, jsDelivr or Hugging Face, so the model, blending and PDF/zip reading have not been run in a real browser. Pure helpers in `lib.js` are tested with Node.
- Blending depends on how `kokoro-js@1.2.1` loads voices (a fetch to `.../voices/<name>.bin`, voice list at `tts.voices`). If the library version changes, re-check this. The app falls back to Voice A if blending fails.
- Bump the cache name in `sw.js` when the list of files or their contents change, so phones pick up updates.
