// The live viewer (spec section 17): the core runs here, in the browser, through the harness API.
// It reloads when a file changes, shows validator and lint findings in place, and the path of
// whatever is clicked.
import { Viewer } from "./lib/diagrams-harness.mjs";

const $ = (id) => document.getElementById(id);
const stage = $("stage"), overlay = $("overlay");
let viewer = null, version = null, viewport = null, highlight = [], selected = null, diagnostics = [];

async function fetchBundle() {
  const r = await fetch("bundle", { cache: "no-store" });
  if (!r.ok) throw new Error("bundle: " + r.status);
  return r.text();
}

function setStatus(text) { $("status").textContent = text; }

async function load(first) {
  const bundle = await fetchBundle();
  const parsed = JSON.parse(bundle);
  version = parsed.version;
  $("title").textContent = parsed.title || parsed.main;
  document.title = (parsed.title || parsed.main) + " · diagrams";
  try {
    if (first || !viewer) viewer = new Viewer(bundle); else viewer.reload(bundle);
    window.viewer = viewer; // the harness API, for scripts in the page
    setStatus((first ? "loaded " : "reloaded ") + new Date().toLocaleTimeString());
  } catch (e) {
    setStatus("error: " + e.message);
  }
  refreshAll();
}

function draw() {
  const svg = viewer && viewer.svg();
  if (!svg) {
    stage.innerHTML = '<div id="empty">The document has errors: see Diagnostics.</div>';
    overlay.innerHTML = "";
    viewport = null; // nothing is drawn: the overlay has nothing to align with
    return;
  }
  stage.innerHTML = svg;
  const el = stage.querySelector("svg");
  const vb = el.viewBox.baseVal;
  viewport = { w: vb.width, h: vb.height };
  drawOverlay();
}

function drawOverlay() {
  if (!viewport) return;
  const boxes = [];
  for (const h of highlight) {
    const b = viewer.read(h.path + ".box");
    if (b) boxes.push([JSON.parse(b), h.color]);
  }
  if (selected) {
    const b = viewer.read(selected === "scene" ? "scene.box" : selected + ".box");
    if (b && selected !== "scene") boxes.push([JSON.parse(b), "#2d6cdf"]);
  }
  const svgEl = stage.querySelector("svg");
  if (!svgEl) return;
  const r = svgEl.getBoundingClientRect(), o = overlay.getBoundingClientRect();
  const rects = boxes.map(([b, c]) => `<rect x="${b[0] - 3}" y="${b[1] - 3}" width="${b[2] + 6}" height="${b[3] + 6}" fill="none" stroke="${c}" stroke-width="2" stroke-dasharray="5 3" rx="3"/>`).join("");
  overlay.innerHTML = `<svg viewBox="0 0 ${viewport.w} ${viewport.h}" style="position:absolute;left:${r.left - o.left}px;top:${r.top - o.top}px;width:${r.width}px;height:${r.height}px">${rects}</svg>`;
}

function refreshAll() {
  draw();
  refreshStates();
  refreshDiagnostics();
  refreshClock();
}

function refreshClock() {
  if (!viewer) return;
  const d = viewer.duration();
  const t = viewer.time();
  const slider = $("time");
  slider.max = Math.max(d, 0.01);
  slider.value = Math.min(t, d);
  slider.disabled = d <= 0;
  $("play").disabled = d <= 0;
  $("play").textContent = viewer.playing() ? "Pause" : "Play";
  $("clock").textContent = t.toFixed(2) + " / " + d.toFixed(2) + " s";
}

function button(label, title, f) {
  const b = document.createElement("button");
  b.textContent = label;
  if (title) b.title = title;
  b.onclick = () => { const err = f(); if (err) setStatus(err); afterDrive(); };
  return b;
}

function afterDrive() { refreshStates(); draw(); }

