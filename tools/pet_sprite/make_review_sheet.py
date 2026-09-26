#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成人工审阅物料：单张长图总览 + 网页（本模型无图像输入能力，观感必须由人判）。

用法：
    python3 tools/pet_sprite/make_review_sheet.py            # 读 drawable-nodpi 成品
    python3 tools/pet_sprite/make_review_sheet.py --draft    # 读 assets_draft 草稿（入库前先看）

产物（都在 tools/pet_sprite/ 下）：
    out/_contact_sheet.png   14 物种 × 7 状态列长图，一眼看全与横向比对帧间漂移
    review.html              同内容的网页版，配合 `python3 -m http.server` 在浏览器里放大看

注意：审阅页用**绝对路径**引用 `app-host/...` 与 `tools/...`，所以必须从**仓库根**起静态服务：
    python3 -m http.server 8931 --bind 0.0.0.0     # 打开 /tools/pet_sprite/review.html
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
FINAL_DIR = os.path.join(ROOT, "app-host", "src", "main", "res", "drawable-nodpi")
DRAFT_DIR = os.path.join(HERE, "assets_draft")
OUT_DIR = os.path.join(HERE, "out")
SHEET = os.path.join(OUT_DIR, "_contact_sheet.png")
PAGE = os.path.join(HERE, "review.html")

SPECIES = ["cat", "dog", "rabbit", "penguin", "hamster", "panda", "fox",
           "dragon", "sheep", "hedgehog", "frog", "pig", "owl", "snake"]
COLS = ["idle", "hungry", "happy", "sleep", "blink", "idle_a", "idle_b"]
# 只有猫/狗/兔有呼吸与眨眼帧（引擎 breathRes 只认这三档），其余物种该列留空格
ZOOM, PAD, RH = 2, 30, 96 * 2 + 8
BG, CELL, TXT, DIM = (38, 50, 56), (55, 71, 79), (220, 230, 235), (160, 180, 190)


def compare_sheet(before_dir, after_dir, path):
    """新旧对照长图：每格左边=旧、右边=新，用于"改了几个常量却全量重跑"时快速判是否可接受。

    为什么需要：外描边这类全局改动会让 65 张全部字节级变化，只看变更清单无法判断观感，
    而没有对照图就要用户凭记忆比较两版长图。
    """
    files = [f for f in sorted(os.listdir(after_dir)) if f.endswith(".png")]
    if not files:
        return 0
    cols = len(COLS) * 2 + 1
    w = PAD + cols * 96 + 8
    h = PAD + 2 + len(SPECIES) * (96 + 8)
    img = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(img)
    d.text((PAD, 6), "左=旧  右=新   列序：" + " / ".join(COLS), fill=TXT)
    d.line([(PAD, 20), (w - PAD, 20)], fill=(90, 110, 120))
    hit = 0
    for r, code in enumerate(SPECIES):
        y = PAD + 2 + r * (96 + 8)
        d.text((2, y + 44), code[:4], fill=DIM)
        for c, st in enumerate(COLS):
            name = "%s_%s.png" % (code, st)
            for k, src in ((0, before_dir), (1, after_dir)):
                pth = os.path.join(src, name)
                x = PAD + (c * 2 + k) * (96 + 4)
                if not os.path.exists(pth):
                    d.rectangle([x, y, x + 95, y + 95], outline=(70, 85, 92))
                    continue
                im = Image.open(pth).convert("RGBA")
                bgc = (55, 71, 79) if k == 0 else (62, 82, 90)   # 右侧略亮，闭眼也能看出是"新"
                bg = Image.new("RGB", (96, 96), bgc)
                bg.paste(im, (0, 0), im)
                img.paste(bg, (x, y))
                hit += 1
        # 行内分隔：状态之间画一条暗竖线，避免 14 格连成一片看错列
        d.line([(PAD + len(COLS) * 2 * (96 + 4), y), (PAD + len(COLS) * 2 * (96 + 4), y + 95)],
               fill=(70, 85, 92))
    img.save(path)
    return hit, img.size


