#!/usr/bin/env python3
"""InkLink 云朵宠 · 动作场景合成预览。
把 scene_bg + raw 宠物帧 + 道具贴层叠成 96x96 动作帧，
并输出 4x 预览 sheet 供用户审。
"""
from PIL import Image, ImageDraw
import os

RAW = 'preview/raw'
OUT = 'preview'
PAL = {
    'pink': (0xF6, 0xAF, 0xC3), 'pink_dk': (0xD9, 0x8F, 0xA3),
    'white': (0xFD, 0xF4, 0xF7), 'cream': (0xFF, 0xE8, 0xC8),
    'orange': (0xFF, 0xB7, 0x4D), 'teal': (0x4F, 0xC3, 0xF7),
    'teal_l': (0x81, 0xD4, 0xFA), 'gray': (0x78, 0x90, 0x9C),
    'gray_dk': (0x45, 0x55, 0x63), 'green': (0x8B, 0xC3, 0x4A),
    'blue': (0x79, 0x86, 0xCB), 'red': (0xFF, 0x8A, 0x80),
    'shadow': (0x10, 0x1A, 0x20),
}


def load_pet(name):
    im = Image.open(f'{RAW}/{name}.png').convert('RGBA')
    return im


def scene(name):
    return Image.open(f'{OUT}/scene_{name}.png').convert('RGBA')


def paste_pet(canvas, pet, dx=0, dy=0):
    canvas.alpha_composite(pet, (dx, dy))


# ---------- 道具 (PIL, 像素块) ----------
def draw_bowl(c, ox, oy):
    d = ImageDraw.Draw(c)
    d.ellipse([ox - 12, oy - 6, ox + 12, oy + 8], fill=PAL['gray_dk'])
    d.ellipse([ox - 9, oy - 7, ox + 9, oy], fill=PAL['gray'])
    d.ellipse([ox - 7, oy - 9, ox + 7, oy - 2], fill=PAL['orange'])   # 食物
    for i in (-4, 0, 4):
        d.ellipse([ox + i - 2, oy - 7, ox + i + 2, oy - 3], fill=PAL['cream'])  # 米粒


def draw_cushion(c, x0, x1, y):
    d = ImageDraw.Draw(c)
    d.ellipse([x0, y - 6, x1, y + 10], fill=PAL['blue'])
    d.ellipse([x0 + 6, y - 9, x1 - 6, y + 2], fill=PAL['white'])


def draw_ball(c, cx, cy):
    d = ImageDraw.Draw(c)
    d.ellipse([cx - 10, cy - 10, cx + 10, cy + 10], fill=PAL['teal'])
    d.ellipse([cx - 8, cy - 8, cx + 8, cy + 8], fill=PAL['teal_l'])
    d.arc([cx - 8, cy - 8, cx + 8, cy + 8], 45, 225, fill=PAL['white'], width=2)
    d.arc([cx - 8, cy - 8, cx + 8, cy + 8], 270, 360, fill=PAL['white'], width=2)


