#!/usr/bin/env python3
"""生成黑猪数学的 App 图标与站内黑猪形象。

为什么要有这个脚本：电视桌面显示的是 mipmap 里的位图（自适应图标那套在不少电视
桌面上不生效），以前只有矢量里画了猪头，位图是旧的/深色压深色，桌面上就成了一团。
所以这里同时生成：
    mipmap-*/ic_launcher.png             传统方形图标（画满 1024 → 缩小）
    mipmap-*/ic_launcher_round.png       圆形图标（部分桌面用）
    mipmap-*/ic_launcher_foreground.png  自适应图标前景（透明底，猪头收在安全区里）
    drawable-xhdpi/ic_pig_head.png       透明底猪头，首页标题 / 关于页当图形用

2026-10-31 重画：黑猪要“一眼看出来是猪”——脸更大更圆、耳朵更宽（粉色内耳）、
鼻子更大更靠下、眼睛更大带高光，金色背景保持不变（品牌色）。

用法：.venv/bin/python tools/gen_icon.py
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

S = 1024                     # 先在大画布上画，再缩小，边缘才干净
CHARCOAL = (32, 32, 42, 255)     # 黑猪的“黑”：偏冷调，别用纯黑，金色背景上更有体积
PINK = (255, 150, 170, 255)
PINK_DARK = (226, 108, 132, 255)
GOLD_TOP = (255, 209, 102)
GOLD_BOTTOM = (255, 156, 30)

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def rounded_mask(size, radius):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    return m


def gradient(size, top, bottom):
    g = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / max(size - 1, 1)
        g.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return g.resize((size, size))


def draw_pig(draw, scale=1.0, offset=(0, 0)):
    """在 1024 坐标系里画正面猪头，scale/offset 用于自适应图标的安全区收放"""

    def P(x, y):
        return (x * scale + offset[0], y * scale + offset[1])

    def ell(box, fill):
        draw.ellipse([P(box[0], box[1]), P(box[2], box[3])], fill=fill)

    def poly(points, fill):
        draw.polygon([P(x, y) for x, y in points], fill=fill)

    # ---- 耳朵：先画，被脸压住下半截；外耳黑、内耳粉
    poly([(96, 268), (120, 62), (372, 196)], CHARCOAL)
    poly([(928, 268), (904, 62), (652, 196)], CHARCOAL)
    poly([(176, 236), (190, 128), (330, 200)], PINK_DARK)
    poly([(848, 236), (834, 128), (694, 200)], PINK_DARK)

    # ---- 脸：占画布 84%，圆一点更像猪
    ell([72, 128, 952, 952], CHARCOAL)

    # ---- 腮红
    ell([128, 636, 300, 748], (255, 150, 170, 110))
    ell([724, 636, 896, 748], (255, 150, 170, 110))

    # ---- 眼睛：白巩膜 + 黑瞳 + 高光，够大才在 48dp 上看得见
    ell([248, 348, 448, 548], (255, 255, 255, 255))
    ell([576, 348, 776, 548], (255, 255, 255, 255))
    ell([312, 404, 400, 492], CHARCOAL)
    ell([624, 404, 712, 492], CHARCOAL)
    ell([338, 420, 372, 454], (255, 255, 255, 255))     # 高光
    ell([650, 420, 684, 454], (255, 255, 255, 255))

    # ---- 鼻子：大、居中偏下，两个鼻孔
    ell([334, 590, 690, 812], PINK)
    ell([408, 656, 476, 748], CHARCOAL)
    ell([548, 656, 616, 748], CHARCOAL)


ICON_SCALE = 0.88


def _pig_on_gold(size, round_mask=False):
    img = gradient(S, GOLD_TOP, GOLD_BOTTOM).convert("RGBA")
    draw = ImageDraw.Draw(img, "RGBA")
    pad = S * (1 - ICON_SCALE) / 2
    draw_pig(draw, scale=ICON_SCALE, offset=(pad, pad))
    if round_mask:
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, S - 1, S - 1], fill=255)
        img.putalpha(mask)
    else:
        img.putalpha(rounded_mask(S, int(S * 0.235)))
    return img.resize((size, size), Image.LANCZOS)


def icon_legacy(size):
    return _pig_on_gold(size)


def icon_round(size):
    return _pig_on_gold(size, round_mask=True)


def icon_foreground(size):
    """自适应图标前景：画布 108dp，内容必须在中间 66dp 的安全区内"""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img, "RGBA")
    inner = 0.62                       # 猪头占画布 62%，落在 66/108 ≈ 61% 安全区里
    pad = S * (1 - inner) / 2
    draw_pig(draw, scale=inner, offset=(pad, pad))
    return img.resize((size, size), Image.LANCZOS)


def pig_head(size):
    """透明底猪头：首页标题「黑猪数学」前面的图形，也用不着圆角/金底"""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img, "RGBA")
    inner = 0.94                       # 站内用，不需要留安全区，尽量画满
    pad = S * (1 - inner) / 2
    draw_pig(draw, scale=inner, offset=(pad, pad))
    return img.resize((size, size), Image.LANCZOS)


def main():
    for folder, px in DENSITIES.items():
        d = os.path.join(RES, folder)
        os.makedirs(d, exist_ok=True)
        icon_legacy(px).save(os.path.join(d, "ic_launcher.png"))
        icon_round(px).save(os.path.join(d, "ic_launcher_round.png"))
        icon_foreground(int(px * 108 / 48)).save(
            os.path.join(d, "ic_launcher_foreground.png")
        )
        print(folder, px)

    # 站内透明底猪头（首页标题用），xhdpi 给 384px 够 48~96dp 显示
    d = os.path.join(RES, "drawable-xhdpi")
    os.makedirs(d, exist_ok=True)
    pig_head(384).save(os.path.join(d, "ic_pig_head.png"))
    print("drawable-xhdpi/ic_pig_head.png 384")


if __name__ == "__main__":
    main()
