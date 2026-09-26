#!/usr/bin/env python3
"""dongwu 素材批量转换：白底抠图 → RGBA 透明底 → assets/pixel_arcade/

策略（对 AI 生成的白底渐变图稳健）：
- 背景定义为「8-邻域 flood-fill 从四边起，与种子色差 < BG_TOL」的连通区域，而不是
  全图阈值——白底渐变/角色内部的高光都不会被误抠，只有与背景连续的浅色区域透明。
- 用 HSV 亮度+饱和度判据（纯白背景的 S 很低，角色亮部带色相），进一步降低误抠。
- 输出保持 256×256、RGBA、alpha 仅 0/255（契约二值化）。
- 逐像素 alpha 由 flood mask 决定，不做抗锯齿边缘过渡（符合像素硬边契约）。

用法：convert_arcade.py <src_dir> <out_dir>
"""
import os
import sys
import numpy as np
from PIL import Image

BG_TOL = 26      # flood-fill 与背景颜色的色差上限
SAT_TOL = 60     # 背景种子要求低饱和（防误把彩色物体当背景）
FLOOD_MAX = 500000

def _low_sat(sat, v):
    """背景判据：低饱和度 且 足够亮。纯白底(S≈0)必过；浅绿底(S≈30)也过；彩色角色不过。"""
    return sat < SAT_TOL and v >= 200

def _sat(v, mn):
    return (v - mn) * 255 // max(v, 1)

def flood_mask(img):
    """8-邻域 flood-fill 从四边种子，返回背景 mask（bool HxW）。

    背景 = 与四边角颜色接近的亮色连通域。对白底与浅色渐变底（如 snake 的浅绿）通用。
    种子取四角像素：默认要求低饱和亮色；但当四角颜色彼此高度接近时（饱和度可能较高的
    纯色渐变背景，如 snake 的浅绿 / fish 的浅青 / turtle 的浅绿），按「四角一致」判定为
    背景并放宽饱和度门槛，flood 仍按 RGB 色差 < BG_TOL 且够亮扩展。
    """
    a = np.asarray(img).astype(np.int32)
    H, W, _ = a.shape
    rgb = a[:, :, :3]
    mx = rgb.max(axis=2); mn = rgb.min(axis=2)
    sat = np.where(mx > 0, ((mx - mn).astype(np.float64) / np.maximum(mx, 1)) * 255, 0).astype(np.int32)
    v = mx

    # 四角颜色作为背景种子基准（取最常见的低饱和角）
    corners = [rgb[0, 0], rgb[0, W - 1], rgb[H - 1, 0], rgb[H - 1, W - 1]]
    seeds = [c for c in corners if _low_sat(_sat(int(c.max()), int(c.min())), int(c.max()))]
    relax_sat = False
    if not seeds:
        # 四角彼此高度接近 → 判定为同色背景（即便饱和度高），放宽饱和度门槛
        def _pair_d(c1, c2):
            return int(abs(int(c1[0]) - int(c2[0])) + abs(int(c1[1]) - int(c2[1])) + abs(int(c1[2]) - int(c2[2])))
        if all(int(c.max()) >= 200 for c in corners) and \
           all(_pair_d(corners[i], corners[j]) < BG_TOL * 3 for i in range(4) for j in range(i + 1, 4)):
            seeds = list(corners)
            relax_sat = True
    if not seeds:
        return np.zeros((H, W), dtype=bool)

    mask = np.zeros((H, W), dtype=bool)
    from collections import deque
    q = deque()
    def try_seed(x, y):
        if mask[y, x]: return
        r, g, b = rgb[y, x]
        if v[y, x] < 200: return
        if not relax_sat and not _low_sat(sat[y, x], v[y, x]): return
        # 与任一背景种子色差 < BG_TOL
        for (sr, sg, sb) in seeds:
            if abs(r - sr) + abs(g - sg) + abs(b - sb) < BG_TOL * 3:
                mask[y, x] = True; q.append((x, y)); return
    for x in range(W):
        try_seed(x, 0); try_seed(x, H - 1)
    for y in range(H):
        try_seed(0, y); try_seed(W - 1, y)

    count = 0
    while q and count < FLOOD_MAX:
        x, y = q.popleft(); count += 1
        r0, g0, b0 = rgb[y, x]
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dx == 0 and dy == 0: continue
                nx, ny = x + dx, y + dy
                if nx < 0 or ny < 0 or nx >= W or ny >= H: continue
                if mask[ny, nx]: continue
                r, g, b = rgb[ny, nx]
                if v[ny, nx] < 200: continue
                if not relax_sat and sat[ny, nx] >= SAT_TOL: continue
                # 与已确认背景像素的局部连续性（色差累计量小）
                if abs(r - r0) + abs(g - g0) + abs(b - b0) < BG_TOL * 3:
                    mask[ny, nx] = True; q.append((nx, ny))
    return mask