function refreshStates() {
  const box = $("states");
  if (!viewer) return;
  const cs = JSON.parse(viewer.containers());
  if (!cs.length) { box.className = "muted"; box.textContent = "No state containers."; return; }
  box.className = "";
  box.innerHTML = "";
  for (const c of cs) {
    const div = document.createElement("div");
    div.className = "container";
    const head = document.createElement("div");
    head.className = "head";
    head.innerHTML = `<span class="owner"></span><span class="muted"></span>`;
    head.children[0].textContent = c.owner;
    head.children[1].textContent = c.kind;
    div.appendChild(head);
    const row = document.createElement("div");
    row.className = "buttons";
    const sel = document.createElement("select");
    for (const s of c.states) {
      const o = document.createElement("option");
      o.value = o.textContent = s;
      if (s === c.current) o.selected = true;
      sel.appendChild(o);
    }
    sel.onchange = () => { const err = viewer.goTo(c.owner, sel.value); if (err) setStatus(err); afterDrive(); };
    row.appendChild(sel);
    if (c.kind === "sequence") {
      row.appendChild(button("◀", "prev", () => viewer.prev(c.owner)));
      row.appendChild(button("▶", "next", () => viewer.next(c.owner)));
    } else if (c.kind === "tree") {
      row.appendChild(button("up", "to the parent state", () => viewer.up(c.owner)));
      row.appendChild(button("enter", "into the first child state", () => viewer.enter(c.owner, null)));
      row.appendChild(button("◀", "previous sibling", () => viewer.sibling(c.owner, -1)));
      row.appendChild(button("▶", "next sibling", () => viewer.sibling(c.owner, 1)));
    } else if (c.kind === "graph") {
      const edges = (c.current && c.edges[c.current]) || {};
      for (const e of Object.keys(edges)) row.appendChild(button(e, "follow " + e + " to " + edges[e], () => viewer.follow(c.owner, e)));
    }
    div.appendChild(row);
    box.appendChild(div);
  }
}

function refreshDiagnostics() {
  const box = $("diagnostics");
  diagnostics = viewer ? JSON.parse(viewer.diagnostics()) : [];
  if (!diagnostics.length) { box.className = "muted"; box.textContent = "None."; highlight = []; drawOverlay(); return; }
  box.className = "";
  box.innerHTML = "";
  for (const d of diagnostics) {
    const div = document.createElement("div");
    div.className = "diag " + d.severity;
    const src = d.source ? `${d.source.file}:${d.source.line}:${d.source.column}` : "";
    div.innerHTML = `<div><span class="code"></span> <span class="where"></span></div><div class="msg"></div>`;
    div.querySelector(".code").textContent = d.severity + " · " + d.code;
    div.querySelector(".where").textContent = (d.path || "") + (src ? "  " + src : "");
    div.querySelector(".msg").textContent = d.message;
    div.onmouseenter = () => { highlight = d.path ? [{ path: d.path, color: d.severity === "error" ? "#c0392b" : "#b7791f" }] : []; drawOverlay(); };
    div.onmouseleave = () => { highlight = inPlace(); drawOverlay(); };
    box.appendChild(div);
  }
  highlight = inPlace();
  drawOverlay();
}

// lint findings are drawn in place: every path with a finding gets an outline
function inPlace() {
  return diagnostics.filter((d) => d.path && d.path !== "scene").map((d) => ({ path: d.path, color: d.severity === "error" ? "#c0392b" : "#b7791f" }));
}

stage.addEventListener("click", (e) => {
  const svg = stage.querySelector("svg");
  if (!svg || !viewer) return;
  const pt = svg.createSVGPoint();
  pt.x = e.clientX; pt.y = e.clientY;
  const p = pt.matrixTransform(svg.getScreenCTM().inverse());
  selected = viewer.click(p.x, p.y);
  const box = selected && viewer.read(selected === "scene" ? "scene.box" : selected + ".box");
  const kind = selected && viewer.read(selected + ".kind");
  const state = selected && viewer.read(selected + ".state");
  $("clicked").className = "";
  $("clicked").innerHTML = "";
  const lines = [selected || "nothing"];
  if (kind) lines.push("kind " + JSON.parse(kind));
  if (box) lines.push("box " + JSON.parse(box).map((v) => Math.round(v * 10) / 10).join(", "));
  if (state) lines.push("state " + JSON.parse(state));
  for (const l of lines) { const d = document.createElement("div"); d.textContent = l; $("clicked").appendChild(d); }
  drawOverlay();
});

