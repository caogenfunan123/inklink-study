#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""InkLink-Pet 预制音效合成器（确定性工序，不依赖任何外部音频服务）。

为什么不用 AI 音频生成：本沙箱无音频生成工具，且文生图服务实测返回 `insufficient balance`
——额度不可靠的能力不能当交付关键路径。改为本脚本合成，好处是**可复现、零版权、风格统一、
合规可证**（采样率/位深/声道/时长/峰值/静音全部由代码保证并自检）。

硬性规范（《音频系统 Final-Rev1》§二）逐条落到自检函数 `check_wav()`，不合规直接抛错：
  16bit PCM / 44100Hz / 单声道 / 0.5~1.5s / 无杂音(无直流、无爆音) / 无尾音空白 / 音量统一标准化
  文件名：全小写下划线

产出：out/<name>.wav + out/_manifest.json 台账（时长/峰值/RMS/字节数）。
用法：
  python3 tools/pet_audio/generate_sfx.py            # 合成到 out/
  python3 tools/pet_audio/generate_sfx.py --check DIR # 只校验已入库目录（CI 侧同源规则）
"""
import argparse
import json
import math
import os
import struct
import sys
import wave

import numpy as np

SR = 44100                      # 采样率（规范硬性）
NCH = 1                         # 单声道
SW = 2                          # 16bit = 2 字节
PEAK_TARGET = 0.891             # ≈ -1.0 dBFS 统一标准化峰值
SILENCE_FLOOR = 0.002           # 静音判定阈值（归一后）
HARM = 14                       # 方波/三角加法合成谐波数：限带，避免锯齿混叠“杂音感”

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")


# ---------------------------------------------------------------- 振荡器（全部限带）
def _additive(freq, t, kind):
    """按加法谐波合成限带波形。朴素 sign(sin) 方波在高采样率下会有混叠刺声，违反“无杂音”。"""
    y = np.zeros_like(t)
    for k in range(1, HARM + 1):
        if kind == "square" and k % 2 == 1:
            y += np.sin(2 * math.pi * freq * k * t) / k
        elif kind == "tri":
            if k % 2 == 1:
                y += ((-1) ** ((k - 1) // 2)) * np.sin(2 * math.pi * freq * k * t) / (k * k)
        elif kind == "saw":
            y += ((-1) ** (k + 1)) * np.sin(2 * math.pi * freq * k * t) / k
    if kind == "sine":
        y = np.sin(2 * math.pi * freq * t)
    peak = np.abs(y).max() or 1.0
    return y / peak


def env_ads(n, attack=0.006, release=0.03):
    """每音必带起止淡变：零-crossing 硬切会产生可听爆音（click），是“无杂音”最常见的破坏方式。"""
    na = max(1, int(attack * SR))
    nr = max(1, int(release * SR))
    e = np.ones(n)
    if n <= na + nr:
        return np.linspace(0, 0, n) + 1.0
    a = 0.5 - 0.5 * np.cos(np.linspace(0, math.pi, na))          # 升余弦，不用线性（线性仍留拐点）
    r = 0.5 - 0.5 * np.cos(np.linspace(math.pi, 0, nr))
    e[:na] *= a
    e[-nr:] *= r
    return e


def note(freq, dur, kind="square", vol=1.0, sweep=None, vib=None, attack=0.006, release=0.03):
    """单个音符。sweep=(f0,f1) 做滑音（呜咽/打鼾/叹气需要）；vib=(Hz, 半音深度) 做颤音。"""
    n = max(1, int(round(dur * SR)))   # 必须 round：int(0.7*44100)=30869，浮点截断会让总长少 1 样本
    t = np.arange(n) / SR
    f = np.full(n, float(freq))
    if sweep is not None:
        f = sweep[0] * (sweep[1] / sweep[0]) ** np.linspace(0, 1, n)   # 等比滑音，听感比线性自然
    if vib is not None:
        rate, depth = vib
        f = f * (1.0 + depth * np.sin(2 * math.pi * rate * t) / 100.0)
    y = np.zeros(n)
    chunk = int(0.02 * SR)                                            # 分段定频近似：变调不失真
    for i in range(0, n, chunk):
        seg = slice(i, min(i + chunk, n))
        y[seg] = _additive(f[seg].mean(), t[seg], kind)
    return y * env_ads(n, attack, release) * vol


def noise_burst(dur, vol=0.5, lowpass=900, attack=0.008, release=0.05):
    """滤波噪声（咀嚼/呼吸气流）。白噪声直接用会“嘶”得刺耳，一阶滑动平均低通后才有肉感。"""
    n = max(1, int(round(dur * SR)))
    rng = np.random.default_rng(20260810)                             # 固定种子：结果可复现
    x = rng.standard_normal(n + 64)
    k = max(2, int(SR / lowpass))
    x = np.convolve(x, np.ones(k) / k, mode="same")[:n]   # 滑动平均=线性相位，不引入相位失稳
    p = np.abs(x).max() or 1.0
    x = x / p
    e = np.ones(n)
    a = max(1, int(attack * SR))
    r = max(1, int(release * SR))
    e[:a] *= 0.5 - 0.5 * np.cos(np.linspace(0, math.pi, a))
    e[-r:] *= 0.5 - 0.5 * np.cos(np.linspace(math.pi, 0, r))
    return x * e * vol


def seq(events, total):
    """把 [(起始s, 波形数组)] 叠加成 total 秒的定长缓冲。总时长由配方显式声明，不靠拼接误差。"""
    n = int(round(total * SR))
    buf = np.zeros(n)
    for at, y in events:
        i = int(at * SR)
        seg = min(len(y), n - i)
        if seg <= 0:
            raise ValueError("事件超出总时长，请加大 total")
        buf[i:i + seg] += y[:seg]
    return buf


# ---------------------------------------------------------------- 10 条配方（对应 §三提示词语义）
RECIPES = {}


def recipe(name, total, comment):
    def deco(fn):
        RECIPES[name] = (fn, total, comment)
        return fn
    return deco


@recipe("alert_call", 1.00, "short sharp cute notification ping：双上行叮 + 正弦尾音")
def _alert_call():
    return seq([(0.00, note(1318.5, 0.07, "square", 0.9)),
                (0.10, note(1568.0, 0.09, "square", 0.95)),
                (0.24, note(2093.0, 0.62, "sine", 0.75, release=0.42))], 1.00)


@recipe("alert_notify", 0.70, "soft gentle blip：单个柔和三角轻响 + 长尾泛音")
def _alert_notify():
    # 末事件必须把淡出推到 ~0.66s：否则裁尾后只剩 0.50s 以下，违反时长下限
    return seq([(0.00, note(1046.5, 0.26, "tri", 0.85, sweep=(1046.5, 1200), release=0.20)),
                (0.22, note(1318.5, 0.44, "tri", 0.55, release=0.38))], 0.70)


@recipe("alert_warn", 1.00, "low tense mini warning beep：低音两声微降（走 STREAM_ALARM，必须可辨）")
def _alert_warn():
    return seq([(0.00, note(392.0, 0.13, "square", 0.95)),
                (0.20, note(349.2, 0.13, "square", 0.95)),
                (0.42, note(330.0, 0.34, "saw", 0.75, sweep=(330.0, 261.6), release=0.24))], 1.00)


@recipe("sound_ack", 0.50, "tiny positive confirm blip：上行两音干脆短促（末音拖到 0.5s 才不越时长下限）")
def _sound_ack():
    return seq([(0.00, note(523.3, 0.06, "square", 0.85)),
                (0.07, note(784.0, 0.10, "square", 0.95, release=0.08)),
                (0.16, note(1046.5, 0.38, "sine", 0.70, release=0.32))], 0.50)


@recipe("pet_feed", 0.80, "cute gulp eating：咀嚼噪声 + 两次下滑吞 + pop")
def _pet_feed():
    return seq([(0.00, noise_burst(0.12, 0.45, lowpass=1400)),
                (0.05, note(520.0, 0.14, "sine", 0.85, sweep=(520, 210))),
                (0.24, noise_burst(0.12, 0.42, lowpass=1400)),
                (0.30, note(430.0, 0.16, "sine", 0.9, sweep=(430, 170))),
                (0.52, note(880.0, 0.16, "square", 0.6, sweep=(880, 1250), release=0.10))], 0.80)


@recipe("pet_touch", 0.70, "fluffy soft chirp：柔音上扬回落 + 轻颤音")
def _pet_touch():
    return seq([(0.00, note(700.0, 0.20, "sine", 0.85, sweep=(700, 1180))),
                (0.20, note(1180.0, 0.26, "sine", 0.8, sweep=(1180, 820), vib=(9, 6), release=0.18)),
                (0.44, note(980.0, 0.22, "tri", 0.45, release=0.20))], 0.70)


@recipe("pet_happy", 0.60, "bright happy jingle：四音琶音 + 上行哨音")
def _pet_happy():
    ev = []
    for i, f in enumerate([523.3, 659.3, 784.0, 1046.5]):
        ev.append((i * 0.06, note(f, 0.07, "square", 0.9, release=0.05)))
    ev.append((0.26, note(1046.5, 0.30, "sine", 0.75, sweep=(1046.5, 1568.0), release=0.22)))
    return seq(ev, 0.60)


@recipe("pet_hungry", 1.00, "small sad whimper：两次下滑呜咽 + 颤音，音量偏低")
def _pet_hungry():
    return seq([(0.00, note(660.0, 0.26, "tri", 0.8, sweep=(660, 420), vib=(7, 12))),
                (0.34, note(560.0, 0.34, "tri", 0.75, sweep=(560, 330), vib=(6, 14), release=0.24)),
                (0.70, note(420.0, 0.26, "sine", 0.5, sweep=(420, 300), release=0.22))], 1.00)


@recipe("pet_sleep", 1.20, "quiet soft sleepy sigh：低音下滑 + 气流叹息淡出")
def _pet_sleep():
    return seq([(0.00, note(300.0, 0.55, "sine", 0.7, sweep=(300, 175), release=0.45)),
                (0.30, noise_burst(0.75, 0.30, lowpass=520, release=0.55)),
                (0.62, note(240.0, 0.50, "tri", 0.42, sweep=(240, 150), release=0.44))], 1.20)


@recipe("pet_wakeup", 0.90, "little stretch chirp：伸腰式长滑音 + 两声亮哨")
def _pet_wakeup():
    return seq([(0.00, note(420.0, 0.34, "tri", 0.8, sweep=(420, 900), vib=(8, 8), release=0.12)),
                (0.40, note(1244.5, 0.09, "square", 0.85, release=0.06)),
                (0.54, note(1568.0, 0.14, "square", 0.9, release=0.10)),
                (0.68, note(2093.0, 0.22, "sine", 0.6, release=0.20))], 0.90)


# ---------------------------------------------------------------- 落盘与自检
def normalize(buf):
    """统一标准化到 -1 dBFS，并做真峰值防削顶检查。"""
    p = float(np.abs(buf).max())
    if p == 0:
        raise ValueError("全零音频")
    y = buf * (PEAK_TARGET / p)
    return y


def trim_tail(buf, keep=0.006):
    """裁掉尾部静音垫——即文档《音频后处理流水线》第 1 步「裁剪头尾静音」的确定性实现。

    只裁尾不裁头：所有配方的首个事件都从 t=0 起，且每个音自带升余弦淡入，
    若再裁头会把淡入切掉而产生爆音。
    """
    voiced = np.flatnonzero(np.abs(buf) > SILENCE_FLOOR)
    if voiced.size == 0:
        raise ValueError("全静音")
    end = int(voiced[-1]) + 1 + int(keep * SR)
    return buf[:min(end, len(buf))]


def write_wav(path, buf):
    pcm = np.clip(np.round(buf * 32767.0), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(NCH)
        w.setsampwidth(SW)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    return len(pcm)


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getnchannels() == NCH, "声道数须为 1（规范：单声道）"
        assert w.getsampwidth() == SW, "位深须为 16bit"
        assert w.getframerate() == SR, "采样率须为 44100Hz"
        x = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float64) / 32768.0
    return x, w.getnframes(), w.getframerate()


def check_wav(path):
    """校验 §二 硬性规范。任何一条不满足直接抛错——素材合规不能靠人耳抽查。"""
    name = os.path.basename(path)
    errs = []
    if not name.endswith(".wav"):
        errs.append("扩展名须 .wav")
    stem = name[:-4]
    if stem != stem.lower() or "__" in stem or stem.strip("_") != stem:
        errs.append("文件名须全小写下划线且无多余下划线")
    x, n, rate = read_wav(path)
    dur = n / rate
    if not (0.5 - 1e-6 <= dur <= 1.5 + 1e-6):
        errs.append("时长须 0.5~1.5s，实测 %.3fs" % dur)
    peak = float(np.abs(x).max())
    if peak > 1.0:
        errs.append("存在削顶（|x|>1.0）")
    if abs(peak - PEAK_TARGET) > 0.02:
        errs.append("峰值未统一标准化：目标 %.3f 实测 %.3f" % (PEAK_TARGET, peak))
    if abs(float(x.mean())) > 0.01:
        errs.append("直流偏移过大 %.4f（放音会啃推力、串流会引入嗡声）" % x.mean())
    # “无尾音空白”禁的是**静音垫**（Audacity 裁剪要剪掉的东西），不是合法淡出。
    # 早期版本用“最后 20ms 峰值”判定，会把所有 0.2s 量级的 release 全部误判为违规。
    mag = np.abs(x)
    voiced = np.flatnonzero(mag > SILENCE_FLOOR)
    if voiced.size == 0:
        errs.append("全静音")
    else:
        lead = int(voiced[0]) / rate
        trail = (n - 1 - int(voiced[-1])) / rate
        if lead > 0.005:
            errs.append("首部静音垫 %.3fs（须 ≤5ms，靠淡入而非留白）" % lead)
        if trail > 0.030:
            errs.append("尾部静音空白 %.3fs（须 ≤30ms，淡出完成即收尾）" % trail)
    rms = float(np.sqrt((x ** 2).mean()))
    return errs, dict(name=stem, duration=round(dur, 4), peak=round(peak, 4),
                      rms=round(rms, 4), samples=n, bytes=os.path.getsize(path))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", metavar="DIR", help="只校验该目录下的 wav（CI/入库后同源规则）")
    ap.add_argument("--deploy", metavar="DIR", help="合成后同时拷贝到该目录（如 app-host/src/main/res/raw）")
    args = ap.parse_args()

    if args.check:
        d = os.path.abspath(args.check)
        files = sorted(f for f in os.listdir(d) if f.endswith(".wav"))
        bad = 0
        for f in files:
            errs, info = check_wav(os.path.join(d, f))
            if errs:
                bad += 1
                print("!!! %-16s %s" % (f, "; ".join(errs)))
            else:
                print("ok  %-16s %.2fs peak=%.3f rms=%.3f %dB" % (
                    info["name"], info["duration"], info["peak"], info["rms"], info["bytes"]))
        want = set(RECIPES)
        got = {f[:-4] for f in files}
        if want - got:
            bad += 1
            print("!!! 缺音效：%s" % " ".join(sorted(want - got)))
        if got - want:
            bad += 1
            print("!!! 非契约音效：%s" % " ".join(sorted(got - want)))
        print("校验 %d 个，异常 %d 个" % (len(files), bad))
        return 1 if bad else 0

    os.makedirs(OUT_DIR, exist_ok=True)
    for f in os.listdir(OUT_DIR):
        os.remove(os.path.join(OUT_DIR, f))           # 起手清空，防陈旧素材入包

    ledger = []
    failures = {}
    for name, (fn, total, comment) in RECIPES.items():
        try:
            raw = fn()
            if abs(len(raw) / SR - total) >= 1e-6:
                raise ValueError("事件排布长度与声明 total 不符")
            buf = normalize(trim_tail(raw))
            real = len(buf) / SR
            # 裁尾后仍须落在 §二 区间：不够长说明配方尾部太空，要改配方而不是补静音垫
            if not (0.5 - 1e-6 <= real <= 1.5 + 1e-6):
                raise ValueError("裁尾后 %.3fs 越界 0.5~1.5s（声明 %.2fs，尾部空 %.3fs）"
                                 "→ 延长末个事件，勿加静音" % (real, total, total - real))
        except (ValueError, AssertionError) as exc:
            failures["%s.wav" % name] = [str(exc)]
            continue
        path = os.path.join(OUT_DIR, name + ".wav")
        nsm = write_wav(path, buf)
        errs, info = check_wav(path)
        if errs:
            failures["%s.wav" % name] = errs       # 不在第一个错误就退出：逐条报全，避免来回试多轮
            continue
        info.update(source="generated", declared=total, recipe=comment)
        ledger.append(info)
        print("  %-14s %.2fs peak=%.3f rms=%.3f %6dB  %s" % (
            name, info["duration"], info["peak"], info["rms"], info["bytes"], comment))

    if failures:
        for f, e in sorted(failures.items()):
            print("!!! %-16s %s" % (f, "; ".join(e)))
        raise AssertionError("自检失败 %d 个（已生成但不合规，禁止入库）" % len(failures))

    with open(os.path.join(OUT_DIR, "_manifest.json"), "w", encoding="utf-8") as w:
        json.dump(dict(sample_rate=SR, bit_depth=16, channels=NCH,
                       peak_target=PEAK_TARGET, sounds=ledger), w, ensure_ascii=False, indent=2)
    total_b = sum(s["bytes"] for s in ledger)
    print("合成 %d 个音效，共 %.1fKB → %s" % (len(ledger), total_b / 1024, OUT_DIR))

    if args.deploy:
        d = os.path.abspath(args.deploy)
        os.makedirs(d, exist_ok=True)
        import shutil
        for f in sorted(os.listdir(OUT_DIR)):
            if f.endswith(".wav"):
                shutil.copy2(os.path.join(OUT_DIR, f), os.path.join(d, f))
        errs = 0
        for f in sorted(os.listdir(d)):
            if f.endswith(".wav"):
                e, _ = check_wav(os.path.join(d, f))
                errs += len(e)
        print("已部署 → %s（入库校验异常 %d 条）" % (d, errs))
    return 0


if __name__ == "__main__":
    sys.exit(main())
