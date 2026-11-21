# Draws the 32x32 item textures of the NMK milk-cocktail bottles (original pixel art, no copied artwork).
# Usage: python3 draw_textures.py <output dir>
import os
import sys

from PIL import Image

W = H = 32
OUT = sys.argv[1] if len(sys.argv) > 1 else '.'

OUTLINE = (40, 30, 30, 255)
CAP = (205, 35, 40, 255)
CAP_DARK = (150, 20, 28, 255)
CAP_LIGHT = (235, 90, 90, 255)
BAND = (240, 240, 235, 255)
NECK = (250, 250, 248, 255)
NECK_SHADE = (215, 215, 210, 255)


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c[:3]) + (255,)


def bottle_mask():
    """Pixels inside the bottle, by row: (left, right) inclusive."""
    rows = {}
    for y in range(1, 6):        # cap
        rows[y] = (11, 20)
    rows[6] = (11, 20)           # white ring under the cap
    for y in (7, 8):             # neck
        rows[y] = (12, 19)
    rows[9] = (11, 20)           # shoulder
    rows[10] = (10, 21)
    rows[11] = (9, 22)
    for y in range(12, 29):      # body
        rows[y] = (8, 23)
    rows[29] = (9, 22)           # rounded bottom
    rows[30] = (10, 21)
    return rows


def draw(name, liquid, label, label_dark, icon):
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    px = img.load()
    rows = bottle_mask()
    for y, (l, r) in rows.items():
        for x in range(l, r + 1):
            if y <= 5:
                c = CAP if y > 1 else CAP_LIGHT
                if x in (l, r):
                    c = CAP_DARK
                elif (x - l) % 3 == 0 and y >= 2:
                    c = CAP_DARK  # ridges of the screw cap
            elif y == 6:
                c = BAND
            elif y <= 9:
                c = NECK if x < r - 1 else NECK_SHADE
            else:
                c = liquid
                if x <= l + 1:
                    c = shade(liquid, 1.12)   # highlight
                elif x >= r - 1:
                    c = shade(liquid, 0.82)   # shadow
            px[x, y] = c
    # label: rows 14..24 across the body
    for y in range(14, 25):
        l, r = rows[y]
        for x in range(l, r + 1):
            c = label
            if y in (14, 24):
                c = label_dark
            elif x >= r - 1:
                c = shade(label, 0.85)
            px[x, y] = c
    icon(px)
    # a glint on the bottle
    for y in range(12, 14):
        px[10, y] = (255, 255, 255, 200)
    for y in range(26, 28):
        px[10, y] = (255, 255, 255, 160)
    # outline: transparent pixels that touch the bottle become dark
    out = img.copy()
    opx = out.load()
    for y in range(H):
        for x in range(W):
            if px[x, y][3] == 0:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < W and 0 <= ny < H and px[nx, ny][3] > 0:
                        opx[x, y] = OUTLINE
                        break
    out.save(os.path.join(OUT, name + '.png'))
    return out


def strawberry_icon(px):
    red, red_dark, seed, leaf = (225, 30, 50, 255), (170, 15, 35, 255), (255, 230, 120, 255), (60, 160, 60, 255)
    berry = {16: (13, 18), 17: (12, 19), 18: (12, 19), 19: (12, 19), 20: (13, 18), 21: (14, 17), 22: (15, 16)}
    for y, (l, r) in berry.items():
        for x in range(l, r + 1):
            px[x, y] = red_dark if x == r else red
    for x, y in ((13, 17), (16, 18), (14, 20), (17, 20), (18, 17)):
        px[x, y] = seed
    for x, y in ((14, 15), (15, 15), (16, 15), (17, 15), (15, 14), (16, 14)):
        px[x, y] = leaf


def chocolate_icon(px):
    bar, bar_dark, foil = (95, 55, 35, 255), (60, 32, 20, 255), (230, 215, 190, 255)
    for y in range(16, 23):
        for x in range(12, 20):
            px[x, y] = bar_dark if (x in (15, 19) or y in (19, 22)) else bar
    for x in range(12, 20):
        px[x, 16] = foil if x < 15 else bar


def coffee_icon(px):
    cup, cup_shade, coffee, steam = (250, 248, 240, 255), (205, 200, 190, 255), (110, 65, 40, 255), (235, 235, 235, 200)
    for y in range(18, 23):
        for x in range(12, 18):
            px[x, y] = cup_shade if x == 17 else cup
    for x in range(12, 18):
        px[x, 18] = coffee
    for y in (19, 20, 21):
        px[18, y] = cup_shade if y != 20 else (0, 0, 0, 0) if False else cup_shade
    px[19, 19] = cup_shade
    px[19, 20] = cup_shade
    px[19, 21] = cup_shade
    for x, y in ((13, 16), (14, 15), (15, 16), (16, 15)):
        px[x, y] = steam


def caramel_icon(px):
    gold, gold_dark, gold_light = (225, 160, 60, 255), (170, 105, 30, 255), (250, 205, 120, 255)
    for (ox, oy) in ((12, 18), (16, 16)):
        for y in range(oy, oy + 4):
            for x in range(ox, ox + 4):
                c = gold
                if x == ox + 3 or y == oy + 3:
                    c = gold_dark
                if x == ox or y == oy:
                    c = gold_light
                px[x, y] = c


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    made = [
        draw('strawberry', (243, 196, 206, 255), (248, 244, 238, 255), (215, 35, 40, 255), strawberry_icon),
        draw('chocolate', (110, 66, 49, 255), (232, 214, 184, 255), (150, 110, 80, 255), chocolate_icon),
        draw('coffee', (122, 78, 54, 255), (32, 72, 48, 255), (18, 45, 28, 255), coffee_icon),
        draw('caramel', (236, 222, 194, 255), (120, 66, 28, 255), (205, 120, 40, 255), caramel_icon),
    ]
    # preview: all four, 10x bigger, on a Minecraft-like slot background
    scale = 10
    prev = Image.new('RGBA', (len(made) * (W * scale + 20) + 20, H * scale + 40), (139, 139, 139, 255))
    for i, im in enumerate(made):
        big = im.resize((W * scale, H * scale), Image.NEAREST)
        prev.alpha_composite(big, (20 + i * (W * scale + 20), 20))
    prev.save(os.path.join(OUT, 'preview.png'))
    print('drew', len(made), 'textures and preview.png in', OUT)
