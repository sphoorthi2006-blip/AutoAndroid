import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "node:path";
// Builds the Studio and the smoke test into the Android app's assets. Relative paths (base "./") so the app serves them from any folder.
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: "../app/src/main/assets/web/studio",
    emptyOutDir: true,
    rollupOptions: { input: { index: resolve(__dirname, "index.html"), smoke: resolve(__dirname, "smoke.html") } },
  },
});
