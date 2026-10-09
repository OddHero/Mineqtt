#!/usr/bin/env python3
"""Generate the textures of the blocks MineQTT 2 retired (decommissioned, M2-21).

The Dynamic Publisher and the Topic Relay do nothing any more: they wait to be broken for their
Network Chip and the items they held. Both are cube_all blocks with one texture for every state, so
a block saved while powered looks as dead as any other. Each face shows the slate housing of the
other MineQTT devices with a powered-down faceplate in muted greys and a dark status LED:

- dynamic_publisher.png: a data board whose cells are all unlit.
- topic_relay.png: an input port and an output port with the relay arrow between them, unlit.
"""
import os
from PIL import Image

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Housing (the slate of the Smart Plug, the Climate Sensor and the light remote)
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (55, 60, 70, 255)
BODY_MID = (65, 72, 85, 255)
BODY_LIGHT = (75, 85, 100, 255)
BODY_HIGHLIGHT = (85, 95, 115, 255)
SCREW = (100, 105, 115, 255)

# The powered-down faceplate
PLATE_DARK = (26, 28, 34, 255)
PLATE = (34, 37, 44, 255)
PLATE_EDGE = (48, 52, 60, 255)
CELL = (70, 74, 82, 255)
CELL_DIM = (56, 60, 68, 255)
PORT_RIM = (96, 100, 110, 255)
PORT_HOLE = (12, 12, 16, 255)
ARROW = (88, 92, 102, 255)
LED_DEAD = (48, 40, 40, 255)
LED_DEAD_EDGE = (36, 32, 34, 255)


def put(img, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        img.putpixel((x, y), color)


def draw_housing(img):
    """Dark outer border, a lit top-left bevel, a shaded bottom-right bevel and three corner screws."""
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


def draw_plate(img):
    """The recessed faceplate from (3, 3) to (12, 12): a shadow on its top-left wall, a lip at the bottom right."""
    for x in range(3, 13):
        for y in range(3, 13):
            put(img, x, y, PLATE)
    for i in range(3, 13):
        put(img, i, 3, PLATE_DARK)
        put(img, 3, i, PLATE_DARK)
        put(img, i, 12, PLATE_EDGE)
        put(img, 12, i, PLATE_EDGE)


def draw_led(img):
    """A dark 2x2 status LED in the lower right corner of the housing: the block is never on."""
    for x, y in ((12, 13), (13, 13), (13, 12)):
        put(img, x, y, LED_DEAD)
    put(img, 12, 13, LED_DEAD_EDGE)


def draw_data_board(img):
    """The Dynamic Publisher's board: a scatter of unlit data cells, as v1's green ones sat."""
    for x, y in ((5, 5), (8, 5), (10, 6), (4, 8), (6, 7), (9, 8), (10, 8), (7, 10), (5, 10), (10, 10)):
        put(img, x, y, CELL)
    for x, y in ((6, 5), (4, 9), (8, 8), (8, 10)):
        put(img, x, y, CELL_DIM)


def draw_relay(img):
    """The Topic Relay's face: an input port, an arrow and an output port, nothing lit."""
    for left in (4, 9):
        for x in range(left, left + 3):
            for y in range(6, 9):
                put(img, x, y, PORT_RIM)
        put(img, left + 1, 7, PORT_HOLE)
    # The arrow between the ports points from the input to the output.
    put(img, 7, 7, ARROW)
    put(img, 8, 7, ARROW)
    put(img, 7, 6, CELL_DIM)
    put(img, 7, 8, CELL_DIM)
    # Two dead indicator cells below the ports.
    put(img, 5, 10, CELL)
    put(img, 10, 10, CELL)


def create_texture(face):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    draw_housing(img)
    draw_plate(img)
    face(img)
    draw_led(img)
    return img


def main():
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'
    textures = {
        'dynamic_publisher': (draw_data_board, 'Slate housing, a data board with every cell unlit, LED dark'),
        'topic_relay': (draw_relay, 'Slate housing, input and output ports with the relay arrow, unlit, LED dark'),
    }
    for name, (face, description) in textures.items():
        output_path = f'{base_path}{name}.png'
        create_texture(face).save(output_path)
        print(f"- {output_path}: {description}")
    print("\nRetired block textures generated successfully!")


if __name__ == '__main__':
    main()