def draw_book(c, x0, x1, y):
    d = ImageDraw.Draw(c)
    d.rectangle([x0, y, x1, y + 14], fill=PAL['white'], outline=PAL['gray'])
    d.line([(x0 + x1) // 2, y, (x0 + x1) // 2, y + 14], fill=PAL['gray'])
    d.rectangle([x0 + 2, y + 3, x0 + 8, y + 5], fill=PAL['gray'])
    d.rectangle([(x0 + x1) // 2 + 2, y + 3, (x0 + x1) // 2 + 8, y + 5], fill=PAL['gray'])
    d.rectangle([(x0 + x1) // 2 + 2, y + 8, (x0 + x1) // 2 + 9, y + 10], fill=PAL['gray'])


def draw_bubbles(c, frame):
    d = ImageDraw.Draw(c)
    pts = [(30, 20), (58, 14), (74, 34), (22, 46), (80, 56), (16, 66)]
    off = 3 if frame == 2 else 0
    for i, (bx, by) in enumerate(pts):
        r = 4 + (i % 3)
        x, y = bx + (i % 2) * off, by + (i % 2) * off
        d.ellipse([x - r, y - r, x + r, y + r], fill=PAL['teal_l'])
        d.point((x - 1, y - 2), fill=PAL['white'])


def draw_bucket(c, ox, oy):
    d = ImageDraw.Draw(c)
    d.rectangle([ox - 6, oy, ox + 6, oy + 14], fill=PAL['teal'])
    d.rectangle([ox - 6, oy, ox + 6, oy + 2], fill=PAL['gray'])
    d.arc([ox - 8, oy - 6, ox + 8, oy + 6], 180, 360, fill=PAL['gray'], width=2)


def draw_angry(c, frame):
    d = ImageDraw.Draw(c)
    x, y = 76, 10
    if frame == 2:
        x += 3
    for i in range(3):
        d.line([(x, y + i * 5), (x + 6, y + 2 + i * 5)], fill=PAL['red'], width=2)
        d.line([(x + 6, y + i * 5), (x, y + 2 + i * 5)], fill=PAL['red'], width=2)


# ---------- 动作合成 ----------
# 顺序: 'pre' 道具先画(被宠物遮=在后), 'post' 后画(宠物前)。
ACTIONS = {
    'idle':  {'scene': 'bedroom', 'raw': ['idle_a', 'idle_b'],       'py': -8,  'props': []},
    'eat':   {'scene': 'bedroom', 'raw': ['eat_1', 'eat_2', 'eat_3'], 'py': -10, 'props': [('bowl', 0, 'pre', (46, 86))]},
    'sleep': {'scene': 'bedroom', 'raw': ['sleep_1', 'sleep_2'],     'py': -6,  'props': [('cushion', 0, 'pre', (40, 76))]},
    'happy': {'scene': 'living',  'raw': ['happy_1', 'happy_2', 'happy_3'], 'py': -10,
              'props': [('ball', 0, 'post', (52, 88))]},
    'annoy': {'scene': 'living',  'raw': ['annoy_1', 'annoy_2'],     'py': -10, 'props': [('angry', 0, 'post', (0, 0))]},
    'read':  {'scene': 'window',  'raw': ['read_1', 'read_2'],       'py': -10, 'props': [('book', 0, 'post', (48, 84))]},
    'clean': {'scene': 'window',  'raw': ['clean_1', 'clean_2'],     'py': -10,
              'props': [('bubbles', 1, 'post', (0, 0)), ('bucket', 0, 'post', (84, 82))]},
}

PROP_DRAW = {
    'bowl': lambda c, p, f, a: draw_bowl(c, *a),
    'cushion': lambda c, p, f, a: draw_cushion(c, a[0] - 30, a[0] + 30, a[1]),
    'ball': lambda c, p, f, a: draw_ball(c, *a),
    'book': lambda c, p, f, a: draw_book(c, a[0] - 20, a[0] + 20, a[1]),
    'bubbles': lambda c, p, f, a: draw_bubbles(c, f),
    'bucket': lambda c, p, f, a: draw_bucket(c, *a),
    'angry': lambda c, p, f, a: draw_angry(c, f),
}


def compose(sc, raw, frame_idx, py, props):
    canvas = scene(sc)
    for name, fflag, when, anchor in props:
        if when == 'pre':
            PROP_DRAW[name](canvas, fflag, frame_idx, anchor)
    pet = load_pet(raw)
    paste_pet(canvas, pet, 0, py)
    for name, fflag, when, anchor in props:
        if when == 'post':
            PROP_DRAW[name](canvas, fflag, frame_idx, anchor)
    return canvas


def main():
    os.makedirs(OUT, exist_ok=True)
    frames = []
    labels = []
    for act, cfg in ACTIONS.items():
        n = len(cfg['raw'])
        for i, raw in enumerate(cfg['raw']):
            name = f"act_{cfg['scene']}_{act}_{i + 1}.png"
            im = compose(cfg['scene'], raw, i + 1, cfg['py'], cfg['props'])
            im.convert('RGB').save(f'{OUT}/{name}')
            frames.append((name, f'{cfg["scene"]}/{act} #{i + 1}'))
            print('composed', name)

    # 4x sheet (列=6)
    CELL = 192
    COLS = 6
    rows = (len(frames) + COLS - 1) // COLS
    sheet = Image.new('RGB', (CELL * COLS, CELL * rows), (0x20, 0x2A, 0x33))
    for i, (name, label) in enumerate(frames):
        im = Image.open(f'{OUT}/{name}').convert('RGBA')
        big = im.resize((CELL, CELL), Image.NEAREST)
        r, c = divmod(i, COLS)
        sheet.paste(big, (c * CELL, r * CELL))
    sheet.save(f'{OUT}/acts_sheet.png')
    print('sheet', sheet.size)


if __name__ == '__main__':
    main()
