#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
InkLink-Pet PNG 素材预处理流水线（design 附录A.4 实现）。

输入：assets_draft/<code>_<state>.png   —— AI 文生图草稿（任意尺寸/带背景）
输出：out/<code>_<state>.png            —— 96×96 透明底 PNG-32（alpha 仅 0/255，锚点压 (48,48)）
      out/_preview/<code>.png           —— 该物种全帧联排预览（4× 放大 + 网格 + 槽位标注）
      out/_manifest.json                —— 每张图的处理台账（抠图方式/移除比例/bbox/质心/位移/IoU/告警）

流水线（严格按序，全程 NEAREST，禁任何抗锯齿）：
 1) 抠背景：优先品红色键 #FF00FF（容差内）；覆盖率过低则退回"边界洪水填充"近匀色抠图
 2) 量测身体：alpha 最大连通域 = 身体（道具不参与锚点，否则饭碗会把身体顶离中线）
 3) 物种统一比例：以该物种所有帧中最大的身体为基准，身体目标框 72px（帧间体型不跳变），NEAREST；
    身体质心压 (48,48)；道具越界则先平移再回缩一档，台账记 anchor_error 供人工修
 4) 帧间配准：与同物种 idle 的身体掩膜做互相关（±8px 搜 IOU 最优）；IOU<0.35 视为姿态差异过大，放弃位移并告警
 5) 呼吸派生：缺 idle_a/idle_b 时由 idle 确定性派生（idle_a=原帧；idle_b=身长压扁 1px、脚线不动），台账标 derived
 6) alpha 二值化（阈值 128）→ 存 96×96 RGBA PNG
 7) 自检：画布/二值 alpha/中心 5×5 不透明/相对 idle 身体质心 ≤2px —— 与 CI 的 PetSpriteAssetContractTest 同规则

用法：
  python3 tools/pet_sprite/sprite_pipeline.py                 # 处理全部草稿
  python3 tools/pet_sprite/sprite_pipeline.py --species cat   # 只处理一个物种
