#!/usr/bin/env python3
"""Generate the Climate Sensor block textures for the Mineqtt mod.

The Climate Sensor (block id temperature_sensor_block, M2-19) is a small wall-mounted box that
measures the temperature, the humidity and the illuminance. Its model (models/block/
temperature_sensor_block.json) has a base plate, the body and a thin display in front; the body and
the base take their faces from one texture by UV, the display from the other.

- temperature_sensor_block.png: the slate housing of the MineQTT devices, with a light sensor window
  above the display, a status LED, vent slits below it and mounting screws on the base.
- temperature_sensor_display.png: the display, showing the three readings as glyphs: a red
  thermometer, a blue droplet and a yellow sun.

Both keep their file names, as the model and the humidity sensor's converted blocks use them.
"""
import os
from PIL import Image

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Housing (the slate of the Smart Plug and the humidity sensor)
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (55, 60, 70, 255)
BODY_MID = (65, 72, 85, 255)
BODY_LIGHT = (75, 85, 100, 255)
BODY_HIGHLIGHT = (85, 95, 115, 255)
SLIT = (36, 40, 50, 255)
SCREW = (100, 105, 115, 255)

# Light sensor window, LED and display
WINDOW = (150, 170, 190, 255)
WINDOW_SHINE = (205, 220, 235, 255)
ENDER = (40, 150, 136, 255)
ENDER_BRIGHT = (120, 230, 205, 255)
DISPLAY_BG = (20, 25, 35, 255)
DISPLAY_EDGE = (30, 35, 50, 255)
DISPLAY_SIDE = (25, 30, 40, 255)
DISPLAY_SIDE_TOP = (35, 40, 55, 255)
DISPLAY_SIDE_BOTTOM = (15, 20, 30, 255)

# Reading glyphs
THERMO_TUBE = (200, 70, 60, 255)
THERMO_BULB = (240, 95, 75, 255)
DROP_LIGHT = (140, 200, 255, 255)
DROP = (70, 140, 230, 255)
DROP_DARK = (45, 95, 180, 255)
SUN = (255, 215, 80, 255)
SUN_RAY = (200, 160, 50, 255)


def put(img, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        img.putpixel((x, y), color)


def fill(img, x0, y0, x1, y1, color):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            put(img, x, y, color)


def create_body_texture():
    """The housing. The UV regions overlap, so the whole texture is housing; the details sit where
    the faces that show them map: the body's front at 4-11/4-11, the base's sides at the edges."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    fill(img, 0, 0, 15, 15, BODY_MID)
    # Outer border and the base's strips (top 5-10/0-1, bottom 5-10/14-15, sides 0-1 and 14-15/5-10).
    for i in range(16):
        put(img, i, 0, DARK_BORDER)
        put(img, i, 15, DARK_BORDER)
        put(img, 0, i, DARK_BORDER)
        put(img, 15, i, DARK_BORDER)
    fill(img, 5, 0, 10, 1, BODY_LIGHT)
    fill(img, 5, 14, 10, 15, BODY_DARK)
    fill(img, 0, 5, 1, 10, BODY_LIGHT)
    fill(img, 14, 5, 15, 10, BODY_DARK)
    for y in (5, 10):
        put(img, 0, y, SCREW)
        put(img, 15, y, SCREW)
    put(img, 6, 14, SCREW)
    put(img, 9, 14, SCREW)

    # The body's front: a bevel lit from the top left, shaded at the bottom right.
    fill(img, 4, 4, 11, 11, BODY_MID)
    for i in range(4, 12):
        put(img, i, 4, BODY_HIGHLIGHT)
        put(img, 4, i, BODY_HIGHLIGHT)
        put(img, i, 11, BODY_DARK)
        put(img, 11, i, BODY_DARK)
    # The light sensor window above the display, which sits at 5-10/6-9.
    fill(img, 7, 5, 8, 5, WINDOW)
    put(img, 7, 5, WINDOW_SHINE)
    # Status LED, top right.
    put(img, 10, 5, ENDER_BRIGHT)
    # Vent slits below the display.
    for x in (5, 7, 9):
        put(img, x, 10, SLIT)
        put(img, x + 1, 10, BODY_LIGHT)
    # The body's top and side faces (2-3/4-11, 12-13/4-11, 4-11/2-3) carry vent slits too.
    for y in (6, 8):
        put(img, 2, y, SLIT)
        put(img, 13, y, SLIT)
    for x in (6, 9):
        put(img, x, 3, SLIT)
    put(img, 2, 10, ENDER)
    return img


def create_display_texture():
    """The display: 6x4 pixels at 5-10/6-9 (front and back), thin strips at its edges."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    fill(img, 5, 6, 10, 9, DISPLAY_BG)
    for x in range(5, 11):
        put(img, x, 6, DISPLAY_EDGE)
    # Thermometer: a tube down column 5, the bulb at the bottom.
    put(img, 5, 6, THERMO_TUBE)
    put(img, 5, 7, THERMO_TUBE)
    put(img, 5, 8, THERMO_TUBE)
    put(img, 5, 9, THERMO_BULB)
    put(img, 6, 9, THERMO_TUBE)
    # Droplet: a point on top, round below.
    put(img, 7, 7, DROP_LIGHT)
    put(img, 7, 8, DROP)
    put(img, 8, 8, DROP)
    put(img, 7, 9, DROP_DARK)
    put(img, 8, 9, DROP)
    # Sun: a 2x2 disc with rays.
    put(img, 9, 7, SUN)
    put(img, 10, 7, SUN)
    put(img, 9, 8, SUN)
    put(img, 10, 8, SUN)
    put(img, 10, 6, SUN_RAY)
    put(img, 9, 9, SUN_RAY)
    # The display's thin edges.
    fill(img, 0, 6, 0, 9, DISPLAY_SIDE)
    fill(img, 15, 6, 15, 9, DISPLAY_SIDE)
    fill(img, 5, 0, 10, 0, DISPLAY_SIDE_TOP)
    fill(img, 5, 15, 10, 15, DISPLAY_SIDE_BOTTOM)
    return img


def main():
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'
    textures = {
        'temperature_sensor_block': (create_body_texture, 'slate housing, light window, LED and vent slits'),
        'temperature_sensor_display': (create_display_texture, 'thermometer, droplet and sun glyphs'),
    }
    for name, (create, description) in textures.items():
        path = f'{base_path}{name}.png'
        create().save(path)
        print(f'- {path}: {description}')
    print('\nClimate sensor textures generated successfully!')


if __name__ == '__main__':
    main()
