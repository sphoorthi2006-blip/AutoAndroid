import React from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";

// Inside the Bezalel app (?app=text or ?app=video) the page is a native-looking dark screen: no white flash, dark scrollbars and form controls.
try {
  if (new URLSearchParams(window.location.search).get("app")) {
    const root = document.documentElement;
    root.dataset.app = "bezalel";
    root.style.background = "#0b0d12";
    root.style.colorScheme = "dark";
    document.body.style.background = "#0b0d12";
  }
} catch { /* a plain browser page keeps the light look */ }

createRoot(document.getElementById("root")!).render(<React.StrictMode><App /></React.StrictMode>);
