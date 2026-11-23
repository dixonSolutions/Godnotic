import sys, glob
from PIL import Image
d, out = sys.argv[1], sys.argv[2]
cols = int(sys.argv[3]) if len(sys.argv) > 3 else 5
step = int(sys.argv[4]) if len(sys.argv) > 4 else 1
files = sorted(glob.glob(d + "/f*.png"))[::step]
w, h = 480, 300
rows = (len(files) + cols - 1) // cols
sheet = Image.new("RGB", (cols * w, rows * h), (40, 40, 40))
for i, f in enumerate(files):
    im = Image.open(f).convert("RGB").resize((w - 4, h - 4))
    sheet.paste(im, ((i % cols) * w + 2, (i // cols) * h + 2))
sheet.save(out)
print(len(files), "frames ->", out)
