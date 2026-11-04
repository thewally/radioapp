#!/usr/bin/env python3
"""Create/replace a station's logo drawable from an image file.

Produces app/src/main/res/drawable-nodpi/ic_logo_<key>.png as a SQUARE image:
the source logo is centered with padding on a rounded, contrasting background
(white for dark logos, dark for light ones). Square + contrasting artwork keeps
Android Auto's grid/list thumbnails from squishing non-square logos and keeps
dark logos (e.g. Radio 538) legible on Auto's dark surface. Used everywhere:
the app list, now-playing header, media notification, lock screen, and Auto.

Usage: python3 scripts_set_logo.py <key> <image_path>
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

REPO = Path(__file__).resolve().parent
DRAWABLE_DIR = REPO / "app/src/main/res/drawable-nodpi"

SIZE = 512
PAD_FRAC = 0.14
CORNER_FRAC = 0.16
LIGHT_BG = (255, 255, 255, 255)
DARK_BG = (27, 27, 31, 255)
# Above this mean luminance the logo's own ink is light, so it needs a dark bg.
LIGHT_LOGO_LUM = 160


def _opaque_luminance(im: Image.Image) -> float:
    px = im.load()
    w, h = im.size
    vals = []
    for y in range(0, h, max(1, h // 60)):
        for x in range(0, w, max(1, w // 60)):
            r, g, b, a = px[x, y]
            if a >= 20:
                vals.append(0.299 * r + 0.587 * g + 0.114 * b)
    return sum(vals) / len(vals) if vals else 128.0


def make_square_art(src: Image.Image) -> Image.Image:
    src = src.convert("RGBA")
    bg = DARK_BG if _opaque_luminance(src) > LIGHT_LOGO_LUM else LIGHT_BG

    mask = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, SIZE - 1, SIZE - 1], radius=int(SIZE * CORNER_FRAC), fill=255
    )
    canvas = Image.composite(
        Image.new("RGBA", (SIZE, SIZE), bg),
        Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0)),
        mask,
    )

    avail = int(SIZE * (1 - 2 * PAD_FRAC))
    scale = min(avail / src.width, avail / src.height)
    lw, lh = int(src.width * scale), int(src.height * scale)
    logo = src.resize((lw, lh), Image.LANCZOS)
    canvas.alpha_composite(logo, ((SIZE - lw) // 2, (SIZE - lh) // 2))
    return canvas


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    key, image_path = sys.argv[1], Path(sys.argv[2])

    art = make_square_art(Image.open(image_path))
    DRAWABLE_DIR.mkdir(parents=True, exist_ok=True)
    out = DRAWABLE_DIR / f"ic_logo_{key}.png"
    art.save(out)
    print(f"Wrote {out.relative_to(REPO)} ({art.size[0]}x{art.size[1]})")


if __name__ == "__main__":
    main()
