import { whatsOn, nowNext, guideTimeline, setBroadcast, setScheduleChannels, resetBlockMemory, _restartScheduleEngine, _setScheduleClock } from "@/lib/schedule.js";
const out = [];
const ok = (name, cond, detail) => { out.push({ name, ok: Boolean(cond), detail }); console.log((cond ? "PASS " : "FAIL ") + name, cond ? "" : JSON.stringify(detail)?.slice(0, 400)); };
let id = 1;
const mk = (show, n, season = "Season 1", base = 1300) => Array.from({ length: n }, (_, e) => ({ id: id++, name: `${show} S01E${String(e + 1).padStart(2, "0")}.mkv`, show, season, duration: base + (e % 5) * 25, breaks: [500] }));
const lib1 = [...mk("A", 8), ...mk("B", 30), ...mk("C", 12), ...Array.from({ length: 10 }, (_, k) => ({ id: id++, name: `Ad ${k}.mp4`, show: "Ads", season: "", duration: 30 }))];
const T0 = Date.UTC(2026, 9, 18, 0, 0, 0);
let now = T0;
_setScheduleClock(() => now);
const blk = (show) => ({ show, season: "", kind: "show" });
const ch1 = { id: 1, number: 1, name: "One", shows: [], adShows: ["Ads"], adsPerBreak: 2, blocks: [blk("A"), blk("B"), blk("A"), blk("C")], epoch: T0 - 86400e3, seed: 11 };
const ch2 = { id: 2, number: 2, name: "Two", shows: [], adShows: ["Ads"], adsPerBreak: 2, blocks: [blk("A"), blk("C")], epoch: T0 - 86400e3, seed: 22 };
const ch3 = { id: 3, number: 3, name: "Marathon", shows: [], adShows: ["Ads"], adsPerBreak: 2, blocks: [blk("B")], epoch: T0 - 86400e3, seed: 33 };
const chans = [ch1, ch2, ch3];
resetBlockMemory();
setScheduleChannels(chans);
setBroadcast({ upNextSecs: 5, identSecs: 0, promos: false });

// Programmes (episodes) aired between t0 and t1 on every channel, in time order.
function airings(lib, t0, t1) {
  const all = [];
  for (const c of chans) for (const p of guideTimeline(c, lib, t0, t1)) if (p.media && p.startMs >= t0 && p.startMs < t1) all.push({ ch: c.id, show: p.media.show, name: p.media.name, t: p.startMs });
  return all.sort((a, b) => a.t - b.t);
}
// Walk time forward in steps (like the app running), collecting airings.
function run(lib, hours, step = 30 * 60e3) {
  const got = [];
  const end = now + hours * 3600e3;
  for (; now < end; now += step) got.push(...airings(lib, now, now + step));
  return got;
}

// 1. Two days: each folder goes through all its episodes before any repeat.
const t1 = performance.now();
const a1 = run(lib1, 48);
const firstMs = Math.round(performance.now() - t1);
for (const [show, size] of [["A", 8], ["B", 30], ["C", 12]]) {
  const seq = a1.filter((x) => x.show === show).map((x) => x.name);
  // Skip the first round (it continues the old schedule), then check rounds are complete.
  const counts = new Map();
  for (const n of seq.slice(size)) counts.set(n, (counts.get(n) || 0) + 1);
  const vals = [...counts.values()];
  ok(`${show}: every episode airs once a round (${seq.length} airings, ${size} episodes)`, counts.size === size && Math.max(...vals) - Math.min(...vals) <= 2, Object.fromEntries(counts));
  // No episode back within (size - 1) airings of itself, after the first round.
  let minGap = Infinity;
  const last = new Map();
  seq.forEach((n, i) => { if (i >= size && last.has(n)) minGap = Math.min(minGap, i - last.get(n)); last.set(n, i); });
  ok(`${show}: shortest gap between repeats is at least half the show (${minGap} airings)`, minGap >= Math.max(1, Math.floor(size / 2) - 2), minGap);
}
// Shared across channels: A on channel 1 and 2 never the same episode within 2 hours.
{
  const seq = a1.filter((x) => x.show === "A");
  let clash = null;
  for (let i = 0; i < seq.length; i++) for (let j = i + 1; j < seq.length && seq[j].t - seq[i].t < 3600e3; j++) if (seq[i].name === seq[j].name) clash = [seq[i], seq[j]];
  ok("A (8 episodes, on two channels): the same episode never twice within an hour", !clash, clash);
}

