#!/usr/bin/env python3
"""Generate procedural block sensor textures for Mineqtt mod.

Generates 5 textures for the directional Block Sensor block:
- front: The sensing face with the green LED eye (points in FACING direction)
- back: The back panel with connection ports
- side: Side panels with indicator lights (used for east and west faces)
- top: Top surface with ventilation slots
- bottom: Bottom surface with mounting feet

The block model maps these as:
- north/south/east/west/up/down -> front/back/side/side/top/bottom depending on FACING
"""
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Color palette
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (60, 60, 70, 255)
BODY_MID = (70, 70, 80, 255)
BODY_LIGHT = (80, 80, 90, 255)
METAL_LIGHT = (180, 180, 200, 255)
METAL_MID = (160, 160, 180, 255)
LENS_DARK = (30, 30, 40, 255)
LED_GREEN = (0, 255, 100, 255)
LED_GREEN_GLOW = (0, 180, 80, 255)
LED_GREEN_GLOW_DIM = (0, 150, 70, 255)
ANTENNA_BASE = (100, 100, 110, 255)
ANTENNA_ROD = (140, 140, 160, 255)
DETAIL_LINE = (55, 55, 65, 255)
PORT_COLOR = (50, 50, 60, 255)
PORT_INNER = (35, 35, 45, 255)
VENT_SLOT = (45, 45, 55, 255)
FOOT_COLOR = (50, 50, 60, 255)
FOOT_RUBBER = (35, 35, 45, 255)
SIDE_INDICATOR = (120, 120, 130, 255)
SIDE_INDICATOR_OFF = (80, 80, 90, 255)


def add_border(draw, width, height):
    """Add a dark border around the edge of the texture."""
    draw.rectangle([0, 0, width - 1, 0], fill=DARK_BORDER)
    draw.rectangle([0, height - 1, width - 1, height - 1], fill=DARK_BORDER)
    draw.rectangle([0, 0, 0, height - 1], fill=DARK_BORDER)
    draw.rectangle([width - 1, 0, width - 1, height - 1], fill=DARK_BORDER)


def create_front_texture(width=16, height=16):
    """Create the front sensing face texture with the green LED eye.

    This is the face that points in the FACING direction and detects block changes.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Dark border
    add_border(draw, width, height)

    # Main body - dark gray background
    draw.rectangle([1, 1, width - 2, height - 2], fill=BODY_DARK)

    # Inner panel frame
    draw.rectangle([2, 2, width - 3, height - 3], fill=BODY_MID)

    # Sensor housing area
    draw.rectangle([3, 3, width - 4, height - 4], fill=BODY_LIGHT)

    # Sensor eye - outer ring (light gray metallic)
    draw.ellipse([4, 4, 11, 11], outline=METAL_LIGHT)

    # Sensor eye - inner ring
    draw.ellipse([5, 5, 10, 10], outline=METAL_MID)

    # Sensor eye - lens background (dark)
    draw.ellipse([6, 6, 9, 9], fill=LENS_DARK)

    # Sensor eye - green LED indicator (active sensing)
    draw.ellipse([7, 7, 8, 8], fill=LED_GREEN)

    # LED glow effect around the green dot
    img.putpixel((6, 6), LED_GREEN_GLOW)
    img.putpixel((9, 9), LED_GREEN_GLOW)
    img.putpixel((6, 9), LED_GREEN_GLOW_DIM)
    img.putpixel((9, 6), LED_GREEN_GLOW_DIM)

    # Panel detail lines
    draw.line([(3, 12), (12, 12)], fill=DETAIL_LINE)
    draw.line([(3, 3), (3, 11)], fill=DETAIL_LINE)

    return img


def create_back_texture(width=16, height=16):
    """Create the back panel texture with connection ports.

    This face is opposite the sensing direction.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Dark border
    add_border(draw, width, height)

    # Main body
    draw.rectangle([1, 1, width - 2, height - 2], fill=BODY_DARK)

    # Inner panel
    draw.rectangle([2, 2, width - 3, height - 3], fill=BODY_MID)

    # Connection port area - top section
    draw.rectangle([4, 3, 11, 6], fill=PORT_COLOR)
    draw.rectangle([5, 4, 10, 5], fill=PORT_INNER)

    # Connection port area - bottom section
    draw.rectangle([4, 9, 11, 12], fill=PORT_COLOR)
    draw.rectangle([5, 10, 10, 11], fill=PORT_INNER)

    # Port screws
    draw.rectangle([3, 4, 3, 5], fill=ANTENNA_BASE)
    draw.rectangle([12, 4, 12, 5], fill=ANTENNA_BASE)
    draw.rectangle([3, 10, 3, 11], fill=ANTENNA_BASE)
    draw.rectangle([12, 10, 12, 11], fill=ANTENNA_BASE)

    # Center label area
    draw.rectangle([5, 7, 10, 8], fill=BODY_LIGHT)

    return img


