#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
素材 ASCII 检视器：把 96×96 成品降采样回 24×24 逻辑网格，用字符画打出来，
并统计连通域（身体 + 分离道具）。用于无视觉能力时严格核对造型与"道具不与身体相邻"契约。

用法：
  python3 tools/pet_sprite/sprite_ascii.py cat_idle owl_idle snake_idle
  python3 tools/pet_sprite/sprite_ascii.py --all-idle
"""
import os
import sys
from collections import deque

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
G = 24
RAMP = " .:-=+*#%@"          # 按颜色在图中的出现频次分配灰阶
LEGAL = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ"


def cells(path):
    im = Image.open(path).convert("RGBA")
    sp = im.load()
    grid = {}
    for gy in range(G):
        for gx in range(G):
            # 取块中心像素（成品是实心 4×4 块，取中心最稳）
            c = sp[gx * 4 + 2, gy * 4 + 2]
            if c[3] >= 128:
                grid[(gx, gy)] = c[:3]
    return grid


def components(grid):
    seen = set()
    comps = []
    for s in grid:
        if s in seen:
            continue
        q = deque([s]); seen.add(s); comp = []
        while q:
            i = q.popleft(); comp.append(i)
            x, y = i
            for j in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if j in grid and j not in seen:
                    seen.add(j); q.append(j)
        comps.append(comp)
    return sorted(comps, key=len, reverse=True)


def show(name):
    path = os.path.join(OUT, name + ".png")
    if not os.path.exists(path):
        print("!! 缺文件 %s" % path); return
    grid = cells(path)
    comps = components(grid)
    colors = {}
    for c in grid.values():
        colors[c] = colors.get(c, 0) + 1
    order = [c for c, _ in sorted(colors.items(), key=lambda kv: -kv[1])]
    legend = {}
    for i, c in enumerate(order):
        legend[c] = LEGAL[i % len(LEGAL)]
    body = set(comps[0]) if comps else set()
    print("\n=== %s  不透明逻辑格=%d  连通域=%d(身体%d格%s) 颜色=%d" % (
        name, len(grid), len(comps), len(body),
        " 道具:" + ",".join(str(len(c)) for c in comps[1:]) if len(comps) > 1 else "", len(colors)))
    print("    图例: " + " ".join("%s=%02x%02x%02x(%d)" % (legend[c], c[0], c[1], c[2], colors[c]) for c in order[:8]))
    print("    +" + "-" * G + "+")
    for y in range(G):
        row = ""
        for x in range(G):
            c = grid.get((x, y))
            if c is None:
                row += " "
            else:
                row += legend[c] if (x, y) in body else "^"   # ^ = 非身体连通域（道具）
        print("    |" + row + "|")
    print("    +" + "-" * G + "+")
    # 锚点核对：身体质心应落在 (12,12) 逻辑格附近
    if body:
        cx = sum(x for x, _ in body) / len(body)
        cy = sum(y for _, y in body) / len(body)
        print("    身体质心(逻辑格)=(%.2f, %.2f)  期望≈(12.0,12.0)  偏差=%.2f 格 = %.1f 设备px"
              % (cx, cy, max(abs(cx - 12), abs(cy - 12)), max(abs(cx - 12), abs(cy - 12)) * 4))


def main():
    args = sys.argv[1:]
    if args == ["--all-idle"]:
        args = sorted(f[:-4] for f in os.listdir(OUT) if f.endswith("_idle.png"))
    elif not args:
        args = ["cat_idle"]
    for a in args:
        show(a if "_" in a else a + "_idle")


if __name__ == "__main__":
    main()
