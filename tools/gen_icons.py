#!/usr/bin/env python3
"""生成 DNSSwitch 遗留启动器图标（mipmap-*dpi PNG，minSdk 24 需要）。
纯标准库：zlib + struct，无第三方依赖。
设计：蓝色圆角方底 + 白色盾牌 + 3 条蓝色凹槽（与 adaptive icon 一致）。
"""
import os
import struct
import zlib

BLUE = (0x3B, 0x82, 0xF6)
WHITE = (0xFF, 0xFF, 0xFF)

# 108dp 视口内的盾牌轮廓（与 ic_launcher_foreground.xml 一致）
SHIELD_TOP_L, SHIELD_TOP_R = 14.0, 86.0
SHIELD_TOP_Y = 19.0
SHIELD_MID_Y = 50.0
TIP = (50.0, 94.0)

BARS = [  # (x, y, w, h, r)
    (27, 33, 46, 7, 3.5),
    (27, 46, 46, 7, 3.5),
    (27, 59, 32, 7, 3.5),
]

SS = 4  # 超采样倍数


def bezier(p0, p1, p2, p3, n=40):
    pts = []
    for i in range(n + 1):
        t = i / n
        mt = 1 - t
        x = mt**3 * p0[0] + 3 * mt * mt * t * p1[0] + 3 * mt * t * t * p2[0] + t**3 * p3[0]
        y = mt**3 * p0[1] + 3 * mt * mt * t * p1[1] + 3 * mt * t * t * p2[1] + t**3 * p3[1]
        pts.append((x, y))
    return pts


def shield_polygon():
    pts = [(SHIELD_TOP_L, SHIELD_TOP_Y), (SHIELD_TOP_R, SHIELD_TOP_Y)]
    # 右上：直线到 (86,50) 后贝塞尔到尖端
    pts += bezier((SHIELD_TOP_R, SHIELD_MID_Y), (86, 70), (70, 85), TIP)
    # 左下：贝塞尔回到 (14,50)
    pts += bezier(TIP, (30, 85), (SHIELD_TOP_L, 70), (SHIELD_TOP_L, SHIELD_MID_Y))
    return pts


def in_poly(x, y, poly):
    inside = False
    n = len(poly)
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y):
            xin = xj + (y - yj) * (xi - xj) / (yi - yj)
            if x < xin:
                inside = not inside
        j = i
    return inside


def in_round_rect(x, y, rx, ry, rw, rh, r):
    if x < rx or x > rx + rw or y < ry or y > ry + rh:
        return False
    # 四个角的圆心判定
    cx = min(max(x, rx + r), rx + rw - r)
    cy = min(max(y, ry + r), ry + rh - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r + 1e-9


def render(size):
    poly = shield_polygon()
    big = size * SS
    # 108 视口 -> big 像素
    k = big / 108.0
    acc = [[(0, 0, 0) for _ in range(size)] for _ in range(size)]
    for by in range(big):
        y = (by + 0.5) / k
        for bx in range(big):
            x = (bx + 0.5) / k
            if in_poly(x, y, poly):
                c = WHITE
            elif any(in_round_rect(x, y, b[0], b[1], b[2], b[3], b[4]) for b in BARS):
                c = BLUE
            else:
                c = BLUE  # 底色
            px, py = bx // SS, by // SS
            r, g, b = acc[py][px]
            acc[py][px] = (r + c[0], g + c[1], b + c[2])
    rows = []
    inv = 1.0 / (SS * SS)
    for y in range(size):
        row = bytearray()
        row.append(0)  # filter type: None
        for x in range(size):
            r, g, b = acc[y][x]
            row += bytes((round(r * inv), round(g * inv), round(b * inv)))
        rows.append(bytes(row))
    return b"".join(rows)


def png_chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data +
            struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def write_png(path, size):
    raw = render(size)
    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)  # 8bit RGB
    idat = zlib.compress(raw, 9)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(png_chunk(b"IHDR", ihdr))
        f.write(png_chunk(b"IDAT", idat))
        f.write(png_chunk(b"IEND", b""))
    print("written", path, size)


def main():
    base = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "app", "src", "main", "res")
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for d, px in densities.items():
        dstdir = os.path.join(base, "mipmap-" + d)
        os.makedirs(dstdir, exist_ok=True)
        write_png(os.path.join(dstdir, "ic_launcher.png"), px)
        write_png(os.path.join(dstdir, "ic_launcher_round.png"), px)


if __name__ == "__main__":
    main()
