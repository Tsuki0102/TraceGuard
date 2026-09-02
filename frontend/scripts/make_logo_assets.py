# -*- coding: utf-8 -*-
"""从桌面 JPG logo 生成前端品牌资产：
1. 去除烤进 JPEG 的棋盘格假透明背景（色度键控 + 去灰边）
2. 裁切到内容包围盒
3. 生成 蓝色原版 / 主题金色版 两种透明 PNG + favicon.ico + apple-touch-icon
"""
from PIL import Image, ImageFilter, ImageDraw
import os

SRC = r"C:/Users/13784/Desktop/6a8151632415deb70e8b934b.jpg"
OUT_ASSETS = r"D:/Develop/TraceGuard/frontend/src/assets/logo"
OUT_PUBLIC = r"D:/Develop/TraceGuard/frontend/public"
os.makedirs(OUT_ASSETS, exist_ok=True)
os.makedirs(OUT_PUBLIC, exist_ok=True)

im = Image.open(SRC).convert("RGB")
w, h = im.size
px = im.load()

# ---- 1) 色度键控：蓝色 logo 的通道 spread 远大于中性灰棋盘格 ----
LO, HI = 14.0, 64.0  # spread 低于 LO 视为背景，高于 HI 视为实心
alpha_img = Image.new("L", (w, h))
apx = alpha_img.load()
BG_LUM = 247.0  # 棋盘格两色 240/254 的平均亮度，用于半透明边缘反解

data = []
for y in range(h):
    for x in range(w):
        r, g, b = px[x, y]
        spread = max(r, g, b) - min(r, g, b)
        if spread <= LO:
            a = 0
        elif spread >= HI:
            a = 255
        else:
            a = int((spread - LO) / (HI - LO) * 255)
        apx[x, y] = a

# 中值滤波去孤立噪点，再轻微膨胀回填边缘
alpha_img = alpha_img.filter(ImageFilter.MedianFilter(5))

rgba = im.convert("RGBA")
rpx = rgba.load()
apx = alpha_img.load()
for y in range(h):
    for x in range(w):
        a = apx[x, y]
        r, g, b, _ = rpx[x, y]
        if a == 0:
            rpx[x, y] = (0, 0, 0, 0)
        else:
            fa = a / 255.0
            if a < 255:  # 反解半透明边缘：C' = (C - (1-a)*BG)/a，消除灰边
                r = max(0, min(255, int((r - (1 - fa) * BG_LUM) / fa)))
                g = max(0, min(255, int((g - (1 - fa) * BG_LUM) / fa)))
                b = max(0, min(255, int((b - (1 - fa) * BG_LUM) / fa)))
            rpx[x, y] = (r, g, b, a)

# ---- 2) 裁切到内容包围盒（留 4% 呼吸边） ----
bbox = alpha_img.getbbox()
print("content bbox:", bbox)
pad = int(max(bbox[2] - bbox[0], bbox[3] - bbox[1]) * 0.04)
l = max(0, bbox[0] - pad); t = max(0, bbox[1] - pad)
r_ = min(w, bbox[2] + pad); b_ = min(h, bbox[3] + pad)
rgba = rgba.crop((l, t, r_, b_))
side = max(rgba.size)
canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
canvas.paste(rgba, ((side - rgba.size[0]) // 2, (side - rgba.size[1]) // 2))
rgba = canvas
print("cropped to:", rgba.size)

# ---- 3) 金色主题重着色：按原亮度映射到主题金渐变 ----
STOPS = [(0.0, (122, 92, 30)), (0.55, (201, 155, 63)), (1.0, (232, 200, 119))]  # 深金→蜜糖→奶油金

def ramp(t):
    for (t0, c0), (t1, c1) in zip(STOPS, STOPS[1:]):
        if t <= t1:
            f = 0 if t1 == t0 else (t - t0) / (t1 - t0)
            return tuple(int(c0[i] + (c1[i] - c0[i]) * f) for i in range(3))
    return STOPS[-1][1]

gold = rgba.copy()
gp = gold.load()
lmin, lmax = 1e9, -1e9
tmp = []
for y in range(gold.size[1]):
    for x in range(gold.size[0]):
        r, g, b, a = gp[x, y]
        if a > 128:
            lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            tmp.append((x, y, lum))
            lmin = min(lmin, lum); lmax = max(lmax, lum)
print("luminance range:", round(lmin, 1), round(lmax, 1))
for x, y, lum in tmp:
    r, g, b, a = gp[x, y]
    t = (lum - lmin) / max(1e-6, lmax - lmin)
    nr, ng, nb = ramp(t)
    # 保留一点原始明暗细节，避免完全压平
    detail = 0.82 + 0.36 * (lum / max(1e-6, lmax))
    gp[x, y] = (min(255, int(nr * detail)), min(255, int(ng * detail)), min(255, int(nb * detail)), a)

# ---- 4) 输出各尺寸资产 ----
def save(img, path, size=None):
    out = img.resize((size, size), Image.LANCZOS) if size else img
    out.save(path)
    print("saved", path, out.size)

save(rgba, os.path.join(OUT_ASSETS, "logo-blue.png"), 512)
save(gold, os.path.join(OUT_ASSETS, "logo-gold.png"), 512)
save(gold, os.path.join(OUT_ASSETS, "logo-gold-128.png"), 128)

# favicon.ico（16/32/48 三档）
ico_sizes = [(16, 16), (32, 32), (48, 48)]
gold.save(os.path.join(OUT_PUBLIC, "favicon.ico"), format="ICO", sizes=ico_sizes)
print("saved favicon.ico")

# apple-touch-icon：奶油底 + 金 logo（iOS 反色问题）
ati = Image.new("RGB", (180, 180), (250, 246, 239))
mark = gold.resize((150, 150), Image.LANCZOS)
ati.paste(mark, (15, 15), mark)
ati.save(os.path.join(OUT_PUBLIC, "apple-touch-icon.png"))
print("saved apple-touch-icon.png")

# ---- 5) 预览图：奶油底 / 深色底各一张，供人工检查抠图与配色质量 ----
for name, bg in [("preview-light.png", (250, 246, 239)), ("preview-dark.png", (23, 19, 15))]:
    pv = Image.new("RGB", (1092, 420), bg)
    for i, img in enumerate([rgba, gold]):
        m = img.resize((340, 340), Image.LANCZOS)
        pv.paste(m, (80 + i * 500, 40), m)
    pv.save(os.path.join(OUT_ASSETS, name))
    print("saved preview", name)
