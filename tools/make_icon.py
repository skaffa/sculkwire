"""Generates the Sculkwire icon as 32x32 pixel art, matching the Ghost Mason icon style.

Style rules (from the Ghost Mason icon): 32x32 grid scaled up with nearest neighbour,
dark rounded-square badge (#10161C) with 4-px stepped corners, a soft radial glow
behind the subject, an isometric 2:1 block character with a cute face, and cream
4-point sparkles.

Subject: a sculk sensor (half-height block with tendrils) that broadcasts signal arcs.

Usage: python tools/make_icon.py  -> media/icon.png (512), media/icon-128.png (128)
"""
import math
import os
import sys

from PIL import Image

N = 32
BG = (16, 22, 28, 255)
GLOW = (22, 58, 70)          # glow tint at the centre of the badge

# Sculk palette (brighter than vanilla so it reads on the dark badge)
TOP = (28, 128, 146, 255)
TOP_SPOT = (40, 160, 172, 255)
RIM = (150, 248, 246, 255)
LEFT = (18, 88, 108, 255)
RIGHT = (10, 54, 70, 255)
EDGE = (6, 34, 44, 255)
VEIN = (44, 206, 216, 255)
VEIN_DIM = (30, 140, 156, 255)
EYE = (150, 255, 246, 255)
EYE_HI = (235, 255, 252, 255)
TENDRIL = (26, 150, 166, 255)
TENDRIL_DARK = (16, 104, 120, 255)
TIP = (170, 255, 250, 255)
WAVE = (120, 250, 244)
SPARK = (255, 236, 140)
GROUND = (11, 48, 60, 255)
GROUND_DOT = (36, 170, 182, 255)


def blend(dst, src):
    """Alpha-composite src (r,g,b,a) over dst (r,g,b,a)."""
    sa = src[3] / 255
    da = dst[3] / 255
    oa = sa + da * (1 - sa)
    if oa == 0:
        return (0, 0, 0, 0)
    c = tuple(round((src[i] * sa + dst[i] * da * (1 - sa)) / oa) for i in range(3))
    return c + (round(oa * 255),)


def corner_cut(x, y):
    """Stepped rounded corners: 4,3,2,1 transparent pixels on rows 0..3 (mirrored)."""
    steps = [4, 3, 2, 1]
    for yy, xx in ((y, x), (N - 1 - y, x), (y, N - 1 - x), (N - 1 - y, N - 1 - x)):
        if yy < 4 and xx < steps[yy]:
            return True
    return False


def main(out_dir):
    px = [[(0, 0, 0, 0)] * N for _ in range(N)]

    def put(x, y, c):
        if 0 <= x < N and 0 <= y < N and not corner_cut(x, y):
            px[y][x] = blend(px[y][x], c) if c[3] < 255 else c

    # 1. badge + radial glow
    for y in range(N):
        for x in range(N):
            if corner_cut(x, y):
                continue
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 17) / 14
            t = max(0.0, 1 - d) ** 1.6
            c = tuple(round(BG[i] + (GLOW[i] - BG[i]) * t) for i in range(3))
            px[y][x] = c + (255,)

    # 2. signal arcs above the block (broadcast waves)
    # Hand-placed: computed circles look boxy at this size. (x0, x1, y) spans, mirrored on x=15.5.
    inner = [(14, 17, 6), (13, 13, 7), (12, 12, 8)]
    outer = [(13, 18, 3), (11, 12, 4), (10, 10, 5), (9, 9, 6)]
    for spans, a in ((inner, 255), (outer, 170)):
        for x0, x1, y in spans:
            for x in range(x0, x1 + 1):
                put(x, y, WAVE + (a,))
                if x1 == x0:
                    put(31 - x, y, WAVE + (a,))
            if x1 != x0 and x0 < 15:
                for x in range(31 - x1, 31 - x0 + 1):
                    if not (x0 <= x <= x1):
                        put(x, y, WAVE + (a,))

    # 4. isometric half-height block: top diamond centre (16,17), half-width 10, half-height 5
    xc, yc, W, H, F = 16, 16, 12, 6, 6
    for y in range(N):
        for x in range(N):
            fx, fy = x + 0.5, y + 0.5
            # top face
            if abs(fx - xc) / W + abs(fy - yc) / H <= 1.0:
                put(x, y, TOP)
                continue
            # side faces
            if xc - W <= fx < xc:
                top_edge = yc + (fx - (xc - W)) / 2
                if top_edge <= fy < top_edge + F:
                    put(x, y, LEFT)
            elif xc <= fx < xc + W:
                top_edge = yc + ((xc + W) - fx) / 2
                if top_edge <= fy < top_edge + F:
                    put(x, y, RIGHT)

    # rim highlight on the two front edges of the top face
    for x in range(xc - W, xc + W):
        fx = x + 0.5
        if fx < xc:
            y = math.floor(yc + (fx - (xc - W)) / 2 - 0.01)
        else:
            y = math.floor(yc + ((xc + W) - fx) / 2 - 0.01)
        put(x, y, RIM)

    # dark front vertical edge
    for y in range(yc + H, yc + H + F):
        put(16, y, EDGE)

    # top-face sculk spots (ring around the tendril base)
    for x, y in [(12, 16), (20, 16), (16, 19), (15, 19)]:
        put(x, y, TOP_SPOT)

    # sculk veins on the right face
    for x, y in [(20, 24), (20, 25), (20, 26), (24, 22), (24, 23), (24, 24), (26, 21), (26, 22)]:
        put(x, y, VEIN)
    for x, y in [(21, 26), (25, 24), (18, 27), (22, 23)]:
        put(x, y, VEIN_DIM)

    # 5. tendrils (back centre is tallest, drawn first)
    def tendril(x0, y_base, y_tip, lean):
        for y in range(y_base, y_tip - 1, -1):
            off = lean if y <= (y_base + y_tip) // 2 else 0
            put(x0 + off, y, TENDRIL)
            put(x0 + off + 1, y, TENDRIL_DARK)
        put(x0 + lean, y_tip, TIP)
        put(x0 + lean + 1, y_tip, TIP)

    tendril(15, 14, 9, 0)
    tendril(9, 17, 11, -1)
    tendril(21, 17, 11, 1)

    # 6. face: glowing eyes on the left face, following the iso slope
    for ex, ey in ((7, 20), (11, 22)):
        for dy in range(3):
            for dx in range(2):
                put(ex + dx, ey + dy, EYE)
        put(ex, ey, EYE_HI)

    # 7. cream sparkles (Ghost Mason brand detail)
    def sparkle(x, y, strong=True):
        put(x, y, SPARK + (255,))
        a = 200 if strong else 140
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(x + dx, y + dy, SPARK + (a,))

    sparkle(27, 5)
    sparkle(4, 27, strong=False)
    sparkle(28, 27, strong=False)

    img = Image.new("RGBA", (N, N))
    img.putdata([c for row in px for c in row])
    os.makedirs(out_dir, exist_ok=True)
    img.resize((512, 512), Image.NEAREST).save(os.path.join(out_dir, "icon.png"))
    img.resize((128, 128), Image.NEAREST).save(os.path.join(out_dir, "icon-128.png"))
    print("wrote", out_dir)


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "media"))
