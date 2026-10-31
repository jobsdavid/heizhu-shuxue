#!/usr/bin/env python3
"""生成电视桌面横幅（android:banner，320x180dp → 640x360 @xhdpi）。

图标换了以后横幅里的旧图标就对不上了，这里复用 gen_icon 的猪头画法，
保证桌面上一整排看过去是同一个形象。
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_icon import GOLD_BOTTOM, GOLD_TOP, ICON_SCALE, gradient, draw_pig  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-xhdpi", "tv_banner.png")

W, H = 640, 360
BG = (12, 15, 34)
FONT_BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
FONT_MED = "/usr/share/fonts/opentype/noto/NotoSansCJK-Medium.ttc"


def main():
    img = Image.new("RGB", (W, H), BG).convert("RGBA")
    d = ImageDraw.Draw(img, "RGBA")

    # 左侧图标（和 App 图标同款）
    # 注意：draw_pig 的坐标是按 1024 画的，画布也必须是 1024，最后再缩小
    icon = gradient(1024, GOLD_TOP, GOLD_BOTTOM).convert("RGBA")
    idraw = ImageDraw.Draw(icon, "RGBA")
    pad = 1024 * (1 - ICON_SCALE) / 2
    draw_pig(idraw, scale=ICON_SCALE, offset=(pad, pad))
    mask = Image.new("L", (1024, 1024), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 1023, 1023], radius=240, fill=255)
    icon.putalpha(mask)
    icon = icon.resize((200, 200), Image.LANCZOS)
    img.alpha_composite(icon, (44, 80))

    # 标题
    title = ImageFont.truetype(FONT_BOLD, 76)
    d.text((286, 96), "黑猪数学", font=title, fill=(255, 255, 255, 255))
    # 副标题
    sub = ImageFont.truetype(FONT_MED, 28)
    d.text((290, 196), "口算闯关 · 电视版", font=sub, fill=(154, 163, 199, 255))

    # 底部进度条装饰
    d.rounded_rectangle([290, 252, 590, 262], radius=5, fill=(61, 214, 140, 255))

    img.convert("RGB").save(OUT)
    print("written", OUT, img.size)


if __name__ == "__main__":
    main()
