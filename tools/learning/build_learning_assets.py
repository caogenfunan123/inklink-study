#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""InkLink 学习版 · 资产加工管线(M1)

把参考仓库的离线教学数据转换为 APK 内置 JSON 零服务器资产。
运行前提:本仓库与参考仓库并列放置(../ref-hanzi-study 等),或用环境变量指定路径。
输出:app-host/src/main/assets/learning/*.json + lib/*.js
分级规则(实现文档第 2 节):StudyWord 频率序 easy+medium+hard 串联,
  [0,120)→L1  [120,500)→L2  [500,1300)→L3  [1300,∞)→L4;
  仅存在于 hanzi-study 的字按其自身顺序前 500 个→L3,其余→L4。
"""
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
REF_HANZI = os.environ.get("REF_HANZI", os.path.join(os.path.dirname(ROOT), "ref-hanzi-study"))
REF_JOYE = os.environ.get("REF_JOYE", os.path.join(os.path.dirname(ROOT), "ref-joye-preschool"))
REF_STUDYWORD = os.environ.get("REF_STUDYWORD", os.path.join(os.path.dirname(ROOT), "ref-studyword"))
OUT = os.path.join(ROOT, "app-host", "src", "main", "assets", "learning")

errors = []


def check(cond, msg):
    if cond:
        print("  [OK] %s" % msg)
    else:
        errors.append(msg)
        print("  [FAIL] %s" % msg)


def find_balanced(text, start):
    """从 start(应指向 '{' 或 '[')起做括号配平。支持单/双引号字符串与转义。返回 end(闭括号下标)。"""
    depth = 0
    in_str = ""  # ""=串外;否则为起引号字符
    i = start
    while i < len(text):
        c = text[i]
        if in_str:
            if c == "\\":
                i += 2
                continue
            if c == in_str:
                in_str = ""
        else:
            if c in "'\"":
                in_str = c
            elif c in "{[":
                depth += 1
            elif c in "}]":
                depth -= 1
                if depth == 0:
                    return i
        i += 1
    raise ValueError("括号不配平 at %d" % start)


def ts_to_json(segment):
    """TS/JS 对象字面量 → 合法 JSON 文本:裸键名补双引号、串外单引号换双引号、去尾逗号、剥注释。

    字符串支持单/双引号两种(如 "I'm fine" 中的撇号不能终止字符串)。
    """
    out = []
    in_str = ""  # ""=不在字符串内;否则为起引号字符(' 或 ")
    i = 0
    n = len(segment)

    def skip_ws(pos):
        while pos < n and segment[pos] in " \t\r\n":
            pos += 1
        return pos

    while i < n:
        c = segment[i]
        if in_str:
            if c == "\\":
                out.append(c)
                if i + 1 < n:
                    out.append(segment[i + 1])
                i += 2
                continue
            if c == in_str:
                out.append('"')
                in_str = ""
            else:
                out.append(c)
        else:
            if c in "'\"":
                out.append('"')
                in_str = c
            elif c == ",":
                # 尾逗号:其后(跳过空白)直接是 ] 或 } → 丢弃
                j = skip_ws(i + 1)
                if not (j < n and segment[j] in "]}"):
                    out.append(c)
            elif c == "/" and i + 1 < n and segment[i + 1] == "/":
                # 行注释:跳到行尾(不含换行符,保持行结构)
                j = segment.find("\n", i)
                i = j if j >= 0 else n
                continue
            elif c == "/" and i + 1 < n and segment[i + 1] == "*":
                # 块注释:跳到 */
                j = segment.find("*/", i + 2)
                i = (j + 1) if j >= 0 else n
                continue
            elif c.isalnum() or c in "_$":
                # 裸键名探测(含数字键):标识符且回看上一个非空白字符是 '{' 或 ','
                k = i
                while k < n and (segment[k].isalnum() or segment[k] in "_$"):
                    k += 1
                colon = skip_ws(k)
                if colon < n and segment[colon] == ":":
                    prev = i - 1
                    while prev >= 0 and segment[prev] in " \t\r\n":
                        prev -= 1
                    if prev >= 0 and segment[prev] in "{,":
                        out.append('"' + segment[i:k] + '"')
                        i = k
                        continue
                out.append(c)
            else:
                out.append(c)
        i += 1
    return "".join(out)


