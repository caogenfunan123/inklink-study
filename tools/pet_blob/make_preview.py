#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""小样审查：4张粉 blob 帧 贴 LCD 深底 4x 放大拼图 + 契约指标自检。"""
import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
PREV = os.path.join(HERE, "preview")

BG = (0x26, 0x32, 0x38)      # drawRetroBackdrop 底色
GROUND = (0x37, 0x47, 0x4F)  # 地面
GROUND_Y = 0.68              # 地面高度占比（引擎同款）

files = ["s_idle.png", "s_happy.png", "s_sleep.png", "s_blink.png"]
imgs = []
report = []
for name in files:
    im = Image.open(os.path.join(PREV, name)).convert("RGBA")
    if im.size != (96, 96):
        report.append(f"{name}: 尺寸异常 {im.size}")
        continue
    # alpha 二值化自检
    a = im.split()[3]
    half = sum(1 for v in a.getdata() if v not in (0, 255))
    if half:
        report.append(f"{name}: 半透明像素 {half} 个（需二值化）")
        a = a.point(lambda x: 255 if x >= 128 else 0)
        im.putalpha(a)
    # 中心 5x5 opaque
    center = any(a.getpixel((x, y)) for x in range(46, 51) for y in range(46, 51))
    if not center:
        report.append(f"{name}: 中心 5x5 空洞（锚点没压住）")
    # 质心（最大连通域，粗算用全掩膜）
    mask = a.tobytes()
    sx = sy = m = 0
    for y in range(96):
        row = mask[y * 96:(y + 1) * 96]
        for x in range(96):
            if row[x]:
                sx += x; sy += y; m += 1
    if m:
        report.append(f"{name}: 全图质心=({sx / m:.1f},{sy / m:.1f}) 面积={m}")
    imgs.append(im)

CELL = 384  # 96*4
w = CELL * len(imgs)
h = CELL
sheet = Image.new("RGBA", (w, h), BG + (255,))
ground = Image.new("RGBA", (w, int(h * (1 - GROUND_Y)) + CELL // 6), GROUND + (255,))
sheet.paste(ground, (0, int(h * GROUND_Y) - CELL // 6))
for i, im in enumerate(imgs):
    sheet.paste(im.resize((CELL, CELL), Image.NEAREST), (i * CELL, 0), im.resize((CELL, CELL), Image.NEAREST))
sheet.save(os.path.join(HERE, "preview_sheet.png"))

print("\n".join(report) if report else "契约自检通过")
print("preview_sheet.png written")
