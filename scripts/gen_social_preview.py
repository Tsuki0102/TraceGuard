# -*- coding: utf-8 -*-
"""
生成 GitHub 仓库 Social Preview 卡片（1280x640，Settings -> General -> Social preview 上传）。
用法：python scripts/gen_social_preview.py
输出：.github/social-preview.png
依赖：pip install pillow
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / ".github" / "social-preview.png"
FONT = ROOT / "backend" / "src" / "main" / "resources" / "fonts" / "MiSans-Regular.otf"

W, H = 1280, 640

BG_TOP = (13, 27, 42)      # 深海军蓝
BG_BOTTOM = (27, 58, 75)   # 青蓝
ACCENT = (77, 163, 255)    # 亮蓝
GREEN = (61, 220, 151)     # 结果绿
TEXT_MAIN = (240, 246, 252)
TEXT_SUB = (148, 163, 184)


def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT), size)


def gradient() -> Image.Image:
    img = Image.new("RGB", (W, H), BG_TOP)
    px = img.load()
    for y in range(H):
        t = y / H
        r = int(BG_TOP[0] + (BG_BOTTOM[0] - BG_TOP[0]) * t)
        g = int(BG_TOP[1] + (BG_BOTTOM[1] - BG_TOP[1]) * t)
        b = int(BG_TOP[2] + (BG_BOTTOM[2] - BG_TOP[2]) * t)
        for x in range(W):
            px[x, y] = (r, g, b)
    return img


def glow_circles(img: Image.Image) -> Image.Image:
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse([900, -220, 1420, 300], fill=(77, 163, 255, 46))
    d.ellipse([-160, 430, 320, 790], fill=(61, 220, 151, 30))
    return Image.alpha_composite(img.convert("RGBA"), layer.filter(ImageFilter.GaussianBlur(2)))


def main() -> None:
    img = glow_circles(gradient())

    # 半透明形状必须画在独立 RGBA 层后 alpha_composite，直接画会被 convert(RGB) 丢 alpha 变实心
    shapes = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shapes)
    img = Image.alpha_composite(img, shapes)

    d = ImageDraw.Draw(img)

    f_tag = font(26)
    f_title = font(104)
    f_sub = font(31)
    f_chip = font(25)
    f_metric = font(34)
    f_tech = font(23)

    d.text((80, 66), "大学生创新训练计划项目 · 2026AIC 算法创新赛参赛作品", font=f_tag, fill=TEXT_SUB)

    d.text((76, 118), "TraceGuard", font=f_title, fill=TEXT_MAIN, stroke_width=3, stroke_fill=TEXT_MAIN)
    title_w = d.textlength("TraceGuard", font=f_title)
    pill = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    pd = ImageDraw.Draw(pill)
    pd.rounded_rectangle([82 + title_w + 26, 150, 82 + title_w + 218, 210], radius=16,
                         fill=(61, 220, 151, 42), outline=(61, 220, 151, 160), width=2)
    img = Image.alpha_composite(img, pill)
    d = ImageDraw.Draw(img)
    d.text((82 + title_w + 50, 162), "规码同轨", font=font(30), fill=GREEN)

    d.text((80, 262), "基于形式化需求规约与大模型融合的需求-代码一致性验证与缺陷自动检测系统",
           font=f_sub, fill=TEXT_SUB)

    chips = ["Alloy 形式化锚定", "五语言静态解析", "三维相似度+风险门控", "AI 智能体巡检", "全程本地可运行"]
    chip_layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    cd = ImageDraw.Draw(chip_layer)

    def chip_on_layer(x: int, y: int, text: str) -> float:
        w = cd.textlength(text, font=f_chip)
        asc, desc = f_chip.getmetrics()
        h = asc + desc
        cd.rounded_rectangle([x, y, x + w + 20 * 2, y + h + 11 * 2], radius=h // 2 + 11,
                             fill=(255, 255, 255, 20), outline=(255, 255, 255, 46), width=2)
        cd.text((x + 20, y + 11), text, font=f_chip, fill=TEXT_MAIN)
        return x + w + 40

    x, y = 80, 336
    for c in chips:
        x = chip_on_layer(x, y, c) + 14
    img = Image.alpha_composite(img, chip_layer)
    d = ImageDraw.Draw(img)

    d.text((80, 448), "验证集盲评", font=font(28), fill=TEXT_SUB)
    x = 80 + d.textlength("验证集盲评", font=font(28)) + 18
    d.text((x, 442), "95.0% 准确率", font=f_metric, fill=GREEN, stroke_width=1, stroke_fill=GREEN)
    x += d.textlength("95.0% 准确率", font=f_metric) + 30
    d.text((x, 442), "0.0% 漏检 · 7.3% 误报", font=f_metric, fill=ACCENT, stroke_width=1, stroke_fill=ACCENT)
    x += d.textlength("0.0% 漏检 · 7.3% 误报", font=f_metric) + 30
    d.text((x, 448), "双评 κ=0.8016", font=font(28), fill=TEXT_SUB)

    tech = "Spring Boot 2.7  ·  Vue 3  ·  MySQL 8  ·  JavaParser / Soot / tree-sitter  ·  ONNX BGE  ·  Ollama"
    while d.textlength(tech, font=f_tech) > W - 160:
        tech = tech[:-2].rstrip(" ·") + "…"
    d.text((80, 540), tech, font=f_tech, fill=(120, 134, 156))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(OUT, "PNG", optimize=True)
    size_kb = OUT.stat().st_size // 1024
    print(f"OK -> {OUT} ({size_kb} KB, {W}x{H})")


if __name__ == "__main__":
    main()