def extract_block(text, anchor):
    """定位 anchor(如 'export const foo'),提取其后第一个配平的 [...] 或 {...} 并转 JSON。"""
    idx = text.find(anchor)
    if idx < 0:
        raise ValueError("找不到 %s" % anchor)
    # 跳过类型注解(如 ': PinyinItem[]'),定位到赋值等号之后
    eq = text.find("=", idx)
    if eq < 0:
        raise ValueError("%s 后无赋值" % anchor)
    bracket = -1
    for off in range(eq, len(text)):
        if text[off] in "[{":
            bracket = off
            break
    if bracket < 0:
        raise ValueError("%s 后无数据块" % anchor)
    end = find_balanced(text, bracket)
    return json.loads(ts_to_json(text[bracket:end + 1]))


def strip_html_escape(s):
    return s.strip()


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(os.path.join(OUT, "lib"), exist_ok=True)

    # ---------- 1. 识字字库(hanzi-study + StudyWord 分级) ----------
    print("== 1) 识字字库 ==")
    dl = open(os.path.join(REF_HANZI, "js", "lib", "dataList.js"), encoding="utf-8").read()
    arr_start = dl.find("[")
    arr_end = find_balanced(dl, arr_start)
    hanzi_raw = json.loads(ts_to_json(dl[arr_start:arr_end + 1]))
    check(len(hanzi_raw) > 1200, "hanzi-study 字条数=%d (期望>1200)" % len(hanzi_raw))

    sw = json.load(open(os.path.join(REF_STUDYWORD, "app", "src", "main", "assets",
                                     "character_sets.json"), encoding="utf-8"))
    sw_order = sw["easy"] + sw["medium"] + sw["hard"]
    sw_rank = {c: i for i, c in enumerate(sw_order)}
    check(len(sw_order) == 3000, "StudyWord 字数=%d (期望3000)" % len(sw_order))

    def level_of_studyword(rank):
        if rank < 120:
            return 1
        if rank < 500:
            return 2
        if rank < 1300:
            return 3
        return 4

    hanzi_meta = {h["w"]: h for h in hanzi_raw}
    hanzi_only = [h for h in hanzi_raw if h["w"] not in sw_rank]
    seen = set()
    hanzi_entries = []
    for rank, ch in enumerate(sw_order):
        meta = hanzi_meta.get(ch)
        hanzi_entries.append({
            "char": ch,
            "pinyin": meta["y"] if meta else "",
            "phrase": meta["p"] if meta else "",
            "sentence": meta["s"] if meta else "",
            "level": level_of_studyword(rank),
        })
        seen.add(ch)
    for i, h in enumerate(hanzi_only):
        hanzi_entries.append({
            "char": h["w"], "pinyin": h["y"], "phrase": h["p"], "sentence": h["s"],
            "level": 3 if i < 500 else 4,
        })
        seen.add(h["w"])
    check(len(hanzi_entries) == len(seen), "字库去重后=%d,无重复" % len(seen))
    per_level = {}
    for e in hanzi_entries:
        per_level[e["level"]] = per_level.get(e["level"], 0) + 1
    print("  分级分布: %s" % sorted(per_level.items()))
    check(per_level.get(1) == 120 and per_level.get(2) == 380, "L1=120 L2=380")
    json.dump(hanzi_entries, open(os.path.join(OUT, "hanzi.json"), "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))

    # ---------- 2. 拼音(joye syllables/questions) ----------
    print("== 2) 拼音 ==")
    syl = open(os.path.join(REF_JOYE, "src", "data", "pinyin", "syllables.ts"), encoding="utf-8").read()
    pinyin = {
        "wholeSyllables": extract_block(syl, "export const wholeSyllables"),
        "commonSyllables": extract_block(syl, "export const commonSyllables"),
        "twoSpellExamples": extract_block(syl, "export const twoSpellExamples"),
        "threeSpellExamples": extract_block(syl, "export const threeSpellExamples"),
        "toneMarks": extract_block(syl, "export const toneMarks"),
    }
    check(len(pinyin["wholeSyllables"]) == 16, "整体认读=16")
    check(len(pinyin["commonSyllables"]) == 106, "commonSyllables=106")
    check(len(pinyin["twoSpellExamples"]) == 246, "两拼=246")
    check(len(pinyin["threeSpellExamples"]) == 76, "三拼=76")
    json.dump(pinyin, open(os.path.join(OUT, "pinyin.json"), "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))

    q = open(os.path.join(REF_JOYE, "src", "data", "pinyin", "questions.ts"), encoding="utf-8").read()
    pairs = extract_block(q, "export const pinyinCharPairs")
    check(len(pairs) == 250, "拼音配对题=%d (源文件实测250)" % len(pairs))
    json.dump(pairs, open(os.path.join(OUT, "pinyin_questions.json"), "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))

    # ---------- 3. 英语(joye words/SentencesPage) ----------
    print("== 3) 英语 ==")
    w = open(os.path.join(REF_JOYE, "src", "data", "english", "words.ts"), encoding="utf-8").read()
    words = extract_block(w, "export const englishWords")
    check(len(words) >= 190, "英语单词=%d (期望~191)" % len(words))
    check(all("sentences" in x and x["sentences"] for x in words), "每个单词都带例句")
    json.dump(words, open(os.path.join(OUT, "english_words.json"), "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))

    sp = open(os.path.join(REF_JOYE, "src", "pages", "English", "SentencesPage.tsx"),
              encoding="utf-8").read()
    sents = extract_block(sp, "const sentencePatterns")
    ex_cnt = sum(len(p["examples"]) for cat in sents for p in cat["patterns"])
    check(ex_cnt >= 100, "句型例句=%d (期望~109)" % ex_cnt)
    json.dump(sents, open(os.path.join(OUT, "english_sentences.json"), "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))

    # ---------- 4. 笔顺库与 hanzi-writer 原样内置 ----------
    print("== 4) 笔顺库 ==")
    # 源项目笔画拆两段:dataWriter.js(writerData,422字)+ dataWriter1.js(运行时 Object.assign 合并 writerData1)
    dw = open(os.path.join(REF_HANZI, "js", "lib", "dataWriter.js"), encoding="utf-8").read()
    dw1 = open(os.path.join(REF_HANZI, "js", "lib", "dataWriter1.js"), encoding="utf-8").read()
    part1 = json.loads(dw[dw.find("{"):])
    part2 = json.loads(dw1[dw1.find("{", dw1.find("window.writerData1 =")):])
    merged = dict(part1)
    merged.update(part2)
    check(len(part1) + len(part2) == len(merged), "两段笔画无重复键(合并=%d)" % len(merged))
    check(len(merged) >= 1200, "笔顺字数=%d (期望>=1200)" % len(merged))
    first = next(iter(merged.values()))
    check("strokes" in first and len(first["strokes"]) > 0, "笔顺数据含 strokes")
    # 重新序列化为单文件纯 JSON,WebView 里 fetch 后 JSON.parse,比源运行时合并更简单
    json.dump(merged, open(os.path.join(OUT, "lib", "strokes.json"), "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))
    shutil.copy(os.path.join(REF_HANZI, "js", "lib", "hanzi-writer.min.js"),
                os.path.join(OUT, "lib", "hanzi-writer.min.js"))

    # ---------- 5. 汇总校验 ----------
    print("== 5) 输出清单 ==")
    total = 0
    for f in sorted(os.listdir(OUT)):
        p = os.path.join(OUT, f)
        if os.path.isfile(p):
            sz = os.path.getsize(p)
            total += sz
            print("  %-28s %8.1f KB" % (f, sz / 1024))
    for f in sorted(os.listdir(os.path.join(OUT, "lib"))):
        p = os.path.join(OUT, "lib", f)
        sz = os.path.getsize(p)
        total += sz
        print("  lib/%-24s %8.1f KB" % (f, sz / 1024))
    print("  合计 %.1f MB" % (total / 1024 / 1024))

    if errors:
        print("\n存在 %d 项校验失败:" % len(errors))
        for e in errors:
            print("  - " + e)
        sys.exit(1)
    print("\n全部校验通过。")


if __name__ == "__main__":
    main()
