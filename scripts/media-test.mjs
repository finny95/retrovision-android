// Drives the app's WebView over the DevTools protocol (debug builds only) to check
// that linked media files stream, seek and play as channels.
// Usage: node scripts/media-test.mjs <devtools-port> <out.json> <file>...
// Each <file> is a path relative to the app's cache dir.
import { writeFileSync } from "node:fs";

const [port, outFile, phase, ...files] = process.argv.slice(2);
import { existsSync, readFileSync } from "node:fs";
process.on("unhandledRejection", (e) => { console.error("unhandled", e); });
const PKG = "com.retrovision.player";
const providerUri = (rel) =>
  `content://${PKG}.fileprovider/my_cache_images/${rel.split("/").map(encodeURIComponent).join("/")}`;

async function target() {
  for (let i = 0; i < 30; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
      const page = list.find((t) => t.type === "page" && t.url.startsWith("https://localhost"));
      if (page) return page.webSocketDebuggerUrl;
    } catch {}
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error("No WebView page to inspect");
}

const ws = new WebSocket(await target());
await new Promise((r) => ws.addEventListener("open", r, { once: true }));
let seq = 0;
const pending = new Map();
ws.addEventListener("message", (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
});
const send = (method, params = {}) =>
  new Promise((resolve) => {
    const id = ++seq;
    pending.set(id, resolve);
    ws.send(JSON.stringify({ id, method, params }));
  });
async function evaluate(fn, ...args) {
  const expression = `(${fn})(...${JSON.stringify(args)})`;
  const res = await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true, timeout: 120000 });
  if (res.result?.exceptionDetails) return { exception: res.result.exceptionDetails.exception?.description || res.result.exceptionDetails.text };
  return res.result?.result?.value;
}

// --- 1. Streaming + seeking for each file ---------------------------------
const streamTest = async (name, uri) => {
  const url = `${location.origin}/_rv_media/${encodeURIComponent(name)}?u=${encodeURIComponent(uri)}&n=${Date.now()}`;
  const out = { name };
  const head = async (range) => {
    try {
      const r = await fetch(url + "&r=" + Math.random(), range ? { headers: { Range: range } } : {});
      const body = await r.arrayBuffer();
      return { status: r.status, type: r.headers.get("content-type"), range: r.headers.get("content-range"), length: r.headers.get("content-length"), got: body.byteLength };
    } catch (e) {
      return { error: String(e) };
    }
  };
  out.rangeMid = await head("bytes=100-199");
  out.rangeTail = await head("bytes=-50");
  try {
    out.probe = await window.Capacitor.Plugins.MediaFolder.probe({ uri });
  } catch (e) {
    out.probe = String(e);
  }
  const v = document.createElement("video");
  v.muted = true;
  v.playsInline = true;
  document.body.appendChild(v);
  const wait = (ev, ms) =>
    new Promise((res) => {
      const t0 = performance.now();
      const done = (ok) => res({ ok, ms: Math.round(performance.now() - t0), err: v.error?.code, readyState: v.readyState });
      const timer = setTimeout(() => done(false), ms);
      v.addEventListener(ev, () => { clearTimeout(timer); done(true); }, { once: true });
      v.addEventListener("error", () => { clearTimeout(timer); done(false); }, { once: true });
    });
  v.src = url;
  const loaded = wait("loadeddata", 20000);
  v.play().catch(() => {});
  out.firstFrame = await loaded;
  out.duration = v.duration;
  if (out.firstFrame.ok) {
    const seeked = wait("seeked", 20000);
    v.currentTime = (v.duration || 60) * 0.9;
    out.seek = await seeked;
    out.afterSeek = v.currentTime;
    await new Promise((r) => setTimeout(r, 1500));
    out.playingAfterSeek = !v.paused && v.currentTime > out.afterSeek;
  }
  v.remove();
  return out;
};

