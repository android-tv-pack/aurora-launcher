#!/usr/bin/env python3
"""
Blade Runner 2019 city backdrop for Aurora, rendered offline (numpy + PIL + scipy).

  br_bg.webp      the city + sky + wet street (1600x900, lossy)
  br_emit.webp    windows + lamps only, with bloom: drawn additively so they can breathe with the music
  br_signs.webp   neon-sign atlas (additive); br_signs.txt lists the sprites (placement + size + flicker group)
  br_fog.webp     seamless smog band the app scrolls slowly in front of the city (additive)
  preview.png     everything composited, as the app would draw it with the music silent
"""
import math, os, sys, json
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from scipy.ndimage import gaussian_filter, zoom, map_coordinates

OUT = sys.argv[1] if len(sys.argv) > 1 else "out_br"
os.makedirs(OUT, exist_ok=True)
W, H = 1600, 900
YH = int(H * 0.55)                     # horizon, same as the app (0.55 of the screen height)
rng = np.random.default_rng(1982)

def sm(x): x = np.clip(x, 0, 1); return x * x * (3 - 2 * x)
def lerpc(a, b, t): return np.array(a, np.float32) * (1 - t) + np.array(b, np.float32) * t
def blur(img, s): return gaussian_filter(img, (s, s, 0) if img.ndim == 3 else s, mode="nearest")

def fbm(h, w, base=6, octaves=6, seed=0, stretch=1.0):
    r = np.random.default_rng(seed)
    out = np.zeros((h, w), np.float32); amp = 1.0; tot = 0
    for o in range(octaves):
        gh = max(2, int(base * (2 ** o) / stretch)); gw = max(2, int(base * (2 ** o) * (w / h) ))
        n = r.random((gh, gw)).astype(np.float32)
        out += amp * zoom(n, (h / gh, w / gw), order=3)[:h, :w]
        tot += amp; amp *= 0.55
    out /= tot
    return (out - out.min()) / (out.max() - out.min())

# ------------------------------------------------------------------ sky: smog lit from below
ys = np.linspace(0, 1, H, dtype=np.float32)[:, None, None]
hy = YH / H
top = np.array([2, 5, 10], np.float32); mid = np.array([14, 34, 44], np.float32)
low = np.array([132, 82, 54], np.float32); hor = np.array([255, 134, 52], np.float32)
g = np.clip(ys / hy, 0, 1)
sky = np.where(g < 0.45, lerpc(top, mid, sm(g / 0.45)), np.where(g < 0.8, lerpc(mid, low, sm((g - 0.45) / 0.35)), lerpc(low, hor, sm((g - 0.8) / 0.2))))
sky = np.broadcast_to(sky, (H, W, 3)).copy()
# smog banks, stretched horizontally, lighter where the horizon glow shines through
n1 = fbm(H, W, base=3, octaves=6, seed=3, stretch=0.35)
n2 = fbm(H, W, base=5, octaves=5, seed=4, stretch=0.4)
smog = np.clip(0.55 * n1 + 0.45 * n2, 0, 1)[..., None]
glow_y = np.exp(-((np.linspace(0, 1, H)[:, None] - hy) / 0.22) ** 2)[..., None].astype(np.float32)
sky = sky * (0.55 + 0.9 * smog) + glow_y * smog * np.array([70, 28, 6], np.float32)
# the glow behind the city: a broad orange bloom, strongest left of centre-right
xx = np.linspace(0, 1, W, dtype=np.float32)[None, :, None]
sky += np.exp(-(((xx - 0.52) / 0.42) ** 2) - (((ys - hy) / 0.2) ** 2)) * np.array([90, 40, 8], np.float32)
img = sky.copy()
fog_col = lambda y: np.clip(np.array([255, 128, 54], np.float32) * (0.22 + 0.95 * np.exp(-((y / H - hy) / 0.24) ** 2)), 0, 255)
cmask = np.zeros((H, W, 1), np.float32)

