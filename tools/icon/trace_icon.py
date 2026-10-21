# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0

"""Traces design/miru-icon-source.png (the apple-with-an-eye artwork) into the Miru launcher icon.

Outputs, relative to the repo root:
  design/miru-icon.svg                 full-color icon, background + foreground
  design/miru-icon-monochrome.svg      silhouette used for the Android 13 themed icon
  app/src/main/res/drawable/ic_miru.xml             adaptive foreground (also the splash icon)
  app/src/main/res/drawable/ic_miru_background.xml  adaptive background
  app/src/main/res/drawable/ic_miru_monochrome.xml  adaptive monochrome layer

Run: python tools/icon/trace_icon.py   (needs numpy and opencv-python-headless)
The source has a print texture; masks are blurred before tracing so the vector edges are clean.
"""
import math
import os

import cv2
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
SOURCE = os.path.join(ROOT, "design", "miru-icon-source.png")

# Flat colors for the vector, picked from the source (texture removed). Shared with design/tokens.json.
PAPER = "#FAF5EA"
RED = "#BE1625"
INK = "#321E19"
LEAF = "#4E6443"
MONO = "#000000"

VISIBLE_RADIUS = 34.0   # every point stays inside the 72-unit visible circle of the 108 canvas
MIN_AREA = 150          # px; drops texture specks
SMOOTH = 2.2            # px; Gaussian sigma applied to masks before tracing
BIG_SMOOTH = 10.0       # px; sigma for the apple and leaf outlines
EPSILON = 1.1           # px; polygon simplification before curve fitting


def masks(img):
    b, g, r = [img[..., i].astype(np.int16) for i in range(3)]
    red = (r > 120) & (r - g > 60) & (r - b > 45)
    leaf = (g - r > 6) & (g - b > 12) & (g < 200)
    dark = (np.maximum(np.maximum(r, g), b) < 115) & ~leaf
    cream = ~(red | leaf | dark)
    return {k: smooth(v) for k, v in dict(red=red, leaf=leaf, dark=dark, cream=cream).items()}


def smooth(mask, sigma=SMOOTH):
    m = cv2.GaussianBlur(mask.astype(np.float32), (0, 0), sigma)
    return (m > 0.5).astype(np.uint8)


def fill_holes(mask):
    out = np.zeros_like(mask)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    cv2.drawContours(out, [c for c in contours if cv2.contourArea(c) > MIN_AREA], -1, 1, cv2.FILLED)
    return out


def components(mask):
    count, labels, stats, _ = cv2.connectedComponentsWithStats(mask)
    parts = [(stats[i, cv2.CC_STAT_AREA], i) for i in range(1, count) if stats[i, cv2.CC_STAT_AREA] > MIN_AREA]
    return [(labels == i).astype(np.uint8) for _, i in sorted(parts, reverse=True)]


def dilate(mask, px):
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * px + 1, 2 * px + 1))
    return cv2.dilate(mask, k)


def layers_from_source():
    img = cv2.imread(SOURCE, cv2.IMREAD_COLOR)
    m = masks(img)
    # The apple and leaf are simple outlines; extra smoothing removes the ragged print edge.
    apple = smooth(fill_holes(m["red"]), BIG_SMOOTH)
    cream_in_apple = m["cream"] & apple
    parts = components(cream_in_apple)
    eye_white, glints = parts[0], parts[1:]
    pupil = np.zeros_like(apple)
    for p in glints:
        pupil |= p
    dark = m["dark"] & ~pupil
    leaf = smooth(m["leaf"] & ~apple, BIG_SMOOTH)
    return dict(apple=apple, eye_white=eye_white, dark=dark, pupil=pupil, leaf=leaf)


# ---------------------------------------------------------------- tracing
def contours(mask):
    found, _ = cv2.findContours(mask, cv2.RETR_TREE, cv2.CHAIN_APPROX_NONE)
    out = []
    for c in found:
        if abs(cv2.contourArea(c)) < MIN_AREA:
            continue
        poly = cv2.approxPolyDP(c, EPSILON, True)[:, 0, :].astype(float)
        if len(poly) >= 3:
            out.append(poly)
    return out


def n(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s == "-0" else s


def to_path(polys, tf):
    """Closed Catmull-Rom splines through the simplified points, written as cubic Béziers."""
    out = []
    for poly in polys:
        pts = [tf(x, y) for x, y in poly]
        k = len(pts)
        out.append(f"M{n(pts[0][0])},{n(pts[0][1])}")
        for i in range(k):
            p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % k], pts[(i + 2) % k]
            c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
            c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
            out.append(f"C{n(c1[0])},{n(c1[1])} {n(c2[0])},{n(c2[1])} {n(p2[0])},{n(p2[1])}")
        out.append("Z")
    return "".join(out)


def fit_transform(masks_):
    union = np.zeros_like(masks_[0])
    for m in masks_:
        union |= m
    ys, xs = np.nonzero(union)
    cx, cy = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
    reach = math.sqrt(((xs - cx) ** 2 + (ys - cy) ** 2).max())
    scale = VISIBLE_RADIUS / reach
    return lambda x, y: (54 + (x - cx) * scale, 54 + (y - cy) * scale)


# ---------------------------------------------------------------- writers
def svg(layers, title):
    body = [f'  <path fill="{fill}" fill-rule="evenodd" d="{d}"/>' for d, fill in layers]
    return "\n".join([f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">', f"  <title>{title}</title>", *body, "</svg>"]) + "\n"


def vector(layers):
    out = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    out += [f'    <path android:fillColor="{fill}" android:fillType="evenOdd" android:pathData="{d}" />' for d, fill in layers]
    out.append("</vector>")
    return "\n".join(out) + "\n"


def write(rel, text):
    path = os.path.join(ROOT, rel)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print("wrote", rel, f"({len(text) // 1024} KB)")


def main():
    L = layers_from_source()
    tf = fit_transform([L["apple"], L["dark"], L["leaf"]])

    # Ink is grown by a pixel so no red hairline shows where it overlaps the eye white.
    foreground = [
        (to_path(contours(L["apple"]), tf), RED),
        (to_path(contours(L["eye_white"]), tf), PAPER),
        (to_path(contours(dilate(L["dark"], 1)), tf), INK),
        (to_path(contours(L["pupil"]), tf), PAPER),
        (to_path(contours(L["leaf"]), tf), LEAF),
    ]
    background = [("M0,0H108V108H0Z", PAPER)]

    # One shape: the eye opening and glint become holes, and a thin gap keeps the
    # lid and lashes readable where they sit on the apple.
    solid = L["apple"] | L["dark"] | L["leaf"]
    gap = (dilate(L["dark"], 13) & ~dilate(L["dark"], 1)) & L["apple"] & ~L["eye_white"]
    mono = solid & ~L["eye_white"] & ~L["pupil"] & ~gap
    mono |= L["dark"] & ~L["pupil"]
    monochrome = [(to_path(contours(smooth(mono)), tf), MONO)]

    write("design/miru-icon.svg", svg(background + foreground, "Miru icon"))
    write("design/miru-icon-monochrome.svg", svg(monochrome, "Miru icon, monochrome"))
    write("app/src/main/res/drawable/ic_miru.xml", vector(foreground))
    write("app/src/main/res/drawable/ic_miru_background.xml", vector(background))
    write("app/src/main/res/drawable/ic_miru_monochrome.xml", vector(monochrome))


if __name__ == "__main__":
    main()
