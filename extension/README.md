# Image Upscaler (browser extension)

Same tool as the Android app, as a Chrome / Edge / Brave extension (Manifest V3, Chrome 103+).
Click the toolbar icon to open the Upscaler page in a tab.

## Install (no store needed)
1. Unzip `UpscalerExtension.zip` (or use the `extension` folder from the repo).
2. Open `chrome://extensions` (Edge: `edge://extensions`).
3. Switch on **Developer mode** (top right).
4. Click **Load unpacked** and select the unzipped folder (the one that contains `manifest.json`).
5. Pin the extension and click its icon.

## Use
- Drop images, ZIPs or a whole folder on the page, or use the two buttons.
- The corner mark is removed automatically and every picture is upscaled 2x (same settings as the app).
- **Before / After slider**: open it from the results (button or click a picture). Drag the handle to compare,
  scroll / pinch / double-click to zoom, arrow keys for previous / next, Esc to close.
- Get the results as single PNGs, **Download all (ZIP)**, or **Save to folder...** (Chrome / Edge).

Everything runs locally in the browser. The extension asks for no permissions and makes no network requests.