KEEP_THRESHOLD = 200  # 保留的连通域最小面积（px）：清除白底渐变孤岛噪点，保住分离部件（兔耳/鱼鳍）

def largest_components(a, thr=KEEP_THRESHOLD):
    """返回要保留的前景 mask：面积 >= thr 的 4-邻域连通域（主域必然保留，分离部件>=thr 也保留）。"""
    H, W = a.shape
    mask = a == 255
    seen = np.zeros((H, W), bool)
    keep = np.zeros((H, W), bool)
    from collections import deque
    for y in range(H):
        for x in range(W):
            if mask[y, x] and not seen[y, x]:
                seen[y, x] = True
                q = deque([(x, y)])
                cells = []
                while q:
                    cx, cy = q.popleft()
                    cells.append((cx, cy))
                    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                        nx, ny = cx + dx, cy + dy
                        if 0 <= nx < W and 0 <= ny < H and mask[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = True
                            q.append((nx, ny))
                if len(cells) >= thr:
                    for (cx, cy) in cells:
                        keep[cy, cx] = True
    return keep

def convert_one(src, dst):
    im = Image.open(src).convert("RGB")
    m = flood_mask(im)
    src_rgba = im.convert("RGBA")
    src_a = np.asarray(src_rgba)
    a = np.zeros((im.size[1], im.size[0], 4), dtype=np.uint8)
    a[:, :, :3] = src_a[:, :, :3]
    # 前景 = 非背景 且 属于足够大的连通域（清噪点）
    fg = ~m
    keep = largest_components(fg.astype(np.uint8) * 255) if fg.any() else fg
    a[:, :, 3] = np.where(keep, 255, 0)
    out = Image.fromarray(a, "RGBA")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    out.save(dst, "PNG")
    opaque = int(keep.sum())
    total = m.size
    return opaque, total, im.size

def main():
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    stats = {}
    n = 0
    for sp in sorted(os.listdir(src_dir)):
        d = os.path.join(src_dir, sp)
        if not os.path.isdir(d): continue
        for f in sorted(os.listdir(d)):
            if not f.endswith(".png"): continue
            # pixel_arcade_{pet}_{state}_{frame}.png → {pet}_{state}_{frame}.png
            stem = f[:-4]
            if stem.startswith("pixel_arcade_"):
                stem = stem[len("pixel_arcade_"):]
            dst = os.path.join(out_dir, sp, stem + ".png")
            opaque, total, size = convert_one(os.path.join(d, f), dst)
            stats.setdefault(sp, []).append((f, opaque, total, size))
            n += 1
    print(f"共转换 {n} 张")
    for sp, rows in sorted(stats.items()):
        first = rows[0]
        print(f"{sp:10s} {len(rows):3d}张  前景占比 {first[1]/first[2]*100:.0f}%  {first[3][0]}x{first[3][1]}")

if __name__ == "__main__":
    main()