# ------------------------------------------------------------------ buildings (far -> near), with haze between layers
emit = np.zeros((H, W, 3), np.float32)           # windows and lamps only
def draw_layer(depth, seed, count, hmin, hmax, wmin, wmax, body, win_density, win_size, haze, tall_every=0, edge=False, x0=-60, x1=W + 60, skip=None):
    global img, cmask
    fc0 = np.broadcast_to(np.stack([fog_col(y) for y in range(H)])[:, None, :], (H, W, 3))
    img = img * (1 - haze * cmask) + fc0 * haze * cmask
    r = np.random.default_rng(seed)
    L = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(L)
    E = Image.new("RGB", (W, H), (0, 0, 0)); de = ImageDraw.Draw(E)
    x = x0
    bi = 0
    while x < x1:
        bw = int(r.uniform(wmin, wmax))
        hgt = r.uniform(hmin, hmax)
        if tall_every and bi % tall_every == tall_every - 1: hgt *= r.uniform(1.4, 1.9)
        top_y = int(YH - hgt)
        tone = r.uniform(0.75, 1.25)
        col = tuple(int(np.clip(c * tone, 0, 255)) for c in body)
        style = r.choice(["flat", "flat", "step", "step", "spire", "slant", "twin"])
        pts = []
        if style == "flat": pts = [(x, YH + 2), (x, top_y), (x + bw, top_y), (x + bw, YH + 2)]
        elif style == "step":
            s1 = int(bw * r.uniform(0.12, 0.28)); s2 = int(bw * r.uniform(0.3, 0.45)); h1 = int(hgt * 0.12); h2 = int(hgt * 0.24)
            pts = [(x, YH + 2), (x, top_y + h2), (x + s1, top_y + h2), (x + s1, top_y + h1), (x + s2, top_y + h1), (x + s2, top_y),
                   (x + bw - s2, top_y), (x + bw - s2, top_y + h1), (x + bw - s1, top_y + h1), (x + bw - s1, top_y + h2), (x + bw, top_y + h2), (x + bw, YH + 2)]
        elif style == "spire":
            pts = [(x, YH + 2), (x, top_y + hgt * 0.1), (x + bw * 0.45, top_y + hgt * 0.1), (x + bw * 0.5, top_y - hgt * 0.12), (x + bw * 0.55, top_y + hgt * 0.1), (x + bw, top_y + hgt * 0.1), (x + bw, YH + 2)]
        elif style == "slant":
            pts = [(x, YH + 2), (x, top_y + hgt * 0.18), (x + bw, top_y), (x + bw, YH + 2)]
        else:
            gp = int(bw * 0.12)
            pts = [(x, YH + 2), (x, top_y), (x + bw * 0.45, top_y), (x + bw * 0.45, top_y + hgt * 0.1), (x + bw * 0.55, top_y + hgt * 0.1), (x + bw * 0.55, top_y), (x + bw, top_y), (x + bw, YH + 2)]
        d.polygon(pts, fill=col + (255,))
        if style == "spire" and depth >= 1:
            d.line([(x + bw * 0.5, top_y - hgt * 0.12), (x + bw * 0.5, top_y - hgt * 0.12 - hgt * 0.18)], fill=col + (255,), width=max(1, int(2 + depth)))
            de.ellipse((x + bw * 0.5 - 2, top_y - hgt * 0.3 - 2, x + bw * 0.5 + 2, top_y - hgt * 0.3 + 2), fill=(255, 40, 30))
        # windows: warm rectangles, columns and floors, random on/off, some whole floors lit
        if win_density > 0 and bw > 8:
            ww, wh = win_size
            gx = ww * 2.1; gy = wh * 2.0
            cols = int((bw - 6) / gx); rows = int((YH - top_y - 8) / gy)
            for rr in range(rows):
                floor_on = r.random() < 0.045
                for cc in range(cols):
                    if floor_on or r.random() < win_density:
                        wx = x + 4 + cc * gx; wy = top_y + 8 + rr * gy
                        if wy < top_y + 8 or wy > YH - 4: continue
                        inten = r.uniform(0.25, 0.8)
                        kind = r.random()
                        c = (255, 196, 120) if kind < 0.72 else ((140, 220, 255) if kind < 0.88 else (255, 120, 90))
                        c = tuple(int(v * inten) for v in c)
                        de.rectangle((wx, wy, wx + ww, wy + wh), fill=c)
        x += bw + (r.uniform(0, 4) if depth >= 3 else 0)
        bi += 1
    Lr = np.asarray(L, np.float32) / 255.0
    a = Lr[..., 3:4]
    body_rgb = Lr[..., :3] * 255.0
    # street glow washes the bottom of every building, dark crown on top
    yy = np.arange(H, dtype=np.float32)[:, None, None]
    up = np.exp(-(YH - yy) / (H * (0.16 + 0.1 * (1 - depth / 4.0)))) * np.array([54, 30, 12], np.float32) * (0.5 + 0.5 * (1 - depth / 4.0))
    body_rgb = body_rgb + up * (a > 0)
    # rim light from the horizon bloom on the edges facing the middle
    ed = a - np.roll(a, 2, axis=1)
    rim = np.clip(np.abs(ed), 0, 1) * np.array([120, 62, 20], np.float32) * (0.15 + 0.3 * (1 - depth / 4.0))
    wm = np.asarray(E, np.float32)
    img = img * (1 - a) + (body_rgb + rim) * a
    # windows sit on the building: show them in the picture too (dimmer than in the additive layer)
    img = img + wm * 0.55 * (a > 0.5)
    emit_add = wm * (a > 0.5)
    # haze between layers (atmospheric perspective)
    fc = np.broadcast_to(np.stack([fog_col(y) for y in range(H)])[:, None, :], (H, W, 3))
    cmask = np.maximum(cmask, a)
    global emit
    emit += emit_add