const results = existsSync(outFile) ? JSON.parse(readFileSync(outFile, "utf8")) : { files: [] };
const save = () => writeFileSync(outFile, JSON.stringify(results, null, 2));
if (phase === "files") {
results.files = [];
for (const rel of files) {
  const name = rel.split("/").pop();
  results.files.push(await evaluate(streamTest, name, providerUri(rel)));
  save();
  console.log("file done", name);
}

// --- 1b. Play, switch and replay several times (the "works once" case) -----
const okFiles = results.files.map((f, i) => (f.firstFrame?.ok ? files[i] : null)).filter(Boolean);
if (okFiles.length) {
  results.repeat = await evaluate(async (uris) => {
    const v = document.createElement("video");
    v.muted = true;
    document.body.appendChild(v);
    const runs = [];
    for (let i = 0; i < 8; i++) {
      const uri = uris[i % uris.length];
      const url = `${location.origin}/_rv_media/x.mp4?u=${encodeURIComponent(uri)}&n=${Date.now()}-${i}`;
      const t0 = performance.now();
      const ok = await new Promise((res) => {
        const timer = setTimeout(() => res(false), 20000);
        v.onloadeddata = () => { clearTimeout(timer); res(true); };
        v.onerror = () => { clearTimeout(timer); res(false); };
        v.src = url;
        v.play().catch(() => {});
      });
      let seekOk = null;
      if (ok) {
        seekOk = await new Promise((res) => {
          const timer = setTimeout(() => res(false), 20000);
          v.onseeked = () => { clearTimeout(timer); res(true); };
          v.currentTime = (v.duration || 60) * (0.2 + 0.09 * i);
        });
        await new Promise((r) => setTimeout(r, 800));
      }
      runs.push({ i, ok, seekOk, ms: Math.round(performance.now() - t0), err: v.error?.code });
    }
    v.remove();
    return runs;
  }, okFiles.map(providerUri));
  save();
}
}

// --- 2. The app's own channel flow -----------------------------------------
const first = phase === "channel" && results.files.find((f) => f.firstFrame?.ok && f.probe?.duration > 30);
if (first) {
  const rel = files[results.files.indexOf(first)];
  await evaluate(
    async (record, channel) => {
      const db = await new Promise((res, rej) => {
        const r = indexedDB.open("crt-media-library", 2);
        r.onsuccess = () => res(r.result);
        r.onerror = () => rej(r.error);
      });
      const tx = db.transaction(["media", "channels"], "readwrite");
      tx.objectStore("media").put(record);
      tx.objectStore("channels").put(channel);
      await new Promise((r) => (tx.oncomplete = r));
      return true;
    },
    { id: 9001, name: first.name, type: "video/mp4", size: 1, added: Date.now(), duration: first.probe.duration, show: "Test Show", season: "Season 1", uri: providerUri(rel) },
    // Epoch chosen so the live position is about 60% into the episode.
    { id: 9101, number: 7, name: "Test Channel", shows: ["Test Show"], adShows: [], adsPerBreak: 0, blocks: [], lineup: [{ mediaId: 9001, kind: "show" }], epoch: Date.now() - first.probe.duration * 600 }
  );
  await send("Page.reload");
  await new Promise((r) => setTimeout(r, 6000));
  results.channel = [];
  for (let attempt = 0; attempt < 3; attempt++) {
  results.channel.push(await evaluate(async () => {
    const btn = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Test Channel"));
    if (!btn) return { error: "channel not listed" };
    btn.click();
    const t0 = performance.now();
    const v = document.querySelector("video");
    while (performance.now() - t0 < 30000) {
      await new Promise((r) => setTimeout(r, 500));
      const loading = document.body.textContent.includes("Loading signal");
      if (!loading && v.currentTime > 1 && !v.paused) {
        return { ok: true, ms: Math.round(performance.now() - t0), currentTime: v.currentTime, duration: v.duration };
      }
    }
    return { ok: false, currentTime: v.currentTime, readyState: v.readyState, paused: v.paused, err: v.error?.code, src: v.currentSrc.slice(0, 80) };
  }));
  // Tune away (quick play nothing) by reloading the page, like reopening the app.
  save();
  await send("Page.reload");
  await new Promise((r) => setTimeout(r, 6000));
  }
}

