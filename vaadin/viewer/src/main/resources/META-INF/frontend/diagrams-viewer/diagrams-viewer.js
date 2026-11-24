// <diagrams-viewer>: the diagrams core (the harness compiled to JavaScript, spec sections 16 and 17)
// as a custom element. The server-side DiagramViewer component sets its properties, calls its
// methods and listens to its events; it works on its own too.
//
// Properties: load (JSON {keep, revision, bundle}), outlines, keyboard, audio, autoplay, selectable,
// timeUpdates (ms), emptyText, selected, highlights (JSON [{path, color}]).
// Events (detail): diagrams-loaded, diagrams-click, diagrams-states, diagrams-diagnostics,
// diagrams-playback, diagrams-time, diagrams-error.
import { Viewer } from "./diagrams-embed.mjs";

const SEVERITY = { error: "#c0392b", warning: "#b7791f", info: "#2d6cdf" };
const SELECTED = "#2d6cdf";
const SVG_NS = "http://www.w3.org/2000/svg";

const STYLE = `
  :host { display: block; position: relative; overflow: hidden; min-height: 8rem; outline: none; }
  :host([hidden]) { display: none; }
  :host(:focus-visible) { box-shadow: inset 0 0 0 2px var(--lumo-primary-color-50pct, rgba(45, 108, 223, .5)); }
  .stage, .overlay { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; }
  .stage > svg { width: 100%; height: 100%; display: block; }
  .overlay { pointer-events: none; }
  .overlay > svg { width: 100%; height: 100%; display: block; overflow: visible; }
  .empty { padding: 1em; text-align: center; color: var(--lumo-secondary-text-color, #6b7785); }
`;

const PROPERTIES = ["load", "outlines", "keyboard", "audio", "autoplay", "selectable", "timeUpdates", "emptyText", "selected", "highlights"];

class DiagramsViewer extends HTMLElement {
  constructor() {
    super();
    const root = this.attachShadow({ mode: "open" });
    root.innerHTML = `<style>${STYLE}</style><div class="stage" part="stage"></div><div class="overlay" part="overlay"></div>`;
    this._stage = root.querySelector(".stage");
    this._overlay = root.querySelector(".overlay");
    this._viewer = null;
    this._bundle = null;
    this._title = null;
    this._load = null;
    this._outlines = true;
    this._keyboard = false;
    this._audio = true;
    this._autoplay = false;
    this._selectable = true;
    this._timeUpdates = 0;
    this._emptyText = "No document.";
    this._selected = null;
    this._highlights = [];
    this._viewBox = null;
    this._findings = [];
    this._states = null;
    this._diagnostics = null;
    this._clock = { playing: false, duration: 0 };
    this._players = new Map();
    this._raf = 0;
    this._last = 0;
    this._lastTime = 0;
    this._onKey = (e) => this._key(e);
    this._stage.addEventListener("click", (e) => this._pointer(e));
  }

  connectedCallback() {
    // properties set before the element was defined shadow its accessors: apply them
    for (const p of PROPERTIES) {
      if (Object.prototype.hasOwnProperty.call(this, p)) {
        const v = this[p];
        delete this[p];
        this[p] = v;
      }
    }
    this.addEventListener("keydown", this._onKey);
    this._last = performance.now();
    if (!this._raf) this._raf = requestAnimationFrame((t) => this._frame(t));
    this._draw();
  }

  disconnectedCallback() {
    cancelAnimationFrame(this._raf);
    this._raf = 0;
    this.removeEventListener("keydown", this._onKey);
    this._stopAudio();
  }

  // ---- properties ----

  get load() { return this._load; }
  set load(value) {
    this._load = value;
    if (!value) {
      this._stopAudio();
      this._viewer = null;
      this._bundle = null;
      this._title = null;
      this._selected = null;
      this._loaded(null);
      return;
    }
    let spec;
    try {
      spec = JSON.parse(value);
    } catch (e) {
      this._loaded("the bundle is not JSON: " + e.message);
      return;
    }
    const bundle = JSON.stringify(spec.bundle);
    this._title = spec.bundle.title || spec.bundle.main;
    let error = null;
    try {
      if (spec.keep && this._viewer) {
        this._viewer.reload(bundle);
      } else {
        this._stopAudio();
        this._viewer = new Viewer(bundle);
        this._selected = null;
        if (this._autoplay && this._viewer.duration() > 0) this._viewer.play();
      }
      this._bundle = bundle;
    } catch (e) {
      error = message(e);
      if (!spec.keep) this._viewer = null;
    }
    this._loaded(error);
  }

