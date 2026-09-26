#!/usr/bin/env python3
"""云朵宠素材落盘: alpha 二值化(>=128=255) 后按 drawable 命名拷入 drawable-nodpi。

契约:
  - cloud_<pose>_<n>.png / cloud_<expr>.png  : 96x96 RGBA 二值alpha (引擎 PIXEL_SCENE 宠帧)
  - scene_<id>.png                           : 250x250 RGB 全不透明 (场景底, id∈bedroom/living_room/windowsill)
"""
from PIL import Image
import os, sys

RAW = 'preview/raw'
PREVIEW = 'preview'
DEST = '../../app-host/src/main/res/drawable-nodpi'

CLOUD_FRAMES = [
    'idle_a', 'idle_b',
    'happy_1', 'happy_2', 'happy_3',
    'sleep_1', 'sleep_2',
    'eat_1', 'eat_2', 'eat_3',
    'read_1', 'read_2',
    'clean_1', 'clean_2',
    'annoy_1', 'annoy_2',
    'blink', 'hungry', 'sad',
]

SCENES = {'bedroom': 'scene250_bedroom.png', 'living_room': 'scene250_living.png',
          'windowsill': 'scene250_window.png'}


def binary_alpha(im):
    a = im.split()[3].point(lambda v: 255 if v >= 128 else 0)
    im.putalpha(a)
    return im


def main():
    dest = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), DEST))
    os.makedirs(dest, exist_ok=True)
    count = 0
    for name in CLOUD_FRAMES:
        src = os.path.join(RAW, f'{name}.png')
        im = Image.open(src).convert('RGBA')
        binary_alpha(im)
        out = os.path.join(dest, f'cloud_{name}.png')
        im.convert('RGB', colors=256) if False else None
        im.save(out)   # 保留 alpha: RGBA
        count += 1
        print('cloud →', os.path.basename(out), im.size)
    for sid, fname in SCENES.items():
        im = Image.open(os.path.join(PREVIEW, fname)).convert('RGBA')
        binary_alpha(im)
        out = os.path.join(dest, f'scene_{sid}.png')
        im.save(out)
        print('scene →', os.path.basename(out), im.size)
        count += 1
    print('deployed', count, 'assets →', dest)


if __name__ == '__main__':
    main()