// --- 3. Audio: Web Audio routing of streamed files (per-show boost) ----------
if (phase === "audio") {
  const firstOk = results.files.find((f) => f.firstFrame?.ok && !/big/.test(f.name));
  const rel = firstOk && files[results.files.indexOf(firstOk)];
  if (rel) {
    // a) Platform: does a context start without a tap, and does a streamed file
    //    come through MediaElementSource with sound (not silenced as cross-origin)?
    results.audioPlatform = await evaluate(async (uri) => {
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      const out = {};
      const ctx = new AudioContext();
      out.stateAtStart = ctx.state;
      await sleep(400);
      out.stateAfter = ctx.state;
      const t0 = ctx.currentTime;
      const v = document.createElement("video");
      v.src = `${location.origin}/_rv_media/a.mp4?u=${encodeURIComponent(uri)}&n=${Date.now()}`;
      document.body.appendChild(v);
      const src = ctx.createMediaElementSource(v);
      const an = ctx.createAnalyser();
      an.fftSize = 2048;
      src.connect(an);
      an.connect(ctx.destination);
      await v.play().catch((e) => (out.playError = String(e)));
      await sleep(3000);
      const buf = new Float32Array(an.fftSize);
      an.getFloatTimeDomainData(buf);
      out.rms = Math.sqrt(buf.reduce((s, x) => s + x * x, 0) / buf.length);
      out.ctxAdvanced = ctx.currentTime - t0;
      out.videoTime = v.currentTime;
      out.paused = v.paused;
      v.pause();
      v.remove();
      await ctx.close();
      return out;
    }, providerUri(rel));
    save();

    // b) The app itself: boost a show, tune its channel, and listen before and
    //    after the boost chain (taps added next to the app's own nodes).
    await send("Page.enable");
    await send("Page.addScriptToEvaluateOnNewDocument", {
      source: `(() => {
        const AC = window.AudioContext;
        const mes = AC.prototype.createMediaElementSource;
        const tap = (ctx, node, key) => { const an = ctx.createAnalyser(); an.fftSize = 2048; node.connect(an); window.__tap = window.__tap || {}; window.__tap[key] = an; window.__tap.ctx = ctx; };
        AC.prototype.createMediaElementSource = function (el) { const n = mes.call(this, el); tap(this, n, "pre"); return n; };
        const cdc = AC.prototype.createDynamicsCompressor;
        AC.prototype.createDynamicsCompressor = function () { const n = cdc.call(this); tap(this, n, "post"); return n; };
      })();`,
    });
    await evaluate((rec) => {
      localStorage.setItem("retrovision.prefs.v1", JSON.stringify({ boost: { [rec]: 9 }, volume: 1, muted: false, lastTab: "channels" }));
      return true;
    }, "Test Show");
    await send("Page.reload");
    await new Promise((r) => setTimeout(r, 7000));
    results.audioApp = await evaluate(async () => {
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      const btn = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Test Channel"));
      if (!btn) return { error: "channel not listed" };
      btn.click();
      const v = document.querySelector("video");
      const t0 = performance.now();
      while (performance.now() - t0 < 30000 && !(v.currentTime > 1 && !v.paused)) await sleep(500);
      await sleep(3000);
      const rms = (an) => {
        if (!an) return null;
        const buf = new Float32Array(an.fftSize);
        an.getFloatTimeDomainData(buf);
        return Math.sqrt(buf.reduce((s, x) => s + x * x, 0) / buf.length);
      };
      return {
        playing: !v.paused && v.currentTime > 1,
        ctxState: window.__tap?.ctx?.state || "no context routed",
        diag: window.__rvAudio?.(),
        tapInstalled: String(AudioContext.prototype.createMediaElementSource).includes("tap"),
        pre: rms(window.__tap?.pre),
        post: rms(window.__tap?.post),
        volume: v.volume,
        muted: v.muted,
        text: document.body.innerText.slice(0, 300),
      };
    });
    save();
  }
}

