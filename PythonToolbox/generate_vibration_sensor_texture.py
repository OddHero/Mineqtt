#!/usr/bin/env python3
"""Generate procedural Vibration Sensor textures for Mineqtt mod.

Generates 3 textures for the Vibration Sensor block, which hears all around it and so has no facing. It wears the
Block Observer's housing with sculk-teal accents:
- top: The listening membrane, a sculk-teal disc in rings of vibration waves with a bright core
- side: Side panels with a vibration wave line and a status light (used for all four sides)
- bottom: Bottom surface with mounting feet

The block model (minecraft:block/cube_bottom_top) maps these as:
- up -> top, down -> bottom, north/south/east/west -> side
"""
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Color palette: the Block Observer's housing, sculk teal instead of green
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (60, 60, 70, 255)
BODY_MID = (70, 70, 80, 255)
BODY_LIGHT = (80, 80, 90, 255)
METAL_LIGHT = (180, 180, 200, 255)
METAL_MID = (160, 160, 180, 255)
SCULK_DEEP = (8, 38, 46, 255)
SCULK_DARK = (16, 72, 80, 255)
SCULK_TEAL = (30, 140, 140, 255)
SCULK_GLOW = (60, 220, 210, 255)
SCULK_BRIGHT = (180, 255, 245, 255)
DETAIL_LINE = (55, 55, 65, 255)
SCREW = (100, 100, 110, 255)
FOOT_COLOR = (50, 50, 60, 255)
FOOT_RUBBER = (35, 35, 45, 255)
SIDE_INDICATOR_OFF = (80, 80, 90, 255)


def add_border(draw, width, height):
    """Add a dark border around the edge of the texture."""
    draw.rectangle([0, 0, width - 1, 0], fill=DARK_BORDER)
    draw.rectangle([0, height - 1, width - 1, height - 1], fill=DARK_BORDER)
    draw.rectangle([0, 0, 0, height - 1], fill=DARK_BORDER)
    draw.rectangle([width - 1, 0, width - 1, height - 1], fill=DARK_BORDER)


def add_body(draw, width, height):
    """The housing every face shares: border, body and inner panel."""
    add_border(draw, width, height)
    draw.rectangle([1, 1, width - 2, height - 2], fill=BODY_DARK)
    draw.rectangle([2, 2, width - 3, height - 3], fill=BODY_MID)


def create_top_texture(width=16, height=16):
    """Create the listening membrane: rings of vibration waves around a glowing sculk core.

    This is the top face, where the sensor listens like a sculk sensor's tendrils.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    add_body(draw, width, height)

    # Metal ring holding the membrane
    draw.ellipse([2, 2, 13, 13], outline=METAL_MID)
    draw.arc([2, 2, 13, 13], start=180, end=270, fill=METAL_LIGHT)

    # The membrane: deep sculk with two rings of waves
    draw.ellipse([3, 3, 12, 12], fill=SCULK_DEEP)
    draw.ellipse([4, 4, 11, 11], outline=SCULK_DARK)
    draw.ellipse([5, 5, 10, 10], outline=SCULK_TEAL)

    # The glowing core with a highlight
    draw.rectangle([7, 6, 8, 9], fill=SCULK_GLOW)
    draw.rectangle([6, 7, 9, 8], fill=SCULK_GLOW)
    img.putpixel((7, 7), SCULK_BRIGHT)

    # Corner screws
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        img.putpixel((x, y), SCREW)

    return img


def create_side_texture(width=16, height=16):
    """Create the side panel texture: a vibration wave across a dark window and a status light.

    Used for all four sides.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    add_body(draw, width, height)

    # Housing panel and a dark window across the middle
    draw.rectangle([3, 3, width - 4, height - 4], fill=BODY_LIGHT)
    draw.rectangle([3, 5, 12, 10], fill=SCULK_DEEP)
    draw.line([(3, 10), (12, 10)], fill=METAL_MID)

    # A vibration wave: small at the edges, tall in the middle, as a seismograph draws it
    wave = [(3, 8), (4, 7), (5, 8), (6, 6), (7, 9), (8, 5), (9, 8), (10, 7), (11, 8), (12, 8)]
    for x, y in wave:
        img.putpixel((x, y), SCULK_TEAL)
    for x, y in ((7, 9), (8, 5)):
        img.putpixel((x, y), SCULK_GLOW)

    # Status light and panel detail below the window
    img.putpixel((12, 12), SCULK_GLOW)
    img.putpixel((10, 12), SIDE_INDICATOR_OFF)
    draw.line([(3, 12), (8, 12)], fill=DETAIL_LINE)

    return img


def create_bottom_texture(width=16, height=16):
    """Create the bottom surface texture with mounting feet.

    This is the bottom face of the block.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    add_body(draw, width, height)

    # Mounting feet at the corners
    for x, y in ((3, 3), (10, 3), (3, 10), (10, 10)):
        draw.rectangle([x, y, x + 2, y + 2], fill=FOOT_COLOR)
        img.putpixel((x + 1, y + 1), FOOT_RUBBER)

    # Center label with a teal mark, so the bottom tells it apart from the other observers
    draw.rectangle([6, 7, 9, 8], fill=BODY_LIGHT)
    draw.rectangle([7, 7, 8, 8], fill=SCULK_DARK)

    return img


def main():
    """Generate all Vibration Sensor textures and save them."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {
        'vibration_sensor_top': create_top_texture,
        'vibration_sensor_side': create_side_texture,
        'vibration_sensor_bottom': create_bottom_texture,
    }

    descriptions = {
        'vibration_sensor_top': 'Top listening membrane with sculk-teal wave rings',
        'vibration_sensor_side': 'Side panel with a vibration wave and a status light',
        'vibration_sensor_bottom': 'Bottom surface with mounting feet',
    }

    for name, func in textures.items():
        img = func()
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        print(f"- {output_path}: {descriptions[name]}")

    print("\nVibration Sensor textures generated successfully!")


if __name__ == '__main__':
    main()