"""
import argparse
import glob
import json
import os
import sys
from collections import deque

from PIL import Image

Image.MAX_IMAGE_PIXELS = None

HERE = os.path.dirname(os.path.abspath(__file__))
DRAFT_DIR = os.path.join(HERE, "assets_draft")
OUT_DIR = os.path.join(HERE, "out")
PREVIEW_DIR = os.path.join(OUT_DIR, "_preview")

CANVAS = 96          # 附录A.1 画布
ANCHOR = 48          # 锚点 (48,48)
KEY_COLOR = (255, 0, 255)   # 约定草稿底色：品红
KEY_TOL = 90                 # 欧氏色距容差
FLOOD_TOL = 60               # 边界洪水填充容差
STATE_ORDER = ["idle", "hungry", "happy", "sleep", "blink", "idle_a", "idle_b"]
# 只有这三档在引擎里做呼吸交替（PetSpriteView.breathRes），其余物种的 idle_a/idle_b 是死资源，
# 既不入包也不生成——省 22 个 PNG。
BREATH_SPECIES = ("cat", "dog", "rabbit")
SPECIES = ["cat", "dog", "rabbit", "penguin", "hamster", "panda", "fox",
           "dragon", "sheep", "hedgehog", "frog", "pig", "owl", "snake"]


# ---------------------------------------------------------------- PNG 读写
def load_rgba(path):
    im = Image.open(path)
    im = im.convert("RGBA")  # Pillow 内部处理 16bit/调色板，产物统一 8bit RGBA
    return im


def save_rgba(im, path):
    assert im.size == (CANVAS, CANVAS), im.size
    im.save(path, "PNG", optimize=False)  # 非隔行、8bit RGBA


# ---------------------------------------------------------------- 抠背景
def key_out_background(im):
    """返回 (rgba_image, method, removed_fraction)。"""
    px = im.load()
    w, h = im.size
    n = w * h

    def dist(c1, c2):
        return ((c1[0] - c2[0]) ** 2 + (c1[1] - c2[1]) ** 2 + (c1[2] - c2[2]) ** 2) ** 0.5

    magenta = sum(1 for y in range(h) for x in range(w) if dist(px[x, y][:3], KEY_COLOR) <= KEY_TOL)
    if magenta / n >= 0.05:  # 品红铺满边缘，认定为色键底
        out = im.copy()
        o = out.load()
        removed = 0
        for y in range(h):
            for x in range(w):
                if dist(o[x, y][:3], KEY_COLOR) <= KEY_TOL:
                    o[x, y] = (0, 0, 0, 0)
                    removed += 1
        return out, "magenta_key", removed / n

    # 退回：从四条边采样均值作背景色，4-邻域洪水填充（保护内部同色像素，如白肚子）
    samples = [px[x, 0][:3] for x in range(w)] + [px[x, h - 1][:3] for x in range(w)] + \
              [px[0, y][:3] for y in range(h)] + [px[w - 1, y][:3] for y in range(h)]
    bg = tuple(sum(c[i] for c in samples) // len(samples) for i in range(3))
    out = im.copy()
    o = out.load()
    seen = bytearray(n)
    q = deque()
    for x in range(w):
        q.append((x, 0)); q.append((x, h - 1))
    for y in range(h):
        q.append((0, y)); q.append((w - 1, y))
    removed = 0
    while q:
        x, y = q.popleft()
        if x < 0 or y < 0 or x >= w or y >= h:
            continue
        idx = y * w + x
        if seen[idx]:
            continue
        seen[idx] = 1
        if o[x, y][3] == 0 or dist(o[x, y][:3], bg) > FLOOD_TOL:
            continue
        o[x, y] = (0, 0, 0, 0)
        removed += 1
        q.extend(((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))
    return out, "border_flood(bg=%s)" % (bg,), removed / n


# ---------------------------------------------------------------- 掩膜/几何
def mask_of(im):
    """bytes 掩膜：1=不透明。"""
    a = im.split()[3].tobytes()
    return bytes(1 if v >= 128 else 0 for v in a)


def bbox_of(mask, w, h):
    xs = [x for i, v in enumerate(mask) if v for x in [i % w]]
    ys = [i // w for i, v in enumerate(mask) if v]
    if not xs:
        return None
    return min(xs), min(ys), max(xs), max(ys)


def centroid_of(mask, w, h):
    sx = sy = m = 0
    for i, v in enumerate(mask):
        if v:
            sx += i % w; sy += i // w; m += 1
    if not m:
        return None
    return sx / m, sy / m


def largest_cc(mask, w, h):
    """4-邻域最大连通域（=身体）。道具（饭碗/音符/星星）是独立连通块，
    不能参与锚点计算，否则会把身体推离 (48,48)——附录A.1 锚点契约的核心陷阱。"""
    seen = bytearray(w * h)
    best = None
    best_n = 0
    for start in range(w * h):
        if mask[start] and not seen[start]:
            seen[start] = 1
            q = deque([start])
            comp = []
            while q:
                i = q.popleft()
                comp.append(i)
                x, y = i % w, i // w
                for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                    if 0 <= nx < w and 0 <= ny < h:
                        j = ny * w + nx
                        if mask[j] and not seen[j]:
                            seen[j] = 1
                            q.append(j)
            if len(comp) > best_n:
                best_n, best = len(comp), comp
    if not best:
        return None, None, None
    sx = sum(i % w for i in best); sy = sum(i // w for i in best)
    cen = (sx / best_n, sy / best_n)
    xs = [i % w for i in best]; ys = [i // w for i in best]
    bb = (min(xs), min(ys), max(xs), max(ys))
    return cen, bb, set(best)


def body_centroid(im):
    """素材的"身体质心"：先取最大连通域；仅一块时等价于全图质心。"""
    w, h = im.size
    m = mask_of(im)
    cen, bb, _ = largest_cc(m, w, h)
    return cen, bb, bbox_of(m, w, h)


BODY_SAFE = 80       # 身体（最大连通域）目标框：程序化画师按 20 格(80px) 出图，此值让流水线落在 1.0 → 免重采样
SCALE_MAX = 2.0      # 允许的最大 NEAREST 放大倍率（草稿太小时）


def measure_keyed(keyed):
    """缩放量测：返回 (full_bb, body_cen, body_bb)，坐标在原图像素系。"""
    w, h = keyed.size
    m = mask_of(keyed)
    full = bbox_of(m, w, h)
    if full is None:
        raise ValueError("抠图后整幅全透明")
    cen, bb, _ = largest_cc(m, w, h)
    return full, cen, bb


def fit_limit(full, body_cen):
    """解析上限：在该缩放下，身体质心能精确落在 (48,48) 且整图不被裁掉。
    （替代早期"先平移再缩档"的迭代法——平移会牺牲锚点，正是附录A.6 禁止的补丁思路）"""
    x0, y0, x1, y1 = full
    W, H = x1 - x0 + 1, y1 - y0 + 1
    bx = body_cen[0] - x0      # 身体质心在 bbox 内的局部坐标
    by = body_cen[1] - y0
    lim = [SCALE_MAX]
    for v, room in ((bx, ANCHOR), (by, ANCHOR), (W - bx, CANVAS - 1 - ANCHOR), (H - by, CANVAS - 1 - ANCHOR)):
        lim.append(v / room if v > 1e-6 else 1e9)
    return min(lim)


def place_scaled(keyed, full, body_cen, scale):
    """按全物种统一 scale 邻近缩放，身体质心精确压 (48,48)，不裁切。

    scale 接近 1.0 时强制取 1.0：像素画的 4px 块一旦被 0.96/1.03 之类的倍率重采样，
    块宽会变成 3/4 像素混排（显脏）。整数平移则是无损的，永远允许。
    """
    x0, y0, x1, y1 = full
    if 0.95 <= scale <= 1.05:
        # 试探恒等缩放是否仍可容纳全图，可行就用它
        s1w, s1h = x1 - x0 + 1, y1 - y0 + 1
        q = 1.0
        ax = int(round(ANCHOR - (body_cen[0] - x0) * q))
        ay = int(round(ANCHOR - (body_cen[1] - y0) * q))
        if ax >= 0 and ay >= 0 and ax + s1w <= CANVAS and ay + s1h <= CANVAS:
            scale = q
    crop = keyed.crop(full)
    nw, nh = max(1, int(round((x1 - x0 + 1) * scale))), max(1, int(round((y1 - y0 + 1) * scale)))
    body = crop.resize((nw, nh), Image.NEAREST)
    px = int(round(ANCHOR - (body_cen[0] - x0) * scale))
    py = int(round(ANCHOR - (body_cen[1] - y0) * scale))
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.paste(body, (px, py), body)
    final_cen, _b, _f = body_centroid(canvas)
    off = 0.0 if not final_cen else round(max(abs(final_cen[0] - ANCHOR), abs(final_cen[1] - ANCHOR)), 2)
    return canvas, {"scale": round(scale, 4), "draw_xy": [px, py], "anchor_error": off}




def place_passthrough(keyed, full, body_cen):
    """自有 LUT 素材的直通路径：**分层**放置——身体做无损整数平移回 (48,48)，道具保持草稿绝对坐标。

    为什么分层：草稿里饭碗贴左墙、音符贴右墙，而整图平移量由身体质心决定；一旦"连着道具一起
    挪"就会把道具推出画布（实测 snake/hungry 需 py=-3 被裁）。缩放则始终禁止——4px 块被 0.24
    这类倍率重采样会把 80px 的身体压成 18px 且碎片化（外部素材才需要 --adapt 兜底）。
    """
    w, h = keyed.size
    m = mask_of(keyed)
    cen, bb, body_idx = largest_cc(m, w, h)
    if not cen:
        raise ValueError("抠图后整幅全透明")
    ab = keyed.split()[3].tobytes()
    bm = bytearray(w * h)
    for i in body_idx:
        bm[i] = 1
    body_a = bytes(bm[i] * ab[i] for i in range(w * h))
    prop_a = bytes((1 - bm[i]) * ab[i] for i in range(w * h))
    body = keyed.copy(); body.putalpha(Image.frombytes("L", (w, h), body_a))
    props = keyed.copy(); props.putalpha(Image.frombytes("L", (w, h), prop_a))
    dx = int(round(ANCHOR - cen[0]))
    dy = int(round(ANCHOR - cen[1]))
    bx0, by0, bx1, by1 = bb
    if bx0 + dx < 0 or by0 + dy < 0 or bx1 + dx >= CANVAS or by1 + dy >= CANVAS:
        raise ValueError("身体平移到 (%d,%d) 后会出画（身体框 %s）→ 设计没把身体留在画布中部，"
                         "请在画师侧回中" % (dx, dy, bb))
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.paste(body, (dx, dy), body)
    canvas = Image.alpha_composite(canvas, props)
    final_cen, _b, _f = body_centroid(canvas)
    off = 0.0 if not final_cen else round(max(abs(final_cen[0] - ANCHOR), abs(final_cen[1] - ANCHOR)), 2)
    return canvas, {"scale": 1.0, "draw_xy": [dx, dy], "anchor_error": off,
                    "prop_px": sum(1 for i in range(w * h) if m[i] and not bm[i])}


def align_to(ref_canvas, canvas, max_shift=8):
    """±max_shift 搜"身体 IOU"最优位移（只看最大连通域，道具跟身走）。"""
    rw, rh = ref_canvas.size
    refm = _body_mask(ref_canvas, rw, rh)
    cmask = _body_mask(canvas, rw, rh)
    if not any(cmask):
        return canvas, {"iou": 0.0, "shift": [0, 0], "skipped": "empty"}
    best = (-1.0, 0, 0)
    for dy in range(-max_shift, max_shift + 1):
        for dx in range(-max_shift, max_shift + 1):
            inter = union = 0
            for y in range(rh):
                sy = y - dy
                if sy < 0 or sy >= rh:
                    continue
                row_ref = y * rw
                row_c = sy * rw
                for x in range(rw):
                    sx = x - dx
                    if sx < 0 or sx >= rw:
                        continue
                    a = refm[row_ref + x]; b = cmask[row_c + sx]
                    if a and b:
                        inter += 1; union += 1
                    elif a or b:
                        union += 1
            iou = inter / union if union else 0.0
            if iou > best[0]:
                best = (iou, dx, dy)
    iou, dx, dy = best
    if iou < 0.35:  # 姿态差异过大（如蜷睡帧），强行配准反而破坏接地
        return canvas, {"iou": round(iou, 3), "shift": [0, 0], "skipped": "pose_too_different"}
    if dx == 0 and dy == 0:
        return canvas, {"iou": round(iou, 3), "shift": [0, 0]}
    shifted = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    shifted.paste(canvas, (dx, dy), canvas)
    return shifted, {"iou": round(iou, 3), "shift": [dx, dy]}


def _body_mask(im, w, h):
    """掩膜只保留最大连通域（身体）。"""
    m = mask_of(im)
    _cen, _bb, comp = largest_cc(m, w, h)
    if not comp:
        return m
    out = bytearray(w * h)
    for i in comp:
        out[i] = 1
    return bytes(out)


def binarize_alpha(im):
    r, g, b, a = im.split()
    a = a.point(lambda v: 255 if v >= 128 else 0)
    return Image.merge("RGBA", (r, g, b, a))


def derive_breath(idle):
    """idle_a=原帧；idle_b=身高压扁 1px 且脚线不动（像素风呼吸的标准做法）。"""
    a = idle.copy()
    m = mask_of(idle)
    bb = bbox_of(m, CANVAS, CANVAS)
    x0, y0, x1, y1 = bb
    h = y1 - y0 + 1
    if h < 6:
        return a, idle.copy()
    region = idle.crop((x0, y0, x1 + 1, y1 + 1))
    sq = region.resize((region.width, max(1, h - 1)), Image.NEAREST)
    b = idle.copy()
    b.paste(Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0)), (0, 0))
    base = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    base.paste(b, (0, 0), b)  # 保留 idle 全图用于回填非身体区（耳朵/尾巴保持）
    b2 = idle.copy()
    b2.paste(Image.new("RGBA", (x1 - x0 + 1, h), (0, 0, 0, 0)), (x0, y0))  # 挖掉身体
    b2.paste(sq, (x0, y1 + 1 - sq.height), sq)                              # 脚线对齐重贴
    return a, b2


# ---------------------------------------------------------------- 自检
def self_check(path, ref_path=None):
    im = load_rgba(path)
    errs = []
    if im.size != (CANVAS, CANVAS):
        errs.append("画布 %s" % (im.size,))
    a = im.split()[3].tobytes()
    bad = sum(1 for v in a if v not in (0, 255))
    if bad:
        errs.append("半透明像素 %d" % bad)
    m = mask_of(im)
    px = im.load()
    center = any(px[x, y][3] > 0 for x in range(46, 51) for y in range(46, 51))
    if not center:
        errs.append("中心5×5全透明(身体未压住锚点)")
    cen, _bb, _full = body_centroid(im)   # 质心一律按"最大连通域=身体"计，道具不参与
    if cen is None:
        errs.append("全透明素材")
    if ref_path and os.path.exists(ref_path) and cen:
        rc, _, _ = body_centroid(load_rgba(ref_path))
        if rc and (abs(cen[0] - rc[0]) > 2.0 or abs(cen[1] - rc[1]) > 2.0):
            errs.append("身体质心漂移 idle=%s now=%s" % (tuple(round(v, 1) for v in rc), tuple(round(v, 1) for v in cen)))
    return errs, (tuple(round(v, 2) for v in cen) if cen else None)


# ---------------------------------------------------------------- 预览
def build_preview(code, frames, path):
    items = [(s, frames[s]) for s in STATE_ORDER if s in frames]
    if not items:
        return
    z, pad, label = 4, 8, 14
    W = len(items) * (CANVAS * z + pad) + pad
    H = CANVAS * z + pad * 2 + label
    sheet = Image.new("RGBA", (W, H), (24, 26, 33, 255))
    sp = sheet.load()
    # 品红底提示"这是待终检半成品"
    for i, (state, im) in enumerate(items):
        ox = pad + i * (CANVAS * z + pad)
        oy = pad
        big = im.resize((CANVAS * z, CANVAS * z), Image.NEAREST)
        big = big.convert("RGBA")
        bg = Image.new("RGBA", big.size, (60, 10, 60, 255))
        bg.paste(big, (0, 0), big)
        sheet.paste(bg, (ox, oy))
        # 锚点十字 (48,48)*z
        ax, ay = ox + ANCHOR * z, oy + ANCHOR * z
        for t in range(-z, z + 1):
            for xx, yy in ((ax + t, ay), (ax, ay + t)):
                if 0 <= xx < W and 0 <= yy < oy + CANVAS * z:
                    px = sp[xx, yy]
                    sp[xx, yy] = (255, 235, 59, 255) if px[3] < 200 else px
        sheet.paste(text_tile(state), (ox, oy + CANVAS * z + 2))
    sheet.save(path)


_tiles = {}


def text_tile(text):
    """无字体依赖：用 PIL ImageDraw 默认字体画标签。"""
    key = text
    if key not in _tiles:
        from PIL import ImageDraw
        im = Image.new("RGBA", (CANVAS * 4, 14), (24, 26, 33, 0))
        d = ImageDraw.Draw(im)
        d.text((2, 1), text, fill=(230, 230, 230, 255))
        _tiles[key] = im
    return _tiles[key]


# ---------------------------------------------------------------- 点货
def core_states(code):
    """附录A 契约：每物种 4 张核心帧；dog/cat/rabbit 额外 1 张 blink（idle_a/idle_b 可由脚本派生）。"""
    states = ["idle", "hungry", "happy", "sleep"]
    if code in ("cat", "dog", "rabbit"):
        states.append("blink")
    return states


def cmd_expect(want_species):
    """点货：列出应投料文件与当前缺失/越界情况，供投料方自查。"""
    present = {}
    if os.path.isdir(DRAFT_DIR):
        for fn in sorted(os.listdir(DRAFT_DIR)):
            if not fn.endswith(".png"):
                continue
            stem = fn[:-4]
            st = max((s for s in STATE_ORDER if stem.endswith("_" + s)), key=len, default=None)
            code = stem[: -(len(st) + 1)] if st else None
            present.setdefault(code, set()).add(st) if st else present.setdefault("__bad__", set()).add(fn)
    total_need = 0
    print("草稿目录：%s" % DRAFT_DIR)
    for code in SPECIES:
        if want_species and code != want_species:
            continue
        need = core_states(code)
        got = sorted(present.get(code, set()))
        extra = [s for s in got if s not in need]
        missing = [s for s in need if s not in got]
        total_need += len(need)
        flag = "OK " if not missing else ("%3d张" % len(got))
        print("  [%s] %-9s 需%-2d 实%-2d  %s" % (flag, code, len(need), len(got),
              "齐" if not missing else "缺 " + " ".join(missing)))
        if extra:
            print("        另有（可覆盖派生呼吸帧）：%s" % " ".join(extra))
    if present.get("__bad__"):
        print("  ⚠️ 命名不合规（会被跳过）：%s" % " ".join(sorted(present["__bad__"])))
    print("合计应投 %d 张（idle_a/idle_b 不投，由脚本从 idle 派生）；已投 %d 张。" %
          (total_need, sum(len(v) for k, v in present.items() if k != "__bad__")))
    return 0


# ---------------------------------------------------------------- 主流程
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--species", default=None, help="只处理指定物种 code")
    ap.add_argument("--no-align", action="store_true", help="跳过帧间互相关（调试）")
    ap.add_argument("--expect", action="store_true", help="只点货：列出应投/缺失草稿，不做任何加工")
    ap.add_argument("--adapt", action="store_true",
                    help="外部素材兜底路径：允许统一比例缩放（自有 LUT 画师产物不需要，默认关闭走无损直通）")
    args = ap.parse_args()

    if args.expect:
        return cmd_expect(args.species)

    os.makedirs(OUT_DIR, exist_ok=True)
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    # 起手清空产物：上一轮跑过、这一轮物种/状态被删掉的文件若留着，会作为陈旧素材被拷进 APK
    for stale in glob.glob(os.path.join(OUT_DIR, "*.png")):
        os.remove(stale)

    drafts = sorted(f for f in os.listdir(DRAFT_DIR) if f.endswith(".png")) if os.path.isdir(DRAFT_DIR) else []
    entries = []
    for fn in drafts:
        stem = fn[:-4]
        # 状态后缀最长匹配：rpartition('_') 会把 cat_idle_b 错切成 code=cat_idle/state=b
        state = max((s for s in STATE_ORDER if stem.endswith("_" + s)), key=len, default=None)
        code = stem[: -(len(state) + 1)] if state else None
        if code not in SPECIES or state not in STATE_ORDER:
            print("跳过（命名不在附录A.2 闭集）：%s" % fn)
            continue
        if args.species and code != args.species:
            continue
        entries.append((code, state, os.path.join(DRAFT_DIR, fn)))

    if not entries:
        print("assets_draft 下没有可用草稿（阶段4 待投料）。")
        return 0

    by_species = {}
    for code, state, path in entries:
        by_species.setdefault(code, {})[state] = path

    report = []
    entries_in = len(entries)
    for code, states in sorted(by_species.items()):
        prepared = {}
        warn = []
        # 1) 抠图 + 量测（缩放必须全物种统一，故先只量身体）
        keyed_frames = {}
        for state in STATE_ORDER:
            if state not in states:
                continue
            try:
                im = load_rgba(states[state])
                keyed, method, frac = key_out_background(im)
                full, bcen, bbb = measure_keyed(keyed)
            except ValueError as e:
                warn.append("%s/%s: %s" % (code, state, e))
                continue
            keyed_frames[state] = (keyed, full, bcen, bbb, method, frac)
        if not keyed_frames:
            continue
        # 2/3) 定位：直通路径（默认）只做整数平移；--adapt 才允许全物种统一比例缩放
        if args.adapt:
            max_extent = max(max(m[3][2] - m[3][0], m[3][3] - m[3][1]) for m in keyed_frames.values())
            scale = min(BODY_SAFE / max(max_extent, 1),
                        min(fit_limit(m[1], m[2]) for m in keyed_frames.values()))
        for state, (keyed, full, bcen, bbb, method, frac) in keyed_frames.items():
            try:
                canvas, info = (place_scaled(keyed, full, bcen, scale) if args.adapt
                                else place_passthrough(keyed, full, bcen))
            except ValueError as e:
                warn.append("%s/%s: %s" % (code, state, e))
                continue
            canvas = binarize_alpha(canvas)
            prepared[state] = (canvas, {"method": "passthrough" if not args.adapt else method,
                                        "removed": round(frac, 3), **info})
            if info["anchor_error"] > 0.9:   # 整数取整本身有 ±0.5px 噪声，阈值放宽免制造噪音
                warn.append("%s/%s: 锚点偏差 %.2fpx → 取整误差偏大，需人工核对" %
                            (code, state, info["anchor_error"]))
        if "idle" not in prepared:
            warn.append("%s: 缺 idle 基准帧，其余帧不做配准" % code)
        # 4) 帧间配准（只在外部素材上搜索位移；自有素材身体本就同格，改成"零漂移"硬校验）
        if not args.adapt:
            if "idle" in prepared:
                ref_cen, _, _ = body_centroid(prepared["idle"][0])
                for state in STATE_ORDER:
                    if state == "idle" or state not in prepared:
                        continue
                    cen, _, _ = body_centroid(prepared[state][0])
                    if not cen or not ref_cen:
                        continue
                    drift = max(abs(cen[0] - ref_cen[0]), abs(cen[1] - ref_cen[1]))
                    # 阈值取 1.0：每帧各自整数取整回中，本身带 ±0.5px，两帧叠加即 ±1px；
                    # 跨状态是瞬时切帧、不做补间，1px 不可见。再大就是真的造型没同格起画。
                    if drift > 1.0:
                        warn.append("%s/%s: 身体相对 idle 漂移 %.2fpx（道具不计入），"
                                    "造型必须同格起画" % (code, state, drift))
        elif not args.no_align and "idle" in prepared:
            ref = prepared["idle"][0]
            for state in STATE_ORDER:
                if state == "idle" or state not in prepared:
                    continue
                moved, ainfo = align_to(ref, prepared[state][0])
                prepared[state] = (moved, {**prepared[state][1], "align": ainfo})
        # 5) 呼吸派生（仅 cat/dog/rabbit；缺帧才派生）
        if "idle" in prepared and code in BREATH_SPECIES:
            if "idle_a" not in prepared or "idle_b" not in prepared:
                a, b = derive_breath(prepared["idle"][0])
                if "idle_a" not in prepared:
                    prepared["idle_a"] = (a, {"derived": "breath_from_idle"})
                if "idle_b" not in prepared:
                    prepared["idle_b"] = (b, {"derived": "breath_squash_1px"})
        # 6) 落盘 + 7) 自检
        frames_out = {}
        for state, (img, meta) in sorted(prepared.items(), key=lambda kv: STATE_ORDER.index(kv[0])):
            out_path = os.path.join(OUT_DIR, "%s_%s.png" % (code, state))
            save_rgba(img, out_path)
            errs, cen = self_check(out_path, os.path.join(OUT_DIR, "%s_idle.png" % code) if state != "idle" else None)
            frames_out[state] = img
            report.append({"file": os.path.basename(out_path), "centroid": cen,
                           "warnings": errs, **meta})
            if errs:
                warn.append("%s/%s: %s" % (code, state, "; ".join(errs)))
        build_preview(code, frames_out, os.path.join(PREVIEW_DIR, "%s.png" % code))

    # 收尾断言：产出集合必须精确等于契约要求（多一张=陈旧素材入包，少一张=引擎侧 drawable 找不到）
    want = set()
    for c in sorted(by_species):
        want |= set("%s_%s" % (c, st) for st in core_states(c))
        if c in BREATH_SPECIES:
            want |= {"%s_idle_a" % c, "%s_idle_b" % c}
    have = set(r["file"][:-4] for r in report)
    if want != have:
        warn.append("产出集合不合规 缺:%s 多:%s" % (sorted(want - have) or "无", sorted(have - want) or "无"))
    else:
        print("产出集合校验通过：%d 张 = 投料 %d 张 + 派生呼吸帧 %d 张" %
              (len(have), entries_in, len(have) - entries_in))

    with open(os.path.join(OUT_DIR, "_manifest.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)

    print("处理 %d 张 → %s" % (len(report), OUT_DIR))
    for r in report:
        print("  %-18s method=%-9s removed=%-6s align=%s centroid=%s%s" % (
            r["file"], (r.get("method") or r.get("derived", "-"))[:9], r.get("removed", "-"),
            r.get("align", {}).get("shift", "-") if r.get("align") else "-",
            r["centroid"], "  WARN:" + ";".join(r["warnings"]) if r["warnings"] else ""))
    if warn:
        print("\n!!! 需人工修（自有素材改 sprite_author 设计常量；外部素材回 Aseprite 重画）：")
        for w in sorted(set(warn)):
            print("   -", w)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