// --- 4. Launch without internet: airplane mode, or a connection that stalls ---
if (phase === "offline" || phase === "stalled") {
  const sample = results.files.find((f) => f.probe?.duration > 30 && !/big/.test(f.name)) || results.files[0];
  const rel = files[results.files.indexOf(sample)] || files[0];
  const loaded = await evaluate(async () => {
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    const t0 = performance.now();
    while (performance.now() - t0 < 20000 && !document.querySelector('[data-tab="channels"]')) await sleep(250);
    return { ui: Boolean(document.querySelector('[data-tab="channels"]')), online: navigator.onLine, offlineBadge: /offline/i.test(document.body.innerText) };
  });
  // Make sure the test channel exists (an earlier phase may have been cut short), then reopen.
  await evaluate(
    async (record, channel) => {
      const db = await new Promise((res, rej) => {
        const r = indexedDB.open("crt-media-library", 2);
        r.onupgradeneeded = () => {
          const d = r.result;
          if (!d.objectStoreNames.contains("media")) d.createObjectStore("media", { keyPath: "id", autoIncrement: true });
          if (!d.objectStoreNames.contains("channels")) d.createObjectStore("channels", { keyPath: "id", autoIncrement: true });
        };
        r.onsuccess = () => res(r.result);
        r.onerror = () => rej(r.error);
      });
      const tx = db.transaction(["media", "channels"], "readwrite");
      tx.objectStore("media").put(record);
      tx.objectStore("channels").put(channel);
      await new Promise((r) => (tx.oncomplete = r));
      localStorage.setItem("retrovision.prefs.v1", JSON.stringify({ lastTab: "channels" }));
      return true;
    },
    { id: 9001, name: sample?.name || "Episode_1.mp4", type: "video/mp4", size: 1, added: Date.now(), duration: sample?.probe?.duration || 240, show: "Test Show", season: "Season 1", uri: providerUri(rel) },
    { id: 9101, number: 7, name: "Test Channel", shows: ["Test Show"], adShows: [], adsPerBreak: 0, blocks: [], lineup: [{ mediaId: 9001, kind: "show" }], epoch: Date.now() - 60000 }
  );
  await send("Page.reload");
  await new Promise((r) => setTimeout(r, 8000));
  results[phase] = {
    ...loaded,
    ...(await evaluate(async () => {
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
      const btn = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Test Channel"));
      if (!btn) return { error: "channel not listed", text: document.body.innerText.slice(0, 200) };
      btn.click();
      const v = document.querySelector("video");
      const t1 = performance.now();
      while (performance.now() - t1 < 20000 && !(v.currentTime > 1 && !v.paused)) await sleep(500);
      return { playing: v.currentTime > 1 && !v.paused, currentTime: v.currentTime, onlineAfterReload: navigator.onLine };
    })),
  };
  save();
}

// --- 5. A "coming up next" bumper (canvas graphics through the CRT shader) ----
if (phase === "bumper") {
  const sample = results.files.find((f) => f.probe?.duration > 30 && !/big/.test(f.name)) || results.files[0];
  const rel = files[results.files.indexOf(sample)] || files[0];
  await evaluate(
    async (record, channel) => {
      const db = await new Promise((res, rej) => {
        const r = indexedDB.open("crt-media-library", 2);
        r.onupgradeneeded = () => {
          const d = r.result;
          if (!d.objectStoreNames.contains("media")) d.createObjectStore("media", { keyPath: "id", autoIncrement: true });
          if (!d.objectStoreNames.contains("channels")) d.createObjectStore("channels", { keyPath: "id", autoIncrement: true });
        };
        r.onsuccess = () => res(r.result);
        r.onerror = () => rej(r.error);
      });
      const tx = db.transaction(["media", "channels"], "readwrite");
      tx.objectStore("media").put(record);
      tx.objectStore("channels").put(channel);
      await new Promise((r) => (tx.oncomplete = r));
      localStorage.setItem("retrovision.prefs.v1", JSON.stringify({ lastTab: "channels", broadcast: { upNext: true, upNextSecs: 15 } }));
      return true;
    },
    { id: 9001, name: sample?.name || "Episode_1.mp4", type: "video/mp4", size: 1, added: Date.now(), duration: sample?.probe?.duration || 240, show: "Test Show", season: "Season 1", uri: providerUri(rel) },
    { id: 9201, number: 8, name: "Bumper Test", shows: ["Test Show"], adShows: [], adsPerBreak: 0, blocks: [], lineup: [{ mediaId: 9001, kind: "show" }], epoch: Date.now() + 5000 }
  );
  await send("Page.reload");
  await new Promise((r) => setTimeout(r, 7000));
  results.bumper = await evaluate(async () => {
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
    document.querySelector('[data-tab="channels"]')?.click();
    await sleep(500);
    const btn = [...document.querySelectorAll("button")].find((b) => b.textContent.includes("Bumper Test"));
    if (!btn) return { error: "channel not listed" };
    btn.click();
    await sleep(5000);
    return { bumper: document.querySelector("[data-bumper]")?.dataset.bumper || null, item: document.querySelector("[data-item]")?.dataset.item || null };
  });
  save();
}

save();
console.log(JSON.stringify(results, null, 2));
ws.close();
process.exit(0);
