// Caches the app shell so the page opens offline. The voice model and voices
// are cached separately by the browser after the first download.
const CACHE = "kokoro-narrator-v4";
const SHELL = ["./", "index.html", "app.js", "lib.js", "files.js", "manifest.json", "icon-192.png", "icon-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE && k.startsWith("kokoro-narrator")).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

// Speed boost: GitHub Pages cannot send the headers that let the browser run the
// model on several processor cores at once (cross-origin isolation), so this
// worker adds them to the page. Opening the page with ?safe turns this off.
function isolate(res) {
  if (!res || res.type === "opaque" || res.status === 0) return res;
  const h = new Headers(res.headers);
  h.set("Cross-Origin-Opener-Policy", "same-origin");
  h.set("Cross-Origin-Embedder-Policy", "credentialless");
  h.set("Cross-Origin-Resource-Policy", "cross-origin");
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers: h });
}

self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return; // model and library downloads go straight through
  const boost = req.mode === "navigate" && !url.searchParams.has("safe");
  e.respondWith(
    fetch(req)
      .then((res) => {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req, copy));
        return boost ? isolate(res) : res;
      })
      .catch(() => caches.match(req).then((res) => (boost ? isolate(res) : res)))
  );
});
