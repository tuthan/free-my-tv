"""Draws the home screen's day and night backgrounds (Pillow, numpy). Usage: python3 tools/make_backgrounds.py
Without photos: soft color blobs on a flat base, light enough for dark text by day, dark enough for white text at night.
With pictures in backgrounds/ (JPEG or PNG): a day and a night version of each one (cropped to 16:9, location data
removed), light-N.jpg and dark-N.jpg in app/assets/backgrounds/; the home screen shows one pair a day, in turn.
backgrounds/ and the photo versions are in .gitignore: your pictures stay on your computer."""
import glob
import json
import os

import numpy as np
from PIL import Image, ImageFilter, ImageOps

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app/assets/backgrounds")
PHOTOS = os.path.join(ROOT, "backgrounds")
# Optional backgrounds/focus.json, {"file name": 0.4}: where the 16:9 crop sits on a portrait or square picture
# (0 = top, 1 = bottom). The middle otherwise.
FOCUS_FILE = os.path.join(PHOTOS, "focus.json")
W, H = 1280, 720

# (x, y, radius, color, strength): positions and radius as a fraction of the width / height.
LIGHT_BASE = (246, 244, 250)
LIGHT = [
    (0.12, 0.18, 0.55, (255, 211, 176), 0.95),   # peach
    (0.88, 0.12, 0.50, (255, 192, 212), 0.85),   # pink
    (0.82, 0.88, 0.55, (210, 200, 255), 0.95),   # lavender
    (0.15, 0.92, 0.50, (186, 224, 250), 0.90),   # sky
    (0.52, 0.50, 0.40, (214, 240, 226), 0.55),   # mint
]
DARK_BASE = (9, 11, 22)
DARK = [
    (0.15, 0.12, 0.55, (44, 40, 122), 0.95),     # indigo
    (0.88, 0.18, 0.50, (86, 44, 138), 0.85),     # violet
    (0.12, 0.92, 0.50, (12, 84, 108), 0.85),     # teal
    (0.86, 0.90, 0.50, (118, 36, 98), 0.70),     # magenta
    (0.52, 0.52, 0.40, (22, 30, 80), 0.60),
]


def paint(base, blobs, seed):
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    img = np.empty((H, W, 3), np.float32)
    img[:] = base
    for cx, cy, r, color, strength in blobs:
        d2 = ((x - cx * W) ** 2 + (y - cy * H) ** 2) / (r * W) ** 2
        w = (np.exp(-d2 * 2.2) * strength)[..., None]
        img = img * (1 - w) + np.array(color, np.float32) * w
    # A little noise keeps the soft gradients from banding.
    img += np.random.default_rng(seed).normal(0, 1.1, (H, W, 1)).astype(np.float32)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))


def crop(img, focus):
    """The largest 16:9 area, its vertical position set by `focus`, scaled to W x H."""
    w, h = img.size
    ch = min(h, round(w * 9 / 16))
    cw = round(ch * 16 / 9)
    top = round((h - ch) * focus)
    left = (w - cw) // 2
    return img.crop((left, top, left + cw, top + ch)).resize((W, H), Image.LANCZOS)


def fade(side_alpha, color):
    """A left-to-right veil (strongest at the left, where the clock is) as an RGB image and a mask."""
    x = np.linspace(0, 1, W, dtype=np.float32)[None, :].repeat(H, 0)
    mask = np.clip(1 - x / 0.55, 0, 1) ** 1.5 * side_alpha
    return Image.new("RGB", (W, H), color), Image.fromarray((mask * 255).astype(np.uint8))


def treat(photo, light):
    img = photo.filter(ImageFilter.GaussianBlur(3))
    if light:   # by day: washed toward white, so the dark text reads
        img = Image.blend(img, Image.new("RGB", (W, H), (250, 249, 252)), 0.42)
        veil, mask = fade(0.45, (250, 249, 252))
    else:       # at night: dimmed and a little desaturated
        img = Image.blend(ImageOps.autocontrast(img, 1), Image.new("RGB", (W, H), (8, 10, 20)), 0.68)
        veil, mask = fade(0.45, (6, 8, 16))
    img.paste(veil, (0, 0), mask)
    return img


def photos():
    names = sorted(f for f in glob.glob(os.path.join(PHOTOS, "*")) if f.lower().endswith((".jpg", ".jpeg", ".png")))
    for old in glob.glob(os.path.join(OUT, "light-*.jpg")) + glob.glob(os.path.join(OUT, "dark-*.jpg")):
        os.remove(old)
    focus = json.load(open(FOCUS_FILE)) if os.path.exists(FOCUS_FILE) else {}
    for n, path in enumerate(names, 1):
        with Image.open(path) as src:
            img = ImageOps.exif_transpose(src).convert("RGB")   # re-saved without the original's metadata
        shot = crop(img, focus.get(os.path.basename(path), 0.5))
        for name, light in (("light", True), ("dark", False)):
            out = os.path.join(OUT, "%s-%d.jpg" % (name, n))
            treat(shot, light).save(out, quality=88, optimize=True)
            print("Wrote", os.path.relpath(out, ROOT), "(%d KB)" % (os.path.getsize(out) // 1024), "from", os.path.basename(path))


def main():
    os.makedirs(OUT, exist_ok=True)
    photos()
    for name, base, blobs, seed in (("light", LIGHT_BASE, LIGHT, 1), ("dark", DARK_BASE, DARK, 2)):
        path = os.path.join(OUT, name + ".jpg")
        paint(base, blobs, seed).save(path, quality=92, subsampling=0, optimize=True)
        print("Wrote", os.path.relpath(path, ROOT), "(%d KB)" % (os.path.getsize(path) // 1024))


if __name__ == "__main__":
    main()
