# Draws Mila's player skin (64x64, slim arms): original pixel art for the companion plugin.
# Honey-blonde long hair with a red bow, green eyes, cream sweater with red trim, navy pleated skirt,
# white knee socks with a navy band and red sneakers.
# Usage: python3 draw_skin.py <output dir>   ->  mila.png and mila_preview.png
import os
import sys

from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else '.'

C = {
    'H': (214, 160, 92), 'h': (178, 124, 64), 'L': (238, 196, 128),        # hair, shade, highlight
    'S': (250, 220, 198), 's': (232, 196, 172), 'B': (244, 168, 168),       # skin, shade, blush
    'W': (255, 255, 255), 'I': (64, 150, 120), 'i': (40, 100, 80), 'K': (70, 45, 35),  # eye white, iris, lash
    'M': (214, 110, 110),                                                     # mouth
    'R': (210, 36, 44), 'r': (150, 20, 28),                                   # red (bow, trim)
    'C': (246, 241, 230), 'c': (226, 219, 203),                               # cream sweater
    'N': (44, 56, 104), 'n': (32, 40, 78), 'l': (62, 76, 130),                # navy skirt
    'O': (244, 244, 244), 'E': (196, 32, 40), 'e': (230, 230, 230),           # sock, shoe, sole
}

img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
px = img.load()


def put(x, y, key):
    if key in ('.', ' '):
        return
    px[x, y] = C[key] + (255,)


def paint(x0, y0, rows):
    for dy, row in enumerate(rows):
        for dx, k in enumerate(row):
            put(x0 + dx, y0 + dy, k)


def fill(x0, y0, w, h, fn):
    """fn(x, y) -> colour key for pixel x,y inside a w*h face."""
    for y in range(h):
        for x in range(w):
            put(x0 + x, y0 + y, fn(x, y))


def faces(u, v, w, h, d):
    """Standard skin UV layout of a box: name -> (x, y, width, height)."""
    return {
        'top': (u + d, v, w, d), 'bottom': (u + d + w, v, w, d),
        'right': (u, v + d, d, h), 'front': (u + d, v + d, w, h),
        'left': (u + d + w, v + d, d, h), 'back': (u + d + w + d, v + d, w, h),
    }


# ---------------------------------------------------------------- head (8x8x8 at 0,0)
head = faces(0, 0, 8, 8, 8)
paint(*head['front'][:2], [
    'HLLHHLHH',
    'HhHHHhHH',
    'HSSHSSSH',
    'HKKSSKKH',
    'HWISSIWH',
    'HSiSSiSH',
    'HBSSSSBH',
    'HSSMMSSH',
])
fill(*head['top'], lambda x, y: 'L' if x == 3 else ('h' if x in (0, 7) else 'H'))
fill(*head['bottom'], lambda x, y: 's' if y < 3 else 'h')
# right side: column 7 touches the face; ear/cheek skin near the front
fill(*head['right'], lambda x, y: 'S' if (y in (4, 5) and x in (5, 6)) else ('h' if x % 3 == 0 else 'H'))
# left side: column 0 touches the face
fill(*head['left'], lambda x, y: 'S' if (y in (4, 5) and x in (1, 2)) else ('h' if x % 3 == 2 else 'H'))
fill(*head['back'], lambda x, y: 'h' if x in (1, 4, 6) and y > 1 else 'H')

# hat layer (32,0): side locks, hair volume and the red bow on the back
hat = faces(32, 0, 8, 8, 8)
fill(*hat['front'], lambda x, y: 'h' if x in (0, 7) and y >= 1 else '.')
fill(*hat['right'], lambda x, y: ('H' if x % 2 else 'h') if (y <= 3 or x <= 4) else '.')
fill(*hat['left'], lambda x, y: ('H' if x % 2 else 'h') if (y <= 3 or x >= 3) else '.')
paint(*hat['back'][:2], [
    'hRRhhRRh',
    'HRrrrrRH',
    'hRRhhRRh',
    'HhHHhHhH',
    'HHhHHhHH',
    'hHHhHHhH',
    'HhHHhHHh',
    'HHhHHhHH',
])
fill(*hat['top'], lambda x, y: 'R' if (y <= 1 and 2 <= x <= 5) else '.')

# ---------------------------------------------------------------- body (8x12x4 at 16,16)
body = faces(16, 16, 8, 12, 4)


def sweater_row(y):
    if y == 9:
        return 'R'          # red rib at the hem
    if y >= 10:
        return 'N'          # skirt waist
    return None


paint(*body['front'][:2], [
    'CCcSScCC',
    'CCCccCCC',
    'CCCCCCCC',
    'CRRCRRCC',
    'CRRRRRCC',
    'CCRRRCCC',
    'CCCRCCCC',
    'cCCCCCCc',
    'cCCCCCCc',
    'RRRRRRRR',
    'NlNNlNNl',
    'nNnNnNnN',
])
for side in ('right', 'left'):
    fill(*body[side], lambda x, y: sweater_row(y) or ('c' if y in (7, 8) else 'C'))
