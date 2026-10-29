#!/usr/bin/env python3
"""生成黑猪数学的 App 图标。

为什么要有这个脚本：电视桌面显示的是 mipmap 里的位图（自适应图标那套在不少电视
桌面上不生效），以前只有矢量里画了猪头，位图是旧的/深色压深色，桌面上就成了一团。
所以这里同时生成：
    mipmap-*/ic_launcher.png             传统方形图标（画满 512 → 缩小）
    mipmap-*/ic_launcher_foreground.png  自适应图标前景（透明底，猪头收在安全区里）

用法：.venv/bin/python tools/gen_icon.py
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

S = 1024                     # 先在大画布上画，再缩小，边缘才干净
CHARCOAL = (46, 46, 58, 255)
PINK = (255, 159, 178, 255)
PINK_DARK = (231, 120, 143, 255)
GOLD_TOP = (255, 208, 102)
GOLD_BOTTOM = (255, 168, 46)

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


def draw_pig(draw, scale=1.0, offset=(0, 0), shadows=True):
    """在 1024 坐标系里画猪头，scale/offset 用于自适应图标的安全区收放"""
    def P(x, y):
        return ((x * scale + offset[0]), (y * scale + offset[1]))

    def ell(box, fill):
        draw.ellipse([P(box[0], box[1]), P(box[2], box[3])], fill=fill)

    def poly(points, fill):
        draw.polygon([P(x, y) for x, y in points], fill=fill)

    # 耳朵（先画，被脸压住一半）
    poly([(150, 250), (150, 90), (330, 200)], CHARCOAL)
    poly([(874, 250), (874, 90), (694, 200)], CHARCOAL)
    poly([(206, 232), (204, 158), (296, 210)], PINK_DARK)
    poly([(818, 232), (820, 158), (728, 210)], PINK_DARK)

    # 脸
    ell([110, 150, 914, 860], CHARCOAL)

    # 腮红
    ell([150, 600, 300, 700], (255, 159, 178, 120))
    ell([724, 600, 874, 700], (255, 159, 178, 120))

    # 眼睛
    ell([278, 380, 430, 532], (255, 255, 255, 255))
    ell([594, 380, 746, 532], (255, 255, 255, 255))
    ell([330, 420, 392, 486], CHARCOAL)
    ell([632, 420, 694, 486], CHARCOAL)
    # 眼神高光
    ell([352, 432, 378, 458], (255, 255, 255, 255))
    ell([646, 432, 672, 458], (255, 255, 255, 255))

    # 鼻子
    ell([370, 560, 654, 750], PINK)
    ell([432, 615, 486, 690], CHARCOAL)
    ell([538, 615, 592, 690], CHARCOAL)


ICON_SCALE = 0.84


def icon_legacy(size):
    img = gradient(S, GOLD_TOP, GOLD_BOTTOM).convert("RGBA")
    draw = ImageDraw.Draw(img, "RGBA")
    pad = S * (1 - ICON_SCALE) / 2
    draw_pig(draw, scale=ICON_SCALE, offset=(pad, pad))
    img.putalpha(rounded_mask(S, int(S * 0.235)))
    return img.resize((size, size), Image.LANCZOS)


def icon_foreground(size):
    """自适应图标前景：画布 108dp，内容必须在中间 66dp 的安全区内"""
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img, "RGBA")
    inner = 0.58                       # 猪头占画布 58%，落在 66/108 ≈ 61% 安全区里
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
    # README / 电视横幅用的大图
    icon_legacy(512).save(os.path.join(RES, "drawable-nodpi", "app_icon_512.png")) \
        if os.path.isdir(os.path.join(RES, "drawable-nodpi")) else None


def icon_round(size):
    img = gradient(S, GOLD_TOP, GOLD_BOTTOM).convert("RGBA")
    draw = ImageDraw.Draw(img, "RGBA")
    pad = S * (1 - ICON_SCALE) / 2
    draw_pig(draw, scale=ICON_SCALE, offset=(pad, pad))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, S - 1, S - 1], fill=255)
    img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    main()
