#!/usr/bin/env python3
"""生成内置语音包（assets/voice）。

词表在 app/src/main/assets/voice/lexicon.json：
    token  App 里写的朗读片段（VoicePack 按 token 拼句子）
    file   assets/voice/raw/<file>.raw
    text   交给 edge-tts 的原文（带标点，让断句自然）

用法：
    .venv/bin/python tools/gen_voice.py            # 只补缺失的
    .venv/bin/python tools/gen_voice.py --force    # 全部重生成
    .venv/bin/python tools/gen_voice.py --check    # 只体检，不生成

输出：22050Hz / 单声道 / s16le 裸 PCM，首尾静音已裁掉，
这样数字之间拼起来是连读，不会一个字一个字往外蹦。
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VOICE = os.path.join(ROOT, "app", "src", "main", "assets", "voice")
RAW = os.path.join(VOICE, "raw")
LEXICON = os.path.join(VOICE, "lexicon.json")
EDGE_TTS = os.path.join(ROOT, ".venv", "bin", "edge-tts")
VOICE_NAME = "zh-CN-XiaoxiaoNeural"
RATE = 22050

TRIM = (
    "silenceremove=start_periods=1:start_silence=0.005:start_threshold=-50dB,"
    "areverse,"
    "silenceremove=start_periods=1:start_silence=0.005:start_threshold=-50dB,"
    "areverse"
)


def load():
    with open(LEXICON, encoding="utf-8") as f:
        return json.load(f)["tokens"]


def gen_one(entry, force):
    name = entry["file"]
    out = os.path.join(RAW, name + ".raw")
    if os.path.exists(out) and os.path.getsize(out) > 2000 and not force:
        return name, "skip", os.path.getsize(out) / 2 / RATE
    with tempfile.TemporaryDirectory() as tmp:
        mp3 = os.path.join(tmp, "a.mp3")
        r = subprocess.run(
            [EDGE_TTS, "--voice", VOICE_NAME, "--text", entry["text"], "--write-media", mp3],
            capture_output=True, text=True,
        )
        if r.returncode != 0 or not os.path.exists(mp3):
            return name, "tts-fail: " + (r.stderr or "").strip()[-200:], 0
        r = subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", mp3, "-af", TRIM,
             "-ar", str(RATE), "-ac", "1", "-f", "s16le", out],
            capture_output=True, text=True,
        )
        if r.returncode != 0:
            return name, "ffmpeg-fail: " + (r.stderr or "").strip()[-200:], 0
    dur = os.path.getsize(out) / 2 / RATE
    return name, "ok", dur


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    entries = load()
    os.makedirs(RAW, exist_ok=True)
    print("词表 %d 条，音频目录 %s" % (len(entries), RAW))

    if args.check:
        missing = [e["file"] for e in entries if not os.path.exists(os.path.join(RAW, e["file"] + ".raw"))]
        if missing:
            print("缺失：", ", ".join(missing))
            return 1
        print("全部齐了")
        return 0

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda e: gen_one(e, args.force), entries))

    bad = [r for r in results if not r[1].startswith(("ok", "skip"))]
    for name, status, dur in results:
        print("%-18s %-8s %5.2fs" % (name, status, dur))
    total = sum(dur for _, _, dur in results)
    print("合计 %.1f 秒音频" % total)

    used = {e["file"] + ".raw" for e in entries}
    for f in sorted(os.listdir(RAW)):
        if f.endswith(".raw") and f not in used:
            os.remove(os.path.join(RAW, f))
            print("清理未引用：", f)

    if bad:
        print("失败 %d 条" % len(bad))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