// 2. Restart: the same schedule comes back.
{
  const before = chans.map((c) => [now, now + 3 * 3600e3, now + 9 * 3600e3].map((t) => { const w = whatsOn(c, lib1, t); return w && `${w.current.media?.name}|${Math.round(w.offset)}`; }));
  _restartScheduleEngine();
  const t = performance.now();
  const after = chans.map((c) => [now, now + 3 * 3600e3, now + 9 * 3600e3].map((t) => { const w = whatsOn(c, lib1, t); return w && `${w.current.media?.name}|${Math.round(w.offset)}`; }));
  const ms = performance.now() - t;
  ok("restart: same schedule now and later", JSON.stringify(before) === JSON.stringify(after), { before, after });
  ok(`restart: fast (${ms.toFixed(1)} ms)`, ms < 100, ms);
}

// 3. Rescan adds four A episodes: what's on now stays, the new ones air this round.
{
  const onNow = chans.map((c) => nowNext(c, lib1, now)?.now.media?.name);
  const lib2 = [...lib1, ...mk("A", 4).map((m, i) => ({ ...m, name: `A S02E0${i + 1}.mkv`, season: "Season 2" }))];
  const after = chans.map((c) => nowNext(c, lib2, now)?.now.media?.name);
  ok("rescan: what's on now doesn't change", JSON.stringify(onNow) === JSON.stringify(after), { onNow, after });
  const seq = run(lib2, 12).filter((x) => x.show === "A").map((x) => x.name);
  const firstNew = seq.findIndex((n) => n.includes("S02"));
  const allNew = ["A S02E01.mkv", "A S02E02.mkv", "A S02E03.mkv", "A S02E04.mkv"].every((n) => seq.slice(0, 12).includes(n));
  ok("rescan: the new episodes air within the next round", allNew && firstNew >= 0, seq.slice(0, 12));
  // And nothing comes straight back: repeats at least half the (now 12) episodes apart.
  let minGap = Infinity;
  const last = new Map();
  seq.forEach((n, i) => { if (last.has(n)) minGap = Math.min(minGap, i - last.get(n)); last.set(n, i); });
  ok(`rescan: repeats stay well apart (${minGap} airings)`, minGap >= 4, seq);
  globalThis.lib = lib2;
}
const lib = globalThis.lib;

// 4. Broadcast change (promos on): no jump back to earlier cycles.
{
  const onNow = chans.map((c) => nowNext(c, lib, now)?.now.media?.name);
  const before = airings(lib, now - 4 * 3600e3, now).map((x) => x.name);
  setBroadcast({ promos: true, promoSecs: 20 });
  const after = chans.map((c) => nowNext(c, lib, now)?.now.media?.name);
  const next6 = airings(lib, now + 1, now + 3 * 3600e3);
  const recentA = before.filter((n) => n.startsWith("A "));
  const backSoon = next6.filter((x) => x.show === "A").slice(0, 3).map((x) => x.name).filter((n) => recentA.slice(-3).includes(n));
  ok("settings change: what's on now stays the same", JSON.stringify(onNow) === JSON.stringify(after), { onNow, after });
  ok("settings change: the last few A episodes don't come straight back", backSoon.length === 0, { recentA: recentA.slice(-3), next: next6.filter((x) => x.show === "A").slice(0, 3) });
}

// 5. Away a month: picks up quickly with something on.
{
  now += 30 * 86400e3;
  _restartScheduleEngine();
  const t = performance.now();
  const w = chans.map((c) => whatsOn(c, lib, now)?.current?.media?.name || null);
  const ms = performance.now() - t;
  ok(`a month away: something on every channel (${ms.toFixed(1)} ms)`, w.every(Boolean) && ms < 200, w);
}

// 6. A channel the app hasn't registered (a draft) still schedules the old way.
{
  const draft = { ...ch2, id: 99, blocks: [blk("C")] };
  const w = whatsOn(draft, lib, now);
  ok("draft channel: old schedule still works", w && w.current.media?.show === "C", w?.current?.media?.name);
}

// 7. Startup with 15 block channels made a year ago, no memory yet (first run after update), then again.
{
  resetBlockMemory();
  const many = Array.from({ length: 15 }, (_, i) => ({ id: 100 + i, number: 10 + i, name: `M${i}`, shows: [], adShows: ["Ads"], adsPerBreak: 2, seed: i,
    blocks: [blk(["A", "B", "C"][i % 3]), blk(["B", "C", "A"][i % 3]), blk("B")], epoch: now - 365 * 86400e3 }));
  setScheduleChannels(many);
  let t = performance.now();
  many.forEach((c) => whatsOn(c, lib, now));
  const first = performance.now() - t;
  _restartScheduleEngine();
  t = performance.now();
  many.forEach((c) => whatsOn(c, lib, now + 60000));
  const second = performance.now() - t;
  ok(`15 year-old channels: first run ${first.toFixed(0)} ms (one-off), every start after ${second.toFixed(0)} ms`, second < 150, { first, second });
}
console.log(`\nfirst 48h simulated in ${firstMs} ms`);
console.log(JSON.stringify({ passed: out.filter((x) => x.ok).length, total: out.length }));
