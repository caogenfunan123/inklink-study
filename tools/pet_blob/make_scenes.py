#!/usr/bin/env python3
"""InkLink 云朵宠 · 场景底图 v250 (250x250 LCD 像素风室内场景)。

规则:
  - 250x250 RGBA, 内容是整屏房间(侧视), 画面下缘 ~0.9h(226px) 为地面线,
    宠物(96 raw 帧) 将站在 (125, 226) 附近, 道具沿地面线与家具布置。
  - 像素块风格: 几何色块 + 少量 2px 提亮, LCD 配色。
"""
from PIL import Image, ImageDraw

W = 250
FLOOR = 226          # 墙 / 地面分界线(宠物脚线)
BG = (0x26, 0x32, 0x38)
WALL = (0x2B, 0x39, 0x43)
WALL_T = (0x33, 0x44, 0x50)
FLOOR_T = (0x2E, 0x3C, 0x46)
FLOOR_L = (0x3D, 0x4E, 0x58)
GRID = (0x22, 0x2C, 0x34)
C = {
    'pink': (0xF6, 0xAF, 0xC3), 'pink_h': (0xFF, 0xD7, 0xE3), 'pink_d': (0xD9, 0x8F, 0xA3),
    'white': (0xEC, 0xEF, 0xF1), 'cream': (0xFF, 0xEA, 0xB8), 'sheet': (0xE8, 0xEA, 0xF6),
    'lav': (0x9F, 0xA8, 0xDA), 'lav_d': (0x6C, 0x74, 0xA3), 'per': (0x79, 0x86, 0xCB),
    'per_d': (0x56, 0x63, 0xA8), 'wood': (0x8D, 0x6E, 0x63), 'wood_d': (0x6D, 0x53, 0x4A),
    'teal': (0x4F, 0xC3, 0xF7), 'teal_l': (0x81, 0xD4, 0xFA), 'sky': (0x7E, 0xBB, 0xE8),
    'sky_l': (0xA9, 0xD6, 0xF1), 'sun': (0xFF, 0xCC, 0x66), 'green': (0x8B, 0xC3, 0x4A),
    'green_d': (0x68, 0x9F, 0x38), 'amber': (0xFF, 0xB7, 0x4D), 'dark': (0x18, 0x22, 0x28),
    'rug': (0x4E, 0x3B, 0x45), 'rug_l': (0x6A, 0x50, 0x5C), 'gray': (0x78, 0x90, 0x9C),
    'red': (0xE5, 0x39, 0x35), 'orange': (0xFF, 0x8A, 0x80), 'grass': (0x5D, 0x86, 0x3A),
}


