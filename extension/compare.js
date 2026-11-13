// Before / After slider on a <canvas>. Same behaviour as the Android app's CompareView:
//  - left of the handle = original (BEFORE), right = result (AFTER), both drawn at the same size
//  - drag the handle (or drag anywhere while not zoomed) to move the slider
//  - mouse wheel / pinch / double-click to zoom in and check detail, drag to pan when zoomed

export class CompareViewer {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.before = null;
    this.after = null;
    this.message = 'Loading ...';
    this.divider = 0.5;
    this.fit = 1; this.zoom = 1; this.tx = 0; this.ty = 0;
    this.pointers = new Map();
    this.draggingSlider = false;
    this.pinch = null;
    this.dpr = window.devicePixelRatio || 1;

    canvas.addEventListener('pointerdown', (e) => this.onDown(e));
    canvas.addEventListener('pointermove', (e) => this.onMove(e));
    canvas.addEventListener('pointerup', (e) => this.onUp(e));
    canvas.addEventListener('pointercancel', (e) => this.onUp(e));
    canvas.addEventListener('wheel', (e) => this.onWheel(e), { passive: false });
    canvas.addEventListener('dblclick', (e) => this.onDouble(e));
    this.ro = new ResizeObserver(() => this.resize());
    this.ro.observe(canvas);
    this.resize();
  }

  destroy() {
    this.ro.disconnect();
    this.setImages(null, null);
  }

  setImages(before, after, message = 'Loading ...') {
    for (const old of [this.before, this.after]) if (old && old !== before && old !== after && old.close) old.close();
    this.before = before; this.after = after; this.message = message;
    this.divider = 0.5;
    this.resetView();
  }

  get cssW() { return this.canvas.clientWidth; }
  get cssH() { return this.canvas.clientHeight; }

  resize() {
    this.dpr = window.devicePixelRatio || 1;
    this.canvas.width = Math.max(1, Math.round(this.cssW * this.dpr));
    this.canvas.height = Math.max(1, Math.round(this.cssH * this.dpr));
    this.resetView();
  }

  resetView() {
    this.zoom = 1;
    const a = this.after;
    if (a && this.cssW > 0 && this.cssH > 0) {
      this.fit = Math.min(this.cssW / a.width, this.cssH / a.height);
      this.tx = (this.cssW - a.width * this.fit) / 2;
      this.ty = (this.cssH - a.height * this.fit) / 2;
    }
    this.draw();
  }

  zoomAt(newZoom, fx, fy) {
    const z = Math.min(12, Math.max(1, newZoom));
    const k = z / this.zoom;
    this.tx = fx - (fx - this.tx) * k;
    this.ty = fy - (fy - this.ty) * k;
    this.zoom = z;
    this.clamp();
    this.draw();
  }

  clamp() {
    const a = this.after;
    if (!a) return;
    const sw = a.width * this.fit * this.zoom, sh = a.height * this.fit * this.zoom;
    this.tx = sw <= this.cssW ? (this.cssW - sw) / 2 : Math.min(0, Math.max(this.cssW - sw, this.tx));
    this.ty = sh <= this.cssH ? (this.cssH - sh) / 2 : Math.min(0, Math.max(this.cssH - sh, this.ty));
  }

  pos(e) {
    const r = this.canvas.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  onDown(e) {
    if (!this.after || !this.before) return;
    this.canvas.setPointerCapture(e.pointerId);
    const p = this.pos(e);
    this.pointers.set(e.pointerId, p);
    if (this.pointers.size === 1) {
      this.draggingSlider = this.zoom <= 1.05 || Math.abs(p.x - this.cssW * this.divider) < 24;
      if (this.draggingSlider) this.moveSlider(p.x);
    } else if (this.pointers.size === 2) {
      this.draggingSlider = false;
      const [a, b] = [...this.pointers.values()];
      this.pinch = { dist: Math.hypot(a.x - b.x, a.y - b.y), cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2 };
    }
  }

  onMove(e) {
    if (!this.pointers.has(e.pointerId)) return;
    const p = this.pos(e);
    const prev = this.pointers.get(e.pointerId);
    this.pointers.set(e.pointerId, p);
    if (this.pointers.size === 1) {
      if (this.draggingSlider) this.moveSlider(p.x);
      else { this.tx += p.x - prev.x; this.ty += p.y - prev.y; this.clamp(); this.draw(); }
    } else if (this.pointers.size === 2 && this.pinch) {
      const [a, b] = [...this.pointers.values()];
      const dist = Math.hypot(a.x - b.x, a.y - b.y);
      const cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2;
      this.tx += cx - this.pinch.cx; this.ty += cy - this.pinch.cy;
      this.zoomAt(this.zoom * (dist / this.pinch.dist), cx, cy);
      this.pinch = { dist, cx, cy };
    }
  }

  onUp(e) {
    this.pointers.delete(e.pointerId);
    this.pinch = null;
    this.draggingSlider = false;
  }

  onWheel(e) {
    if (!this.after) return;
    e.preventDefault();
    const p = this.pos(e);
    this.zoomAt(this.zoom * Math.exp(-e.deltaY * 0.0015), p.x, p.y);
  }

  onDouble(e) {
    if (!this.after) return;
    const p = this.pos(e);
    if (this.zoom > 1.05) this.resetView(); else this.zoomAt(3, p.x, p.y);
  }

  moveSlider(x) {
    this.divider = Math.min(0.99, Math.max(0.01, x / this.cssW));
    this.draw();
  }

  draw() {
    const { ctx, canvas } = this;
    const W = this.cssW, H = this.cssH, d = this.dpr;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#111';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.setTransform(d, 0, 0, d, 0, 0);
    const a = this.after, b = this.before;
    if (!a || !b) {
      ctx.fillStyle = '#aaa'; ctx.font = '16px system-ui, sans-serif'; ctx.textAlign = 'center';
      ctx.fillText(this.message, W / 2, H / 2);
      return;
    }
    const s = this.fit * this.zoom;
    const dx = W * this.divider;
    ctx.imageSmoothingEnabled = true;
    ctx.imageSmoothingQuality = 'high';

    // AFTER everywhere ...
    ctx.save();
    ctx.translate(this.tx, this.ty); ctx.scale(s, s);
    ctx.drawImage(a, 0, 0, a.width, a.height);
    ctx.restore();

    // ... BEFORE on top, only left of the slider (stretched to the same size as AFTER)
    ctx.save();
    ctx.beginPath(); ctx.rect(0, 0, dx, H); ctx.clip();
    ctx.translate(this.tx, this.ty); ctx.scale(s, s);
    ctx.drawImage(b, 0, 0, a.width, a.height);
    ctx.restore();

    // slider line + handle
    ctx.strokeStyle = 'rgba(0,0,0,.35)'; ctx.lineWidth = 5;
    ctx.beginPath(); ctx.moveTo(dx, 0); ctx.lineTo(dx, H); ctx.stroke();
    ctx.strokeStyle = '#fff'; ctx.lineWidth = 2;
    ctx.beginPath(); ctx.moveTo(dx, 0); ctx.lineTo(dx, H); ctx.stroke();
    ctx.fillStyle = '#fff'; ctx.shadowColor = 'rgba(0,0,0,.4)'; ctx.shadowBlur = 6;
    ctx.beginPath(); ctx.arc(dx, H / 2, 22, 0, Math.PI * 2); ctx.fill();
    ctx.shadowBlur = 0; ctx.fillStyle = '#333';
    ctx.beginPath(); ctx.moveTo(dx - 4, H / 2 - 7); ctx.lineTo(dx - 4, H / 2 + 7); ctx.lineTo(dx - 11, H / 2); ctx.fill();
    ctx.beginPath(); ctx.moveTo(dx + 4, H / 2 - 7); ctx.lineTo(dx + 4, H / 2 + 7); ctx.lineTo(dx + 11, H / 2); ctx.fill();

    // labels (hidden when the slider is over them)
    this.chip('BEFORE', 10, 10, false, dx);
    this.chip('AFTER', W - 10, 10, true, dx);
  }

  chip(text, x, y, right, dx) {
    const ctx = this.ctx;
    ctx.font = 'bold 12px system-ui, sans-serif';
    const w = ctx.measureText(text).width + 16, h = 24;
    const left = right ? x - w : x;
    if (!right && dx < left + w) return;
    if (right && dx > left) return;
    ctx.fillStyle = 'rgba(0,0,0,.6)';
    ctx.beginPath(); ctx.roundRect(left, y, w, h, h / 2); ctx.fill();
    ctx.fillStyle = '#fff'; ctx.textAlign = 'left';
    ctx.fillText(text, left + 8, y + 16);
  }
}