def contact_sheet(src_dir):
    w = PAD + len(COLS) * 96 * ZOOM
    h = PAD + 2 + len(SPECIES) * RH
    sheet = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(sheet)
    d.text((PAD, 8), "PIXEL_PNG 成品总览（96x96 素材，x2 显示）  列序：" + " / ".join(COLS), fill=TXT)
    d.line([(PAD, 22), (w - PAD, 22)], fill=(90, 110, 120))
    hit = 0
    for r, code in enumerate(SPECIES):
        y = PAD + 2 + r * RH
        d.text((4, y + RH // 2 - 4), code[:4], fill=DIM)
        for c, st in enumerate(COLS):
            p = os.path.join(src_dir, "%s_%s.png" % (code, st))
            x = PAD + c * 96 * ZOOM
            if not os.path.exists(p):
                d.rectangle([x, y, x + 96 * ZOOM - 1, y + 96 * ZOOM - 1], outline=(70, 85, 92))
                continue
            im = Image.open(p).convert("RGBA").resize((96 * ZOOM, 96 * ZOOM), Image.NEAREST)
            bg = Image.new("RGB", (96 * ZOOM, 96 * ZOOM), CELL)
            bg.paste(im, (0, 0), im)          # 深底衬一下，透明边不会被误判成"缺图"
            sheet.paste(bg, (x, y))
            hit += 1
    os.makedirs(OUT_DIR, exist_ok=True)
    sheet.save(SHEET)
    return hit, sheet.size


CSS = """body{background:#263238;color:#dce5e8;font:14px/1.5 system-ui;margin:24px}
h1{font-size:18px}h3{margin:18px 0 6px;color:#9fb3bc}
.row{display:flex;gap:10px;flex-wrap:wrap}figure{margin:0;text-align:center}
img{width:96px;height:96px;image-rendering:pixelated;background:#37474f;border-radius:8px}
figcaption{font-size:11px;color:#8fa3ac}.sheet img{width:100%;height:auto;max-width:1000px}
.note{color:#8fa3ac;max-width:820px}"""


def web_page(src_dir):
    rel = "/" + os.path.relpath(src_dir, ROOT).replace(os.sep, "/")
    secs = []
    for code in SPECIES:
        tiles = ['<figure><img src="%s/%s_%s.png"><figcaption>%s</figcaption></figure>' % (rel, code, st, st)
                 for st in COLS if os.path.exists(os.path.join(src_dir, "%s_%s.png" % (code, st)))]
        secs.append('<section><h3>%s</h3><div class="row">%s</div></section>' % (code, "".join(tiles)))
    sheet_rel = "/" + os.path.relpath(SHEET, ROOT).replace(os.sep, "/")
    html = ('<!doctype html><meta charset="utf-8"><title>InkLink PIXEL_PNG 素材审阅</title>'
            '<style>' + CSS + '</style>'
            '<h1>PIXEL_PNG 素材审阅（14 物种 / 最多 65 张）</h1>'
            '<p class="note">契约：96×96 透明底、Alpha 仅 0/255、NEAREST 整数倍放大、身体质心锚 (48,48)；'
            'CI 已硬断言文件集合 == 契约闭集。<br>请判：<b>① 造型像不像该动物 ② 状态差分（hungry/happy/sleep）'
            '读不读得出来 ③ 风格是否与游戏统一</b>。不满意的物种+状态反馈后，改 '
            '<code>sprite_author.py</code> 设计常量重跑即可，业务层零改动。</p>'
            '<section><h3>总览（单张长图）</h3><div class="sheet"><img src="' + sheet_rel + '"></div></section>'
            + "".join(secs))
    open(PAGE, "w", encoding="utf-8").write(html)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--draft", action="store_true", help="审阅 assets_draft 草稿而非入库成品")
    ap.add_argument("--against", default=None, metavar="DIR",
                    help="与该目录里的旧版做左右对照长图（用于全局改动后快速判观感）")
    args = ap.parse_args()
    src = DRAFT_DIR if args.draft else FINAL_DIR
    if not os.path.isdir(src):
        print("目录不存在：%s" % src)
        return 1
    hit, size = contact_sheet(src)
    web_page(src)
    cmp_note = ""
    if args.against:
        cmp_path = os.path.join(OUT_DIR, "_compare_sheet.png")
        n2, sz2 = compare_sheet(os.path.abspath(args.against), src, cmp_path)
        cmp_note = "\n对照图：%s (%dx%d, %d 格) → 浏览器打开 /%s" % (
            os.path.relpath(cmp_path, ROOT), sz2[0], sz2[1], n2, os.path.relpath(cmp_path, ROOT))
    print("审阅物料已生成：%d 张 → %s (%dx%d)、%s" % (hit, os.path.relpath(SHEET, ROOT), size[0], size[1],
                                                    os.path.relpath(PAGE, ROOT)))
    print("浏览器打开：仓库根起 http.server 后访问 /%s" % os.path.relpath(PAGE, ROOT) + cmp_note)
    return 0


if __name__ == "__main__":
    sys.exit(main())
