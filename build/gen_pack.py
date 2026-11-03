"""Generates the Kitchen Rush resource pack (original pixel art, HUD fonts) and glyph widths."""
import io
import json
import os
import struct
import sys
import zipfile
import zlib

OUT_ZIP = sys.argv[1]
OUT_GLYPHS = sys.argv[2]
PACK_FORMAT = 97
OUTLINE = (43, 29, 22, 255)


def hexc(s, a=255):
    s = s.lstrip('#')
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), a)


def png(c):
    raw = b''.join(
        b'\x00' + bytes(v for p in row for v in (p if p else (0, 0, 0, 0)))
        for row in c.p)

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', c.w, c.h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


class Canvas:
    def __init__(self, w=16, h=16):
        self.w, self.h = w, h
        self.p = [[None] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.p[y][x] = c

    def get(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.p[y][x]
        return None

    def ellipse(self, cx, cy, rx, ry, c, cond=None):
        for y in range(self.h):
            for x in range(self.w):
                if ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2 <= 1.0:
                    if cond is None or cond(x, y):
                        self.set(x, y, c)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, c)

    def dots(self, pts, c):
        for x, y in pts:
            self.set(x, y, c)

    def outline(self, c=OUTLINE):
        add = []
        for y in range(self.h):
            for x in range(self.w):
                if self.p[y][x] is None and any(
                        self.get(x + dx, y + dy) is not None
                        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    add.append((x, y))
        for x, y in add:
            self.p[y][x] = c
        return self

    def width(self):
        for x in range(self.w - 1, -1, -1):
            if any(self.p[y][x] is not None and self.p[y][x][3] != 0 for y in range(self.h)):
                return x + 1
        return 0


# ---------- palette ----------
RED, RED_D, RED_L = hexc('e8402a'), hexc('c22d1d'), hexc('ff7a5c')
GREEN, GREEN_D, GREEN_L = hexc('7ccf3a'), hexc('4f9a24'), hexc('b4ee6a')
LEAF = hexc('3fa535')
MEAT, MEAT_FAT = hexc('d9475a'), hexc('ffd6dc')
PATTY_RAW, PATTY_RAW_D = hexc('e57a86'), hexc('c95564')
PATTY, PATTY_L, PATTY_D = hexc('8a4b2a'), hexc('a8643c'), hexc('5c2f18')
BUN, BUN_D, SESAME = hexc('e3a14a'), hexc('c97f2e'), hexc('fff3d0')
PLATE, PLATE_IN = hexc('f7f7f7'), hexc('dce6ec')
SOUP, SOUP_L = hexc('e2572f'), hexc('ff9466')
CHAR, CHAR_L, SMOKE = hexc('262626'), hexc('474747'), hexc('a0a0a0', 210)
WHITE = hexc('ffffff')


def tomato(c, cx=8, cy=9, rx=6, ry=5.5):
    c.ellipse(cx, cy, rx, ry, RED)
    c.ellipse(cx, cy, rx, ry, RED_D, cond=lambda x, y: (x - cx) + (y - cy) > 3)
    c.dots([(cx - 3, cy - 3), (cx - 2, cy - 3), (cx - 3, cy - 2)], RED_L)
    c.dots([(cx - 1, cy - 6), (cx, cy - 6), (cx, cy - 7), (cx + 1, cy - 6), (cx - 2, cy - 5), (cx + 2, cy - 5)], LEAF)


def slice_(c, cx, cy):
    c.ellipse(cx, cy, 3, 3, RED)
    c.ellipse(cx, cy, 2, 2, RED_L)
    c.set(int(cx), int(cy), hexc('ffe08a'))


def bun_top(c, cx, cy, rx, ry):
    c.ellipse(cx, cy, rx, ry, BUN, cond=lambda x, y: y + 0.5 <= cy)
    c.ellipse(cx, cy, rx, ry, BUN_D, cond=lambda x, y: y + 0.5 <= cy and y + 0.5 > cy - 1.2)


FISH, FISH_D = hexc('5aa0d8'), hexc('3a74a8')
FISH_MEAT, FISH_MEAT_D = hexc('ffb7a1'), hexc('e88d74')
FRY, FRY_L, FRY_D = hexc('e0a23c'), hexc('f6c75e'), hexc('b0741f')
POTATO, POTATO_D, POTATO_IN = hexc('c99a5a'), hexc('a3763c'), hexc('f3d98a')
FRIES_BOX = hexc('d8342c')
DIRT, DIRT_L = hexc('8a6a3a'), hexc('b09060')


def fish_body(c, cx, cy, rx, ry, col, dark):
    c.ellipse(cx, cy, rx, ry, col)
    c.ellipse(cx, cy, rx, ry, dark, cond=lambda x, y: y > cy + 1)
    for i in range(4):
        c.rect(int(cx + rx) + 1 + i // 2, int(cy) - 1 - i, int(cx + rx) + 1 + i // 2, int(cy) + i, col)
    c.set(int(cx - rx) + 2, int(cy) - 1, OUTLINE)


def fries(c, cx, bottom, small=False):
    bottom = int(bottom)
    w = 3 if small else 4
    for i, x in enumerate(range(int(cx) - w + 1, int(cx) + w, 2)):
        c.rect(x, bottom - (6 if small else 9) - (i % 2), x, bottom - 2, POTATO_IN)
    c.rect(int(cx) - w, bottom - (2 if small else 4), int(cx) + w, bottom, FRIES_BOX)


def plate_base(c):
    c.ellipse(8, 9.5, 7, 5, PLATE)
    c.ellipse(8, 9.5, 5, 3.3, PLATE_IN)


def draw(name):
    c = Canvas()
    if name == 'tomato':
        tomato(c)
    elif name == 'tomato_chopped':
        slice_(c, 5, 6); slice_(c, 11, 7); slice_(c, 7.5, 11.5)
    elif name == 'lettuce':
        c.ellipse(8, 8.5, 6.5, 6, GREEN)
        c.ellipse(6.5, 7, 3.5, 3, GREEN_L)
        c.rect(8, 4, 8, 13, GREEN_D)
        c.dots([(6, 9), (5, 10), (10, 9), (11, 10)], GREEN_D)
    elif name == 'lettuce_chopped':
        for (x, y, col) in ((4.5, 5, GREEN), (10.5, 4.5, GREEN_L), (6, 10.5, GREEN_L), (11.5, 10.5, GREEN), (8.5, 7.5, GREEN)):
            c.ellipse(x, y, 2.2, 2, col)
        c.dots([(4, 5), (10, 4), (6, 10), (11, 10), (8, 7)], GREEN_D)
    elif name == 'meat':
        c.ellipse(8, 8.5, 6.5, 5, MEAT)
        c.ellipse(8, 8.5, 6.5, 5, MEAT_FAT, cond=lambda x, y: abs((x - y) + 1) < 1)
        c.ellipse(12, 5, 1.6, 1.6, WHITE)
    elif name == 'patty_raw':
        c.ellipse(8, 9, 6, 4.5, PATTY_RAW)
        c.dots([(5, 8), (8, 7), (10, 10), (6, 11), (11, 8)], PATTY_RAW_D)
    elif name == 'patty_cooked':
        c.ellipse(8, 9, 6, 4.5, PATTY)
        c.ellipse(8, 9, 6, 4.5, PATTY_L, cond=lambda x, y: y < 7)
        for gx in (5, 8, 11):
            c.ellipse(8, 9, 6, 4.5, PATTY_D, cond=lambda x, y, gx=gx: x == gx and 6 < y < 12)
    elif name == 'burnt':
        c.ellipse(8, 10.5, 6, 4, CHAR)
        c.ellipse(7, 9.5, 3, 1.5, CHAR_L)
        c.ellipse(6, 4, 2, 2, SMOKE)
        c.ellipse(9.5, 2.8, 1.6, 1.6, SMOKE)
    elif name == 'bread':
        bun_top(c, 8, 11, 6.5, 6.5)
        c.rect(2, 11, 13, 12, BUN_D)
        c.dots([(6, 7), (9, 6), (11, 8), (5, 9), (8, 9)], SESAME)
    elif name == 'plate':
        plate_base(c)
    elif name == 'plate_salad':
        plate_base(c)
        for (x, y, col) in ((6, 8.5, GREEN), (9.5, 8, GREEN_L), (10.5, 10.5, GREEN), (6.5, 11, GREEN_L)):
            c.ellipse(x, y, 1.9, 1.6, col)
    elif name == 'plate_mixsalad':
        plate_base(c)
        for (x, y, col) in ((6, 8.5, GREEN), (10.5, 10.5, GREEN), (6.5, 11, GREEN_L)):
            c.ellipse(x, y, 1.9, 1.6, col)
        c.ellipse(9.5, 8.5, 1.8, 1.6, RED)
        c.set(9, 8, RED_L)
    elif name == 'plate_bread':
        plate_base(c)
        bun_top(c, 8, 10, 4.2, 4)
        c.rect(4, 10, 11, 10, BUN_D)
        c.dots([(7, 7), (9, 8)], SESAME)
    elif name == 'plate_patty':
        plate_base(c)
        c.ellipse(8, 9.5, 4.2, 2.6, PATTY)
        c.dots([(6, 9), (8, 9), (10, 9)], PATTY_D)
    elif name == 'plate_burger':
        plate_base(c)
        c.rect(4, 10, 11, 11, BUN_D)
        c.rect(3, 9, 12, 9, PATTY)
        c.dots([(5, 9), (8, 9), (11, 9)], PATTY_D)
        bun_top(c, 7.5, 9, 4.8, 4.5)
        c.dots([(6, 6), (9, 6), (7, 7)], SESAME)
    elif name == 'plate_soup':
        plate_base(c)
        c.ellipse(8, 9.5, 4.6, 2.9, SOUP)
        c.dots([(6, 9), (7, 8), (9, 10), (10, 9)], SOUP_L)
    elif name == 'fish':
        fish_body(c, 7.5, 8.5, 5.5, 3.5, FISH, FISH_D)
    elif name == 'fish_chopped':
        c.ellipse(6, 6, 3.6, 2.4, FISH_MEAT)
        c.ellipse(10.5, 10.5, 3.6, 2.4, FISH_MEAT)
        c.dots([(4, 6), (6, 5), (9, 10), (11, 11)], FISH_MEAT_D)
        c.rect(13, 3, 14, 4, FISH)
    elif name == 'fish_fried':
        fish_body(c, 7, 8.5, 5.5, 3.5, FRY, FRY_D)
        c.ellipse(7, 8.5, 5.5, 3.5, FRY_L, cond=lambda x, y: y < 7)
        c.dots([(5, 9), (8, 10), (10, 8)], FRY_D)
        c.set(3, 7, OUTLINE)
    elif name == 'potato':
        c.ellipse(8, 8.5, 6, 5, POTATO)
        c.ellipse(8, 8.5, 6, 5, POTATO_D, cond=lambda x, y: (x - 8) + (y - 8.5) > 3)
        c.dots([(6, 7), (10, 9), (8, 11)], POTATO_D)
    elif name == 'potato_chopped':
        for x0 in (3, 6, 9, 12):
            c.rect(x0, 4 + (x0 % 3), x0 + 1, 12 - (x0 % 2), POTATO_IN)
    elif name == 'fries':
        fries(c, 8, 12)
    elif name == 'dirty_plate':
        plate_base(c)
        c.dots([(5, 8), (6, 8), (9, 10), (10, 10), (10, 9), (7, 11), (11, 8)], DIRT)
        c.dots([(6, 10), (8, 8)], DIRT_L)
    elif name == 'plate_tomato':
        plate_base(c)
        c.ellipse(6.5, 9.5, 2, 2, RED)
        c.ellipse(9.5, 9.5, 2, 2, RED_L)
    elif name == 'plate_fish':
        plate_base(c)
        c.ellipse(8, 9.5, 4.2, 2.4, FRY)
        c.dots([(6, 9), (9, 10)], FRY_D)
    elif name == 'plate_fries':
        plate_base(c)
        fries(c, 8, 11, small=True)
    elif name == 'plate_fishfries':
        plate_base(c)
        c.ellipse(6, 9.5, 3, 2, FRY)
        fries(c, 10.5, 11, small=True)
    elif name == 'plate_special':
        plate_base(c)
        c.rect(4, 11, 11, 11, BUN_D)
        c.rect(3, 10, 12, 10, PATTY)
        c.rect(3, 9, 12, 9, RED)
        c.rect(3, 8, 12, 8, GREEN)
        c.dots([(4, 8), (7, 8), (10, 8)], GREEN_L)
        bun_top(c, 7.5, 8, 4.8, 4.2)
        c.dots([(6, 5), (9, 5), (7, 6)], SESAME)
    elif name == 'plate_fishset':
        plate_base(c)
        c.ellipse(5.5, 9.5, 2.8, 1.8, FRY)
        fries(c, 10, 10.5, small=True)
        c.dots([(7, 11), (8, 11)], GREEN)
        c.dots([(8, 8)], RED)
    elif name == 'knife':
        for i in range(8):
            c.set(3 + i, 12 - i, hexc('e6ebef'))
            c.set(4 + i, 12 - i, hexc('c9d1d8'))
        c.rect(10, 3, 12, 5, hexc('7a5230'))
        c.set(13, 2, hexc('7a5230'))
    elif name == 'plate_cover':
        for x in (2, 13):
            c.rect(x, 4, x, 13, hexc('8f9aa3'))
        for y in (4, 9, 13):
            c.rect(2, y, 13, y, hexc('c9d1d8'))
        c.rect(7, 2, 8, 3, hexc('eef2f5'))
    elif name == 'soup':
        c.ellipse(8, 8, 7, 7, SOUP)
        c.dots([(5, 6), (6, 5), (7, 5), (10, 9), (11, 10), (9, 11), (6, 10)], SOUP_L)
        c.outline()
        return c
    elif name == 'pan':
        c.ellipse(7, 8, 5.5, 5.5, hexc('3b3f45'))
        c.ellipse(7, 8, 4.2, 4.2, hexc('5a6068'))
        c.rect(12, 7, 14, 8, hexc('7a5230'))
    elif name == 'clock':
        c.ellipse(8, 8, 6.5, 6.5, hexc('ffb347'))
        c.ellipse(8, 8, 5, 5, WHITE)
        c.rect(7, 4, 7, 8, OUTLINE)
        c.rect(7, 8, 10, 8, OUTLINE)
    elif name == 'coin':
        c.ellipse(8, 8, 6.5, 6.5, hexc('f2b705'))
        c.ellipse(8, 8, 4.5, 4.5, hexc('ffd84a'))
        c.dots([(6, 5), (5, 6)], WHITE)
    else:
        raise ValueError(name)
    return c.outline()


ITEMS = ['tomato', 'tomato_chopped', 'lettuce', 'lettuce_chopped', 'meat', 'patty_raw', 'patty_cooked',
         'burnt', 'bread', 'plate', 'plate_salad', 'plate_mixsalad', 'plate_bread', 'plate_patty', 'plate_burger',
         'pan', 'fish', 'fish_chopped', 'fish_fried', 'potato', 'potato_chopped', 'fries', 'dirty_plate',
         'plate_tomato', 'plate_fish', 'plate_fries', 'plate_fishfries', 'plate_special', 'plate_fishset',
         'plate_cover', 'knife']
ICONS_ONLY = ['clock', 'coin']

# ---------------------------------------------------------------- 3D models
# Every food is built from cuboids that sample one pixel of a shared palette texture,
# so Minecraft's face shading gives them depth. Local coordinates: x/z centred on 0, y from 0 up.
PAL = {}
PAL_ORDER = []


def pal(name, h):
    PAL[name] = hexc(h)
    PAL_ORDER.append(name)


for _n, _h in [('red', 'e8402a'), ('red_d', 'b52a1b'), ('red_l', 'ff8a6a'), ('pink_in', 'ffb09a'),
               ('green', '6cc23a'), ('green_d', '3f8f22'), ('green_l', 'b4ee6a'), ('leaf', '2f8a2a'),
               ('meat', 'd9475a'), ('meat_d', 'a8303f'), ('fat', 'ffe0e4'), ('bone', 'f4efe2'),
               ('patty_raw', 'e8808c'), ('patty_raw_d', 'c95564'), ('patty', '8a4b2a'), ('patty_d', '5c2f18'),
               ('bun', 'e3a14a'), ('bun_d', 'c07a2a'), ('bun_in', 'f6dca8'), ('sesame', 'fff6dc'),
               ('fish', '5aa0d8'), ('fish_d', '3a74a8'), ('fish_belly', 'cfe6f6'), ('fish_meat', 'ffb7a1'),
               ('fry', 'e0a23c'), ('fry_l', 'f6c75e'), ('fry_d', 'b0741f'),
               ('potato', 'c99a5a'), ('potato_d', '9a7038'), ('potato_in', 'f3d98a'), ('carton', 'd8342c'),
               ('plate', 'f7f7f4'), ('plate_in', 'e2e8ec'), ('plate_rim', 'd2d8dc'),
               ('soup', 'e2572f'), ('soup_l', 'ff9466'), ('dirt', '8a6a3a'), ('dirt_l', 'b09060'),
               ('char', '2a2a2a'), ('char_l', '4a4a4a'), ('smoke', 'a8a8a8'), ('pan', '3b3f45'), ('pan_in', '575d66'),
               ('handle', '7a5230'), ('black', '1a1a1a'), ('white', 'ffffff'),
               ('steel', 'c9d1d8'), ('steel_d', '8f9aa3'), ('steel_l', 'eef2f5')]:
    pal(_n, _h)


def uv(col):
    i = PAL_ORDER.index(col)
    x, y = i % 16, i // 16
    return [x + 0.25, y + 0.25, x + 0.75, y + 0.75]


class Model:
    def __init__(self):
        self.elements = []

    def box(self, t, x0, y0, z0, x1, y1, z1, col, top=None):
        dx, dy, dz, s = t
        f = [8 + dx + x0 * s, dy + y0 * s, 8 + dz + z0 * s]
        to = [8 + dx + x1 * s, dy + y1 * s, 8 + dz + z1 * s]
        f, to = [round(min(a, b), 3) for a, b in zip(f, to)], [round(max(a, b), 3) for a, b in zip(f, to)]
        for i in range(3):
            if to[i] - f[i] < 0.01:
                to[i] = f[i] + 0.01
        faces = {d: {'uv': uv(top if (top and d == 'up') else col), 'texture': '#p'}
                 for d in ('north', 'south', 'east', 'west', 'up', 'down')}
        self.elements.append({'from': f, 'to': to, 'faces': faces})

    def disc(self, t, r, y0, y1, col, top=None):
        self.box(t, -r, y0, -r * 0.55, r, y1, r * 0.55, col, top)
        self.box(t, -r * 0.55, y0, -r, r * 0.55, y1, r, col, top)
        self.box(t, -r * 0.82, y0, -r * 0.82, r * 0.82, y1, r * 0.82, col, top)

    def ball(self, t, r, col, top=None, squash=1.0):
        steps = 5
        for k in range(steps):
            ya, yb = -r + 2 * r * k / steps, -r + 2 * r * (k + 1) / steps
            ym = (ya + yb) / 2
            rr = max(0.4, (r * r - ym * ym) ** 0.5)
            self.disc(t, rr, (ya + r) * squash, (yb + r) * squash, col, top if k == steps - 1 else None)

    def json(self):
        return {
            'textures': {'p': 'kitchen:item/palette', 'particle': 'kitchen:item/palette'},
            'elements': self.elements,
            'display': {
                'fixed': {'rotation': [0, 0, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
                'gui': {'rotation': [30, 225, 0], 'translation': [0, 3, 0], 'scale': [0.85, 0.85, 0.85]},
                'ground': {'translation': [0, 3, 0], 'scale': [0.5, 0.5, 0.5]},
                'firstperson_righthand': {'rotation': [0, 45, 0], 'translation': [0, 4, 0], 'scale': [0.78, 0.78, 0.78]},
                'firstperson_lefthand': {'rotation': [0, 45, 0], 'translation': [0, 4, 0], 'scale': [0.78, 0.78, 0.78]},
                'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.65, 0.65, 0.65]},
                'thirdperson_lefthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.65, 0.65, 0.65]},
                'head': {'translation': [0, 14, 0]},
            }}


def T(dx=0.0, dy=0.0, dz=0.0, s=1.0):
    return (dx, dy, dz, s)


def m_tomato(m, t):
    m.ball(t, 3.6, 'red', squash=0.9)
    dx, dy, dz, s = t
    m.box(T(dx, dy, dz, s), -0.4, 6.3, -0.4, 0.4, 7.6, 0.4, 'leaf')
    m.box(T(dx, dy, dz, s), -1.6, 6.3, -0.4, 1.6, 6.8, 0.4, 'leaf')
    m.box(T(dx, dy, dz, s), -0.4, 6.3, -1.6, 0.4, 6.8, 1.6, 'leaf')


def m_tomato_slices(m, t):
    dx, dy, dz, s = t
    for i, (x, z) in enumerate(((-2.4, -1.2), (2.2, -1.6), (0, 2.2))):
        m.disc(T(dx + x * s, dy + i * 0.15 * s, dz + z * s, s), 2.2, 0, 0.9, 'red', top='pink_in')
        m.box(T(dx + x * s, dy + i * 0.15 * s, dz + z * s, s), -0.35, 0.9, -0.35, 0.35, 0.95, 0.35, 'sesame')


def m_lettuce(m, t):
    m.ball(t, 4.2, 'green', top='green_l', squash=0.85)
    dx, dy, dz, s = t
    m.box(T(dx, dy, dz, s), -0.3, 0.5, -4.1, 0.3, 6.6, 4.1, 'green_d')


def m_lettuce_flakes(m, t):
    dx, dy, dz, s = t
    for i, (x, z, c) in enumerate(((-3, -2, 'green'), (2.5, -2.5, 'green_l'), (-1.5, 2.5, 'green_l'),
                                   (3, 2, 'green'), (0, 0, 'green_d'))):
        m.box(T(dx, dy, dz, s), x - 1.5, i * 0.3, z - 1.2, x + 1.5, i * 0.3 + 0.6, z + 1.2, c)


def m_patty(m, t, raw=False):
    if raw:
        m.disc(t, 3.6, 0, 1.6, 'patty_raw', top='patty_raw')
        dx, dy, dz, s = t
        for x, z in ((-1.5, -1), (1, 1.2), (1.8, -1.5)):
            m.box(T(dx, dy, dz, s), x - 0.4, 1.6, z - 0.4, x + 0.4, 1.65, z + 0.4, 'patty_raw_d')
    else:
        m.disc(t, 3.6, 0, 1.6, 'patty', top='patty')
        dx, dy, dz, s = t
        for x in (-1.8, 0, 1.8):
            m.box(T(dx, dy, dz, s), x - 0.3, 1.6, -2.6, x + 0.3, 1.65, 2.6, 'patty_d')


def m_bun_top(m, t):
    dx, dy, dz, s = t
    m.disc(T(dx, dy, dz, s), 4, 0, 1.2, 'bun_in', top='bun')
    m.disc(T(dx, dy, dz, s), 3.6, 1.2, 2.4, 'bun')
    m.disc(T(dx, dy, dz, s), 2.6, 2.4, 3.1, 'bun')
    for x, z in ((-1.5, -1), (1.2, -1.6), (0.3, 1.4), (-2, 1.2), (2.2, 0.8)):
        y = 3.1 if abs(x) < 2.6 and abs(z) < 2.6 and (x * x + z * z) < 4 else 2.4
        m.box(T(dx, dy, dz, s), x - 0.3, y, z - 0.2, x + 0.3, y + 0.15, z + 0.2, 'sesame')


def m_bun_bottom(m, t):
    m.disc(t, 4, 0, 1.2, 'bun_d', top='bun_in')


def m_fish(m, t):
    dx, dy, dz, s = t
    m.box(t, -4, 0, -1.6, 3, 3, 1.6, 'fish', top='fish')
    m.box(t, -4, 0, -1.4, 3, 1, 1.4, 'fish_belly')
    m.box(t, 3, 0.8, -0.8, 4, 2.2, 0.8, 'fish')
    m.box(t, 4, 0.2, -0.3, 5.6, 2.8, 0.3, 'fish_d')
    m.box(t, -3.2, 1.8, -1.65, -2.6, 2.4, 1.65, 'black')
    m.box(t, -1, 3, -0.3, 1.5, 3.8, 0.3, 'fish_d')


def m_fish_pieces(m, t):
    dx, dy, dz, s = t
    m.box(t, -3.6, 0, -2.4, -0.4, 1.6, -0.2, 'fish_meat')
    m.box(t, 0.4, 0, 0.2, 3.6, 1.6, 2.4, 'fish_meat')
    m.box(t, 3.6, 0, 0.2, 3.9, 1.6, 2.4, 'fish')


def m_fish_fried(m, t):
    """A whole battered fish lying on its side: tapered body, tail fin, fin on top, eye and crumbs."""
    # body tapers from head (-x) to tail (+x)
    m.box(t, -4.2, 0, -1.0, -3.2, 2.4, 1.0, 'fry', top='fry_l')
    m.box(t, -3.2, 0, -1.4, 1.2, 3.2, 1.4, 'fry', top='fry_l')
    m.box(t, 1.2, 0.3, -1.1, 2.8, 2.8, 1.1, 'fry', top='fry_l')
    m.box(t, 2.8, 0.7, -0.7, 3.8, 2.3, 0.7, 'fry')
    # forked tail fin
    m.box(t, 3.8, 1.2, -0.4, 4.6, 1.8, 0.4, 'fry_d')
    m.box(t, 4.6, 1.8, -0.35, 5.8, 3.4, 0.35, 'fry_d', top='fry')
    m.box(t, 4.6, -0.0, -0.35, 5.8, 1.2, 0.35, 'fry_d')
    # dorsal fin and eye
    m.box(t, -1.6, 3.2, -0.3, 1.0, 3.9, 0.3, 'fry_d')
    m.box(t, -3.6, 1.6, -1.45, -3.0, 2.2, 1.45, 'black')
    # crispy crumbs
    for x, y in ((-2, 0.8), (0, 1.8), (1.8, 1.1), (-0.8, 2.7)):
        m.box(t, x - 0.3, y, -1.45, x + 0.3, y + 0.4, 1.45, 'fry_l')


def m_potato(m, t):
    m.ball(t, 3.4, 'potato', squash=0.75)
    dx, dy, dz, s = t
    for x, y, z in ((-1.5, 3.5, -1), (1.4, 2.2, 3.2), (2.3, 4, 0.5)):
        m.box(T(dx, dy, dz, s), x - 0.35, y, z - 0.35, x + 0.35, y + 0.4, z + 0.35, 'potato_d')


def m_potato_sticks(m, t):
    for i, x in enumerate((-3, -1.5, 0, 1.5, 3)):
        m.box(t, x - 0.6, i % 2 * 0.2, -3, x + 0.6, 1.2 + i % 2 * 0.2, 3, 'potato_in')


def m_fries(m, t):
    dx, dy, dz, s = t
    for i, (x, z) in enumerate(((-1.4, -0.6), (-0.4, 0.5), (0.6, -0.5), (1.5, 0.4), (0, -0.1), (-1, 0.7))):
        h = 6.5 + (i % 3) * 0.8
        m.box(t, x - 0.4, 2, z - 0.4, x + 0.4, h, z + 0.4, 'fry_l')
    m.box(t, -2.4, 0, -1.6, 2.4, 4, 1.6, 'carton')
    m.box(t, -2.5, 3.2, -1.65, 2.5, 3.5, 1.65, 'white')


def m_plate(m, t, dirty=False):
    m.disc(t, 6.4, 0, 0.5, 'plate_rim', top='plate')
    m.disc(t, 6.4, 0.5, 0.9, 'plate', top='plate')
    m.disc(t, 4.6, 0.9, 0.95, 'plate_in', top='plate_in')
    if dirty:
        for x, z, c in ((-2.5, -1, 'dirt'), (2, 1.8, 'dirt_l'), (0.5, -2.5, 'dirt'), (-1, 2.4, 'dirt_l'), (3, -1.5, 'dirt')):
            m.box(t, x - 0.9, 0.95, z - 0.6, x + 0.9, 1.0, z + 0.6, c)


def m_soup_disc(m, t):
    m.disc(t, 6.6, 0, 0.6, 'soup', top='soup')
    for x, z in ((-2, -1), (1.5, 2), (2.5, -2.2), (-3, 2.4)):
        m.box(t, x - 0.6, 0.6, z - 0.6, x + 0.6, 0.65, z + 0.6, 'soup_l')


def m_bowl_soup(m, t):
    m.disc(t, 4, 0, 2.6, 'plate', top='plate')
    m.disc(t, 3.3, 2.6, 2.7, 'soup', top='soup')
    dx, dy, dz, s = t
    m.box(t, -1, 2.7, -0.5, 0.2, 2.75, 0.3, 'soup_l')


def m_burnt(m, t):
    m.disc(t, 3.6, 0, 1.6, 'char', top='char_l')
    m.box(t, -1.2, 2.5, -1, 0.2, 3.9, 0.4, 'smoke')
    m.box(t, 0.4, 4.4, -0.2, 1.4, 5.4, 0.8, 'smoke')


def m_pan(m, t):
    m.disc(t, 5.2, 0, 0.6, 'pan', top='pan_in')
    for x0, z0, x1, z1 in ((-5.2, -2.8, -4.6, 2.8), (4.6, -2.8, 5.2, 2.8), (-2.8, -5.2, 2.8, -4.6), (-2.8, 4.6, 2.8, 5.2)):
        m.box(t, x0, 0.6, z0, x1, 1.4, z1, 'pan')
    m.box(t, 5.2, 0.6, -0.6, 9, 1.2, 0.6, 'handle')


def m_meat(m, t):
    m.box(t, -4.5, 0, -3, 3.5, 2, 3, 'meat', top='meat')
    m.box(t, -3.5, 0, -3.6, 2.5, 2, 3.6, 'meat', top='meat')
    m.box(t, -4.5, 2, -0.3, 3.5, 2.05, 0.4, 'fat')
    m.box(t, -4.6, 0, -3, -4.4, 2, 3, 'fat')
    m.box(t, 3.5, 0.6, -0.6, 6.2, 1.4, 0.6, 'bone')
    m.box(t, 5.8, 0.4, -1.1, 6.8, 1.6, 1.1, 'bone')


def m_plate_cover(m, t):
    """Wire cage over the plate pile: shows plates here are only for taking, not for assembling dishes."""
    r, h, w = 7.2, 6.5, 0.45
    for x in (-r, r - w):
        for z in (-r, r - w):
            m.box(t, x, 0, z, x + w, h, z + w, 'steel_d')
    for y in (h - w, 2.8):
        col = 'steel' if y > 3 else 'steel_d'
        m.box(t, -r, y, -r, r, y + w, -r + w, col)
        m.box(t, -r, y, r - w, r, y + w, r, col)
        m.box(t, -r, y, -r, -r + w, y + w, r, col)
        m.box(t, r - w, y, -r, r, y + w, r, col)
    for k in (-3.6, 0, 3.6):
        m.box(t, k - w / 2, h - w, -r, k + w / 2, h, r, 'steel')
        m.box(t, -r, h - w, k - w / 2, r, h, k + w / 2, 'steel')
    m.box(t, -1.2, h, -1.2, 1.2, h + 0.9, 1.2, 'steel_l')


def m_knife(m, t):
    """A kitchen knife lying flat along the right edge of a chopping board."""
    x0, x1 = 6.0, 7.4
    m.box(t, x0, 0, -6.2, x1, 0.35, 1.0, 'steel_l')        # blade
    m.box(t, x1 - 0.3, 0, -6.2, x1, 0.45, 1.0, 'steel_d')  # spine
    m.box(t, x0 + 0.2, 0, -6.8, x1 - 0.2, 0.3, -6.2, 'steel_l')  # tip
    m.box(t, x0 - 0.05, 0, 1.0, x1 + 0.05, 0.75, 1.5, 'steel')  # bolster
    m.box(t, x0 + 0.1, 0, 1.5, x1 - 0.1, 0.8, 5.6, 'handle')  # handle
    for z in (2.6, 4.4):
        m.box(t, x0 + 0.05, 0.8, z - 0.25, x1 - 0.05, 0.85, z + 0.25, 'steel_l')  # rivets


FOOD_3D = {
    'knife': lambda m: m_knife(m, T()),
    'plate_cover': lambda m: m_plate_cover(m, T()),
    'tomato': lambda m: m_tomato(m, T()),
    'tomato_chopped': lambda m: m_tomato_slices(m, T()),
    'lettuce': lambda m: m_lettuce(m, T()),
    'lettuce_chopped': lambda m: m_lettuce_flakes(m, T()),
    'meat': lambda m: m_meat(m, T()),
    'patty_raw': lambda m: m_patty(m, T(), raw=True),
    'patty_cooked': lambda m: m_patty(m, T()),
    'burnt': lambda m: m_burnt(m, T()),
    'bread': lambda m: (m_bun_bottom(m, T()), m_bun_top(m, T(dy=1.4))),
    'fish': lambda m: m_fish(m, T()),
    'fish_chopped': lambda m: m_fish_pieces(m, T()),
    'fish_fried': lambda m: m_fish_fried(m, T()),
    'potato': lambda m: m_potato(m, T()),
    'potato_chopped': lambda m: m_potato_sticks(m, T()),
    'fries': lambda m: m_fries(m, T()),
    'pan': lambda m: m_pan(m, T()),
    'plate': lambda m: m_plate(m, T()),
    'dirty_plate': lambda m: m_plate(m, T(), dirty=True),
    'plate_salad': lambda m: (m_plate(m, T()), m_lettuce_flakes(m, T(dy=0.95, s=0.8))),
    'plate_mixsalad': lambda m: (m_plate(m, T()), m_lettuce_flakes(m, T(dy=0.95, s=0.8)),
                                 m_tomato_slices(m, T(dx=0.4, dy=1.9, dz=-0.3, s=0.6))),
    'plate_tomato': lambda m: (m_plate(m, T()), m_tomato_slices(m, T(dy=0.95, s=0.8))),
    'plate_bread': lambda m: (m_plate(m, T()), m_bun_bottom(m, T(dy=0.95)), m_bun_top(m, T(dy=2.2))),
    'plate_patty': lambda m: (m_plate(m, T()), m_patty(m, T(dy=0.95))),
    'plate_burger': lambda m: (m_plate(m, T()), m_bun_bottom(m, T(dy=0.95)), m_patty(m, T(dy=2.15, s=1.05)),
                               m_bun_top(m, T(dy=3.85))),
    'plate_special': lambda m: (m_plate(m, T()), m_bun_bottom(m, T(dy=0.95)), m_patty(m, T(dy=2.15, s=1.05)),
                                m.disc(T(dy=3.85), 3.4, 0, 0.6, 'red', top='pink_in'),
                                m.disc(T(dy=4.45), 4.3, 0, 0.5, 'green', top='green_l'),
                                m_bun_top(m, T(dy=4.95))),
    'plate_fish': lambda m: (m_plate(m, T()), m_fish_fried(m, T(dy=0.95, s=0.9))),
    'plate_fries': lambda m: (m_plate(m, T()), m_fries(m, T(dy=0.95, s=0.85))),
    'plate_fishfries': lambda m: (m_plate(m, T()), m_fish_fried(m, T(dx=-1.6, dy=0.95, s=0.7)),
                                  m_fries(m, T(dx=2.6, dy=0.95, dz=0.5, s=0.65))),
    'plate_fishset': lambda m: (m_plate(m, T()), m_fish_fried(m, T(dx=-1.8, dy=0.95, dz=-1.2, s=0.65)),
                                m_fries(m, T(dx=2.8, dy=0.95, dz=-0.6, s=0.6)),
                                m_lettuce_flakes(m, T(dx=-1, dy=0.95, dz=2.8, s=0.45)),
                                m_tomato_slices(m, T(dx=1.8, dy=0.95, dz=2.8, s=0.4))),
}

palette = Canvas(16, 16)
for i, n in enumerate(PAL_ORDER):
    palette.set(i % 16, i // 16, PAL[n])

# ---------------------------------------------------------------- building block textures
import random as _random


def noise_fill(c, base, amount, seed, x0=0, y0=0, x1=15, y1=15):
    rnd = _random.Random(seed)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            d = rnd.randint(-amount, amount)
            c.set(x, y, tuple(max(0, min(255, v + d)) for v in base[:3]) + (255,))


def tex_floor():
    c = Canvas()
    noise_fill(c, hexc('efe2c6'), 4, 1)
    return c


def tex_counter_top():
    c = Canvas()
    noise_fill(c, hexc('e8c086'), 5, 2)
    for y in (3, 8, 13):
        for x in range(16):
            c.set(x, y, hexc('d4a76a'))
    for x in range(16):
        c.set(x, 0, hexc('f3d6a6'))
        c.set(x, 15, hexc('c8975a'))
    return c


def tex_counter_side():
    c = Canvas()
    noise_fill(c, hexc('c6864a'), 5, 3)
    for x in range(16):
        c.set(x, 0, hexc('f0cf98'))
        c.set(x, 1, hexc('e2b77a'))
        c.set(x, 15, hexc('7a4a22'))
    for x0 in (1, 9):
        for y in range(3, 14):
            for x in range(x0, x0 + 6):
                edge = y in (3, 13) or x in (x0, x0 + 5)
                c.set(x, y, hexc('9a6232') if edge else hexc('b87a42'))
        c.set(x0 + (4 if x0 == 1 else 1), 8, hexc('f2d27a'))
        c.set(x0 + (4 if x0 == 1 else 1), 9, hexc('f2d27a'))
    return c


def tex_counter_bottom():
    c = Canvas()
    noise_fill(c, hexc('6e4424'), 4, 4)
    return c


def tex_bricks():
    c = Canvas()
    noise_fill(c, hexc('c4613a'), 8, 5)
    mortar = hexc('e8d2ae')
    for y in (3, 7, 11, 15):
        for x in range(16):
            c.set(x, y, mortar)
    for row, y0 in enumerate((0, 4, 8, 12)):
        for x in ((7, 15) if row % 2 == 0 else (3, 11)):
            for y in range(y0, y0 + 3):
                c.set(x, y, mortar)
    return c


STEEL, STEEL_L, STEEL_D, STEEL_DD = hexc('c9d1d8'), hexc('e6ebef'), hexc('9aa5ae'), hexc('5d6770')
WOOD, WOOD_L, WOOD_D, WOOD_DD = hexc('c6864a'), hexc('e0a96a'), hexc('9a6232'), hexc('6e4424')


def brushed(c, base, seed, x0=0, y0=0, x1=15, y1=15):
    """Brushed-metal look: horizontal streaks."""
    rnd = _random.Random(seed)
    for y in range(y0, y1 + 1):
        d = rnd.randint(-6, 6)
        for x in range(x0, x1 + 1):
            e = d + rnd.randint(-2, 2)
            c.set(x, y, tuple(max(0, min(255, v + e)) for v in base[:3]) + (255,))


def frame(c, col, x0=0, y0=0, x1=15, y1=15):
    for x in range(x0, x1 + 1):
        c.set(x, y0, col)
        c.set(x, y1, col)
    for y in range(y0, y1 + 1):
        c.set(x0, y, col)
        c.set(x1, y, col)


def tex_crate_side():
    c = Canvas()
    noise_fill(c, WOOD, 6, 11)
    for y in (4, 8, 12):
        for x in range(16):
            c.set(x, y, WOOD_D)
    frame(c, WOOD_DD)
    for x in range(1, 15):
        c.set(x, 1, WOOD_L)
    for i in range(1, 15):
        c.set(i, i, WOOD_D)
    for x, y in ((1, 1), (14, 1), (1, 14), (14, 14)):
        c.set(x, y, STEEL_D)
    return c


def tex_crate_top():
    c = Canvas()
    noise_fill(c, WOOD_L, 6, 12)
    for x in (3, 7, 11):
        for y in range(16):
            c.set(x, y, WOOD_DD)
    frame(c, WOOD_DD)
    for x, y in ((1, 1), (14, 1), (1, 14), (14, 14)):
        c.set(x, y, STEEL_D)
    return c


def tex_crate_bottom():
    c = Canvas()
    noise_fill(c, WOOD_D, 4, 13)
    frame(c, WOOD_DD)
    return c


ENAMEL, ENAMEL_D = hexc('f0e4cc'), hexc('d6c4a2')


def tex_stove_side():
    c = Canvas()
    noise_fill(c, ENAMEL, 3, 21)
    frame(c, hexc('8a6a50'))
    for x in range(1, 15):
        c.set(x, 14, ENAMEL_D)
    return c


def tex_stove_front(glow):
    c = Canvas()
    noise_fill(c, ENAMEL, 3, 22)
    frame(c, hexc('8a6a50'))
    for x in (3, 6, 9, 12):
        c.set(x, 2, hexc('d8342c'))
        c.set(x + 1, 2, hexc('d8342c'))
        c.set(x, 3, hexc('9a1e18'))
        c.set(x + 1, 3, hexc('9a1e18'))
    for y in range(6, 14):
        for x in range(2, 14):
            edge = y in (6, 13) or x in (2, 13)
            if edge:
                c.set(x, y, STEEL_DD)
            elif glow:
                c.set(x, y, hexc('ff9a3a') if (x + y + glow) % 3 else hexc('ffc860'))
            else:
                c.set(x, y, hexc('3a3a44'))
    for x in range(5, 11):
        c.set(x, 5, STEEL_D)
    return c


def tex_stove_top():
    c = Canvas()
    brushed(c, hexc('3b3f45'), 23)
    frame(c, hexc('22262b'))
    for i in range(2, 14):
        c.set(i, 7, hexc('1a1d21'))
        c.set(i, 8, hexc('1a1d21'))
        c.set(7, i, hexc('1a1d21'))
        c.set(8, i, hexc('1a1d21'))
    return c


def tex_dark():
    c = Canvas()
    noise_fill(c, hexc('3b3f45'), 3, 24)
    return c


def tex_serve():
    c = Canvas()
    brushed(c, STEEL_L, 31)
    frame(c, STEEL_DD)
    for x in range(1, 15):
        c.set(x, 1, hexc('ffffff'))
    yellow, dark = hexc('ffcc33'), hexc('c8901a')
    for k in range(3):
        for i in range(4):
            c.set(4 + i + k * 3, 4 + i, yellow)
            c.set(4 + i + k * 3, 11 - i, yellow)
            c.set(4 + i + k * 3, 5 + i, dark)
            c.set(4 + i + k * 3, 12 - i, dark)
    return c


BIN, BIN_D, BIN_L = hexc('6f8a7a'), hexc('4a6050'), hexc('9ab8a4')


def tex_bin_side():
    c = Canvas()
    noise_fill(c, BIN, 4, 41)
    for x in (2, 5, 8, 11, 14):
        for y in range(3, 15):
            c.set(x, y, BIN_D)
            if x + 1 < 16:
                c.set(x + 1, y, BIN_L)
    for x in range(16):
        c.set(x, 0, BIN_L)
        c.set(x, 1, BIN_L)
        c.set(x, 2, BIN_D)
        c.set(x, 15, BIN_D)
    return c


def tex_bin_top():
    c = Canvas()
    for y in range(16):
        for x in range(16):
            c.set(x, y, hexc('1e1e1e'))
    frame(c, BIN_L)
    frame(c, BIN, 1, 1, 14, 14)
    return c


def tex_bin_bottom():
    c = Canvas()
    noise_fill(c, BIN_D, 3, 42)
    return c


def tex_board():
    c = Canvas()
    noise_fill(c, hexc('ead7ac'), 4, 51)
    frame(c, hexc('c9a874'))
    rnd = _random.Random(52)
    for _ in range(6):
        x, y = rnd.randint(3, 11), rnd.randint(3, 12)
        for i in range(3):
            c.set(x + i, y - i // 2, hexc('cdb684'))
    return c


def tex_plate_station():
    """Pale blue tiled dish cabinet with a white plate emblem (smooth quartz)."""
    c = Canvas()
    noise_fill(c, hexc('cfe3ee'), 3, 81)
    frame(c, hexc('7f9fb2'))
    for x in range(1, 15):
        c.set(x, 1, hexc('eaf4fa'))
    c.ellipse(8, 8.5, 5, 5, hexc('ffffff'))
    c.ellipse(8, 8.5, 3.2, 3.2, hexc('e2ecf2'))
    for y in range(16):
        for x in range(16):
            r = ((x + 0.5 - 8) ** 2 + (y + 0.5 - 8.5) ** 2) ** 0.5
            if 4.6 < r <= 5.4 and c.p[y][x] != hexc('ffffff'):
                c.set(x, y, hexc('9ab4c4'))
    return c


def tex_cauldron_side():
    c = Canvas()
    brushed(c, STEEL, 61)
    for x in range(16):
        c.set(x, 0, STEEL_L)
        c.set(x, 1, STEEL_L)
        c.set(x, 2, STEEL_D)
    for y in range(13, 16):
        for x in range(4, 12):
            c.p[y][x] = None
    for y in range(13, 16):
        for x in (0, 1, 2, 3, 12, 13, 14, 15):
            c.set(x, y, STEEL_DD)
    return c


def tex_cauldron_top():
    c = Canvas()
    for y in range(16):
        for x in range(16):
            if x < 2 or y < 2 or x > 13 or y > 13:
                c.set(x, y, STEEL_L if (x + y) % 5 else STEEL)
    return c


def tex_cauldron_inner():
    c = Canvas()
    brushed(c, STEEL_D, 62)
    return c


def tex_cauldron_bottom():
    c = Canvas()
    brushed(c, STEEL_DD, 63)
    return c


def tex_oil():
    """Four animation frames of bubbling golden frying oil."""
    frames = 4
    c = Canvas(16, 16 * frames)
    for f in range(frames):
        rnd = _random.Random(70 + f)
        for y in range(16):
            for x in range(16):
                c.set(x, f * 16 + y, (232 + rnd.randint(-8, 8), 168 + rnd.randint(-10, 10), 52 + rnd.randint(-8, 8), 255))
        for k in range(5):
            bx, by = (k * 5 + f * 3) % 14 + 1, (k * 7 + f * 4) % 14 + 1
            c.set(bx, f * 16 + by, hexc('ffe7a0'))
            c.set(bx + 1, f * 16 + by, hexc('ffd77a'))
    return c


def tex_stove_front_on():
    c = Canvas(16, 32)
    for f in range(2):
        fr = tex_stove_front(glow=f + 1)
        for y in range(16):
            for x in range(16):
                c.p[f * 16 + y][x] = fr.p[y][x]
    return c


BLOCK_TEXTURES = {
    'white_concrete': tex_floor,
    'sandstone_top': tex_counter_top,
    'sandstone': tex_counter_side,
    'sandstone_bottom': tex_counter_bottom,
    'bricks': tex_bricks,
    # ingredient crates (barrels facing up)
    'barrel_side': tex_crate_side,
    'barrel_top': tex_crate_top,
    'barrel_top_open': tex_crate_top,
    'barrel_bottom': tex_crate_bottom,
    # stove under the pans (smoker)
    'smoker_side': tex_stove_side,
    'smoker_front': lambda: tex_stove_front(glow=0),
    'smoker_top': tex_stove_top,
    'smoker_bottom': tex_dark,
    # serving hatch
    'lime_glazed_terracotta': tex_serve,
    # bin (composter)
    'composter_side': tex_bin_side,
    'composter_top': tex_bin_top,
    'composter_bottom': tex_bin_bottom,
    # chopping board (birch pressure plate uses birch planks)
    'birch_planks': tex_board,
    # plate stand and drying rack (smooth quartz uses quartz_block_bottom on every face)
    'quartz_block_bottom': tex_plate_station,
    # fryer and sink (cauldrons): stainless steel
    'cauldron_side': tex_cauldron_side,
    'cauldron_top': tex_cauldron_top,
    'cauldron_inner': tex_cauldron_inner,
    'cauldron_bottom': tex_cauldron_bottom,
}
ANIMATED_TEXTURES = {
    'lava_still': (tex_oil, 4),
    'smoker_front_on': (tex_stove_front_on, 8),
}

# ---------------------------------------------------------------- HUD panels and big digits
DIGITS = {
    '0': ['01110', '10001', '10011', '10101', '11001', '10001', '01110'],
    '1': ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
    '2': ['01110', '10001', '00001', '00110', '01000', '10000', '11111'],
    '3': ['11110', '00001', '00001', '01110', '00001', '00001', '11110'],
    '4': ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
    '5': ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
    '6': ['00110', '01000', '10000', '11110', '10001', '10001', '01110'],
    '7': ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
    '8': ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
    '9': ['01110', '10001', '10001', '01111', '00001', '00010', '01100'],
    ':': ['0', '1', '1', '0', '1', '1', '0'],
}


def digit_canvas(rows):
    w = len(rows[0])
    c = Canvas(w + 1, 8)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == '1':
                c.set(x, y, WHITE)
                c.set(x + 1, y + 1, (60, 40, 30, 255)) if c.get(x + 1, y + 1) is None else None
    return c


def panel(w, h, fill, edge, light):
    c = Canvas(w, h)
    for y in range(h):
        for x in range(w):
            c.set(x, y, fill)
    for x in range(w):
        c.set(x, 0, edge)
        c.set(x, h - 1, edge)
        c.set(x, 1, light)
    return c


def panel_cap(w, h, fill, edge, light, left):
    c = panel(w, h, fill, edge, light)
    for y in range(h):
        c.set(0 if left else w - 1, y, edge)
    corner = [(0, 0), (0, h - 1)] if left else [(w - 1, 0), (w - 1, h - 1)]
    for x, y in corner:
        c.p[y][x] = None
    if not left:
        c.p[1][w - 1] = edge
    return c


CARD = (hexc('fff4dc'), hexc('4a3426'), hexc('ffffff'))
NAVY = (hexc('2b3a57'), hexc('141c2c'), hexc('4c6088'))
HUD_GLYPHS = [
    # name, canvas, height, ascent
    ('card_l', panel_cap(3, 16, *CARD, left=True), 16, 12),
    ('card_m', panel(8, 16, *CARD), 16, 12),
    ('card_r', panel_cap(3, 16, *CARD, left=False), 16, 12),
    ('nav_l', panel_cap(3, 22, *NAVY, left=True), 22, 17),
    ('nav_m', panel(8, 22, *NAVY), 22, 17),
    ('nav_r', panel_cap(3, 22, *NAVY, left=False), 22, 17),
]

files = {}
glyphs = []
providers = []
code = 0xE000

files['pack.mcmeta'] = json.dumps({'pack': {
    'description': 'Kitchen Rush',
    'pack_format': PACK_FORMAT, 'min_format': PACK_FORMAT, 'max_format': PACK_FORMAT}}, indent=2)

for name in ITEMS + ICONS_ONLY:
    c = draw(name)
    folder = 'item' if name in ITEMS else 'font'
    files[f'assets/kitchen/textures/{folder}/{name}.png'] = png(c)
    if name in ITEMS:
        if name in FOOD_3D:
            mdl = Model()
            FOOD_3D[name](mdl)
            files[f'assets/kitchen/models/item/{name}.json'] = json.dumps(mdl.json())
        else:
            files[f'assets/kitchen/models/item/{name}.json'] = json.dumps(
                {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'kitchen:item/{name}'}})
        files[f'assets/kitchen/items/{name}.json'] = json.dumps(
            {'model': {'type': 'minecraft:model', 'model': f'kitchen:item/{name}'}})
    ch = chr(code)
    code += 1
    providers.append({'type': 'bitmap', 'file': f'kitchen:{folder}/{name}.png', 'height': 9, 'ascent': 8, 'chars': [ch]})
    advance = int(0.5 + c.width() * (9 / 16)) + 1
    glyphs.append(f'{name} {ord(ch):04X} {advance}')

pip = Canvas(3, 7)
pip.rect(0, 0, 2, 6, WHITE)
files['assets/kitchen/textures/font/pip.png'] = png(pip)
ch = chr(code)
providers.append({'type': 'bitmap', 'file': 'kitchen:font/pip.png', 'height': 7, 'ascent': 7, 'chars': [ch]})
glyphs.append(f'pip {ord(ch):04X} {pip.width() + 1}')

files['assets/kitchen/textures/item/palette.png'] = png(palette)

for name, cv, height, ascent in HUD_GLYPHS:
    code += 1
    ch = chr(code)
    files[f'assets/kitchen/textures/font/{name}.png'] = png(cv)
    providers.append({'type': 'bitmap', 'file': f'kitchen:font/{name}.png', 'height': height, 'ascent': ascent, 'chars': [ch]})
    glyphs.append(f'{name} {ord(ch):04X} {int(0.5 + cv.width() * height / cv.h) + 1}')

# Big icons and digits for the bottom score/timer panels.
for name in ('clock', 'coin', 'plate'):
    code += 1
    ch = chr(code)
    folder = 'item' if name in ITEMS else 'font'
    cv = draw(name)
    providers.append({'type': 'bitmap', 'file': f'kitchen:{folder}/{name}.png', 'height': 14, 'ascent': 12, 'chars': [ch]})
    glyphs.append(f'big_{name} {ord(ch):04X} {int(0.5 + cv.width() * 14 / 16) + 1}')
for d, rows in DIGITS.items():
    code += 1
    ch = chr(code)
    cv = digit_canvas(rows)
    key = 'colon' if d == ':' else d
    files[f'assets/kitchen/textures/font/digit_{key}.png'] = png(cv)
    providers.append({'type': 'bitmap', 'file': f'kitchen:font/digit_{key}.png', 'height': 14, 'ascent': 12, 'chars': [ch]})
    glyphs.append(f'digit_{key} {ord(ch):04X} {int(0.5 + cv.width() * 14 / 8) + 1}')

for block, fn in BLOCK_TEXTURES.items():
    files[f'assets/minecraft/textures/block/{block}.png'] = png(fn())
for block, (fn, frametime) in ANIMATED_TEXTURES.items():
    files[f'assets/minecraft/textures/block/{block}.png'] = png(fn())
    files[f'assets/minecraft/textures/block/{block}.png.mcmeta'] = json.dumps({'animation': {'frametime': frametime}})

files['assets/kitchen/font/icons.json'] = json.dumps({'providers': providers}, ensure_ascii=True, indent=1)

# Original music and jingles made by gen_audio.py (run first by build.ps1).
AUDIO_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'audio')
sound_events = {}
for folder, event_prefix, stream in (('music', 'music', True), ('sfx', 'sfx', False)):
    d = os.path.join(AUDIO_DIR, folder)
    if not os.path.isdir(d):
        continue
    for fn in sorted(os.listdir(d)):
        if fn.endswith('.ogg'):
            name = fn[:-4]
            with open(os.path.join(d, fn), 'rb') as fh:
                files[f'assets/kitchen/sounds/{folder}/{fn}'] = fh.read()
            sound_events[f'{event_prefix}.{name}'] = {
                'sounds': [{'name': f'kitchen:{folder}/{name}', 'stream': stream}]}
if sound_events:
    files['assets/kitchen/sounds.json'] = json.dumps(sound_events, indent=1)

advances = {}
for bit in range(11):
    advances[chr(0xF001 + bit)] = -(1 << bit)
    advances[chr(0xF011 + bit)] = (1 << bit)
files['assets/kitchen/font/space.json'] = json.dumps({'providers': [{'type': 'space', 'advances': advances}]},
                                                     ensure_ascii=True, indent=1)

# Boss bar used for the HUD: make its bar fully transparent so only the text shows.
clear = Canvas(182, 5)
for part in ('white_background', 'white_progress'):
    files[f'assets/minecraft/textures/gui/sprites/boss_bar/{part}.png'] = png(clear)

buf = io.BytesIO()
with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED) as z:
    for path in sorted(files):
        info = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        data = files[path]
        z.writestr(info, data if isinstance(data, bytes) else data.encode('utf-8'))
os.makedirs(os.path.dirname(OUT_ZIP), exist_ok=True)
with open(OUT_ZIP, 'wb') as f:
    f.write(buf.getvalue())
with open(OUT_GLYPHS, 'w', encoding='utf-8') as f:
    f.write('\n'.join(glyphs) + '\n')

# Preview sheet for checking the art (not part of the pack).
names = ITEMS + ICONS_ONLY
sheet = Canvas(16 * len(names) + 2 * len(names), 16)
for i, n in enumerate(names):
    c = draw(n)
    for y in range(16):
        for x in range(16):
            if c.p[y][x]:
                sheet.set(i * 18 + x, y, c.p[y][x])
big = Canvas(sheet.w * 6, sheet.h * 6)
for y in range(sheet.h):
    for x in range(sheet.w):
        col = sheet.p[y][x] or hexc('b8d8f0')
        for yy in range(6):
            for xx in range(6):
                big.p[y * 6 + yy][x * 6 + xx] = col
with open(os.path.join(os.path.dirname(OUT_GLYPHS), 'preview.png'), 'wb') as f:
    f.write(png(big))
# Isometric preview of the 3D models and block textures (not part of the pack).
def render_iso(model, k=6):
    W, H = 34 * k, 30 * k
    c = Canvas(W, H)
    zbuf = [[-1e9] * W for _ in range(H)]
    ox, oy = W / 2, H * 0.7

    def proj(x, y, z):
        x, z = x - 8, z - 8
        return ox + (x - z) * k * 0.87, oy + (x + z) * k * 0.5 - y * k

    for el in model.elements:
        (x0, y0, z0), (x1, y1, z1) = el['from'], el['to']
        for face, shade in (('up', 1.0), ('south', 0.78), ('east', 0.62)):
            u = el['faces'][face]['uv']
            col = PAL[PAL_ORDER[int(u[1]) * 16 + int(u[0])]]
            col = tuple(int(v * shade) for v in col[:3]) + (255,)
            steps = 24
            for i in range(steps + 1):
                for j in range(steps + 1):
                    a, b = i / steps, j / steps
                    if face == 'up':
                        p = (x0 + (x1 - x0) * a, y1, z0 + (z1 - z0) * b)
                    elif face == 'south':
                        p = (x0 + (x1 - x0) * a, y0 + (y1 - y0) * b, z1)
                    else:
                        p = (x1, y0 + (y1 - y0) * b, z0 + (z1 - z0) * a)
                    sx, sy = proj(*p)
                    depth = p[0] + p[2] + p[1] * 0.01
                    px, py = int(sx), int(sy)
                    if 0 <= px < W and 0 <= py < H and depth >= zbuf[py][px]:
                        zbuf[py][px] = depth
                        c.p[py][px] = col
    return c


iso_names = list(FOOD_3D)
cols = 8
tile_w, tile_h = 34 * 6, 30 * 6
tex_all = list(BLOCK_TEXTURES.items()) + [(k, v[0]) for k, v in ANIMATED_TEXTURES.items()]
per_row = (tile_w * cols) // 136
sheet3d = Canvas(tile_w * cols, tile_h * ((len(iso_names) + cols - 1) // cols) + 136 * ((len(tex_all) + per_row - 1) // per_row))
for i, n in enumerate(iso_names):
    mdl = Model()
    FOOD_3D[n](mdl)
    r = render_iso(mdl)
    bx, by = (i % cols) * tile_w, (i // cols) * tile_h
    for y in range(r.h):
        for x in range(r.w):
            if r.p[y][x]:
                sheet3d.set(bx + x, by + y, r.p[y][x])
by = tile_h * ((len(iso_names) + cols - 1) // cols)
for i, (block, fn) in enumerate(tex_all):
    t = fn()
    tx, ty = (i % per_row) * 136, by + (i // per_row) * 136
    for y in range(16):
        for x in range(16):
            for yy in range(8):
                for xx in range(8):
                    sheet3d.set(tx + x * 8 + xx, ty + y * 8 + yy, t.p[y][x])
for y in range(sheet3d.h):
    for x in range(sheet3d.w):
        if sheet3d.p[y][x] is None:
            sheet3d.p[y][x] = hexc('3a4a5a')
with open(os.path.join(os.path.dirname(OUT_GLYPHS), 'preview3d.png'), 'wb') as f:
    f.write(png(sheet3d))
print(f'{len(files)} files, glyphs: {len(glyphs)}')
