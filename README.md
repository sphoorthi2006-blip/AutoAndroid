# Kokoro Narrator

A free web app for English narration. It runs the open-source Kokoro-82M model (Apache 2.0) in the browser, so there is no server, no account and no cost. It installs to a phone's home screen like an app.

## Features
- **Add files:** .txt, .md, .docx, .pdf, .epub, .odt, .pptx, .rtf, .html, .srt, .vtt, .csv and other plain-text files. Several files can be added, and the text can be edited before narrating. PDFs need selectable text (scans are not read).
- **Narration styles:** suggested presets for storytelling, conversation, and British narration. Tune by ear.
- **Voice blending:** pick Voice A and Voice B and mix them with a slider (for example female + male).
- **Preview** the first sentence before generating a long script.
- **Best model, no setup:** the full Kokoro model (about 330 MB) downloads once and opens by itself, using the graphics chip where possible.
- **Faster:** uses several processor cores where the phone allows.
- Long texts are made in parts of about 4 minutes each, each with its own player and WAV download.

## Files

| File | Purpose |
|---|---|
| `index.html` | The page |
| `app.js` | Model loading, blending, generation |
| `lib.js` | Voice list, presets, blend maths, text cleaning, text splitting, WAV writer |
| `files.js` | Reads uploaded files (zip formats and PDF libraries load only when needed) |
| `manifest.json`, `sw.js`, `icon-*.png` | Home-screen install and offline shell |

## Hosting (GitHub Pages)

In the repository on GitHub: **Settings > Pages > Build and deployment > Deploy from a branch**, choose branch `main` and folder `/ (root)`, then save. The app appears at `https://<username>.github.io/<repository>/`.

On a phone: open that address in Chrome, then menu > **Add to Home screen**. The first run downloads the model (about 330 MB), then the browser caches it.

## Notes
- Blending relies on how the `kokoro-js` library (pinned to 1.2.1) loads voices. If blending fails, the app says so and uses Voice A alone.
- Generation runs on the phone's processor, so older phones take longer than the length of the audio. The screen is kept on while generating where the browser allows it.
- Model: https://huggingface.co/hexgrad/Kokoro-82M (Apache 2.0).
