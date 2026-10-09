#!/usr/bin/env python3
"""Generate the Bridge Console block textures for the Mineqtt mod.

The Bridge Console (block id terminal_block, M2-23) is a full block that shows how the MQTT bridge
runs. Its model (models/block/terminal_block.json, parent minecraft:block/orientable) has a front, a
top and four sides; the front faces the player who placed it.

- terminal_block_front.png: the slate housing of the MineQTT devices with a dark screen showing
  ender-teal status lines, one line flagged amber, and below the screen a status LED, a link LED and
  three function keys.
- terminal_block_side.png: the housing with vent slits.
- terminal_block_top.png: the housing with a cable grommet and the corner screws.
"""
import os
from PIL import Image

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Housing (the slate of the Smart Plug, the Climate Sensor and the Wall Switch)
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (55, 60, 70, 255)
BODY_MID = (65, 72, 85, 255)
BODY_LIGHT = (75, 85, 100, 255)
BODY_HIGHLIGHT = (85, 95, 115, 255)
SLIT = (36, 40, 50, 255)
SCREW = (100, 105, 115, 255)

# Screen
BEZEL = (30, 33, 42, 255)
SCREEN = (16, 22, 30, 255)
SCREEN_GLARE = (26, 34, 44, 255)
TEXT = (60, 180, 160, 255)
TEXT_BRIGHT = (120, 230, 205, 255)
TEXT_DIM = (40, 110, 100, 255)
AMBER = (225, 170, 60, 255)

# LEDs and keys
ENDER = (40, 150, 136, 255)
ENDER_BRIGHT = (120, 230, 205, 255)
LED_AMBER = (200, 140, 40, 255)
KEY = (95, 102, 118, 255)
KEY_SHADOW = (45, 50, 60, 255)
GROMMET = (24, 26, 32, 255)
GROMMET_RIM = (90, 96, 110, 255)


def put(img, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        img.putpixel((x, y), color)


def fill(img, x0, y0, x1, y1, color):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            put(img, x, y, color)


def draw_housing(img, screws=True):
    """Dark outer border, a lit top-left bevel, a shaded bottom-right bevel and corner screws."""
    fill(img, 0, 0, 15, 15, BODY_MID)
    for i in range(16):
        put(img, i, 0, DARK_BORDER)
        put(img, i, 15, DARK_BORDER)
        put(img, 0, i, DARK_BORDER)
        put(img, 15, i, DARK_BORDER)
    for i in range(1, 15):
        put(img, i, 1, BODY_HIGHLIGHT)
        put(img, 1, i, BODY_LIGHT)
        put(img, i, 14, BODY_DARK)
        put(img, 14, i, BODY_DARK)
    if screws:
        for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
            put(img, x, y, SCREW)


def draw_screen(img):
    """A bezel from (2, 2) to (13, 10) around the screen, which shows four status lines."""
    fill(img, 2, 2, 13, 10, BEZEL)
    fill(img, 3, 3, 12, 9, SCREEN)
    # A faint glare across the top-right corner.
    for x, y in ((11, 3), (12, 3), (12, 4)):
        put(img, x, y, SCREEN_GLARE)
    # Status lines: label, then value, as the console's Bridge tab lists them; one flagged amber.
    lines = (
        (3, ((4, 5, TEXT_DIM), (7, 10, TEXT_BRIGHT))),
        (5, ((4, 5, TEXT_DIM), (7, 9, TEXT))),
        (7, ((4, 5, TEXT_DIM), (7, 8, AMBER))),
        (9, ((4, 5, TEXT_DIM), (7, 11, TEXT))),
    )
    for y, runs in lines:
        for x0, x1, color in runs:
            for x in range(x0, x1 + 1):
                put(img, x, y, color)


def draw_controls(img):
    """Below the screen: a lit status LED, an amber link LED and three function keys."""
    put(img, 3, 12, ENDER)
    put(img, 4, 12, ENDER_BRIGHT)
    put(img, 6, 12, LED_AMBER)
    for x0 in (8, 10, 12):
        put(img, x0, 12, KEY)
        put(img, x0, 13, KEY_SHADOW)


def create_front():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    draw_housing(img, screws=False)
    draw_screen(img)
    draw_controls(img)
    return img


def create_side():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    draw_housing(img)
    # Two columns of vent slits.
    for y in range(4, 12, 2):
        for x in range(4, 7):
            put(img, x, y, SLIT)
        for x in range(9, 12):
            put(img, x, y, SLIT)
    return img


def create_top():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    draw_housing(img)
    # A round cable grommet in the middle.
    for x in range(16):
        for y in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d <= 1.6:
                put(img, x, y, GROMMET)
            elif d <= 2.6:
                put(img, x, y, GROMMET_RIM)
    return img


def main():
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'
    textures = {
        'terminal_block_front': (create_front, 'Slate housing with the status screen, LEDs and keys'),
        'terminal_block_side': (create_side, 'Slate housing with vent slits'),
        'terminal_block_top': (create_top, 'Slate housing with a cable grommet'),
    }
    for name, (create, description) in textures.items():
        output_path = f'{base_path}{name}.png'
        create().save(output_path)
        print(f"- {output_path}: {description}")
    print("\nBridge Console textures generated successfully!")


if __name__ == '__main__':
    main()
