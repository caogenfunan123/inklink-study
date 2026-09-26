#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
InkLink-Pet 程序化像素画师：把 app-host 的矢量物种表 PixelSpecies.kt
光栅化成附录A 合规的 96×96 像素素材草稿。

为什么走代码而不是文生图：
 - 文生图服务额度已耗尽（insufficient balance），且 AI 逐帧重画的风格/体型一致性最差；
 - 开源素材包没有 hungry/happy/sleep 状态差分帧，跨包混搭必然风格分裂；
 - App 内已有一套自有矢量物种表（配色 + 耳/尾/翅/喙/眼斑/无肢 槽位），
   把它光栅化 = 自有资产派生，风格与三档渲染天然对齐，锚点与帧间零漂移由构造保证。

真值源：app-host/src/main/java/com/inklink/host/ui/view/PixelSpecies.kt（解析，不复制，避免配色漂移）

产物：tools/pet_sprite/assets_draft/<code>_<state>.png  —— 透明底、alpha 二值、24×24 网格 ×4 邻近放大
随后交给 sprite_pipeline.py 做统一缩放/锚点/配准/自检/预览（本脚本不做这些工序）。

网格约定：CELL 逻辑像素 = ZOOM(4) 设备像素；96/4 = 24 网格。
道具（碗/音符/睡泡）必须与身体**不相邻**，否则会被并进"身体"连通域污染锚点。
"""
import os
import re
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
LUT_KT = os.path.normpath(os.path.join(
    HERE, "../../app-host/src/main/java/com/inklink/host/ui/view/PixelSpecies.kt"))
OUT_DIR = os.path.join(HERE, "assets_draft")

G = 24          # 逻辑网格
ZOOM = 4        # 每逻辑像素放大 4 → 96
BOWL_BLUE = 0xFF90A4AE
BOWL_DARK = 0xFF546E7A
NOTE_YEL = 0xFFFFD54F
TONGUE = 0xFFE53935
WHITE = 0xFFFFFFFF
BLACK = 0xFF212121


# ---------------------------------------------------------------- 物种表解析
def parse_lut(path):
    """从 Kotlin 源码解析 SpeciesDef 表；字段顺序：code, headSlot, backSlot, tailSlot, palette, flags。"""
    src = open(path, encoding="utf-8").read()
    blocks = re.findall(r'"([a-z_]+)" to SpeciesDef\((.*?)\n        \)', src, re.S)
    if len(blocks) != 14:
        raise SystemExit("解析到 %d 个物种，预期 14（PixelSpecies.kt 结构变了，先去修解析器）" % len(blocks))
    table = {}
    for code, body in blocks:
        quotes = re.findall(r'"([a-zA-Z_]*)"', body)
        if len(quotes) < 4:
            raise SystemExit("物种 %s 槽位数量异常: %s" % (code, quotes))
        head, back, tail = quotes[1], quotes[2], quotes[3]
        hexes = [int(h, 16) for h in re.findall(r"0x([0-9A-Fa-f]{8})\.toInt\(\)", body)]
        if len(hexes) != 7:
            raise SystemExit("物种 %s 调色板应有 7 色，解析到 %d" % (code, len(hexes)))
        flags = {k: ("%s = true" % k) in body for k in
                 ("beak", "eyePatch", "snout", "bigEyes", "noLimbs")}
        table[code] = {"code": code, "head": head, "back": back, "tail": tail,
                       "pal": dict(zip(("body", "shade", "belly", "patch", "line", "cheek", "special"), hexes)),
                       **flags}
    return table


def rgb(c):
    return ((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF)


# ---------------------------------------------------------------- 画布
class Sheet:
    """24×24 逻辑网格画布（透明底）。

    zc（cell 空间放大倍率）用于"把小图撑满画布"：放大必须发生在 cell 层，
    这样每个逻辑格仍恰好是 4×4 设备像素；若交给流水线做 1.5× 之类的非整数缩放，
    块宽会变成 5/6 像素混排，像素画立刻显脏——这是本项目的硬约束。
    """

    def __init__(self, zc=1.0, cx=12.0, cy=12.0):
        self.px = {}
        self.zc = zc
        self.cx = cx
        self.cy = cy

    def set(self, x, y, color):
        """格坐标直接落格，本画师**不做任何放大**。

        曾试过按 zc 倍率放大 cell 坐标：中心点取整会让相邻格 pitch 变成 5.33px 而块宽仍是
        4px，1px 缝隙把身体撕成 5 个孤立连通域（实测 cat/dog/frog/penguin 全碎）。
        "撑满画布"是设计常量的责任，不是重采样的责任。
        """
        xi, yi = int(round(x)), int(round(y))
        if 0 <= xi < G and 0 <= yi < G:
            self.px[(xi, yi)] = color

    def set_abs(self, x, y, color):
        """绝对格坐标（道具专用：不参与 zc 放大，固定在四角）。"""
        xi, yi = int(round(x)), int(round(y))
        if 0 <= xi < G and 0 <= yi < G:
            self.px[(xi, yi)] = color

    def get(self, x, y):
        return self.px.get((int(x), int(y)))

    def ellipse(self, cx, cy, rx, ry, color):
        for y in range(G):
            for x in range(G):
                dx = (x - cx) / max(rx, 0.4)
                dy = (y - cy) / max(ry, 0.4)
                if dx * dx + dy * dy <= 1.0:
                    self.set(x, y, color)

    def rect(self, x0, y0, x1, y1, color):
        for y in range(int(y0), int(y1) + 1):
            for x in range(int(x0), int(x1) + 1):
                self.set(x, y, color)

    def tri(self, apex, b1, b2, color):
        """轴对齐近似三角：按 apex→base 逐行插值填宽。"""
        ax, ay = apex
        ys = range(int(ay), int(max(b1[1], b2[1])) + 1)
        ybot = max(b1[1], b2[1])
        for y in ys:
            t = (y - ay) / max(ybot - ay, 1)
            lx = ax + (b1[0] - ax) * t
            rx = ax + (b2[0] - ax) * t
            for x in range(int(min(lx, rx)), int(max(lx, rx)) + 1):
                self.set(x, y, color)

    def polyline(self, pts, color, w=2):
        for i in range(len(pts) - 1):
            (x0, y0), (x1, y1) = pts[i], pts[i + 1]
            steps = int(max(abs(x1 - x0), abs(y1 - y0), 1) * 2)
            for k in range(steps + 1):
                t = k / steps
                cx, cy = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
                self.ellipse(cx, cy, w / 2.0, w / 2.0, color)

    def outline(self, color):
        """1 逻辑格**外**描边（掌机像素风）：只涂轮廓外围的空格。

        早期实现把颜色写回 `edge` 里的**身体边界格本身**（=内描边），后果不只是风格不对：
        任何画在轮廓边上的五官都会被描边色覆盖掉（snake/hedgehog 闭眼"淡"的第二个成因，
        第一个是闭眼用了与轮廓同色的 pal["line"]）。
        """
        filled = set(self.px)
        ring, self.clipped = set(), []
        for (x, y) in filled:
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if (nx, ny) in filled:
                    continue
                if 0 <= nx < G and 0 <= ny < G:
                    ring.add((nx, ny))
                else:
                    self.clipped.append((x, y))   # 墨迹顶到最外格 → 它的描边无处可画
        for c in ring:
            self.px.setdefault(c, color)

    def save(self, path):
        im = Image.new("RGBA", (G * ZOOM, G * ZOOM), (0, 0, 0, 0))
        for (x, y), c in self.px.items():
            # 整块 4×4 必须是实心不透明：早期版本只涂块内 (0,0) 一个像素，
            # 导致 alpha 掩膜里身体碎成上千个孤立点，"最大连通域=身体"直接失效。
            blk = Image.new("RGBA", (ZOOM, ZOOM), rgb(c) + (255,))
            im.paste(blk, (x * ZOOM, y * ZOOM))
        im.save(path, "PNG")
        return path


# ---------------------------------------------------------------- 部件绘制（先画 = 被身体压在下层）
def draw_head(sheet, sp, eye_y):
    p, pal = sp["head"], sp["pal"]
    ear_c = pal["patch"] if sp["eyePatch"] else pal["body"]
    inner = pal["cheek"]
    if p == "cat_ears":
        for d in (-1, 1):
            cx = 12 + d * 4.5
            sheet.tri((cx, 3.5), (cx - 2.6, 8.5), (cx + 2.6, 8.5), ear_c)
            sheet.tri((cx, 5.0), (cx - 1.2, 8.2), (cx + 1.2, 8.2), inner)
    elif p == "big_ears":  # 狐狸：更高更尖，内耳深色
        for d in (-1, 1):
            cx = 12 + d * 5.2
            sheet.tri((cx, 1.5), (cx - 3.0, 9.0), (cx + 3.0, 9.0), ear_c)
            sheet.tri((cx, 4.0), (cx - 1.3, 8.5), (cx + 1.3, 8.5), pal["shade"])
    elif p == "drop_ears":  # 狗：耷拉在身体两侧
        for d in (-1, 1):
            sheet.ellipse(12 + d * 6.6, 12.5, 2.0, 4.2, pal["shade"])
    elif p == "long_ears":  # 兔：两根长耳
        for d in (-1, 1):
            cx = 12 + d * 2.6
            sheet.ellipse(cx, 5.0, 1.8, 5.0, pal["body"])
            sheet.ellipse(cx, 5.4, 0.8, 3.4, inner)
    elif p == "round_ears":  # 仓鼠/熊猫
        for d in (-1, 1):
            sheet.ellipse(12 + d * 4.6, 6.4, 2.3, 2.3, ear_c)
    elif p == "tiny_ears":  # 刺猬/猪
        for d in (-1, 1):
            cx = 12 + d * 5.0
            sheet.tri((cx, 5.6), (cx - 1.8, 8.4), (cx + 1.8, 8.4), pal["shade"])
    elif p == "horns":  # 龙：小犄角
        for d in (-1, 1):
            cx = 12 + d * 4.0
            sheet.tri((cx, 2.6), (cx - 1.4, 7.0), (cx + 1.4, 7.0), pal["special"])
    elif p == "wool_horns":  # 羊：卷角 + 头顶羊毛
        for d in (-1, 1):
            cx = 12 + d * 5.4
            sheet.ellipse(cx, 8.0, 2.0, 1.6, pal["special"])
            sheet.ellipse(cx, 8.0, 0.9, 0.7, pal["shade"])
        for d in (-1, 0, 1):
            sheet.ellipse(12 + d * 3.0, 6.0, 2.6, 2.2, pal["belly"])
    elif p == "frog_eyes":  # 青蛙：眼泡长在头顶（眼睛本体由该槽画，body 不再画眼）
        for d in (-1, 1):
            cx = 12 + d * 4.2
            sheet.ellipse(cx, 6.6, 2.9, 2.7, pal["body"])
            sheet.ellipse(cx, 6.4, 1.9, 1.8, WHITE)
            sheet.set(cx, 6.4, BLACK)
            sheet.set(cx + 1, 6.4, BLACK)
            sheet.set(cx, 7.4, BLACK)
            sheet.set(cx + 1, 7.4, BLACK)
    elif p == "tufts":  # 猫头鹰耳羽簇：底边必须伸进头部轮廓（y≈10.6），否则悬空成孤立连通域
        for d in (-1, 1):
            cx = 12 + d * 4.6
            sheet.tri((cx, 3.0), (cx - 2.2, 10.6), (cx + 0.8, 10.6), pal["shade"])
    elif p == "none":
        pass
    else:
        raise SystemExit("未知 headSlot: %s（sprite_author 未覆盖，请补）" % p)


def draw_back(sheet, sp):
    b, pal = sp["back"], sp["pal"]
    if b == "wings":
        for d in (-1, 1):
            sheet.ellipse(12 + d * 6.9, 13.2, 2.3, 4.4, pal["shade"])
    elif b == "spikes":  # 刺猬：背上锯齿
        x = 5
        while x < 20:
            sheet.tri((x + 1.4, 4.6), (x, 9.6), (x + 2.8, 9.6), pal["patch"])
            x += 2.6
    elif b == "frill":  # 羊：身侧羊毛鼓包
        for d in (-1, 1):
            sheet.ellipse(12 + d * 6.4, 13.6, 2.6, 2.6, pal["belly"])
    elif b == "none":
        pass
    else:
        raise SystemExit("未知 backSlot: %s" % b)


def draw_tail(sheet, sp):
    t, pal = sp["tail"], sp["pal"]
    if t == "curvy":
        sheet.polyline([(17.6, 16.5), (20.4, 14.5), (19.2, 11.2), (21.0, 9.4)], pal["shade"], w=2.4)
    elif t == "nub":
        sheet.ellipse(18.8, 15.6, 1.7, 1.7, pal["shade"])
    elif t == "cotton":
        sheet.ellipse(18.9, 14.6, 1.8, 1.8, WHITE)
    elif t == "fan":   # 逐行填的扇尾：原 tri 只出 3 行插值，末行只剩 1 格悬空
        for y, x0, x1 in ((13, 19, 21), (14, 18, 21), (15, 18, 20), (16, 17, 19), (17, 17, 18)):
            sheet.rect(x0, y, x1, y, pal["shade"])
    elif t == "bushy":
        sheet.ellipse(19.6, 15.0, 2.8, 3.4, pal["body"])
        sheet.ellipse(20.6, 13.4, 1.6, 1.8, pal["belly"])
    elif t == "longtail":
        sheet.polyline([(17.4, 17.0), (21.0, 16.2), (21.6, 13.6)], pal["shade"], w=2.2)
        sheet.tri((22.4, 12.4), (20.4, 14.2), (22.8, 14.6), pal["special"])
    elif t in ("none", "coil"):
        pass
    else:
        raise SystemExit("未知 tailSlot: %s" % t)


def draw_limbs(sheet, sp):
    if sp["noLimbs"]:
        return
    pal = sp["pal"]
    col = pal["patch"] if sp["eyePatch"] else pal["shade"]
    for d in (-1, 1):
        sheet.ellipse(12 + d * 2.6, 19.6, 2.0, 1.2, col)      # 脚
        sheet.ellipse(12 + d * 6.5, 14.2, 1.5, 2.1, col)      # 手


def draw_body(sheet, sp):
    pal = sp["pal"]
    if sp["code"] == "snake":
        sheet.ellipse(12, 15.8, 7.6, 4.6, pal["body"])
        for i, (rx, ry) in enumerate(((5.6, 2.6), (3.4, 1.4))):
            sheet.ellipse(12, 16.4, rx, ry, pal["shade"] if i == 0 else pal["belly"])
        sheet.ellipse(12, 8.4, 4.4, 3.8, pal["body"])          # 头
        return
    if sp["code"] == "frog":
        sheet.ellipse(12, 13.4, 7.8, 6.0, pal["body"])
        sheet.ellipse(12, 15.6, 5.2, 3.0, pal["belly"])
        for d in (-1, 1):
            sheet.ellipse(12 + d * 5.6, 18.4, 2.6, 1.5, pal["shade"])
        return
    if sp["code"] == "sheep":
        sheet.ellipse(12, 13.8, 7.0, 6.0, pal["belly"])
        for a in range(0, 360, 40):
            import math
            cx = 12 + 6.4 * math.cos(math.radians(a))
            cy = 13.8 + 5.4 * math.sin(math.radians(a))
            sheet.ellipse(cx, cy, 2.2, 2.0, pal["belly"])
        sheet.ellipse(12, 10.6, 4.4, 3.6, pal["body"])          # 脸
        return
    # 通用 chibi 蛋形身体
    rx, ry = (7.6, 6.4) if sp["code"] == "penguin" else (7.0, 6.6)
    sheet.ellipse(12, 13.2, rx, ry, pal["body"])
    if sp["code"] == "penguin":
        sheet.ellipse(12, 14.6, 4.6, 4.4, pal["belly"])
    elif sp["code"] == "owl":
        sheet.ellipse(12, 15.0, 4.2, 3.6, pal["belly"])
        for d in (-1, 1):
            sheet.ellipse(12 + d * 2.4, 17.0, 1.2, 1.8, pal["shade"])   # 胸纹
    elif sp["code"] == "cat":
        for x in (8.6, 11.4, 14.2):
            sheet.rect(x, 7.2, x + 1, 9.2, pal["patch"])
    elif sp["code"] == "hamster":
        for d in (-1, 1):
            sheet.ellipse(12 + d * 5.2, 13.4, 2.4, 2.4, pal["patch"])   # 鼓腮
    elif sp["code"] == "dragon":
        for y in (13.6, 15.6, 17.2):
            sheet.ellipse(12, y, 4.0 - (y - 13.6) * 0.5, 1.1, pal["belly"])
    elif sp["code"] == "rabbit":
        sheet.ellipse(12, 14.8, 4.4, 3.4, pal["belly"])
    elif sp["code"] == "pig":
        sheet.ellipse(12, 14.8, 4.4, 3.4, pal["belly"])
    elif sp["code"] == "fox":
        sheet.ellipse(12, 15.0, 4.4, 3.6, pal["belly"])
        sheet.tri((12, 8.0), (9.6, 11.4), (14.4, 11.4), pal["belly"])
    elif sp["code"] == "hedgehog":
        sheet.ellipse(12, 13.0, 5.0, 4.6, pal["belly"])
        sheet.ellipse(9.4, 12.6, 3.0, 2.8, pal["body"])          # 露出脸
    else:  # dog/panda 保持纯色身体 + 补丁
        sheet.ellipse(12, 15.0, 4.2, 3.2, pal["belly"])
        if sp["code"] == "dog":
            sheet.ellipse(9.4, 10.6, 2.4, 2.0, pal["patch"])


# 头部横向空间窄的物种：眼睛 3 格宽会顶到头部轮廓环（line 色），闭眼时整条糊成"眉毛"
# （用户审阅对 snake / hedgehog 的原话就是"闭眼效果弱、像眉毛微调"）。
NARROW_HEAD = {"snake"}


def closed_eye(sheet, cx, ey, color, d, thick, narrow):
    """闭眼的两种语义分开画，不能共用一条细线：

    blink = 瞬时眨眼 → 1 格细线（已验收观感，不动）；
    sleep = 放松眼睑 → 2 格厚 + 外侧下垂 1 格眼角。加厚是必要的：1 格线在深色小头上
    与轮廓环连成一片，读不出"闭眼"；下垂眼角让它读作"放松"而不是"一条直线=不高兴"。
    """
    if not thick:
        sheet.rect(cx - 1, ey, cx + 1, ey, color)
        return
    x0, x1 = cx - 1, cx + 1
    if narrow:                      # 窄头：把朝轮廓那一侧让开 1 格，留出净空
        if d < 0:
            x0 += 1
        else:
            x1 -= 1
    sheet.rect(x0, ey, x1, ey, color)
    sheet.rect(x0, ey + 1, x1, ey + 1, color)
    sheet.set(x0 if d < 0 else x1, ey + 2, color)


def draw_face(sheet, sp, state, eye_y=11.4):
    pal = sp["pal"]
    # 嘴/鼻/喙/腮红全部由 eye_y 推导，绝不各写死一套 y：
    # snake 的头是 draw_body 画的、中心在 y=8.4，写死 15 会让五官落在盘身上（头部变空白青斑）。
    my = eye_y + 3.6
    if sp["head"] == "frog_eyes":
        # 青蛙眼泡长在头顶（由 draw_head 画），所以该物种**天生没有状态眼**：
        # sleep 全程睁眼睡、happy 也无笑眼——与用户反馈的 snake/hedgehog 是同一类缺陷，
        # 只是更严重。这里在身体绘制之后再覆盖（draw_head 早于 draw_body，直接改头槽会被身体涂掉）。
        if state != "hungry":
            for d in (-1, 1):
                cx = 12 + d * 4.2
                sheet.ellipse(cx, 6.4, 1.9, 1.8, pal["body"])      # 抹掉眼白 + 瞳孔
                if state == "happy":
                    sheet.set(cx - 1, 6.4, pal["line"])
                    sheet.rect(cx - 1, 5.4, cx + 1, 5.4, pal["line"])
                    sheet.set(cx + 1, 6.4, pal["line"])
                else:
                    closed_eye(sheet, cx, 6.4, BLACK, d, state == "sleep", False)
        if state == "hungry":
            sheet.rect(9, my, 14, my + 1, pal["line"])
        elif state in ("sleep", "blink"):
            sheet.rect(8, my, 15, my, pal["line"])
        else:
            sheet.rect(8, my, 15, my, pal["line"])
            sheet.set(8, my - 1, pal["line"])
            sheet.set(15, my - 1, pal["line"])
        return
    ex = 3.0
    big = sp["bigEyes"]
    if state == "happy":
        for d in (-1, 1):
            cx = 12 + d * ex
            sheet.set(cx - 1, eye_y, pal["line"])
            sheet.rect(cx - 1, eye_y - 1, cx + 1, eye_y - 1, pal["line"])
            sheet.set(cx + 1, eye_y, pal["line"])
    elif state in ("sleep", "blink"):
        # 墨色规则：凡是"替代瞳孔"的眼部笔触一律用瞳孔同色 BLACK，绝不用 pal["line"]。
        # 实测 snake 的 line = #004D40 与轮廓环同色，闭眼画成 line 再厚也会和头部外轮廓
        # 糊成一片（用户观感"只是眉毛微调"）；改成 BLACK 后与睁开时的瞳孔同色、对比度一致。
        narrow = sp["code"] in NARROW_HEAD
        for d in (-1, 1):
            closed_eye(sheet, 12 + d * ex, eye_y, BLACK, d, state == "sleep", narrow)
    elif state == "hungry":
        for d in (-1, 1):
            cx = 12 + d * ex
            sheet.rect(cx - 1, eye_y, cx, eye_y + 1, pal["line"])
            sheet.set(cx - 1, eye_y - 1, pal["shade"])
    else:
        for d in (-1, 1):
            cx = 12 + d * ex
            if big:
                sheet.ellipse(cx, eye_y, 2.6, 2.6, WHITE)
                sheet.rect(cx - 1, eye_y - 1, cx + 1, eye_y + 1, BLACK)
            else:
                sheet.rect(cx - 1, eye_y, cx, eye_y + 1, BLACK)
                sheet.set(cx - 1, eye_y, WHITE)
    if sp["eyePatch"]:  # 熊猫眼斑压回深色
        for d in (-1, 1):
            sheet.ellipse(12 + d * ex, eye_y, 2.4, 2.8, pal["patch"])
        if state in ("sleep", "blink"):
            for d in (-1, 1):   # 眼斑是深色的，闭眼线必须反白；几何与普通物种完全同源
                closed_eye(sheet, 12 + d * ex, eye_y, WHITE, d, state == "sleep", False)
        elif state == "happy":
            pass
        else:
            for d in (-1, 1):
                sheet.rect(12 + d * ex - 1, eye_y, 12 + d * ex, eye_y + 1, BLACK)
    if sp["snout"]:
        sheet.ellipse(12, my + 0.2, 3.0, 2.2, pal["cheek"])
        sheet.rect(10.6, my, 11.2, my + 0.6, pal["line"])
        sheet.rect(12.8, my, 13.4, my + 0.6, pal["line"])
        # 结构性缺陷：snout 分支原来画完鼻孔就吃掉整条 if/elif/else，**状态嘴永远不画**，
        # 于是 pig 的 happy 与 idle 几乎同图（用户审阅反馈）。idle 保持不动（已验收），
        # 只给 hungry/happy/sleep 补鼻下嘴。
        if state == "hungry":
            sheet.ellipse(12, my + 3.4, 2.0, 1.2, pal["line"])                  # 张嘴等饭
        elif state == "happy":
            sheet.rect(9, my + 2.6, 10, my + 3.2, pal["line"])                  # 左嘴角上扬
            sheet.rect(14, my + 2.6, 15, my + 3.2, pal["line"])                 # 右嘴角上扬
            sheet.rect(11, my + 3.6, 13, my + 3.6, pal["line"])                 # 下唇中段（低于嘴角=笑脸）
        elif state == "sleep":
            sheet.rect(11, my + 3.2, 13, my + 3.2, pal["line"])                 # 抿嘴睡
    elif sp["beak"]:
        sheet.tri((12, my - 2.2), (10.4, my + 0.2), (13.6, my + 0.2), pal["special"])
        if state == "hungry":
            # 喙是实色三角、不随状态变形 → 企鹅"等饭"读不出来（用户审阅反馈）。补张开的下喙。
            sheet.tri((12, my + 0.6), (10.2, my + 3.0), (13.8, my + 3.0), pal["line"])
    else:
        if state == "hungry":
            sheet.rect(11, my, 13, my + 1, pal["line"])  # 张嘴等饭
        elif state == "happy":
            sheet.rect(10, my - 0.4, 11, my + 0.6, pal["line"])
            sheet.rect(13, my - 0.4, 14, my + 0.6, pal["line"])
            sheet.rect(11, my + 1, 13, my + 1, pal["line"])
        elif state == "sleep":
            sheet.rect(11, my, 12, my, pal["line"])
        else:
            sheet.set(11, my, pal["line"])
            sheet.set(12, my, pal["line"])
    if sp["code"] == "frog":
        pass
    for d in (-1, 1):  # 腮红：只能在已有身体墨迹上染色，轮廓外一律不画
        # 蛇头 rx 只有 4.4，±5.4 会落到头部之外变成悬空 1 格（连通域门禁直接报错）；
        # 悬空点即使被 outline 接上也会污染身体质心，所以是"不画"而不是"挪进来"。
        bx, by = int(round(12 + d * 5.4)), int(round(my - 1.6))
        if sheet.get(bx, by) is not None:
            sheet.set(bx, by, pal["cheek"])


# ---------------------------------------------------------------- 状态道具（必须与身体不相邻）
# 图案单元：[(相对格坐标, 颜色), ...]，锚点是图案原点。每个状态给"降级阶梯"：
# 身体占太满（frog 宽体、dragon 大翅膀）时自动退到更小的图标，而不是硬塞进身体里连通。
HUNGRY_BOWL = [(0, 1, BOWL_BLUE), (1, 1, BOWL_BLUE), (2, 1, BOWL_BLUE),
               (-1, 2, BOWL_BLUE), (0, 2, BOWL_BLUE), (1, 2, BOWL_DARK),
               (2, 2, BOWL_BLUE), (3, 2, BOWL_BLUE)]
HUNGRY_SMALL = [(0, 1, BOWL_BLUE), (1, 1, BOWL_BLUE), (2, 1, BOWL_BLUE), (1, 2, BOWL_DARK)]
HUNGRY_PELLET = [(1, 2, BOWL_BLUE), (2, 2, BOWL_DARK)]
HAPPY_NOTES = [(0, 2, NOTE_YEL), (0, 1, NOTE_YEL), (0, 0, NOTE_YEL), (1, 2, NOTE_YEL),
               (3, 1, NOTE_YEL), (3, 0, NOTE_YEL), (4, 1, NOTE_YEL)]
HAPPY_NOTE = [(0, 2, NOTE_YEL), (0, 1, NOTE_YEL), (0, 0, NOTE_YEL), (1, 2, NOTE_YEL)]
SLEEP_ZZZ = [(0, 0, WHITE), (1, 0, WHITE), (0, 1, WHITE), (1, 1, WHITE), (-2, 3, WHITE)]
SLEEP_ZZ = [(0, 0, WHITE), (1, 0, WHITE), (0, 1, WHITE)]

# 每档 = (格子, 首选锚点, 备选锚点列表)
PROP_LADDER = {
    "hungry": [(HUNGRY_BOWL, (1, 19), [(19, 19), (1, 1), (19, 1)]),
               (HUNGRY_SMALL, (1, 19), [(19, 19), (1, 1), (19, 1)]),
               (HUNGRY_PELLET, (1, 19), [(19, 19), (1, 1), (19, 1)])],
    "happy": [(HAPPY_NOTES, (2, 1), [(20, 1), (2, 17), (20, 17)]),
              (HAPPY_NOTE, (2, 1), [(20, 1), (2, 17), (20, 17), (4, 1), (18, 1)])],
    "sleep": [(SLEEP_ZZZ, (19, 3), [(1, 3), (19, 17), (1, 17)]),
              (SLEEP_ZZ, (19, 3), [(1, 3), (19, 17), (1, 17), (20, 3), (2, 3)])],
}


# 净空 2 格：道具位不得落在身体（含其外描边）的 4-邻域内，否则两团墨连通、锚点被道具顶偏。
# 判定时机在描边之后，故 `ink` 已含描边环，1 格邻域判定就足够。
CLEAR = 2
# 道具安全框：只要求自身不出画布（道具不参与身体回中平移，流水线是分层贴回的）。
SAFE_LO, SAFE_HI = 1, 22


def fits(cells, ax, ay, ink):
    """道具图案放在 (ax, ay) 是否合法：① 全格都在安全框内；② 每格与身体（含描边）留足净空。"""
    for rx, ry, _c in cells:
        x, y = ax + rx, ay + ry
        if not (SAFE_LO <= x <= SAFE_HI and SAFE_LO <= y <= SAFE_HI):
            return False
        for bx in range(x - CLEAR + 1, x + CLEAR):
            for by in range(y - CLEAR + 1, y + CLEAR):
                if (bx, by) in ink:
                    return False
    return True


def draw_props(sheet, sp, state):
    """把状态道具放到四角：先找净空位，找不到就沿降级阶梯换更小的图标。

    为什么必须自动：hedgehog 的背刺一度伸到 (18,6) 与 Zzz 合并成同一连通域，锚点（最大连通域
    质心）当场被道具顶偏 4px；这类 bug 靠人眼迟早漏掉，交给规则兜。
    返回值是所用档位下标（0=满配），供 main 打印诊断——降级过头会体现在观感上，得看得见。
    """
    ladder = PROP_LADDER.get(state)
    if not ladder:
        return 0
    ink = set(sheet.px)     # 已含身体外描边，故净空判定直接把描边算进去
    offsets = sorted({(dx, dy) for dx in range(-4, 5) for dy in range(-4, 5)},
                     key=lambda o: (abs(o[0]) + abs(o[1]), o[1], o[0]))   # 离首选角越近越优先=确定性
    for vi, (cells, primary, alt_anchors) in enumerate(ladder):
        for (ax, ay) in [primary] + list(alt_anchors):
            for dx, dy in offsets:
                if fits(cells, ax + dx, ay + dy, ink):
                    for rx, ry, c in cells:
                        sheet.set(ax + dx + rx, ay + dy + ry, c)
                    return vi
    raise SystemExit("%s/%s: 连最小档道具都放不下（身体占满四角）→ 请缩小该物种设计常量"
                     % (sp["code"], state))


# 头部垂直中心（格）。蛇没有独立头槽位，头是 body 的一部分，必须单独给。
FACE_EYE_Y = {"snake": 8.4}


# ---------------------------------------------------------------- 合成
def render(sp, state, zc=1.0):
    s = Sheet(zc=zc)
    if sp["tail"] != "coil":
        draw_tail(s, sp)
    draw_back(s, sp)
    # 眼位=头部中心；snake 的头由 draw_body 单独画在 y=8.4，其余物种统一 11.4
    ey = FACE_EYE_Y.get(sp["code"], 11.4)
    draw_head(s, sp, ey)
    draw_limbs(s, sp)
    draw_body(s, sp)
    draw_face(s, sp, state, ey)
    if sp["code"] == "snake" and state == "idle":   # 信子从嘴下方探出
        s.set(12, ey + 5.0, TONGUE)
        s.set(12, ey + 6.0, TONGUE)
    # 顺序要紧：先给身体描外圈，再把道具当**扁平贴纸**贴上去（道具不参与描边）。
    # 若道具也被描边，身体圈与道具圈会在净空里相遇并连通（实测 cat_happy 直接并成一个域），
    # 净空要求就得涨到 4 格，四角根本放不下。
    s.outline(sp["pal"]["line"])
    return s, draw_props(s, sp, state)


def cc_parts(sheet):
    """连通域列表 [(size, bbox)]，降序。"""
    from collections import deque
    pts = set(sheet.px)
    seen, out = set(), []
    for s0 in pts:
        if s0 in seen:
            continue
        q = deque([s0]); seen.add(s0); comp = []
        while q:
            i = q.popleft(); comp.append(i)
            x, y = i
            for j in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if j in pts and j not in seen:
                    seen.add(j); q.append(j)
        xs = [p[0] for p in comp]; ys = [p[1] for p in comp]
        out.append((len(comp), (min(xs), min(ys), max(xs), max(ys))))
    return sorted(out, reverse=True)


def cc_sizes(sheet):
    """连通域尺寸表（降序）：idle 应只有 1 域，hungry=2、happy=3、sleep=2（道具独立成域，
    且道具绝不能与身体相邻——否则"最大连通域=身体"的锚点规则会被道具顶偏，见附录A.6）。"""
    from collections import deque
    pts = set(sheet.px)
    seen, out = set(), []
    for s0 in pts:
        if s0 in seen:
            continue
        q = deque([s0]); seen.add(s0); k = 0
        while q:
            i = q.popleft(); k += 1
            x, y = i
            for j in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if j in pts and j not in seen:
                    seen.add(j); q.append(j)
        out.append(k)
    return sorted(out, reverse=True)


def body_extent_cells(sheet):
    """最大连通域（身体）的格级跨度；道具是独立连通域，不参与撑幅计算。"""
    from collections import deque
    pts = set(sheet.px)
    seen, best = set(), []
    for s0 in pts:
        if s0 in seen:
            continue
        q = deque([s0]); seen.add(s0); comp = []
        while q:
            i = q.popleft(); comp.append(i)
            x, y = i
            for j in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if j in pts and j not in seen:
                    seen.add(j); q.append(j)
        if len(comp) > len(best):
            best = comp
    if not best:
        return 1
    xs = [x for x, _ in best]; ys = [y for _, y in best]
    return max(max(xs) - min(xs) + 1, max(ys) - min(ys) + 1)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    table = parse_lut(LUT_KT)
    n = 0
    for code in sorted(table):
        sp = table[code]
        states = ["idle", "hungry", "happy", "sleep"]
        if code in ("cat", "dog", "rabbit"):
            states.append("blink")
        diag = []
        for st in states:
            sh, pv = render(sp, st)
            if getattr(sh, "clipped", None):
                raise SystemExit("%s_%s: 身体墨迹顶到画布最外一格 %s → 外描边无处可画（会被静默裁掉），"
                                 "请缩小该物种设计常量" % (code, st, sh.clipped[:4]))
            parts = cc_parts(sh)
            # 硬门禁①：idle/blink 无道具，身体所有零件必须连成一体，悬空件=造型 bug
            if st in ("idle", "blink") and len(parts) != 1:
                floaters = ", ".join("%d格@%s" % (k, b) for k, b in parts[1:])
                raise SystemExit("%s_%s 有悬空零件（身体 %d格 + %s）→ 修几何常量" %
                                 (code, st, parts[0][0], floaters))
            # 硬门禁②：道具必须全部独立成域，且不能大过身体（否则最大域不是身体，锚点会被顶偏）
            if st in ("hungry", "happy", "sleep"):
                if len(parts) < 2:
                    raise SystemExit("%s_%s: 道具与身体合并成同一连通域（净空被外描边吃掉）→ "
                                     "检查 CLEAR/道具位" % (code, st))
                if parts[1][0] >= parts[0][0]:
                    raise SystemExit("%s_%s: 最大连通域不是身体（%s）" % (code, st, parts[:2]))
            sh.save(os.path.join(OUT_DIR, "%s_%s.png" % (code, st)))
            diag.append("%s %d格/%d域%s" % (st, parts[0][0], len(parts),
                                            "" if pv == 0 else "·道具降%d档" % pv))
            n += 1
        print("  %-9s %s" % (code, "  ".join(diag)))
    print("生成 %d 张草稿 → %s（源：%s）" % (n, OUT_DIR, os.path.relpath(LUT_KT, os.path.join(HERE, "../.."))))
    return 0


if __name__ == "__main__":
    sys.exit(main())