# far haze-blue skyline, mid cityscape, tall mids, near blocks
draw_layer(0, 11, 0, hmin=150, hmax=330, wmin=46, wmax=118, body=(120, 70, 46), win_density=0.0, win_size=(2, 3), haze=0.0, tall_every=4)
draw_layer(1, 12, 0, hmin=130, hmax=380, wmin=52, wmax=130, body=(74, 52, 44), win_density=0.05, win_size=(2, 3), haze=0.42, tall_every=4)
draw_layer(2, 13, 0, hmin=120, hmax=400, wmin=60, wmax=150, body=(36, 34, 38), win_density=0.07, win_size=(3, 4), haze=0.34, tall_every=4)

# ------------------------------------------------------------------ the pyramid (Tyrell), stepped, lit in bands; sits between layers 2 and 3
def pyramid(cx, base_w, steps, step_h, body):
    global img, emit, cmask
    fc0 = np.broadcast_to(np.stack([fog_col(y) for y in range(H)])[:, None, :], (H, W, 3))
    img = img * (1 - 0.22 * cmask) + fc0 * 0.22 * cmask
    L = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(L)
    E = Image.new("RGB", (W, H), (0, 0, 0)); de = ImageDraw.Draw(E)
    sw = base_w / 2; y = YH
    for s in range(steps):
        sh = step_h * (1 - 0.04 * s)
        d.rectangle((cx - sw, y - sh, cx + sw, y + 2), fill=body + (255,))
        # window grid, tight, mostly lit
        gx = 8; gy = 9
        for wy in np.arange(y - sh + 4, y - 3, gy):
            for wx in np.arange(cx - sw + 5, cx + sw - 7, gx):
                if rng.random() < 0.34:
                    inten = rng.uniform(0.35, 0.85)
                    de.rectangle((wx, wy, wx + 3, wy + 3), fill=(int(255 * inten), int(180 * inten), int(96 * inten)))
        # bright ledge
        de.rectangle((cx - sw, y - sh, cx + sw, y - sh + 2), fill=(255, 214, 150))
        y -= sh; sw *= 0.8
    # capstone beacon
    de.ellipse((cx - 5, y - 8, cx + 5, y + 2), fill=(255, 230, 190))
    Lr = np.asarray(L, np.float32) / 255.0; a = Lr[..., 3:4]
    wm = np.asarray(E, np.float32)
    shade = body_arr = Lr[..., :3] * 255.0
    img = img * (1 - a) + shade * a + wm * 0.7 * (a > 0.5)
    emit += wm * (a > 0.5)
    fc = np.broadcast_to(np.stack([fog_col(yy) for yy in range(H)])[:, None, :], (H, W, 3))
    cmask = np.maximum(cmask, a)
pyramid(int(W * 0.575), 470, 7, 56, (16, 18, 24))
spire_x = int(W * 0.60)

draw_layer(3, 14, 0, hmin=70, hmax=270, wmin=70, wmax=170, body=(12, 14, 20), win_density=0.05, win_size=(4, 5), haze=0.26, tall_every=5)
draw_layer(4, 15, 0, hmin=40, hmax=130, wmin=80, wmax=200, body=(4, 6, 9), win_density=0.03, win_size=(5, 6), haze=0.18)

