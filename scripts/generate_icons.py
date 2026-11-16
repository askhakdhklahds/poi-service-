import os
from PIL import Image, ImageDraw, ImageFont

OUT = r"D:\jue\gis\poi-service\src\main\resources\static\icons"
os.makedirs(OUT, exist_ok=True)

SIZE = 64
FONT = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 34)

CATS = {
    "food": ("餐", "#ef4444"),
    "shop": ("购", "#f59e0b"),
    "hospital": ("医", "#dc2626"),
    "school": ("学", "#8b5cf6"),
    "transport": ("交", "#2563eb"),
    "attraction": ("景", "#10b981"),
    "business": ("商", "#ec4899"),
    "other": ("其", "#64748b"),
}

for key, (char, color) in CATS.items():
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse((3, 3, SIZE - 3, SIZE - 3),
                 fill=color, outline="white", width=3)
    bbox = draw.textbbox((0, 0), char, font=FONT)
    w = bbox[2] - bbox[0]
    h = bbox[3] - bbox[1]
    x = (SIZE - w) / 2 - bbox[0]
    y = (SIZE - h) / 2 - bbox[1]
    draw.text((x, y), char, font=FONT, fill="white")
    img.save(os.path.join(OUT, key + ".png"))

print("icons generated:", len(CATS))
