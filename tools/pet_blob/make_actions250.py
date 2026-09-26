#!/usr/bin/env python3
"""InkLink 云朵宠 · 250 动作场景合成预览。
scene250_*.png(250) + 宠物 raw(96, 底对齐地面线226) + 道具(250坐标)。
"""
from PIL import Image, ImageDraw
import os

RAW = 'preview/raw'
OUT = 'preview'
FLOOR = 226          # 墙地分界 = 宠物脚线
PET_X = (250 - 96) // 2   # 宠物 raw 水平锚点(居中)
C = {
    'white': (0xFD, 0xF4, 0xF7), 'cream': (0xFF, 0xE8, 0xC8),
    'orange': (0xFF, 0xB7, 0x4D), 'gray_dk': (0x45, 0x55, 0x63),
    'gray': (0x78, 0x90, 0x9C), 'teal': (0x4F, 0xC3, 0xF7),
    'teal_l': (0x81, 0xD4, 0xFA), 'blue': (0x79, 0x86, 0xCB),
    'blue_d': (0x56, 0x63, 0xA8), 'red': (0xFF, 0x8A, 0x80),
    'dark': (0x18, 0x22, 0x28), 'pink': (0xF6, 0xAF, 0xC3),
}


def scene(name):
    return Image.open(f'{OUT}/scene250_{name}.png').convert('RGBA')


def pet_bottom(pet):
    a = pet.split()[3]
    ys = [y for y in range(96) for x in range(96) if a.getpixel((x, y)) > 0]
    return max(ys)


def paste_pet(canvas, name):
    pet = Image.open(f'{RAW}/{name}.png').convert('RGBA')
    bottom = pet_bottom(pet)
    canvas.alpha_composite(pet, (PET_X, FLOOR - bottom))
    return bottom


# ---------- 道具 (250 坐标) ----------
def draw_bowl(c, cx, cy):
    d = ImageDraw.Draw(c)
    d.ellipse([cx - 14, cy - 4, cx + 14, cy + 12], fill=C['gray_dk'])
    d.ellipse([cx - 11, cy - 6, cx + 11, cy + 4], fill=C['gray'])
    d.ellipse([cx - 8, cy - 8, cx + 8, cy], fill=C['orange'])
    for i in (-4, 0, 4):
        d.ellipse([cx + i - 2, cy - 6, cx + i + 2, cy - 2], fill=C['cream'])


def draw_cushion(c, x0, x1, yc):
    d = ImageDraw.Draw(c)
    d.ellipse([x0, yc - 8, x1, yc + 12], fill=C['blue'])
    d.ellipse([x0 + 8, yc - 10, x1 - 8, yc + 2], fill=C['white'])


def draw_ball(c, cx, cy):
    d = ImageDraw.Draw(c)
    d.ellipse([cx - 11, cy - 11, cx + 11, cy + 11], fill=C['teal'])
    d.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill=C['teal_l'])
    d.arc([cx - 8, cy - 8, cx + 8, cy + 8], 40, 220, fill=C['white'], width=2)
    d.arc([cx - 8, cy - 8, cx + 8, cy + 8], 280, 360, fill=C['white'], width=2)


