#!/usr/bin/env python3
"""Generate the Wall Switch block textures for the Mineqtt mod.

The Wall Switch (block id light_remote_block) is a slate plate on a wall with two rocker buttons
side by side. The models map these textures onto their boxes:

- light_remote_block.png: the plate. Its front is the 12x10 region from (2, 3) to (13, 12): a dark
  border, a lit top-left bevel, a shaded bottom-right bevel, a groove between the two rockers and a
  screw above and below it. The plate's sides take the plain body at the left edge.
- light_remote_block_rocker.png: a rocker that is up. Its front is the 4x7 region from (6, 4) to
  (9, 10): a lighter top half where the rocker tilts towards the light, a ridge, and a dark
  ender-teal indicator slot near its foot. Its sides take the body at the left edge.
- light_remote_block_rocker_pressed.png: a rocker held down. The rocker is shaded flat and its
  indicator glows ender-teal, as the Smart Plug's LED does while it is on.
- textures/item/light_remote_block.png: the item, the switch seen from the front with both rockers up.
"""
import os
from PIL import Image

os.chdir(os.path.dirname(os.path.abspath(__file__)))

OUT = '../common/src/main/resources/assets/mineqtt/textures/block/'

# Housing (the slate of the Smart Plug and the Climate Sensor)
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (55, 60, 70, 255)
BODY_MID = (65, 72, 85, 255)
BODY_LIGHT = (75, 85, 100, 255)
BODY_HIGHLIGHT = (85, 95, 115, 255)
SCREW = (100, 105, 115, 255)
GROOVE = (34, 37, 46, 255)

# Rockers
ROCKER_EDGE = (92, 98, 112, 255)
ROCKER_LIGHT = (176, 182, 196, 255)
ROCKER_MID = (150, 156, 172, 255)
ROCKER_DARK = (124, 130, 146, 255)
RIDGE = (196, 202, 214, 255)

# Indicator
LED_OFF = (30, 70, 68, 255)
ENDER = (40, 150, 136, 255)
ENDER_BRIGHT = (120, 230, 205, 255)

PLATE = (2, 3, 13, 12)
ROCKER = (6, 4, 9, 10)


def fill(img, color):
    for x in range(16):
        for y in range(16):
            img.putpixel((x, y), color)


def create_plate():
    img = Image.new('RGBA', (16, 16))
    fill(img, BODY_MID)
    x0, y0, x1, y1 = PLATE
    for x in range(x0, x1 + 1):
        img.putpixel((x, y0), DARK_BORDER)
        img.putpixel((x, y1), DARK_BORDER)
    for y in range(y0, y1 + 1):
        img.putpixel((x0, y), DARK_BORDER)
        img.putpixel((x1, y), DARK_BORDER)
    for x in range(x0 + 1, x1):
        img.putpixel((x, y0 + 1), BODY_HIGHLIGHT)
        img.putpixel((x, y1 - 1), BODY_DARK)
    for y in range(y0 + 1, y1):
        img.putpixel((x0 + 1, y), BODY_LIGHT)
        img.putpixel((x1 - 1, y), BODY_DARK)
    img.putpixel((x0 + 1, y0 + 1), BODY_HIGHLIGHT)
    # The groove between the rockers, and a screw above and below it.
    for y in range(y0 + 2, y1 - 1):
        img.putpixel((7, y), GROOVE)
        img.putpixel((8, y), GROOVE)
    for x in (7, 8):
        img.putpixel((x, y0 + 1), SCREW)
        img.putpixel((x, y1 - 1), SCREW)
    return img


def create_rocker(pressed):
    img = Image.new('RGBA', (16, 16))
    fill(img, ROCKER_DARK if pressed else ROCKER_MID)
    x0, y0, x1, y1 = ROCKER
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            if pressed:
                color = ROCKER_DARK
            else:
                color = ROCKER_LIGHT if y < y0 + 3 else RIDGE if y == y0 + 3 else ROCKER_MID
            img.putpixel((x, y), color)
    for x in range(x0, x1 + 1):
        img.putpixel((x, y1), ROCKER_EDGE)
    for y in range(y0, y1 + 1):
        img.putpixel((x1, y), ROCKER_EDGE)
    # The indicator slot near the rocker's foot.
    for x in (x0 + 1, x0 + 2):
        img.putpixel((x, y1 - 1), ENDER if pressed else LED_OFF)
    if pressed:
        img.putpixel((x0 + 1, y1 - 1), ENDER_BRIGHT)
    return img


def create_item():
    """The item: the switch seen from the front, the plate filling the sprite with both rockers up."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    x0, y0, x1, y1 = 1, 2, 14, 13
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            edge = x in (x0, x1) or y in (y0, y1)
            img.putpixel((x, y), DARK_BORDER if edge else BODY_MID)
    for x in range(x0 + 1, x1):
        img.putpixel((x, y0 + 1), BODY_HIGHLIGHT)
        img.putpixel((x, y1 - 1), BODY_DARK)
    for rx in (3, 9):
        for x in range(rx, rx + 4):
            for y in range(4, 12):
                img.putpixel((x, y), ROCKER_LIGHT if y < 7 else RIDGE if y == 7 else ROCKER_MID)
            img.putpixel((x, 11), ROCKER_EDGE)
        for y in range(4, 12):
            img.putpixel((rx + 3, y), ROCKER_EDGE)
        img.putpixel((rx + 1, 10), LED_OFF)
        img.putpixel((rx + 2, 10), LED_OFF)
    for y in range(4, 12):
        img.putpixel((7, y), GROOVE)
        img.putpixel((8, y), GROOVE)
    return img


def main():
    create_plate().save(OUT + 'light_remote_block.png')
    create_rocker(False).save(OUT + 'light_remote_block_rocker.png')
    create_rocker(True).save(OUT + 'light_remote_block_rocker_pressed.png')
    create_item().save('../common/src/main/resources/assets/mineqtt/textures/item/light_remote_block.png')
    print('Wall Switch textures created')


if __name__ == '__main__':
    main()
