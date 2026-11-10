import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import test from "node:test";
import { build } from "esbuild";
import { JSDOM } from "jsdom";
import { act, createElement } from "react";

test("tone editing accepts a complete number, commits on Enter, and rejects invalid edits", async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: "http://localhost/" });
  for (const key of ["window", "document", "navigator", "HTMLElement", "Event", "KeyboardEvent"]) {
    Object.defineProperty(globalThis, key, { configurable: true, value: dom.window[key] });
  }
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  const directory = await mkdtemp(join(process.cwd(), ".rtty-ui-test-"));
  let root;
  try {
    // Only the Tauri IPC boundary is replaced. Render the actual App/controls;
    // modem/DSP/engine behavior is tested against real Android PCM elsewhere.
    const compiled = await build({
      entryPoints: ["src/App.tsx"], bundle: true, write: false,
      platform: "node", format: "esm", jsx: "automatic",
      external: ["react", "react-dom"],
      plugins: [{ name: "ui-ipc-fixture", setup(builder) {
        builder.onResolve({ filter: /^\.\/ipc$/ }, () => ({ path: "ipc", namespace: "fixture" }));
        builder.onLoad({ filter: /.*/, namespace: "fixture" }, () => ({ contents: `
          let listener;
          export async function onEngineEvent(cb) { listener = cb; return () => {}; }
          export const api = {
            refreshStatus() {}, listBands: async () => [], getConfig: async () => null,
            setModem: async (config) => listener({type: "modem", data: config}),
          };
        ` }));
      } }],
    });
    const filename = join(directory, "app.mjs");
    await writeFile(filename, compiled.outputFiles[0].contents);
    const { default: App } = await import(pathToFileURL(filename));
    const { createRoot } = await import("react-dom/client");
    root = createRoot(document.getElementById("root"));
    await act(async () => { root.render(createElement(App)); });
    const field = () => document.querySelector('[aria-label="Lower tone Hz"]');
    const setValue = Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, "value").set;
    const type = (value) => {
      setValue.call(field(), value);
      field().dispatchEvent(new Event("input", { bubbles: true }));
    };
    await act(async () => { field().focus(); type("1"); });
    assert.equal(field().value, "1", "allow incomplete input while editing");
    assert.match(document.body.textContent, /M 2125 \/ S 2295 Hz/);
    await act(async () => {
      type("1275");
      field().dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
    });
    assert.equal(field().value, "1275");
    assert.match(document.body.textContent, /M 1275 \/ S 1445 Hz/);
    for (const invalid of ["", "499", "2831"]) {
      await act(async () => { field().focus(); type(invalid); field().blur(); });
      assert.equal(field().value, "1275");
      assert.match(document.body.textContent, /M 1275 \/ S 1445 Hz/);
    }
  } finally {
    if (root) await act(async () => { root.unmount(); });
    dom.window.close();
    await rm(directory, { recursive: true, force: true });
  }
});
