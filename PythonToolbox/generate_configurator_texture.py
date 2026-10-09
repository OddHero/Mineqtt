#!/usr/bin/env python3
"""Generate the Configurator item texture for the Mineqtt mod.

The Configurator (registry id cyberdeck) is crafted from a stick, copper, a Network Chip and an amethyst shard
(design section 7.3: " CA / CN / S  "). The 16x16 texture shows it as a hand-held probe lying diagonally like a vanilla
tool: a stick handle at the bottom left, a copper casing with a chip window and its ender-teal die in the middle, and an
amethyst crystal tip at the top right. Light comes from the top left. The background stays transparent, as item
textures with the minecraft:item/generated parent expect. It is written to textures/item/cyberdeck.png, the texture the
item model names.
"""
import os
from PIL import Image

os.chdir(os.path.dirname(os.path.abspath(__file__)))

TRANSPARENT = (0, 0, 0, 0)

# Stick handle
STICK_LIGHT = (160, 122, 52, 255)
STICK = (122, 92, 38, 255)
STICK_DARK = (73, 54, 21, 255)

# Copper casing
COPPER_LIGHT = (231, 146, 96, 255)
COPPER = (192, 107, 66, 255)
COPPER_DARK = (139, 72, 43, 255)
COPPER_OUTLINE = (88, 44, 26, 255)

# Chip window and its ender-teal die
PACKAGE = (34, 36, 44, 255)
ENDER = (40, 150, 136, 255)
ENDER_BRIGHT = (120, 230, 205, 255)

# Amethyst tip
AMETHYST_LIGHT = (222, 190, 255, 255)
AMETHYST = (165, 110, 214, 255)
AMETHYST_DARK = (110, 62, 160, 255)
AMETHYST_OUTLINE = (62, 32, 96, 255)


def along(x, y):
    """Position along the diagonal from the bottom left (-15) to the top right (15)."""
    return x - y


def across(x, y):
    """Offset across the diagonal: negative towards the lit top left, positive towards the shaded bottom right."""
    return x + y - 15


def handle(s, d):
    if not -13 <= s <= -4 or d not in (-1, 0):
        return None
    return STICK_LIGHT if d == -1 else STICK_DARK if s % 4 == 0 else STICK


def casing(s, d):
    if not -5 <= s <= 7 or not -3 <= d <= 2:
        return None
    # The chip window: a dark package with the die in its middle.
    if -1 <= s <= 4 and -1 <= d <= 0:
        if 1 <= s <= 2:
            return ENDER_BRIGHT if d == -1 else ENDER
        return PACKAGE
    if d == 2 or s in (-5, 7):
        return COPPER_OUTLINE
    if d == -3:
        return COPPER_LIGHT
    return COPPER if d < 0 else COPPER_DARK


def tip(s, d):
    if not 8 <= s <= 14:
        return None
    # A crystal that narrows towards its point.
    half = 2 if s <= 9 else 1 if s <= 12 else 0
    if not -half - 1 <= d <= half:
        return None
    if d == half and half > 0:
        return AMETHYST_OUTLINE
    if d == -half - 1:
        return AMETHYST_LIGHT
    return AMETHYST if d < 0 else AMETHYST_DARK


def create_configurator_texture():
    img = Image.new('RGBA', (16, 16), TRANSPARENT)
    for x in range(16):
        for y in range(16):
            s, d = along(x, y), across(x, y)
            color = tip(s, d) or casing(s, d) or handle(s, d)
            if color:
                img.putpixel((x, y), color)
    return img


def main():
    output_path = '../common/src/main/resources/assets/mineqtt/textures/item/cyberdeck.png'
    create_configurator_texture().save(output_path)
    print(f"- {output_path}: stick handle, copper casing with a chip window, amethyst tip")
    print("\nConfigurator texture generated successfully!")


if __name__ == '__main__':
    main()
