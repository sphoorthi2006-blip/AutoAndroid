import React from "react";
import { createRoot } from "react-dom/client";
import TextEditor from "./TextEditor";
import "./styles.css";

// The page is the Text Editor screen of the Bezalel app (it is opened as index.html?app=text; the query is ignored now, there is only one screen).
// Native-looking dark screen: no white flash, dark scrollbars and form controls.
try {
  const root = document.documentElement;
  root.dataset.app = "bezalel";
  root.style.background = "#0b0d12";
  root.style.colorScheme = "dark";
  document.body.style.background = "#0b0d12";
} catch { /* a plain browser page keeps the default look */ }

createRoot(document.getElementById("root")!).render(<React.StrictMode><TextEditor /></React.StrictMode>);
