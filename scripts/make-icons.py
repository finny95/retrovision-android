#!/usr/bin/env python3
"""Builds the Android launcher icons, TV banner and splash screens from the web app's icon.

Usage: python3 scripts/make-icons.py www/icon-512.png

The source icon comes from the live Base44 bundle (fetched by fetch-bundle.mjs),
so the installed app always matches the icon you set in Base44.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
APP_NAME = "RetroVision"
DARK = (10, 10, 10, 255)
AMBER = (251, 191, 36, 255)

LAUNCHER = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SPLASH = {
    "drawable": (480, 320),
    "drawable-land-mdpi": (480, 320),
    "drawable-land-hdpi": (800, 480),
    "drawable-land-xhdpi": (1280, 720),
    "drawable-land-xxhdpi": (1600, 960),
    "drawable-land-xxxhdpi": (1920, 1280),
    "drawable-port-mdpi": (320, 480),
    "drawable-port-hdpi": (480, 800),
    "drawable-port-xhdpi": (720, 1280),
    "drawable-port-xxhdpi": (960, 1600),
    "drawable-port-xxxhdpi": (1280, 1920),
}


def save(img, folder, name):
    path = os.path.join(RES, folder)
    os.makedirs(path, exist_ok=True)
    img.save(os.path.join(path, name), optimize=True)


def background_colour(icon):
    """Colour behind adaptive icons: the icon's own corner colour when it is opaque."""
    r, g, b, a = icon.getpixel((2, 2))
    return (r, g, b, 255) if a > 200 else DARK


def rounded_mask(size, radius_ratio):
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=int(size * radius_ratio), fill=255)
    return mask


def font(size):
    for candidate in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationMono-Bold.ttf",
    ):
        if os.path.exists(candidate):
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def main(src):
    icon = Image.open(src).convert("RGBA")
    bg = background_colour(icon)

    for density, px in LAUNCHER.items():
        folder = f"mipmap-{density}"
        # Legacy square icon with softened corners, and a round variant.
        square = icon.resize((px, px), Image.LANCZOS)
        legacy = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        legacy.paste(square, (0, 0), rounded_mask(px, 0.18))
        save(legacy, folder, "ic_launcher.png")

        circle = Image.new("L", (px, px), 0)
        ImageDraw.Draw(circle).ellipse((0, 0, px - 1, px - 1), fill=255)
        round_icon = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        round_icon.paste(square, (0, 0), circle)
        save(round_icon, folder, "ic_launcher_round.png")

        # Adaptive foreground: 108dp canvas, artwork inside the 72dp safe zone.
        canvas = round(px * 108 / 48)
        inner = round(canvas * 72 / 108)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        art = icon.resize((inner, inner), Image.LANCZOS)
        fg.paste(art, ((canvas - inner) // 2, (canvas - inner) // 2), art)
        save(fg, folder, "ic_launcher_foreground.png")

    with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
            f'    <color name="ic_launcher_background">#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}</color>\n'
            "</resources>\n"
        )

    # Android TV home-screen banner (320x180 at xhdpi).
    banner = Image.new("RGBA", (320, 180), DARK)
    art = icon.resize((112, 112), Image.LANCZOS)
    banner.paste(art, (20, 34), rounded_mask(112, 0.18))
    draw = ImageDraw.Draw(banner)
    size = 26
    while size > 12 and draw.textlength(APP_NAME, font=font(size)) > 320 - 146 - 14:
        size -= 1
    draw.text((146, 72), APP_NAME, font=font(size), fill=AMBER)
    draw.text((147, 106), "CRT MEDIA PLAYER", font=font(11), fill=(160, 160, 160, 255))
    save(banner, "drawable-xhdpi", "banner.png")

    # Splash screens: dark background with the icon in the middle.
    for folder, (w, h) in SPLASH.items():
        splash = Image.new("RGBA", (w, h), DARK)
        size = int(min(w, h) * 0.32)
        art = icon.resize((size, size), Image.LANCZOS)
        splash.paste(art, ((w - size) // 2, (h - size) // 2), rounded_mask(size, 0.18))
        save(splash.convert("RGB"), folder, "splash.png")

    print(f"Icons, banner and splash screens written from {src}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: make-icons.py <icon.png>")
    main(sys.argv[1])
