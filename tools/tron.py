#!/usr/bin/env python3
"""
TRON: Legacy "Grid" backdrop for Aurora, rendered offline.
  tr_bg.webp    night sky glow + faceted towers + glossy reflective floor (no grid lines: the app draws the moving grid)
  tr_emit.webp  the towers' glowing edges + beacons (additive, bloomed) so they can pulse with the music
  preview.png
"""
import os, sys
import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import gaussian_filter, zoom, map_coordinates

OUT = sys.argv[1] if len(sys.argv) > 1 else "out_tr"
os.makedirs(OUT, exist_ok=True)
W, H = 1600, 900
YH = int(H * 0.52)
rng = np.random.default_rng(2010)
def sm(x): x = np.clip(x, 0, 1); return x * x * (3 - 2 * x)
def blur(a, s): return gaussian_filter(a, (s, s, 0) if a.ndim == 3 else s, mode="nearest")

# ---- sky: deep black-blue, a cyan glow rising from the horizon, faint particulate haze
ys = np.linspace(0, 1, H, dtype=np.float32)[:, None, None]
hy = YH / H
g = np.clip(ys / hy, 0, 1)
top = np.array([0, 2, 7], np.float32); mid = np.array([0, 24, 44], np.float32); hor = np.array([8, 140, 190], np.float32)
sky = np.where(g < 0.6, top + (mid - top) * sm(g / 0.6), mid + (hor - mid) * sm((g - 0.6) / 0.4) ** 1.6)
img = np.broadcast_to(sky, (H, W, 3)).copy()
xx = np.linspace(0, 1, W, dtype=np.float32)[None, :, None]
img += np.exp(-(((xx - 0.5) / 0.5) ** 2) - (((ys - hy) / 0.09) ** 2)) * np.array([20, 110, 150], np.float32)
# stars / dust
for _ in range(260):
    x, y = rng.integers(0, W), rng.integers(0, int(YH * 0.8))
    img[y, x] += np.array([90, 150, 170], np.float32) * rng.uniform(0.2, 1.0)

emit = np.zeros((H, W, 3), np.float32)
CY = np.array([40, 220, 255], np.float32); OR = np.array([255, 130, 30], np.float32)

