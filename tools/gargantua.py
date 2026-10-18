#!/usr/bin/env python3
"""
Gargantua renderer for Aurora's Interstellar visualiser.

Physics: Schwarzschild null geodesics (u'' + u = 3u^2, M = 1) are integrated once into a table u(phi; b).
Every pixel of a virtual camera (observer at infinity, ~4 deg above the disc plane) is traced backwards through
that table: it either falls into the horizon, hits the thin accretion disc (up to 4 times: the direct image, the
lens-bent copy over/under the hole, and the thin photon rings), or escapes and picks up a *lensed* star from a
procedural sky. The disc gets a Keplerian Doppler/gravitational shift (mild, like the film), a temperature
profile mapped through a black-body colour ramp, fine radial banding, then bloom and a filmic tone-map.

Outputs (all for the 960x480 area the app draws around the hole):
  gar_disc_a.webp / gar_disc_b.webp  disc, two plasma-texture variants (the app cross-fades them)
  gar_stars.webp                     lensed star field (transmittance of the disc applied)
  gar_glow.webp                      wide soft halo, 240x120
  gar_lut.bin                        forward map (ring radius, azimuth) -> image position, per image order,
                                     so the app can run lensed hot-spots along the real light paths
  preview.png                        everything added together
"""
import math, os, sys, struct, time
import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter

OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
os.makedirs(OUT, exist_ok=True)
SS = 2
ASC = 1.5                              # render scale: the app draws the image at 960/W1 of this size
W1, H1 = int(960 * ASC), int(480 * ASC)
W, H = W1 * SS, H1 * SS
PPM = 17.7 * SS * ASC                       # pixels per gravitational radius (M)
THETA = math.radians(88.6)            # observer polar angle: 2 deg above the disc plane (the film is almost exactly edge-on)
RIN, ROUT = 2.3, 30.0
BC = 3 * math.sqrt(3)                 # critical impact parameter (shadow radius)
K = 4                                 # disc crossings followed per ray
t0 = time.time()
def log(*a): print(f"[{time.time()-t0:6.1f}s]", *a, flush=True)

# ------------------------------------------------------------------ 1. geodesic table
NB, B0, B1 = 7000, 0.02, 50.0
bs = np.linspace(B0, B1, NB)
DPHI, STORE, PMAX = 0.0008, 5, 4.6 * math.pi
nst = int(PMAX / DPHI)
NP = nst // STORE + 1
tab = np.zeros((NB, NP), np.float32)
u = np.zeros(NB); v = 1.0 / bs
alive = np.ones(NB, bool); cap = np.zeros(NB, bool)
phif = np.full(NB, np.nan)

def f(u, v): return v, -u + 3 * u * u

