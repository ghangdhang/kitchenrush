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
        c.dots([(8, 10), (8, 9)], RED)
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
        c.ellipse(8, 8.5, 6.5, 3.8, FRY)
        c.ellipse(8, 8.5, 6.5, 3.8, FRY_L, cond=lambda x, y: y < 7)
        c.dots([(5, 9), (8, 10), (11, 8), (6, 7)], FRY_D)
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
         'burnt', 'bread', 'plate', 'plate_salad', 'plate_bread', 'plate_patty', 'plate_burger', 'plate_soup',
         'soup', 'pan', 'fish', 'fish_chopped', 'fish_fried', 'potato', 'potato_chopped', 'fries', 'dirty_plate',
         'plate_tomato', 'plate_fish', 'plate_fries', 'plate_fishfries', 'plate_special', 'plate_fishset']
ICONS_ONLY = ['clock', 'coin']

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

files['assets/kitchen/font/icons.json'] = json.dumps({'providers': providers}, ensure_ascii=True, indent=1)

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
print(f'{len(files)} files, glyphs: {len(glyphs)}')