  get outlines() { return this._outlines; }
  set outlines(v) { this._outlines = !!v; this._drawOverlay(); }

  get keyboard() { return this._keyboard; }
  set keyboard(v) {
    this._keyboard = !!v;
    if (this._keyboard && !this.hasAttribute("tabindex")) this.setAttribute("tabindex", "0");
    else if (!this._keyboard && this.getAttribute("tabindex") === "0") this.removeAttribute("tabindex");
  }

  get audio() { return this._audio; }
  set audio(v) { this._audio = !!v; this._syncAudio(); }

  get autoplay() { return this._autoplay; }
  set autoplay(v) { this._autoplay = !!v; }

  get selectable() { return this._selectable; }
  set selectable(v) { this._selectable = !!v; this._drawOverlay(); }

  get timeUpdates() { return this._timeUpdates; }
  set timeUpdates(v) { this._timeUpdates = Math.max(0, Number(v) || 0); }

  get emptyText() { return this._emptyText; }
  set emptyText(v) { this._emptyText = v == null ? "" : String(v); if (!this._viewBox) this._draw(); }

  get selected() { return this._selected; }
  set selected(v) { this._selected = v || null; this._drawOverlay(); }

  get highlights() { return JSON.stringify(this._highlights); }
  set highlights(v) {
    try {
      this._highlights = v ? (typeof v === "string" ? JSON.parse(v) : v) : [];
    } catch (e) {
      this._highlights = [];
    }
    this._drawOverlay();
  }

  // ---- driving: each returns an error message, or null ----

  goTo(owner, state) { return this._drive("goTo", (v) => v.goTo(owner, state)); }
  next(owner) { return this._drive("next", (v) => v.next(owner || "scene")); }
  prev(owner) { return this._drive("prev", (v) => v.prev(owner || "scene")); }
  enter(owner, child) { return this._drive("enter", (v) => v.enter(owner || "scene", child == null ? null : child)); }
  up(owner) { return this._drive("up", (v) => v.up(owner || "scene")); }
  sibling(owner, delta) { return this._drive("sibling", (v) => v.sibling(owner || "scene", delta == null ? 1 : delta)); }
  follow(owner, edge) { return this._drive("follow", (v) => v.follow(owner || "scene", edge)); }

  play() {
    return this._drive("play", (v) => {
      if (v.duration() <= 0) return "the document has no timeline";
      v.play();
      return null;
    });
  }

  pause() { return this._drive("pause", (v) => { v.pause(); return null; }); }

  togglePlay() {
    if (this._viewer && this._viewer.playing()) return this.pause();
    return this.play();
  }

  seek(t) { return this._drive("seek", (v) => { v.seek(Number(t) || 0); return null; }); }

  /** The document as loaded: first states, clock at zero. */
  reset() {
    if (!this._bundle) return this._fail("reset", "no document is loaded");
    const bundle = JSON.parse(this._bundle);
    this.load = JSON.stringify({ keep: false, bundle });
    return null;
  }

  /** A click at a viewport point: hit-tested, selected, sent to the document's pointer listeners. */
  click(x, y) {
    if (!this._viewer) return null;
    let path = null;
    try {
      path = this._viewer.click(x, y);
    } catch (e) {
      this._fail("click", message(e));
    }
    if (this._selectable) this._selected = path;
    // pointer listeners may change states
    this._draw();
    this._sync(true, false);
    this._dispatch("diagrams-click", { path, x, y });
    return path;
  }

  // ---- reading ----

  /** Applies a JSON list of patches; returns the findings as JSON, or {"error": message}. */
  patch(json) {
    if (!this._viewer) return JSON.stringify({ error: "no document is loaded" });
    let out;
    try {
      out = this._viewer.patch(json);
    } catch (e) {
      out = JSON.stringify({ error: message(e) });
    }
    this._draw();
    this._sync(true, true);
    this._clockChanged(true);
    return out;
  }

  read(path) { return this._viewer ? this._viewer.read(path) : null; }
  hit(x, y) { return this._viewer ? this._viewer.hit(x, y) : null; }
  document() { return this._viewer ? this._viewer.document() : null; }
  svg() { return this._viewer ? this._viewer.svg() : null; }
  media() { return this._viewer ? this._viewer.media() : "[]"; }
  containers() { return this._viewer ? this._viewer.containers() : "[]"; }
  diagnostics() { return this._viewer ? this._viewer.diagnostics() : "[]"; }
  time() { return this._viewer ? this._viewer.time() : 0; }
  duration() { return this._viewer ? this._viewer.duration() : 0; }
  playing() { return this._viewer ? this._viewer.playing() : false; }

