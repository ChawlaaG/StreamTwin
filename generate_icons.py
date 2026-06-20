import os
import sys
from PIL import Image, ImageDraw
import glob

res_dir = r"c:\Users\manis\.gemini\antigravity\scratch\StreamTwin\app\src\main\res"
img_path = r"C:\Users\manis\Downloads\icon.png"

play_store_icon_dir = r"c:\Users\manis\.gemini\antigravity\scratch\StreamTwin\app\src\main\play_store"
os.makedirs(play_store_icon_dir, exist_ok=True)

img = Image.open(img_path).convert("RGBA")
img_resized_512 = img.resize((512, 512), Image.Resampling.LANCZOS)
img_resized_512.save(os.path.join(play_store_icon_dir, "icon.png"))

sizes = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

# Clean old drawable files
for file in glob.glob(os.path.join(res_dir, "drawable", "ic_launcher*")):
    os.remove(file)
# Clean old default mipmap XML files
for file in glob.glob(os.path.join(res_dir, "mipmap", "ic_launcher*.xml")):
    os.remove(file)

for density, (legacy_size, adaptive_size) in sizes.items():
    mipmap_dir = os.path.join(res_dir, f"mipmap-{density}")
    os.makedirs(mipmap_dir, exist_ok=True)
    
    square_img = img.resize((legacy_size, legacy_size), Image.Resampling.LANCZOS)
    square_img.save(os.path.join(mipmap_dir, "ic_launcher.png"))
    
    mask = Image.new("L", (legacy_size, legacy_size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, legacy_size, legacy_size), fill=255)
    round_img = square_img.copy()
    round_img.putalpha(mask)
    round_img.save(os.path.join(mipmap_dir, "ic_launcher_round.png"))
    
    fg_img = Image.new("RGBA", (adaptive_size, adaptive_size), (0,0,0,0))
    safe_img_size = int(adaptive_size * (80 / 108.0)) # a bit larger to fill safely
    img_scaled = img.resize((safe_img_size, safe_img_size), Image.Resampling.LANCZOS)
    
    mask_scaled = Image.new("L", (safe_img_size, safe_img_size), 0)
    draw_scaled = ImageDraw.Draw(mask_scaled)
    # create a round crop of the main image for the foreground so it looks good on a solid background
    draw_scaled.ellipse((0, 0, safe_img_size, safe_img_size), fill=255)
    img_scaled_round = img_scaled.copy()
    img_scaled_round.putalpha(mask_scaled)

    offset = (adaptive_size - safe_img_size) // 2
    fg_img.paste(img_scaled_round, (offset, offset), mask_scaled)
    fg_img.save(os.path.join(mipmap_dir, "ic_launcher_foreground.png"))
    
    bg_color = img.getpixel((0,0))
    bg_img = Image.new("RGBA", (adaptive_size, adaptive_size), bg_color)
    bg_img.save(os.path.join(mipmap_dir, "ic_launcher_background.png"))

# Update anydpi-v26 XMLs
anydpi_dir = os.path.join(res_dir, "mipmap-anydpi-v26")
os.makedirs(anydpi_dir, exist_ok=True)

xml_content = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""

with open(os.path.join(anydpi_dir, "ic_launcher.xml"), "w") as f:
    f.write(xml_content)
with open(os.path.join(anydpi_dir, "ic_launcher_round.xml"), "w") as f:
    f.write(xml_content)

print("Icons generated successfully!")