def create_side_texture(width=16, height=16):
    """Create the side panel texture with indicator lights.

    Used for both east and west faces.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Dark border
    add_border(draw, width, height)

    # Main body
    draw.rectangle([1, 1, width - 2, height - 2], fill=BODY_DARK)

    # Inner panel
    draw.rectangle([2, 2, width - 3, height - 3], fill=BODY_MID)

    # Vertical groove line
    draw.line([(8, 3), (8, 12)], fill=DETAIL_LINE)

    # Status indicator lights - left side
    draw.rectangle([4, 4, 5, 5], fill=LED_GREEN)
    draw.rectangle([4, 7, 5, 8], fill=SIDE_INDICATOR_OFF)
    draw.rectangle([4, 10, 5, 11], fill=SIDE_INDICATOR_OFF)

    # Ventilation slots - right side
    draw.rectangle([10, 4, 13, 4], fill=VENT_SLOT)
    draw.rectangle([10, 6, 13, 6], fill=VENT_SLOT)
    draw.rectangle([10, 8, 13, 8], fill=VENT_SLOT)
    draw.rectangle([10, 10, 13, 10], fill=VENT_SLOT)

    return img


def create_top_texture(width=16, height=16):
    """Create the top surface texture with ventilation slots.

    This is the top face of the block.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Dark border
    add_border(draw, width, height)

    # Main body
    draw.rectangle([1, 1, width - 2, height - 2], fill=BODY_DARK)

    # Inner panel
    draw.rectangle([2, 2, width - 3, height - 3], fill=BODY_MID)

    # Ventilation grid - horizontal slots
    draw.rectangle([4, 4, 11, 4], fill=VENT_SLOT)
    draw.rectangle([4, 6, 11, 6], fill=VENT_SLOT)
    draw.rectangle([4, 8, 11, 8], fill=VENT_SLOT)
    draw.rectangle([4, 10, 11, 10], fill=VENT_SLOT)

    # Corner screws
    draw.ellipse([3, 3, 4, 4], outline=ANTENNA_BASE)
    draw.ellipse([11, 3, 12, 4], outline=ANTENNA_BASE)
    draw.ellipse([3, 11, 4, 12], outline=ANTENNA_BASE)
    draw.ellipse([11, 11, 12, 12], outline=ANTENNA_BASE)

    return img


def create_bottom_texture(width=16, height=16):
    """Create the bottom surface texture with mounting feet.

    This is the bottom face of the block.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Dark border
    add_border(draw, width, height)

    # Main body
    draw.rectangle([1, 1, width - 2, height - 2], fill=BODY_DARK)

    # Inner panel
    draw.rectangle([2, 2, width - 3, height - 3], fill=BODY_MID)

    # Mounting feet at corners
    # Top-left foot
    draw.rectangle([3, 3, 5, 5], fill=FOOT_COLOR)
    draw.rectangle([4, 4, 4, 4], fill=FOOT_RUBBER)

    # Top-right foot
    draw.rectangle([10, 3, 12, 5], fill=FOOT_COLOR)
    draw.rectangle([11, 4, 11, 4], fill=FOOT_RUBBER)

    # Bottom-left foot
    draw.rectangle([3, 10, 5, 12], fill=FOOT_COLOR)
    draw.rectangle([4, 11, 4, 11], fill=FOOT_RUBBER)

    # Bottom-right foot
    draw.rectangle([10, 10, 12, 12], fill=FOOT_COLOR)
    draw.rectangle([11, 11, 11, 11], fill=FOOT_RUBBER)

    # Center label
    draw.rectangle([6, 7, 9, 8], fill=BODY_LIGHT)

    return img


def main():
    """Generate all block sensor textures and save them."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {
        'block_sensor_front': create_front_texture,
        'block_sensor_back': create_back_texture,
        'block_sensor_side': create_side_texture,
        'block_sensor_top': create_top_texture,
        'block_sensor_bottom': create_bottom_texture,
    }

    descriptions = {
        'block_sensor_front': 'Front sensing face with green LED eye',
        'block_sensor_back': 'Back panel with connection ports',
        'block_sensor_side': 'Side panel with indicator lights and vents',
        'block_sensor_top': 'Top surface with ventilation grid',
        'block_sensor_bottom': 'Bottom surface with mounting feet',
    }

    for name, func in textures.items():
        img = func()
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        print(f"- {output_path}: {descriptions[name]}")

    print("\nBlock sensor textures generated successfully!")


if __name__ == '__main__':
    main()