$("play").onclick = () => {
  if (!viewer) return;
  if (viewer.playing()) { viewer.pause(); refreshDiagnostics(); } else viewer.play();
  refreshClock();
  syncAudio();
};
// lint depends on the moment shown: findings are checked again after a seek
$("time").oninput = (e) => { viewer.seek(parseFloat(e.target.value)); draw(); refreshClock(); syncAudio(); };
$("time").onchange = () => refreshDiagnostics();
$("save").onclick = () => {
  const text = viewer && viewer.document();
  if (!text) return;
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type: "text/yaml" }));
  a.download = "canonical.yaml";
  a.click();
};
document.addEventListener("keydown", (e) => {
  if (!viewer || e.target.tagName === "SELECT" || e.target.tagName === "INPUT") return;
  const cs = JSON.parse(viewer.containers());
  const seq = cs.find((c) => c.owner === "scene" && c.kind === "sequence") || cs.find((c) => c.kind === "sequence");
  if (e.key === " ") { $("play").click(); e.preventDefault(); }
  else if (seq && (e.key === "ArrowRight" || e.key === "PageDown")) { viewer.next(seq.owner); afterDrive(); e.preventDefault(); }
  else if (seq && (e.key === "ArrowLeft" || e.key === "PageUp")) { viewer.prev(seq.owner); afterDrive(); e.preventDefault(); }
});
window.addEventListener("resize", drawOverlay);

// audio nodes play with the scene clock: started, seeked and paused to match it (spec section 15)
const players = new Map();
function syncAudio() {
  if (!viewer) return;
  let clips = [];
  try { clips = JSON.parse(viewer.media()).filter((c) => c.kind === "audio" && c.url); } catch (e) { return; }
  const t = viewer.time(), playing = viewer.playing();
  const live = new Set();
  for (const c of clips) {
    const key = c.asset + "@" + c.start;
    live.add(key);
    let a = players.get(key);
    if (!a) { a = new Audio(c.url); a.preload = "auto"; players.set(key, a); }
    const pos = c.in + (t - c.start) * c.rate;
    const inside = t >= c.start && (c.out == null || pos < c.out);
    a.volume = Math.min(1, Math.max(0, c.volume));
    a.playbackRate = c.rate;
    if (playing && inside) {
      if (Math.abs(a.currentTime - pos) > 0.25) a.currentTime = pos;
      if (a.paused) a.play().catch(() => setStatus("click Play again to allow sound"));
    } else if (!a.paused) a.pause();
  }
  for (const [k, a] of players) if (!live.has(k)) { a.pause(); players.delete(k); }
}

let last = performance.now();
function frame(now) {
  const dt = Math.min((now - last) / 1000, 0.5);
  last = now;
  if (viewer && viewer.tick(dt)) {
    draw();
    refreshClock();
    syncAudio();
    if (!viewer.transitioning() && !viewer.playing()) refreshStates();
  }
  requestAnimationFrame(frame);
}

async function poll() {
  let v = null;
  try {
    const r = await fetch("version", { cache: "no-store" });
    v = (await r.text()).trim();
  } catch (e) {
    setStatus("server unreachable");
  }
  try {
    if (v !== null && version !== null && v !== version) await load(false);
  } catch (e) {
    setStatus("reload failed: " + e.message);
  }
  setTimeout(poll, 700);
}

load(true).then(() => { requestAnimationFrame(frame); poll(); }).catch((e) => setStatus("error: " + e.message));