  // ---- internals ----

  _loaded(error) {
    this._states = this._viewer ? this._safe(() => this._viewer.containers(), "[]") : "[]";
    this._diagnostics = this._viewer ? this._safe(() => this._viewer.diagnostics(), "[]") : "[]";
    this._findings = parseList(this._diagnostics);
    this._draw();
    this._syncAudio();
    const v = this._viewer;
    this._dispatch("diagrams-loaded", {
      ok: !error && !!v,
      error: error || (v ? null : this._load ? "no document" : null),
      title: this._title,
      rendered: !!this._viewBox,
      diagnostics: this._diagnostics,
      containers: this._states,
      duration: v ? v.duration() : 0,
    });
    this._clockChanged(true);
  }

  _drive(operation, f) {
    if (!this._viewer) return this._fail(operation, "no document is loaded");
    let error = null;
    try {
      error = f(this._viewer) || null;
    } catch (e) {
      error = message(e);
    }
    this._draw();
    this._sync(true, true);
    this._clockChanged(operation === "seek");
    this._syncAudio();
    return error ? this._fail(operation, error) : null;
  }

  _fail(operation, error) {
    this._dispatch("diagrams-error", { operation, message: error });
    return error;
  }

  _safe(f, fallback) {
    try {
      return f();
    } catch (e) {
      return fallback;
    }
  }

  _dispatch(name, detail) {
    this.dispatchEvent(new CustomEvent(name, { detail, bubbles: true, composed: true }));
  }

  /** Sends the states and the findings when they changed. */
  _sync(states, diagnostics) {
    const v = this._viewer;
    if (!v) return;
    if (states) {
      const c = this._safe(() => v.containers(), "[]");
      if (c !== this._states) {
        this._states = c;
        this._dispatch("diagrams-states", { containers: c });
      }
    }
    if (diagnostics) {
      const d = this._safe(() => v.diagnostics(), "[]");
      if (d !== this._diagnostics) {
        this._diagnostics = d;
        this._findings = parseList(d);
        this._drawOverlay();
        this._dispatch("diagrams-diagnostics", { diagnostics: d });
      }
    }
  }

  /** Sends the clock when it started, stopped or its duration changed (or always, when [force]). */
  _clockChanged(force) {
    const v = this._viewer;
    const playing = v ? v.playing() : false, duration = v ? v.duration() : 0, time = v ? v.time() : 0;
    if (!force && playing === this._clock.playing && duration === this._clock.duration) return;
    const stopped = this._clock.playing && !playing;
    this._clock = { playing, duration };
    this._dispatch("diagrams-playback", { playing, time, duration });
    // lint depends on the moment shown: findings are checked again when the clock stops
    if (stopped) this._sync(true, true);
  }

  _draw() {
    const svg = this._viewer ? this._safe(() => this._viewer.svg(), null) : null;
    if (!svg) {
      this._viewBox = null;
      const empty = document.createElement("div");
      empty.className = "empty";
      empty.setAttribute("part", "empty");
      empty.textContent = this._viewer ? "The document has errors." : this._emptyText;
      this._stage.replaceChildren(empty);
      this._overlay.replaceChildren();
      return;
    }
    this._stage.innerHTML = svg;
    const el = this._stage.querySelector("svg");
    if (el) {
      el.setAttribute("width", "100%");
      el.setAttribute("height", "100%");
      const vb = el.viewBox && el.viewBox.baseVal;
      this._viewBox = vb && vb.width ? [vb.x, vb.y, vb.width, vb.height] : null;
    }
    this._drawOverlay();
  }

  _drawOverlay() {
    if (!this._viewBox || !this._viewer) {
      this._overlay.replaceChildren();
      return;
    }
    const marks = [];
    if (this._outlines) {
      for (const d of this._findings) if (d.path && d.path !== "scene") marks.push([d.path, SEVERITY[d.severity] || SEVERITY.warning, "5 3"]);
    }
    for (const h of this._highlights) if (h && h.path) marks.push([h.path, h.color || SELECTED, ""]);
    if (this._selectable && this._selected && this._selected !== "scene") marks.push([this._selected, SELECTED, "5 3"]);
    if (!marks.length) {
      this._overlay.replaceChildren();
      return;
    }
    const svg = document.createElementNS(SVG_NS, "svg");
    svg.setAttribute("viewBox", this._viewBox.join(" "));
    for (const [path, color, dash] of marks) {
      const b = this._box(path);
      if (!b) continue;
      const r = document.createElementNS(SVG_NS, "rect");
      r.setAttribute("x", b[0] - 3);
      r.setAttribute("y", b[1] - 3);
      r.setAttribute("width", b[2] + 6);
      r.setAttribute("height", b[3] + 6);
      r.setAttribute("rx", 3);
      r.setAttribute("fill", "none");
      r.setAttribute("stroke", color);
      r.setAttribute("stroke-width", 2);
      r.setAttribute("vector-effect", "non-scaling-stroke");
      if (dash) r.setAttribute("stroke-dasharray", dash);
      svg.appendChild(r);
    }
    this._overlay.replaceChildren(svg);
  }