for i in range(1, nst + 1):
    k1u, k1v = f(u, v)
    k2u, k2v = f(u + .5 * DPHI * k1u, v + .5 * DPHI * k1v)
    k3u, k3v = f(u + .5 * DPHI * k2u, v + .5 * DPHI * k2v)
    k4u, k4v = f(u + DPHI * k3u, v + DPHI * k3v)
    un = u + DPHI / 6 * (k1u + 2 * k2u + 2 * k3u + k4u)
    vn = v + DPHI / 6 * (k1v + 2 * k2v + 2 * k3v + k4v)
    hit = alive & (un > 0.5)
    esc = alive & (un <= 0) & (i > 3)
    if esc.any():
        phif[esc] = (i - 1) * DPHI + DPHI * u[esc] / (u[esc] - un[esc])
    cap |= hit
    alive &= ~(hit | esc)
    u = np.where(alive, un, u); v = np.where(alive, vn, v)
    if i % STORE == 0:
        tab[:, i // STORE] = np.where(alive, u, np.where(cap, 0.75, -1.0))
log("geodesic table", tab.shape, "captured below b =", bs[cap].max() if cap.any() else None, "(theory", round(BC, 3), ")")

DP = DPHI * STORE
def lookup(b, phi):
    ib = np.clip((b - B0) / (B1 - B0) * (NB - 1), 0, NB - 1.001)
    ip = np.clip(phi / DP, 0, NP - 1.001)
    i0 = ib.astype(np.int32); j0 = ip.astype(np.int32)
    fb = (ib - i0).astype(np.float32); fp = (ip - j0).astype(np.float32)
    a = tab[i0, j0]; bb_ = tab[i0, j0 + 1]; c = tab[i0 + 1, j0]; d = tab[i0 + 1, j0 + 1]
    val = (a * (1 - fp) + bb_ * fp) * (1 - fb) + (c * (1 - fp) + d * fp) * fb
    ok = (np.minimum(np.minimum(a, bb_), np.minimum(c, d)) > 0) & (np.maximum(np.maximum(a, bb_), np.maximum(c, d)) < 0.5)
    return val, ok

# ------------------------------------------------------------------ 2. camera rays
ys, xs = np.mgrid[0:H, 0:W]
alpha = ((xs + 0.5 - W / 2) / PPM).astype(np.float32).ravel()
beta = ((H / 2 - ys - 0.5) / PPM).astype(np.float32).ravel()
del xs, ys
b = np.maximum(np.hypot(alpha, beta), 1e-3)
sinT, cosT = math.sin(THETA), math.cos(THETA)
ex = np.stack([np.full_like(b, sinT), np.zeros_like(b), np.full_like(b, cosT)], 0)        # to the observer
ehat = np.stack([-beta / b * cosT, alpha / b, beta / b * sinT], 0)                         # pixel direction
A = cosT; Bz = beta / b * sinT
phi0 = np.mod(np.arctan2(-A, Bz), np.pi).astype(np.float32)

# ------------------------------------------------------------------ 3. disc model
rng = np.random.default_rng(11)
NBAND = 6
band_f = rng.uniform(5, 38, (2, NBAND)); band_p = rng.uniform(0, 6.28, (2, NBAND)); band_a = rng.uniform(.4, 1, (2, NBAND))
az_m = np.array([2, 3, 5, 7]); az_p = rng.uniform(0, 6.28, (2, 4))

def smooth(x): x = np.clip(x, 0, 1); return x * x * (3 - 2 * x)

def blackbody(T):
    t = np.clip(T, 1000, 40000) / 100.0
    r = np.where(t <= 66, 255.0, 329.698727446 * np.power(np.maximum(t - 60, 1e-3), -0.1332047592))
    g = np.where(t <= 66, 99.4708025861 * np.log(t) - 161.1195681661, 288.1221695283 * np.power(np.maximum(t - 60, 1e-3), -0.0755148492))
    bl = np.where(t >= 66, 255.0, np.where(t <= 19, 0.0, 138.5177312231 * np.log(np.maximum(t - 10, 1e-3)) - 305.0447927307))
    c = np.clip(np.stack([r, g, bl], -1) / 255.0, 0, 1)
    return c ** 2.2

def disc_emission(r, phid, alp, variant, direct=False):
    inner = smooth((r - RIN) / 0.45)
    outer = 1.0 / (1.0 + (r / 13.0) ** 5.0)
    prof = 1.35 * (RIN / r) ** 2.9
    # relativistic shift of the orbiting plasma (strongly toned down, as in the film)
    om = np.power(np.maximum(r, 3.2), -1.5)
    rc = np.maximum(r, 3.2)
    base = np.sqrt(np.clip(1 - 3.0 / rc, 0.01, 1))
    dim = np.where(r < 3.2, np.sqrt(np.clip((1 - 2.0 / r) / (1 - 2.0 / 3.2), 0, 1)), 1.0)
    g_full = base * dim / np.clip(1 - om * alp * sinT, 0.35, 2.2)
    g = 1 + 0.22 * (g_full - 1)
    if direct:      # the direct image: the long bright band reaching out left and right, dimmer redshifted part inside the shadow
        prof = prof * (np.maximum(r, 5.0) / 5.0) ** 1.5 * (0.30 + 0.70 * smooth((r - 2.4) / 3.2))
    tex = np.ones_like(r)
    for j in range(NBAND):
        tex += 0.032 * band_a[variant, j] * np.sin(band_f[variant, j] * r + band_p[variant, j])
    for j in range(4):
        tex += 0.045 * np.cos(az_m[j] * phid + az_p[variant, j] + 0.3 * r)
    T = 5900.0 * (RIN / r) ** 0.62 * g
    col = blackbody(T)
    lum = prof * inner * outer * tex * np.power(np.clip(g, 0.3, 2.5), 2.0)
    return col * lum[..., None], inner * outer

# ------------------------------------------------------------------ 4. trace
log("tracing", b.size, "rays")
trans = np.ones_like(b)
acc = [np.zeros((b.size, 3), np.float32) for _ in range(2)]
lut_pts = []          # per image order: (r, phid, mask, x, y)
TWO_PI = 2 * math.pi
pix_y = (np.arange(H * W) // W).astype(np.float32)
pix_x = (np.arange(H * W) % W).astype(np.float32)
for k in range(K):
    phi = phi0 + k * math.pi
    uu, ok = lookup(b, phi)
    uu = np.where(ok, uu, 0.1)
    r = 1.0 / uu
    ok &= (r > RIN) & (r < ROUT)
    cp, sp = np.cos(phi), np.sin(phi)
    x3 = r * (cp * ex[0] + sp * ehat[0]); y3 = r * (cp * ex[1] + sp * ehat[1])
    phid = np.mod(np.arctan2(y3, x3), TWO_PI)
    em = []
    opac = None
    for vr in range(2):
        e, op = disc_emission(r, phid, alpha, vr, direct=(k == 0))
        em.append(e); opac = op
    a_k = np.where(ok, 0.93 * opac, 0).astype(np.float32)
    for vr in range(2):
        acc[vr] += (trans * a_k)[:, None] * em[vr] * ok[:, None]
    if k < 3:
        vis = ok & (trans > 0.25)
        lut_pts.append((r[vis], phid[vis], pix_x[vis] / SS / ASC, pix_y[vis] / SS / ASC))   # kept in 960x480 units: the app draws the image at that size
    trans = trans * (1 - a_k)
    log("crossing", k, "valid fraction", round(float(ok.mean()), 4))

# ------------------------------------------------------------------ 5. lensed star field
pf = np.interp(b, bs, np.where(np.isnan(phif), 0, phif)).astype(np.float32)
escaped = (np.interp(b, bs, (~np.isnan(phif)).astype(float)) > 0.999)
dirv = np.cos(pf) * ex + np.sin(pf) * ehat
lon = np.arctan2(dirv[1], dirv[0]); lat = np.arcsin(np.clip(dirv[2], -1, 1))
CELL = 0.0036 / ASC; SIG0 = 0.00052 / ASC; PSTAR = 0.011

def hash32(ix, iy, salt):
    h = (ix.astype(np.int64) * 73856093 ^ iy.astype(np.int64) * 19349663 ^ salt * 83492791) & 0xFFFFFFFF
    h = h.astype(np.uint32)
    h ^= h >> np.uint32(16); h *= np.uint32(0x45d9f3b); h ^= h >> np.uint32(16); h *= np.uint32(0x45d9f3b); h ^= h >> np.uint32(16)
    return h.astype(np.float32) / np.float32(4294967295.0)

cx_ = np.floor(lon / CELL); cy_ = np.floor(lat / CELL)
stars = np.zeros((b.size, 3), np.float32)
coslat = np.cos(lat)
dv = dirv.reshape(3, H, W)
sxs = np.linalg.norm(np.diff(dv, axis=2, append=dv[:, :, -1:]), axis=0)
sys_ = np.linalg.norm(np.diff(dv, axis=1, append=dv[:, -1:, :]), axis=0)
pxang = np.sqrt(np.maximum(sxs * sys_, 1e-12)).ravel().astype(np.float32)   # sky angle covered by one pixel
for di in (-1, 0, 1):
    for dj in (-1, 0, 1):
        ix = cx_ + di; iy = cy_ + dj
        pres = hash32(ix, iy, 1) < PSTAR
        if not pres.any(): continue
        sel = np.nonzero(pres)[0]
        ixs, iys = ix[sel], iy[sel]
        px = (ixs + hash32(ixs, iys, 2)) * CELL; py = (iys + hash32(ixs, iys, 3)) * CELL
        mag = hash32(ixs, iys, 4) ** 4.0 * 0.95 + 0.06
        tint = hash32(ixs, iys, 5)
        sig = SIG0 * (0.8 + 1.4 * mag)
        crowd = (pxang[sel] * PPM * 60.0) ** 2
        keep = hash32(ixs, iys, 6) < np.clip(1.5 / np.maximum(crowd, 1e-3), 0.06, 1.0)
        mag = mag * keep
        dl = (lon[sel] - px) * coslat[sel]; dp = lat[sel] - py
        se2 = sig * sig + (0.55 * pxang[sel]) ** 2
        g_ = np.exp(-(dl * dl + dp * dp) / (2 * se2)) * mag * (sig * sig / se2)
        col = np.stack([0.75 + 0.25 * tint, 0.85 + 0.1 * (1 - np.abs(tint - 0.5)), 1.0 - 0.3 * tint], -1)
        stars[sel] += (g_[:, None] * col * 1.5).astype(np.float32)
stars *= (escaped * trans)[:, None]
log("stars placed")

# ------------------------------------------------------------------ 6. grade
def to_img(x):                               # (N,3) -> (H,W,3) float, 2x down-sample
    return x.reshape(H, W, 3).reshape(H1, SS, W1, SS, 3).mean((1, 3))

def blur(img, s): return gaussian_filter(img, (s, s, 0), mode="constant")

def grade(lin, exposure):
    x = 1 - np.exp(-exposure * np.maximum(lin, 0))
    return x ** (1 / 2.2)

def save(img, name, lossless=True, q=94):
    a = np.clip(img * 255.0 + np.random.default_rng(5).uniform(-0.5, 0.5, img.shape) * (img > 0.002), 0, 255).astype(np.uint8)
    Image.fromarray(a).save(os.path.join(OUT, name), "WEBP", lossless=lossless, quality=q, method=6)

shadow = to_img(((trans > 0.98) & (~escaped)).astype(np.float32)[:, None].repeat(3, 1))
shadow_b = blur(shadow, 1.6 * ASC)
discs = []
for vr in range(2):
    d = to_img(acc[vr])
    bl = 0.38 * blur(d, 2.0 * ASC) + 0.26 * blur(d, 6.5 * ASC) + 0.12 * blur(d, 20 * ASC)
    discs.append(d + bl * (1 - 0.93 * shadow_b))
st = to_img(stars)
st = st + 0.8 * blur(st, 1.6 * ASC)
glow_src = discs[0]
glow = blur(glow_src, 55 * ASC) * (1 - 0.9 * shadow_b) * 0.6 * smooth(np.minimum(np.minimum(np.arange(W1)[None, :], W1 - 1 - np.arange(W1)[None, :]), np.minimum(np.arange(H1)[:, None], H1 - 1 - np.arange(H1)[:, None])) / (90.0 * ASC))[..., None]
gl_small = np.array(Image.fromarray(np.clip(grade(glow, 1.4) * 255, 0, 255).astype(np.uint8)).resize((240, 120), Image.LANCZOS))

yy, xx = np.mgrid[0:H1, 0:W1]
edge = np.minimum(np.minimum(xx, W1 - 1 - xx), np.minimum(yy, H1 - 1 - yy)).astype(np.float32)
win = smooth(edge / (70.0 * ASC))[..., None]
D0 = grade(discs[0] * win, 1.4); D1 = grade(discs[1] * win, 1.4); S = grade(st, 1.0)
save(D0, "gar_disc_a.webp"); save(D1, "gar_disc_b.webp"); save(S, "gar_stars.webp")
Image.fromarray(gl_small).save(os.path.join(OUT, "gar_glow.webp"), "WEBP", lossless=True)
prev = np.clip(D0 + S + 0.6 * np.array(Image.fromarray(gl_small).resize((W1, H1), Image.BICUBIC)) / 255.0, 0, 1)
Image.fromarray((prev * 255).astype(np.uint8)).save(os.path.join(OUT, "preview.png"))
log("images saved")

# ------------------------------------------------------------------ 7. forward LUT (ring radius, azimuth) -> image position
NR, NPH = 40, 240
lut = np.full((3, NR, NPH, 2), -32768, np.int16)
for k, (r, ph, px, py) in enumerate(lut_pts):
    ri = np.clip(((r - RIN) / (14.0 - RIN) * NR).astype(int), 0, NR - 1)
    inr = r < 14.0
    pj = np.clip((ph / TWO_PI * NPH).astype(int), 0, NPH - 1)
    flat = (ri * NPH + pj)[inr]
    cnt = np.bincount(flat, minlength=NR * NPH).astype(np.float64)
    sx = np.bincount(flat, weights=px[inr], minlength=NR * NPH); sy = np.bincount(flat, weights=py[inr], minlength=NR * NPH)
    m = cnt > 0
    lut[k].reshape(-1, 2)[m, 0] = np.round(sx[m] / cnt[m] * 8).astype(np.int16)
    lut[k].reshape(-1, 2)[m, 1] = np.round(sy[m] / cnt[m] * 8).astype(np.int16)
    log("lut order", k, "filled cells", int(m.sum()), "of", NR * NPH)
with open(os.path.join(OUT, "gar_lut.bin"), "wb") as fh:
    fh.write(struct.pack("<4i2f", 3, NR, NPH, 0, RIN, 14.0))
    fh.write(lut.astype("<i2").tobytes())
log("done")
