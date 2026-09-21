#!/usr/bin/env python3
"""Делает голубую версию текстуры портала: сдвигает оттенок (hue) portal.png, сохраняя насыщенность,
яркость и прозрачность. Запусти после того, как перерисуешь portal.png:

    python3 tools/make_blue_portal.py [сдвиг_градусов]      (по умолчанию +100: зелёный -> голубой)

Нужен Pillow:  pip install pillow
"""
import colorsys
import os
import sys
from PIL import Image

shift = (float(sys.argv[1]) if len(sys.argv) > 1 else 100.0) / 360.0
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                    "assets", "portalgun", "textures", "model")
src = Image.open(os.path.join(root, "portal.png")).convert("RGBA")
out = Image.new("RGBA", src.size)
px_in, px_out = src.load(), out.load()
for y in range(src.height):
    for x in range(src.width):
        r, g, b, a = px_in[x, y]
        if a == 0:
            px_out[x, y] = (0, 0, 0, 0)
            continue
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        nr, ng, nb = colorsys.hsv_to_rgb((h + shift) % 1.0, s, v)
        px_out[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255), a)
out.save(os.path.join(root, "portal_blue.png"))
print("portal_blue.png готов")