  _box(path) {
    const json = this._safe(() => this._viewer.read(path + ".box"), null);
    if (!json) return null;
    const b = JSON.parse(json);
    return Array.isArray(b) && b.length === 4 ? b : null;
  }

  _pointer(e) {
    const svg = this._stage.querySelector("svg");
    if (!svg || !this._viewer) return;
    const ctm = svg.getScreenCTM();
    if (!ctm) return;
    const pt = svg.createSVGPoint();
    pt.x = e.clientX;
    pt.y = e.clientY;
    const p = pt.matrixTransform(ctm.inverse());
    this.click(p.x, p.y);
  }

  _key(e) {
    if (!this._keyboard || !this._viewer) return;
    const cs = parseList(this._states);
    const seq = cs.find((c) => c.owner === "scene" && c.kind === "sequence") || cs.find((c) => c.kind === "sequence");
    if (e.key === " ") this.togglePlay();
    else if (seq && (e.key === "ArrowRight" || e.key === "PageDown")) this.next(seq.owner);
    else if (seq && (e.key === "ArrowLeft" || e.key === "PageUp")) this.prev(seq.owner);
    else if (seq && e.key === "Home" && seq.states.length) this.goTo(seq.owner, seq.states[0]);
    else if (seq && e.key === "End" && seq.states.length) this.goTo(seq.owner, seq.states[seq.states.length - 1]);
    else return;
    e.preventDefault();
  }

  _frame(now) {
    this._raf = requestAnimationFrame((t) => this._frame(t));
    const dt = Math.min((now - this._last) / 1000, 0.5);
    this._last = now;
    const v = this._viewer;
    if (!v) return;
    let changed = false;
    try {
      changed = v.tick(dt);
    } catch (e) {
      this._fail("tick", message(e));
      return;
    }
    if (changed) {
      this._draw();
      this._syncAudio();
      if (!v.transitioning() && !v.playing()) this._sync(true, true);
      if (this._timeUpdates > 0 && v.playing() && now - this._lastTime >= this._timeUpdates) {
        this._lastTime = now;
        this._dispatch("diagrams-time", { time: v.time(), duration: v.duration() });
      }
    }
    this._clockChanged(false);
  }

  // audio clips play with the scene clock: started, sought and paused to match it (spec section 15)
  _syncAudio() {
    const v = this._viewer;
    if (!v || !this._audio) {
      this._stopAudio();
      return;
    }
    const clips = parseList(this._safe(() => v.media(), "[]")).filter((c) => c.kind === "audio" && c.url);
    const t = v.time(), playing = v.playing();
    const live = new Set();
    for (const c of clips) {
      const key = c.asset + "@" + c.start;
      live.add(key);
      let a = this._players.get(key);
      if (!a) {
        a = new Audio(c.url);
        a.preload = "auto";
        this._players.set(key, a);
      }
      const pos = c.in + (t - c.start) * c.rate;
      const inside = t >= c.start && (c.out == null || pos < c.out);
      a.volume = Math.min(1, Math.max(0, c.volume));
      a.playbackRate = c.rate;
      if (playing && inside) {
        if (Math.abs(a.currentTime - pos) > 0.25) a.currentTime = pos;
        if (a.paused) a.play().catch(() => this._fail("audio", "the browser blocked sound: play again from a click"));
      } else if (!a.paused) a.pause();
    }
    for (const [k, a] of this._players) {
      if (!live.has(k)) {
        a.pause();
        this._players.delete(k);
      }
    }
  }

  _stopAudio() {
    for (const a of this._players.values()) a.pause();
    this._players.clear();
  }
}

function message(e) {
  return (e && e.message) || String(e);
}

function parseList(json) {
  try {
    const v = JSON.parse(json || "[]");
    return Array.isArray(v) ? v : [];
  } catch (e) {
    return [];
  }
}

if (!customElements.get("diagrams-viewer")) customElements.define("diagrams-viewer", DiagramsViewer);
