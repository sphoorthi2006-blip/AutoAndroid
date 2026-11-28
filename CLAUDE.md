# Voice Studio - notes for Claude

Static web app (no build step): script to natural, conversational narration using Google's Gemini TTS API with the owner's own free API key. Files are at the repo root: `index.html`, `app.js`, `studio-lib.js` (pure helpers, tested with Node), `files.js` + `lib.js` (file reading), `sw.js`, `manifest.json`.

- History: the repo used to hold other projects (Bezalel, Sukko, an old native voice app) and then a Kokoro-in-the-browser narrator. Kokoro was dropped because the 330 MB model crashed the owner's phone and the 90 MB one had poor pronunciation. All of it remains in git history. `sw.js` deletes old caches, including the Kokoro model.
- Hosting: GitHub Pages builds from the `gh-pages` branch (root). After changing `main`, copy the app files onto `gh-pages` and push both. The sandbox proxy blocks the Pages API.
- The owner is not a developer and uses a phone. Keep instructions as short tap-by-tap steps. The owner asked for no fiddly options: keep the UI minimal.
- Never write the owner's GitHub token or Gemini key anywhere in the repo. The Gemini key lives only in the phone's localStorage (`geminiKey`).
- API: `POST https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent` with `x-goog-api-key`. Models are tried in order (`MODELS` in `studio-lib.js`); 404, no audio or exhausted quota moves to the next model; short 429 retryDelay waits and retries. The last working model is remembered (`model` in localStorage). Audio may come back as raw L16 PCM (2.5 models) or WAV (3.8 models); `decodeAudio` handles both.
- Scripts with exactly two "Name: text" speakers use multi-speaker voice config; anything else is single-voice narration. Long scripts are split into pieces of about 2,500 characters and joined into one WAV.
- The sandbox cannot reach Google's API, so tests mock it with Playwright routes. Real-world free-tier limits are not published and vary by account.
- Bump the cache name in `sw.js` when files change.
