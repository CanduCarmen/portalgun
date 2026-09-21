#!/usr/bin/env python3
"""Серая заготовка портала (portal_gray.png) из portal.png: яркость (luminance), растянутая до 255, альфа сохраняется.
Мод красит её выбранным в меню цветом. Запуск: python3 tools/make_gray_portal.py   (нужен Pillow)"""
import os
from PIL import Image
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "portalgun", "textures", "model")
src = Image.open(os.path.join(root, "portal.png")).convert("RGBA")
px = src.load()
out = Image.new("RGBA", src.size)
po = out.load()
lum = {}
mx = 1
for y in range(src.height):
    for x in range(src.width):
        r, g, b, a = px[x, y]
        if a:
            l = 0.2126 * r + 0.7152 * g + 0.0722 * b
            lum[x, y] = l
            mx = max(mx, l)
for (x, y), l in lum.items():
    v = round(min(255, l / mx * 255))
    po[x, y] = (v, v, v, px[x, y][3])
out.save(os.path.join(root, "portal_gray.png"))
print("portal_gray.png готов")