# framing towers at the edges (dark, tall), kept narrow so the hero text on the left stays clear
for (xa, xb, hh_) in ((-30, 40, 520), (1590 - 70, 1650, 560)):
    img[YH - hh_:YH + 2, max(0, xa):min(W, xb)] = np.array([4, 6, 10], np.float32)

# flame stacks (their glow here; the flame itself is animated by the app)
for fx in (0.10, 0.93):
    x = int(W * fx); topy = int(YH - H * 0.30)
    img[topy:YH + 2, x - 5:x + 5] = np.array([6, 10, 14], np.float32)
    emit[topy - 4:topy + 2, x - 3:x + 3] += np.array([255, 150, 60], np.float32)

# ------------------------------------------------------------------ the wet street: a reflection of everything above
street = np.zeros((H - YH, W, 3), np.float32)
src_h = H - YH
src = img[YH - src_h:YH][::-1].copy()                     # mirrored rows, nearest the horizon first
emit_src = emit[YH - src_h:YH][::-1].copy()
dd = np.linspace(0, 1, src_h, dtype=np.float32)[:, None, None]
# ripples bend the mirror sideways
ry = np.arange(src_h, dtype=np.float32)[:, None]; rx = np.arange(W, dtype=np.float32)[None, :]
disp = 5.0 * np.sin(ry * 0.11 + 2.0 * fbm(src_h, W, 8, 3, 21)) * (0.4 + dd[..., 0])
coords = np.stack([np.broadcast_to(ry, (src_h, W)), np.clip(rx + disp, 0, W - 1)])
src2 = np.stack([map_coordinates(src[..., c], coords, order=1, mode="nearest") for c in range(3)], -1)
em2 = np.stack([map_coordinates(emit_src[..., c], coords, order=1, mode="nearest") for c in range(3)], -1)
smear = blur(src2, 2.2); em_s = gaussian_filter(em2, (7, 1.2, 0))
asphalt = np.array([7, 12, 16], np.float32) + np.array([20, 10, 4], np.float32) * np.exp(-dd * 4)
refl = smear * (0.48 * np.exp(-dd * 3.2)) + em_s * (1.0 * np.exp(-dd * 2.4)) * 1.4
street = asphalt + refl
img[YH:] = street
img[YH - 1:YH + 1] = img[YH - 1:YH + 1] * 0.8 + np.array([40, 22, 8], np.float32) * 0.6      # a bright seam at the horizon

# ------------------------------------------------------------------ grading: filmic curve, teal/orange split, grain, vignette
x_ = img / 255.0
x_ = x_ * 1.0
x_ = 1 - np.exp(-x_ * 1.9)
x_ = x_ ** 0.96
lum = x_.mean(-1, keepdims=True)
x_ = x_ + (np.array([-0.02, 0.0, 0.03], np.float32)) * (1 - lum) + (np.array([0.03, 0.005, -0.03], np.float32)) * lum
vy = np.linspace(-1, 1, H, dtype=np.float32)[:, None, None]; vx = np.linspace(-1, 1, W, dtype=np.float32)[None, :, None]
vig = 1 - 0.38 * np.clip((vx ** 2) * 0.55 + (vy ** 2) * 0.45, 0, 1)
x_ = x_ * vig
x_ += rng.normal(0, 0.006, x_.shape).astype(np.float32)
bg = np.clip(x_, 0, 1)

def save(arr, name, q=88, lossless=False):
    Image.fromarray((np.clip(arr, 0, 1) * 255 + 0.5).astype(np.uint8)).save(os.path.join(OUT, name), "WEBP", quality=q, method=6, lossless=lossless)
save(bg, "br_bg.webp", 86)

# emissive layer: windows with soft bloom
em = emit / 255.0
em_b = em + 0.9 * blur(em, 3.0) + 0.6 * blur(em, 10.0)
em_img = 1 - np.exp(-em_b * 1.2)
em_img[YH:] *= 0.0           # the street already has its own reflections
save(em_img * 0.85, "br_emit.webp", 84)

# ------------------------------------------------------------------ neon signs (separate sprites, so each can flicker)
def font(path, size, idx=0):
    return ImageFont.truetype(path, size, index=idx)