def base(d):
    d.rectangle([0, 0, W, FLOOR], fill=WALL)
    # 墙砖细缝
    for y in range(8, FLOOR, 40):
        d.line([0, y, W, y], fill=WALL_T, width=1)
    for x in range(0, W, 50):
        d.line([x, 0, x, FLOOR], fill=WALL_T, width=1)
    # 踢脚线
    d.rectangle([0, FLOOR - 6, W, FLOOR], fill=(0x3A, 0x4B, 0x57))
    # 地面
    d.rectangle([0, FLOOR, W, W], fill=FLOOR_L)
    for y in range(FLOOR + 6, W, 8):
        d.line([0, y, W, y], fill=GRID, width=1)
    for x in range(0, W, 12):
        for y in range(FLOOR + 6, W, 8):
            if ((x + y) // 12) % 2 == 0:
                d.point((x + 3, y + 2), fill=(0x45, 0x56, 0x60))


def rug(d, x0, x1, y0, y1):
    d.rectangle([x0, y0, x1, y1], fill=C['rug'])
    d.rectangle([x0 + 6, y0 + 4, x1 - 6, y1 - 4], fill=C['rug_l'])
    d.line([(x0 + x1) // 2, y0, (x0 + x1) // 2, y1], fill=C['rug'], width=2)


# ---------- 卧室 ----------
def scene_bedroom():
    im = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    base(d)
    # 大窗 (上中, 星空夜)
    d.rectangle([96, 26, 214, 122], fill=(0x12, 0x2A, 0x42))
    d.rectangle([99, 29, 211, 119], fill=(0x1B, 0x3A, 0x5A))
    d.line([155, 29, 155, 119], fill=(0x0E, 0x20, 0x33), width=3)
    d.line([99, 74, 211, 74], fill=(0x0E, 0x20, 0x33), width=3)
    for sx, sy in [(108, 40), (176, 52), (190, 96), (120, 100), (168, 34)]:
        d.point((sx, sy), fill=C['cream'])
    # 月亮
    d.ellipse([170, 36, 194, 60], fill=C['cream'])
    d.ellipse([177, 36, 198, 58], fill=(0x1B, 0x3A, 0x5A))
    # 窗台
    d.rectangle([92, 122, 218, 132], fill=C['wood'])
    d.rectangle([92, 132, 218, 135], fill=C['wood_d'])
    # 床 (左, 窄单床, 让出中间宠物站位 89-160)
    d.rectangle([10, 152, 86, 226], fill=C['wood_d'])
    d.rectangle([10, 152, 86, 158], fill=C['wood'])
    d.rectangle([14, 158, 82, 226], fill=C['per_d'])
    d.rectangle([14, 158, 40, 190], fill=C['sheet'])       # 枕头
    d.rectangle([16, 162, 30, 186], fill=C['lav'])
    d.rectangle([46, 168, 78, 180], fill=C['lav'])          # 被面图案
    d.rectangle([46, 184, 74, 196], fill=C['lav'])
    for bx in range(52, 80, 16):
        d.rectangle([bx, 214, bx + 8, 220], fill=C['lav'])
    # 床腿
    d.rectangle([10, 226, 16, 234], fill=C['dark'])
    d.rectangle([80, 226, 86, 234], fill=C['dark'])
    rug(d, 92, 186, 232, 250)
    # 夜灯 (右上角地面)
    d.rectangle([212, 180, 232, 208], fill=C['lav_d'])
    d.ellipse([206, 168, 238, 190], fill=C['amber'])
    d.ellipse([214, 174, 230, 186], fill=C['cream'])
    d.rectangle([222, 208, 226, 226], fill=C['dark'])
    # 挂饰(左墙上)
    d.rectangle([30, 40, 66, 88], fill=(0x45, 0x55, 0x63))
    d.ellipse([40, 54, 56, 70], fill=C['pink'])
    d.rectangle([30, 86, 66, 92], fill=C['wood_d'])
    return im


# ---------- 客厅 ----------
def scene_living():
    im = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    base(d)
    # 挂画 1(居中, 云朵)
    d.rectangle([84, 34, 164, 116], fill=(0x45, 0x55, 0x63))
    d.rectangle([88, 38, 160, 112], fill=(0xE3, 0xCE, 0xD6))
    d.rectangle([88, 86, 160, 112], fill=(0x78, 0x8E, 0x9C))
    d.ellipse([108, 52, 140, 72], fill=C['white'])
    d.ellipse([116, 44, 136, 60], fill=C['white'])
    d.ellipse([124, 58, 152, 78], fill=C['white'])
    d.ellipse([102, 60, 122, 78], fill=C['white'])
    # 挂画 2(时钟, 左)
    d.rectangle([14, 36, 58, 80], fill=(0x45, 0x55, 0x63))
    d.ellipse([20, 42, 52, 74], fill=C['cream'])
    d.line([36, 46, 36, 58], fill=C['dark'], width=2)
    d.line([36, 58, 44, 62], fill=C['dark'], width=2)
    # 柜子(左后景)
    d.rectangle([10, 158, 92, 226], fill=C['wood'])
    d.rectangle([10, 158, 92, 164], fill=C['wood_d'])
    d.line([10, 186, 92, 186], fill=C['wood_d'], width=2)
    # 柜上盆栽
    d.rectangle([48, 148, 68, 162], fill=C['lav_d'])
    d.ellipse([42, 130, 74, 152], fill=C['green'])
    d.ellipse([52, 122, 66, 136], fill=C['green_d'])
    # 沙发(右侧后景, 右移避开宠物)
    d.rectangle([164, 150, 246, 226], fill=C['per_d'])
    d.rectangle([164, 150, 246, 158], fill=C['wood'])
    d.rectangle([166, 158, 244, 174], fill=C['lav'])       # 靠背
    d.rectangle([168, 174, 242, 218], fill=C['per'])       # 坐垫
    for x in range(176, 236, 16):
        d.line([x, 176, x, 212], fill=C['per_d'], width=2)
    d.rectangle([160, 172, 166, 222], fill=C['lav'])       # 扶手左
    d.rectangle([244, 172, 250, 222], fill=C['lav'])       # 扶手右
    d.rectangle([168, 218, 176, 226], fill=C['dark'])
    d.rectangle([234, 218, 242, 226], fill=C['dark'])
    # 茶几(右前地)
    d.rectangle([182, 230, 240, 246], fill=C['wood'])
    d.rectangle([186, 222, 236, 230], fill=C['wood_d'])
    d.ellipse([196, 216, 224, 228], fill=C['amber'])
    # 地毯
    rug(d, 62, 178, 230, 250)
    # 落地灯(左前)
    d.rectangle([26, 196, 30, 226], fill=C['dark'])
    d.ellipse([14, 184, 42, 200], fill=C['amber'])
    return im


# ---------- 窗台(书房, 白天) ----------
def scene_window():
    im = Image.new('RGBA', (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    base(d)
    # 大窗(几乎全宽)
    d.rectangle([0, 0, 250, 176], fill=(0x9E, 0xD2, 0xF2))
    d.rectangle([0, 0, 250, 150], fill=C['sky_l'])
    # 太阳
    d.ellipse([196, 26, 228, 58], fill=C['sun'])
    for (x1, y1) in [(196, 42), (228, 42), (212, 26), (212, 58)]:
        d.rectangle([x1 - 3, y1 - 3, x1 + 3, y1 + 3], fill=C['sun'])
    # 远山
    d.polygon([(0, 150), (40, 96), (90, 150)], fill=C['green_d'])
    d.polygon([(60, 150), (120, 82), (180, 150)], fill=C['green'])
    d.polygon([(150, 150), (200, 100), (250, 150)], fill=C['green_d'])
    # 白云
    d.ellipse([40, 60, 96, 84], fill=C['white'])
    d.ellipse([52, 48, 88, 68], fill=C['white'])
    d.ellipse([110, 92, 160, 112], fill=C['white'])
    # 窗棂
    d.line([0, 84, 250, 84], fill=(0x9E, 0xD2, 0xF2), width=4)
    d.line([125, 0, 125, 176], fill=(0x9E, 0xD2, 0xF2), width=4)
    # 窗台
    d.rectangle([0, 176, 250, 194], fill=C['wood'])
    d.rectangle([0, 194, 250, 200], fill=C['wood_d'])
    # 窗台盆栽(左)
    d.rectangle([26, 190, 52, 226], fill=C['lav_d'])
    d.rectangle([30, 226, 48, 232], fill=C['dark'])
    d.ellipse([16, 176, 62, 200], fill=C['green'])
    d.ellipse([28, 164, 50, 182], fill=C['green_d'])
    d.ellipse([38, 178, 54, 190], fill=C['pink_h'])
    # 书柜(右)
    d.rectangle([188, 20, 246, 180], fill=C['wood'])
    d.rectangle([188, 20, 246, 26], fill=C['wood_d'])
    for i, b in enumerate([(C['lav'], 40, 70), (C['pink'], 74, 104), (C['teal_l'], 108, 138), (C['green'], 142, 176)]):
        col, y0, y1 = b
        d.rectangle([192, y0, 242, y0 + 4], fill=C['wood_d'])
        for x in range(196, 240, 12):
            d.rectangle([x, y0 + 6, x + 9, y1 - 2], fill=col)
    # 矮凳(窗台前右, 避开宠物)
    d.rectangle([168, 200, 202, 226], fill=C['per'])
    d.rectangle([172, 226, 182, 234], fill=C['dark'])
    d.rectangle([188, 226, 198, 234], fill=C['dark'])
    rug(d, 66, 160, 230, 250)
    return im


SCENES = {'bedroom': scene_bedroom, 'living': scene_living, 'window': scene_window}

if __name__ == '__main__':
    import os
    os.makedirs('preview', exist_ok=True)
    for k, fn in SCENES.items():
        fn().convert('RGB').save(f'preview/scene250_{k}.png')
        print('scene250', k)