def draw_book(c, x0, x1, y):
    d = ImageDraw.Draw(c)
    d.rectangle([x0, y, x1, y + 16], fill=C['white'], outline=C['gray_dk'])
    d.line([(x0 + x1) // 2, y, (x0 + x1) // 2, y + 16], fill=C['gray_dk'])
    for x in range(x0 + 3, (x0 + x1) // 2 - 4, 10):
        d.line([x, y + 4, x, y + 11], fill=C['gray'])
    d.line([(x0 + x1) // 2 + 3, y + 4, (x0 + x1) // 2 + 10, y + 4], fill=C['gray'])


def draw_bubbles(c, frame):
    d = ImageDraw.Draw(c)
    pts = [(132, 128), (186, 160), (84, 182), (192, 200), (168, 112), (108, 156)]
    off = 3 if frame == 2 else 0
    for i, (bx, by) in enumerate(pts):
        r = 4 + (i % 4)
        x, y = bx + (i % 2) * off, by + (i % 3) * off
        d.ellipse([x - r, y - r, x + r, y + r], fill=C['teal_l'])
        d.point((x - 1, y - 2), fill=C['white'])


def draw_bucket(c, ox, oy):
    d = ImageDraw.Draw(c)
    d.rectangle([ox - 7, oy, ox + 7, oy + 16], fill=C['teal'])
    d.rectangle([ox - 7, oy, ox + 7, oy + 3], fill=C['gray_dk'])
    d.arc([ox - 10, oy - 8, ox + 10, oy + 6], 180, 360, fill=C['gray_dk'], width=3)


def draw_angry(c, frame):
    d = ImageDraw.Draw(c)
    x, y = 184, 118
    if frame == 2:
        x += 4
    for i in range(3):
        d.line([(x, y + i * 6), (x + 8, y + 3 + i * 6)], fill=C['red'], width=3)
        d.line([(x + 8, y + i * 6), (x, y + 3 + i * 6)], fill=C['red'], width=3)


ACTIONS = {
    'idle':  {'scene': 'bedroom', 'raw': ['idle_a', 'idle_b'], 'props': []},
    'eat':   {'scene': 'bedroom', 'raw': ['eat_1', 'eat_2', 'eat_3'],
              'props': [('bowl', (136, 238), 'post')]},
    'sleep': {'scene': 'bedroom', 'raw': ['sleep_1', 'sleep_2'],
              'props': [('cushion', (96, 166, 230), 'pre')]},
    'happy': {'scene': 'living', 'raw': ['happy_1', 'happy_2', 'happy_3'],
              'props': [('ball', (96, 236), 'post')]},
    'annoy': {'scene': 'living', 'raw': ['annoy_1', 'annoy_2'],
              'props': [('angry', (), 'post')]},
    'read':  {'scene': 'window', 'raw': ['read_1', 'read_2'],
              'props': [('book', (98, 156, 232), 'post')]},
    'clean': {'scene': 'window', 'raw': ['clean_1', 'clean_2'],
              'props': [('bubbles', (), 'post'), ('bucket', (206, 230), 'post')]},
}

DRAW = {
    'bowl': lambda d, a, f: draw_bowl(d, *a),
    'cushion': lambda d, a, f: draw_cushion(d, *a),
    'ball': lambda d, a, f: draw_ball(d, *a),
    'book': lambda d, a, f: draw_book(d, *a),
    'bubbles': lambda d, a, f: draw_bubbles(d, f),
    'bucket': lambda d, a, f: draw_bucket(d, *a),
    'angry': lambda d, a, f: draw_angry(d, f),
}


def compose(sc, raw, frame, props):
    canvas = scene(sc)
    for name, anchor, when in props:
        if when == 'pre':
            DRAW[name](canvas, anchor, frame)
    paste_pet(canvas, raw)
    for name, anchor, when in props:
        if when == 'post':
            DRAW[name](canvas, anchor, frame)
    return canvas


def main():
    os.makedirs(OUT, exist_ok=True)
    frames = []
    for act, cfg in ACTIONS.items():
        for i, raw in enumerate(cfg['raw']):
            name = f"act250_{cfg['scene']}_{act}_{i + 1}.png"
            im = compose(cfg['scene'], raw, i + 1, cfg['props'])
            im.convert('RGB').save(f'{OUT}/{name}')
            frames.append(name)
    COLS = 6
    CELL = 250
    rows = (len(frames) + COLS - 1) // COLS
    sheet = Image.new('RGB', (CELL * COLS, CELL * rows), (0x20, 0x2A, 0x33))
    for i, name in enumerate(frames):
        im = Image.open(f'{OUT}/{name}').convert('RGBA')
        r, c = divmod(i, COLS)
        sheet.paste(im, (c * CELL, r * CELL))
    sheet.save(f'{OUT}/acts250_sheet.png')
    print('acts250 done', len(frames), 'frames; sheet', sheet.size)


if __name__ == '__main__':
    main()