fill(*body['back'], lambda x, y: 'R' if y == 0 and 2 <= x <= 5 else (sweater_row(y) or ('c' if y in (7, 8) else 'C')))
fill(*body['top'], lambda x, y: 'S' if (y == 3 and x in (3, 4)) else ('R' if y == 3 and x in (2, 5) else 'C'))
fill(*body['bottom'], lambda x, y: 'n')

# jacket layer (16,32): long hair down the back, flared skirt waist
jacket = faces(16, 32, 8, 12, 4)
fill(*jacket['back'], lambda x, y: (('h' if x in (1, 4, 6) else 'H') if y <= 6 else
                                    (('H' if x % 2 else 'h') if y == 7 else
                                     ('l' if x % 3 == 0 else 'N') if y >= 10 else '.')))
fill(*jacket['front'], lambda x, y: ('l' if x % 3 == 0 else 'N') if y >= 10 else '.')
for side in ('right', 'left'):
    fill(*jacket[side], lambda x, y: ('l' if x % 2 == 0 else 'N') if y >= 10 else '.')


# ---------------------------------------------------------------- arms (slim, 3x12x4)
def arm_pixel(x, y):
    if y == 9:
        return 'R'
    if y >= 10:
        return 'S'
    return 'c' if x == 0 and y > 0 else 'C'


for u, v in ((40, 16), (32, 48)):
    a = faces(u, v, 3, 12, 4)
    for f in ('front', 'right', 'left', 'back'):
        fill(*a[f], arm_pixel)
    fill(*a['top'], lambda x, y: 'C')
    fill(*a['bottom'], lambda x, y: 'S')
for u, v in ((40, 32), (48, 48)):          # sleeve layers: puffy red cuffs
    a = faces(u, v, 3, 12, 4)
    for f in ('front', 'right', 'left', 'back'):
        fill(*a[f], lambda x, y: 'r' if y == 9 else '.')


# ---------------------------------------------------------------- legs (4x12x4)
def leg_pixel(x, y):
    if y <= 3:
        return 'l' if x % 2 == 0 else 'N'   # pleats
    if y == 4:
        return 'n'
    if y in (5, 6):
        return 'S'
    if y == 7:
        return 'n'          # navy band at the top of the sock
    if y in (8, 9):
        return 'O'
    if y == 10:
        return 'E'
    return 'e'


for u, v in ((0, 16), (16, 48)):
    g = faces(u, v, 4, 12, 4)
    for f in ('front', 'right', 'left', 'back'):
        fill(*g[f], leg_pixel)
    fill(*g['top'], lambda x, y: 'N')
    fill(*g['bottom'], lambda x, y: 'e')
for u, v in ((0, 32), (0, 48)):           # trouser layers: the skirt flares out over the thighs
    g = faces(u, v, 4, 12, 4)
    for f in ('front', 'right', 'left', 'back'):
        fill(*g[f], lambda x, y: ('l' if x % 2 == 0 else 'N') if y <= 2 else ('n' if y == 3 else '.'))

os.makedirs(OUT, exist_ok=True)
img.save(os.path.join(OUT, 'mila.png'))


# ---------------------------------------------------------------- flat preview: front and back views, x10
def view(side):
    """Paper-doll view: head, body, arms and legs of one side with their overlay layers."""
    v = Image.new('RGBA', (16, 32), (0, 0, 0, 0))

    def blit(region, dx, dy):
        x, y, w, h = region
        v.alpha_composite(img.crop((x, y, x + w, y + h)), (dx, dy))

    h, hh = faces(0, 0, 8, 8, 8), faces(32, 0, 8, 8, 8)
    b, bj = faces(16, 16, 8, 12, 4), faces(16, 32, 8, 12, 4)
    ra, rs = faces(40, 16, 3, 12, 4), faces(40, 32, 3, 12, 4)
    la, ls = faces(32, 48, 3, 12, 4), faces(48, 48, 3, 12, 4)
    rl, rp = faces(0, 16, 4, 12, 4), faces(0, 32, 4, 12, 4)
    ll, lp = faces(16, 48, 4, 12, 4), faces(0, 48, 4, 12, 4)
    blit(h[side], 4, 0)
    blit(hh[side], 4, 0)
    blit(b[side], 4, 8)
    blit(bj[side], 4, 8)
    left_x, right_x = (1, 12) if side == 'front' else (12, 1)
    blit(ra[side], left_x, 8)
    blit(rs[side], left_x, 8)
    blit(la[side], right_x, 8)
    blit(ls[side], right_x, 8)
    blit(rl[side], 4 if side == 'front' else 8, 20)
    blit(rp[side], 4 if side == 'front' else 8, 20)
    blit(ll[side], 8 if side == 'front' else 4, 20)
    blit(lp[side], 8 if side == 'front' else 4, 20)
    return v


prev = Image.new('RGBA', (16 * 10 * 2 + 60, 32 * 10 + 40), (139, 139, 139, 255))
prev.alpha_composite(view('front').resize((160, 320), Image.NEAREST), (20, 20))
prev.alpha_composite(view('back').resize((160, 320), Image.NEAREST), (200, 20))
prev.save(os.path.join(OUT, 'mila_preview.png'))
print('drew mila.png and mila_preview.png in', OUT)