def towers(depth, seed, hmin, hmax, wmin, wmax, edge_a, body, haze):
    """faceted towers; edges glow. depth 0 = far"""
    global img, emit
    r = np.random.default_rng(seed)
    L = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(L)
    E = Image.new("RGB", (W, H), (0, 0, 0)); de = ImageDraw.Draw(E)
    # atmospheric haze over what is behind
    fc = np.array([6, 70, 100], np.float32)
    mask = (img.sum(-1, keepdims=True) > -1)
    x = -40
    while x < W + 40:
        bw = int(r.uniform(wmin, wmax)); hgt = r.uniform(hmin, hmax); top_y = int(YH - hgt)
        kind = r.choice(["slab", "slab", "wedge", "stepped", "needle", "prism"])
        col = body
        col_edge = tuple((OR if r.random() < 0.08 else CY).astype(int) * edge_a // 1)
        ce = tuple(int(v * edge_a) for v in (OR if r.random() < 0.1 else CY))
        if kind == "slab": pts = [(x, YH + 2), (x, top_y), (x + bw, top_y), (x + bw, YH + 2)]
        elif kind == "wedge": pts = [(x, YH + 2), (x, top_y + hgt * 0.25), (x + bw, top_y), (x + bw, YH + 2)]
        elif kind == "stepped":
            s = int(bw * 0.28); pts = [(x, YH + 2), (x, top_y + hgt * 0.2), (x + s, top_y + hgt * 0.2), (x + s, top_y), (x + bw - s, top_y), (x + bw - s, top_y + hgt * 0.2), (x + bw, top_y + hgt * 0.2), (x + bw, YH + 2)]
        elif kind == "needle": pts = [(x, YH + 2), (x + bw * 0.38, top_y + hgt * 0.35), (x + bw * 0.5, top_y - hgt * 0.15), (x + bw * 0.62, top_y + hgt * 0.35), (x + bw, YH + 2)]
        else: pts = [(x, YH + 2), (x + bw * 0.12, top_y + hgt * 0.1), (x + bw * 0.5, top_y), (x + bw * 0.88, top_y + hgt * 0.1), (x + bw, YH + 2)]
        d.polygon(pts, fill=col + (255,))
        # glowing edge: the outline plus a few interior circuit lines
        de.line(pts + [pts[0]], fill=ce, width=2 + depth)
        if kind in ("slab", "stepped", "prism"):
            for k in range(int(r.integers(2, 6))):
                ly = int(top_y + (YH - top_y) * r.uniform(0.15, 0.95))
                if r.random() < 0.6: de.line([(x + 3, ly), (x + bw - 3, ly)], fill=tuple(int(v * 0.45) for v in ce), width=1 + depth // 2)
            lx = int(x + bw * r.uniform(0.25, 0.75))
            de.line([(lx, top_y + 4), (lx, YH)], fill=tuple(int(v * 0.35) for v in ce), width=1 + depth // 2)
        if kind == "needle": de.ellipse((x + bw * 0.5 - 3, top_y - hgt * 0.15 - 3, x + bw * 0.5 + 3, top_y - hgt * 0.15 + 3), fill=(255, 255, 255))
        x += bw + int(r.uniform(-8, 6))
    Lr = np.asarray(L, np.float32) / 255.0; a = Lr[..., 3:4]
    wm = np.asarray(E, np.float32)
    img = img * (1 - haze) + fc * haze          # haze what is already behind
    shade = Lr[..., :3] * 255.0
    up = np.exp(-(YH - np.arange(H, dtype=np.float32)[:, None, None]) / (H * 0.22)) * np.array([4, 40, 56], np.float32) * (0.4 + 0.1 * depth)
    img = img * (1 - a) + (shade + up * (a > 0)) * a + wm * 0.55 * (a > 0.5)
    emit += wm

towers(0, 1, 120, 330, 40, 90, 0.38, (2, 18, 30), 0.0)
towers(1, 2, 140, 400, 46, 110, 0.55, (2, 12, 22), 0.28)
towers(2, 3, 100, 330, 56, 130, 0.8, (1, 7, 14), 0.30)
towers(3, 4, 60, 190, 70, 170, 1.0, (0, 3, 8), 0.22)

# the big central Grid tower: a beam of light rising out of the horizon
bx = int(W * 0.5)
beam = np.exp(-((np.arange(W, dtype=np.float32)[None, :] - bx) / 5.0) ** 2)[..., None] * (np.arange(H)[:, None] < YH)[..., None]
emit += beam * np.array([120, 230, 255], np.float32) * np.clip((YH - np.arange(H, dtype=np.float32)[:, None, None]) / YH, 0, 1) ** 0.6 * 1.2

# ---- glossy floor: mirror of the scene, fresnel-ish: stronger near the horizon, darker further down
sh = H - YH
src = img[YH - sh:YH][::-1].copy(); esrc = emit[YH - sh:YH][::-1].copy()
dd = np.linspace(0, 1, sh, dtype=np.float32)[:, None, None]
ry = np.arange(sh, dtype=np.float32)[:, None]; rx = np.arange(W, dtype=np.float32)[None, :]
disp = 1.4 * np.sin(ry * 0.35) * (0.3 + dd[..., 0])
co = np.stack([np.broadcast_to(ry, (sh, W)), np.clip(rx + disp, 0, W - 1)])
src = np.stack([map_coordinates(src[..., c], co, order=1, mode="nearest") for c in range(3)], -1)
esrc = np.stack([map_coordinates(esrc[..., c], co, order=1, mode="nearest") for c in range(3)], -1)
src = blur(src, 1.4); esrc = gaussian_filter(esrc, (3.0, 1.0, 0))
floor = np.array([0, 5, 12], np.float32) + np.array([0, 18, 28], np.float32) * np.exp(-dd * 5)
floor = floor + src * 0.55 * np.exp(-dd * 2.6) + esrc * 0.9 * np.exp(-dd * 2.0)
img[YH:] = floor
img[YH - 1:YH + 2] = np.array([90, 220, 255], np.float32) * 0.55      # horizon seam

x_ = img / 255.0
x_ = 1 - np.exp(-x_ * 1.7)
vy = np.linspace(-1, 1, H, dtype=np.float32)[:, None, None]; vx = np.linspace(-1, 1, W, dtype=np.float32)[None, :, None]
x_ *= 1 - 0.30 * np.clip(vx ** 2 * 0.55 + vy ** 2 * 0.45, 0, 1)
x_ += rng.normal(0, 0.004, x_.shape).astype(np.float32)
bg = np.clip(x_, 0, 1)

def save(a, n, q=86): Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8)).save(os.path.join(OUT, n), "WEBP", quality=q, method=6)
save(bg, "tr_bg.webp", 86)
em = emit / 255.0
emb = em + 0.8 * blur(em, 2.5) + 0.55 * blur(em, 9) + 0.3 * blur(em, 24)
emi = 1 - np.exp(-emb * 1.15)
emi[YH:] = 0
save(emi * 0.9, "tr_emit.webp", 84)
Image.fromarray((np.clip(bg + 0.45 * emi, 0, 1) * 255).astype(np.uint8)).save(os.path.join(OUT, "preview.png"))
print("done")
