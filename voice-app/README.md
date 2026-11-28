# Kokoro Narrator

A free web app for English narration. It runs the open-source Kokoro-82M model (Apache 2.0) in the browser, so there is no server, no account and no cost. It installs to a phone's home screen like an app.

Main features:
- **Voice blending:** pick Voice A and Voice B and mix them with a slider (for example female + male). The two voices' style tables are averaged.
- **Narration presets:** suggested starting points for storytelling, conversation, documentary and British narration. Tune by ear.
- **Preview** the first sentence before generating a long script.
- Long scripts are split into sentences, then joined into one WAV file you can play or download.

## Files

| File | Purpose |
|---|---|
| `index.html` | The page |
| `app.js` | Model loading, blending, generation |
| `lib.js` | Voice list, presets, blend maths, text splitting, WAV writer |
| `manifest.json`, `sw.js`, `icon-*.png` | Home-screen install and offline shell |

## Hosting

The app is served by GitHub Pages from the `gh-pages` branch of this repository. To update it, copy the files in this folder to the root of `gh-pages`.

On a phone: open the Pages address in Chrome, then menu > **Add to Home screen**. The first run downloads the model (about 90 MB), then the browser caches it.

## Notes

- Blending relies on how the `kokoro-js` library (pinned to 1.2.1) loads voices. If blending ever fails, the app says so and uses Voice A alone.
- Generation runs on the phone's processor, so older phones take longer than the length of the audio.
- Model: https://huggingface.co/hexgrad/Kokoro-82M (Apache 2.0).
- This folder is listed in the Sukko workflow's `paths-ignore`, so changes here do not rebuild the Android app.
