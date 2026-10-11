#!/usr/bin/env python3
"""Generate the Display Panel block textures for the Mineqtt mod.

The Display Panel (block id display_panel) is a 16x16 plate, two pixels thick, on a wall: a slate frame around a
black glass screen, on which the renderer draws the panel's text. The model maps these textures onto its box:

- display_panel.png: the front. A one-pixel dark border, a lit top-left and a shaded bottom-right bevel on the frame
  and a 12x12 screen from (2, 2) to (13, 13), black glass with a faint diagonal sheen in its top-left corner. The
  renderer's text area is the screen less half a pixel on each side.
- display_panel_side.png: the back and the four edges, the plain slate body with a dark seam along its middle.
- textures/item/display_panel.png: the item, the panel seen from the front with three short cyan lines of "text".
"""
import os
from PIL import Image

os.chdir(os.path.dirname(os.path.abspath(__file__)))

OUT = '../common/src/main/resources/assets/mineqtt/textures/'

# Housing (the slate of the Smart Plug, the Climate Sensor and the Wall Switch)
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (55, 60, 70, 255)
BODY_MID = (65, 72, 85, 255)
BODY_LIGHT = (75, 85, 100, 255)
BODY_HIGHLIGHT = (85, 95, 115, 255)
SEAM = (48, 52, 62, 255)

# Screen
GLASS = (12, 14, 18, 255)
GLASS_EDGE = (6, 7, 10, 255)
SHEEN = (24, 28, 36, 255)

# Text on the item
TEXT = (90, 210, 220, 255)
TEXT_DIM = (50, 140, 150, 255)

SCREEN = (2, 2, 13, 13)


def fill(img, color):
    for x in range(16):
        for y in range(16):
            img.putpixel((x, y), color)


def frame(img):
    fill(img, BODY_MID)
    for i in range(16):
        img.putpixel((i, 0), DARK_BORDER)
        img.putpixel((i, 15), DARK_BORDER)
        img.putpixel((0, i), DARK_BORDER)
        img.putpixel((15, i), DARK_BORDER)
    for i in range(1, 15):
        img.putpixel((i, 1), BODY_HIGHLIGHT)
        img.putpixel((1, i), BODY_LIGHT)
        img.putpixel((i, 14), BODY_DARK)
        img.putpixel((14, i), BODY_DARK)
    img.putpixel((1, 1), BODY_HIGHLIGHT)


def screen(img):
    x0, y0, x1, y1 = SCREEN
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            edge = x in (x0, x1) or y in (y0, y1)
            img.putpixel((x, y), GLASS_EDGE if edge else GLASS)
    # A faint sheen across the top-left corner of the glass.
    for d in range(3):
        for x in range(x0 + 1, x1):
            y = y0 + 1 + (x - x0 - 1) - d - 2
            if y0 < y < y1 and x - x0 + y - y0 <= 7:
                img.putpixel((x, y), SHEEN)


def create_front():
    img = Image.new('RGBA', (16, 16))
    frame(img)
    screen(img)
    return img


def create_side():
    img = Image.new('RGBA', (16, 16))
    fill(img, BODY_MID)
    for i in range(16):
        img.putpixel((i, 0), BODY_LIGHT)
        img.putpixel((i, 15), BODY_DARK)
        img.putpixel((i, 7), SEAM)
    return img


def create_item():
    """The item: the panel from the front, three lines of text on its screen."""
    img = Image.new('RGBA', (16, 16))
    frame(img)
    screen(img)
    for x in range(4, 12):
        img.putpixel((x, 5), TEXT)
    for x in range(4, 10):
        img.putpixel((x, 8), TEXT)
    for x in range(4, 11):
        img.putpixel((x, 11), TEXT_DIM)
    return img


def main():
    create_front().save(OUT + 'block/display_panel.png')
    create_side().save(OUT + 'block/display_panel_side.png')
    create_item().save(OUT + 'item/display_panel.png')
    print('Display Panel textures created')


if __name__ == '__main__':
    main()
