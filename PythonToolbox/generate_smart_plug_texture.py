#!/usr/bin/env python3
"""Generate the Smart Plug block textures for the Mineqtt mod.

The Smart Plug is a switchable outlet block (cube_all). Each face shows a slate housing like the
other MineQTT devices, with a round recessed socket in the middle: two pin holes, the earth clips
above and below them, and a status LED in the lower right corner.

- smart_plug.png: off. The LED is dark and the socket rim plain.
- smart_plug_on.png: on. The LED glows ender-teal and the socket rim is lit, so a powered plug
  reads as on from any side.
"""
import os
from PIL import Image

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Housing (the slate of the Climate and Humidity sensors and the light remote)
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (55, 60, 70, 255)
BODY_MID = (65, 72, 85, 255)
BODY_LIGHT = (75, 85, 100, 255)
BODY_HIGHLIGHT = (85, 95, 115, 255)
SCREW = (100, 105, 115, 255)

# Socket
RECESS_DARK = (26, 28, 36, 255)
RECESS = (34, 37, 46, 255)
RIM = (112, 120, 140, 255)
RIM_SHADOW = (82, 90, 106, 255)
HOLE = (8, 8, 12, 255)
METAL_LIGHT = (180, 180, 200, 255)
METAL_MID = (140, 150, 170, 255)

# Status LED and the lit rim
LED_OFF = (30, 70, 68, 255)
LED_OFF_EDGE = (44, 50, 58, 255)
ENDER = (40, 150, 136, 255)
ENDER_BRIGHT = (120, 230, 205, 255)
ENDER_GLOW = (70, 170, 155, 255)

# The socket recess: a disc of radius 4.5 around the face's centre (7.5, 7.5).
CENTRE = 7.5
RECESS_RADIUS = 4.6
RIM_RADIUS = 5.6


def put(img, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        img.putpixel((x, y), color)


def distance(x, y):
    return ((x - CENTRE) ** 2 + (y - CENTRE) ** 2) ** 0.5


def draw_housing(img):
    """Dark outer border, a lit top-left bevel, a shaded bottom-right bevel and four corner screws."""
    for x in range(16):
        for y in range(16):
            put(img, x, y, BODY_MID)
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
    put(img, 1, 1, BODY_HIGHLIGHT)
    for x, y in ((2, 2), (13, 2), (2, 13)):
        put(img, x, y, SCREW)


def draw_socket(img, lit):
    """The rim ring, the recessed face with its shading, two pin holes and the earth clips."""
    for x in range(16):
        for y in range(16):
            d = distance(x, y)
            if d <= RECESS_RADIUS:
                # Light falls from the top left, so the recess is darker towards its top-left wall.
                shade = (x + y) < 13
                put(img, x, y, RECESS_DARK if shade and d > RECESS_RADIUS - 1.2 else RECESS)
            elif d <= RIM_RADIUS:
                if lit:
                    put(img, x, y, ENDER_GLOW if (x + y) >= 15 else ENDER)
                else:
                    put(img, x, y, RIM if (x + y) < 15 else RIM_SHADOW)
    # Pin holes: 2x2 each, on the horizontal centre line.
    for x, y in ((5, 7), (6, 7), (5, 8), (6, 8), (9, 7), (10, 7), (9, 8), (10, 8)):
        put(img, x, y, HOLE)
    # Earth clips above and below the holes, a lit top edge on each.
    for x in (7, 8):
        put(img, x, 3, METAL_LIGHT)
        put(img, x, 4, METAL_MID)
        put(img, x, 11, METAL_LIGHT)
        put(img, x, 12, METAL_MID)


def draw_led(img, lit):
    """A 2x2 status LED in the lower right corner, with a one-pixel glow while on."""
    if lit:
        for x, y in ((12, 12), (13, 12), (12, 13)):
            put(img, x, y, ENDER)
        put(img, 13, 13, ENDER_BRIGHT)
        put(img, 12, 12, ENDER_BRIGHT)
    else:
        for x, y in ((12, 12), (13, 12), (12, 13), (13, 13)):
            put(img, x, y, LED_OFF)
        put(img, 12, 12, LED_OFF_EDGE)


def create_texture(lit):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    draw_housing(img)
    draw_socket(img, lit)
    draw_led(img, lit)
    return img


def main():
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'
    textures = {
        'smart_plug': (False, 'Slate housing with a round socket, LED off'),
        'smart_plug_on': (True, 'The socket rim and the LED lit ender-teal'),
    }
    for name, (lit, description) in textures.items():
        output_path = f'{base_path}{name}.png'
        create_texture(lit).save(output_path)
        print(f"- {output_path}: {description}")
    print("\nSmart Plug textures generated successfully!")


if __name__ == '__main__':
    main()
