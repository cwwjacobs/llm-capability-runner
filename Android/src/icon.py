import os
from PIL import Image

src = r"C:\Users\ultra\.gemini\antigravity\brain\92ef2a35-ef69-4d38-ad3f-0d77d962886a\media__1783193245147.png"
dest_res = r"C:\Users\ultra\OneDrive\Desktop\edge-lite-main\edge-lite-main\Android\src\lightapp\src\main\res"

sizes = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192
}

img = Image.open(src).convert("RGBA")
width, height = img.size
min_dim = min(width, height)
left = (width - min_dim) / 2
top = (height - min_dim) / 2
right = (width + min_dim) / 2
bottom = (height + min_dim) / 2
img = img.crop((left, top, right, bottom))

for density, size in sizes.items():
    resized = img.resize((size, size), Image.Resampling.LANCZOS)
    dir_path = os.path.join(dest_res, f"mipmap-{density}")
    os.makedirs(dir_path, exist_ok=True)
    resized.save(os.path.join(dir_path, "ic_launcher.png"))
    resized.save(os.path.join(dir_path, "ic_launcher_round.png"))

print("Icons generated successfully!")
