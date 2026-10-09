#!/usr/bin/env python3
"""Generate the Network Chip item texture for the Mineqtt mod.

The Network Chip is the one crafting component of the MineQTT line, made from copper,
redstone, quartz and an ender pearl. The 16x16 texture shows it as a top-down integrated
circuit: a dark package with copper pins on all four sides, a quartz pin-1 marker, redstone
traces and a glowing ender-teal die in the middle. The background stays transparent, as
item textures with the minecraft:item/generated parent expect.
"""
import os
from PIL import Image

os.chdir(os.path.dirname(os.path.abspath(__file__)))

TRANSPARENT = (0, 0, 0, 0)

# Package (the chip's dark plastic body)
PACKAGE_OUTLINE = (18, 18, 24, 255)
PACKAGE_DARK = (34, 36, 44, 255)
PACKAGE_MID = (44, 47, 57, 255)
PACKAGE_HIGHLIGHT = (60, 64, 76, 255)

# Copper pins
COPPER_LIGHT = (231, 146, 96, 255)
COPPER = (192, 107, 66, 255)
COPPER_DARK = (139, 72, 43, 255)

# Quartz pin-1 marker
QUARTZ = (236, 230, 220, 255)

# Redstone traces
REDSTONE = (170, 24, 18, 255)
REDSTONE_DIM = (110, 18, 16, 255)

# Ender pearl die
ENDER_DARK = (18, 74, 70, 255)
ENDER = (40, 150, 136, 255)
ENDER_BRIGHT = (120, 230, 205, 255)

# The package covers x and y 3..12; pins sit symmetric around the centre line 7.5.
BODY_MIN = 3
BODY_MAX = 12
PIN_POSITIONS = (4, 6, 9, 11)


def put(img, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        img.putpixel((x, y), color)


def draw_pins(img):
    """Two-pixel copper pins on every side: the outer pixel darker, as if bent down."""
    for p in PIN_POSITIONS:
        # Left and right
        put(img, BODY_MIN - 1, p, COPPER)
        put(img, BODY_MIN - 2, p, COPPER_DARK)
        put(img, BODY_MAX + 1, p, COPPER)
        put(img, BODY_MAX + 2, p, COPPER_DARK)
        # Top and bottom
        put(img, p, BODY_MIN - 1, COPPER_LIGHT)
        put(img, p, BODY_MIN - 2, COPPER)
        put(img, p, BODY_MAX + 1, COPPER)
        put(img, p, BODY_MAX + 2, COPPER_DARK)


def draw_package(img):
    """The body with a dark outline, a lit top-left edge and a slightly lighter inner field."""
    for x in range(BODY_MIN, BODY_MAX + 1):
        for y in range(BODY_MIN, BODY_MAX + 1):
            edge = x in (BODY_MIN, BODY_MAX) or y in (BODY_MIN, BODY_MAX)
            put(img, x, y, PACKAGE_OUTLINE if edge else PACKAGE_DARK)
    for i in range(BODY_MIN + 1, BODY_MAX):
        put(img, i, BODY_MIN + 1, PACKAGE_HIGHLIGHT)
        put(img, BODY_MIN + 1, i, PACKAGE_HIGHLIGHT)
    for x in range(BODY_MIN + 2, BODY_MAX - 1):
        for y in range(BODY_MIN + 2, BODY_MAX - 1):
            put(img, x, y, PACKAGE_MID)


def draw_traces(img):
    """Redstone traces from the die towards the pins."""
    for x in (5, 10):
        put(img, x, 6, REDSTONE_DIM)
        put(img, x, 9, REDSTONE_DIM)
    for y in (5, 10):
        put(img, 6, y, REDSTONE)
        put(img, 9, y, REDSTONE)


def draw_die(img):
    """A glowing ender-teal die in the centre, with the quartz pin-1 marker in the corner."""
    for x in range(6, 10):
        for y in range(6, 10):
            put(img, x, y, ENDER_DARK)
    for x in range(7, 9):
        for y in range(7, 9):
            put(img, x, y, ENDER)
    put(img, 7, 7, ENDER_BRIGHT)
    put(img, BODY_MIN + 1, BODY_MIN + 1, QUARTZ)


def create_network_chip_texture():
    img = Image.new('RGBA', (16, 16), TRANSPARENT)
    draw_pins(img)
    draw_package(img)
    draw_traces(img)
    draw_die(img)
    return img


def main():
    output_path = '../common/src/main/resources/assets/mineqtt/textures/item/network_chip.png'
    create_network_chip_texture().save(output_path)
    print(f"- {output_path}: copper-pinned chip with an ender-teal die")
    print("\nNetwork Chip texture generated successfully!")


if __name__ == '__main__':
    main()