JP = "/System/Library/Fonts/Hiragino Sans GB.ttc"
LATIN = "/System/Library/Fonts/Supplemental/DIN Condensed Bold.ttf"
def tube_text(txt, f, color, pad=40, vertical=False, spacing=6):
    # measure
    tmp = Image.new("L", (10, 10)); dtmp = ImageDraw.Draw(tmp)
    if vertical:
        sizes = [dtmp.textbbox((0, 0), ch, font=f) for ch in txt]
        w_ = max(b[2] - b[0] for b in sizes); h_ = sum(b[3] - b[1] + spacing for b in sizes)
    else:
        b = dtmp.textbbox((0, 0), txt, font=f); w_, h_ = b[2] - b[0], b[3] - b[1]
    w_ += pad * 2; h_ += pad * 2
    m = Image.new("L", (w_, h_), 0); dm = ImageDraw.Draw(m)
    if vertical:
        y = pad
        for ch, bb in zip(txt, sizes):
            dm.text((pad + (w_ - 2 * pad - (bb[2] - bb[0])) // 2 - bb[0], y - bb[1]), ch, font=f, fill=255); y += bb[3] - bb[1] + spacing
    else:
        dm.text((pad - b[0], pad - b[1]), txt, font=f, fill=255)
    return np.asarray(m, np.float32) / 255.0

def neon_from_mask(mask, color, tube=3, glow=1.0):
    # glass tube = outline of the glyphs: dilate - erode
    m = Image.fromarray((mask * 255).astype(np.uint8))
    dil = np.asarray(m.filter(ImageFilter.MaxFilter(3)), np.float32) / 255.0
    ero = np.asarray(m.filter(ImageFilter.MinFilter(3)), np.float32) / 255.0
    outline = np.clip(dil - ero, 0, 1)
    core = blur(outline, 0.6)
    col = np.array(color, np.float32) / 255.0
    white = lerpc(col, (1, 1, 1), 0.7)
    out = core[..., None] * white * 1.2
    out += blur(outline, 2.0)[..., None] * col * 1.0
    out += blur(outline, 6.0)[..., None] * col * 0.85 * glow
    out += blur(outline, 16.0)[..., None] * col * 0.6 * glow
    return out

def frame_box(w_, h_, color, glow=1.0, bar=4):
    m = np.zeros((h_, w_), np.float32)
    m[:bar] = 1; m[-bar:] = 1; m[:, :bar] = 1; m[:, -bar:] = 1
    col = np.array(color, np.float32) / 255.0
    return m, (blur(m, 0.7)[..., None] * lerpc(col, (1, 1, 1), 0.7) * 1.1 + blur(m, 5)[..., None] * col * 0.8 * glow + blur(m, 14)[..., None] * col * 0.55 * glow)

signs = []   # (name, image float HxWx3, screen centre x, y as fractions, scale vs 1600 width, group)
fJP = font(JP, 54); fJPs = font(JP, 40); fL = font(LATIN, 78); fLs = font(LATIN, 56)
# 1. vertical kanji on a pole, magenta
m = tube_text("電脳城", fJP, None, vertical=True, spacing=10); signs.append(("kanji", neon_from_mask(m, (255, 56, 150)), 0.455, 0.40, 0, "A"))
# 2. NOODLES in a frame, cyan
mt = tube_text("NOODLES", fL, None, pad=34); nm = neon_from_mask(mt, (30, 230, 255)); signs.append(("noodles", nm, 0.285, 0.47, 0, "B"))
# 3. ramen in katakana, orange
m = tube_text("ラーメン", fJPs, None, pad=36); signs.append(("ramen", neon_from_mask(m, (255, 138, 40)), 0.115, 0.505, 0, "C"))
# 4. HOTEL, red
m = tube_text("HOTEL", fLs, None, pad=36); signs.append(("hotel", neon_from_mask(m, (255, 52, 70)), 0.665, 0.465, 0, "D"))
# 5. 酒場 vertical, green-cyan, far right
m = tube_text("酒場", fJPs, None, vertical=True, pad=36, spacing=8); signs.append(("sake", neon_from_mask(m, (60, 255, 190)), 0.925, 0.41, 0, "E"))
# 6. big LED billboard with 強力 and a glowing field
bw_, bh_ = 210, 124
bil = np.zeros((bh_ + 80, bw_ + 80, 3), np.float32)
field = np.zeros((bh_, bw_, 3), np.float32); gy = np.linspace(0, 1, bh_)[:, None, None]
field += lerpc((255, 120, 40), (255, 190, 90), gy) / 255.0 * 0.85
mt = tube_text("強力", font(JP, 74), None, pad=0); mh, mw = mt.shape
tm = np.zeros((bh_, bw_), np.float32); oy = (bh_ - mh) // 2; ox = (bw_ - mw) // 2
tm[max(0, oy):max(0, oy) + mh, max(0, ox):max(0, ox) + mw] = mt[: bh_ - max(0, oy), : bw_ - max(0, ox)]
field = field * (1 - tm[..., None] * 0.92) + tm[..., None] * np.array([1.0, 0.22, 0.2]) * 0.8
bil[40:40 + bh_, 40:40 + bw_] = field * 0.8
bil += blur(np.pad(field, ((40, 40), (40, 40), (0, 0))), 14) * 0.7
signs.append(("billboard", np.clip(bil, 0, 2.5), 0.80, 0.30, 0, "F"))

# pack into an atlas (shelf packing), write the sprite list
order = sorted(range(len(signs)), key=lambda i: -signs[i][1].shape[0])
ATW = 1280; x = y = rowh = 0; place = {}
for i in order:
    h_, w_ = signs[i][1].shape[:2]
    if x + w_ > ATW: x = 0; y += rowh; rowh = 0
    place[i] = (x, y); x += w_; rowh = max(rowh, h_)
ATH = y + rowh
atlas = np.zeros((ATH, ATW, 3), np.float32)
lines = []
for i, (nm_, arr, cx, cy, _, grp) in enumerate(signs):
    px, py = place[i]; h_, w_ = arr.shape[:2]
    atlas[py:py + h_, px:px + w_] = arr
    lines.append(f"{nm_} {px} {py} {w_} {h_} {cx:.4f} {cy:.4f} {grp}")
atl = 1 - np.exp(-atlas * 1.1)
save(atl, "br_signs.webp", 88)
open(os.path.join(OUT, "br_signs.txt"), "w").write("\n".join(lines) + "\n")

# ------------------------------------------------------------------ seamless smog band for the app to drift
FW, FH = 1024, 256
f1 = fbm(FH, FW, base=2, octaves=6, seed=31, stretch=0.4)
f2 = fbm(FH, FW, base=3, octaves=5, seed=32, stretch=0.5)
fg = np.clip(0.6 * f1 + 0.4 * f2, 0, 1) ** 1.6
# make it tile horizontally by cross-fading with a shifted copy
sh = np.roll(fg, FW // 2, axis=1); wgt = (0.5 - 0.5 * np.cos(np.linspace(0, 2 * np.pi, FW)))[None, :]
fg = fg * wgt + sh * (1 - wgt)
fgm = np.exp(-((np.linspace(-1, 1, FH)) ** 2) * 2.2)[:, None]
fog = (fg * fgm)[..., None] * np.array([1.0, 0.62, 0.34], np.float32)
save(np.clip(fog * 0.9, 0, 1), "br_fog.webp", 80)

# ------------------------------------------------------------------ preview: what the app shows when quiet
prev = bg + 0.35 * em_img
prev_img = np.clip(prev, 0, 1)
# add signs at their places
canvas = prev_img.copy()
for i, (nm_, arr, cx, cy, _, grp) in enumerate(signs):
    h_, w_ = arr.shape[:2]
    X = int(cx * W - w_ / 2); Y = int(cy * H - h_ / 2)
    xa, ya = max(0, X), max(0, Y); xb, yb = min(W, X + w_), min(H, Y + h_)
    if xb > xa and yb > ya:
        a = (1 - np.exp(-arr * 1.1))[ya - Y:yb - Y, xa - X:xb - X] * 0.95
        canvas[ya:yb, xa:xb] = 1 - (1 - canvas[ya:yb, xa:xb]) * (1 - a)
        # wet reflection under the sign
        ry_ = YH + (YH - (Y + h_ // 2)) // 2
save(canvas, "preview.webp", 92)
Image.fromarray((canvas * 255).astype(np.uint8)).save(os.path.join(OUT, "preview.png"))
print("done", ATW, ATH)
