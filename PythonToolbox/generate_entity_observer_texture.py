#!/usr/bin/env python3
"""Generate procedural Entity Observer textures for Mineqtt mod.

Generates 5 textures for the directional Entity Observer block, in the Block Observer's housing with cyan optics:
- front: The watching face, a wide visor with a cyan lens and a scan line (points in FACING direction)
- back: The back panel with connection ports
- side: Side panels with cyan indicator lights (used for east and west faces)
- top: Top surface with a ventilation grid
- bottom: Bottom surface with mounting feet

The block model maps these as:
- north/south/east/west/up/down -> front/back/side/side/top/bottom depending on FACING
"""
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Color palette: the Block Observer's housing, cyan instead of green
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (60, 60, 70, 255)
BODY_MID = (70, 70, 80, 255)
BODY_LIGHT = (80, 80, 90, 255)
METAL_LIGHT = (180, 180, 200, 255)
METAL_MID = (160, 160, 180, 255)
VISOR_DARK = (25, 28, 38, 255)
LENS_DEEP = (10, 60, 80, 255)
LENS_CYAN = (40, 200, 230, 255)
LENS_BRIGHT = (170, 250, 255, 255)
SCAN_LINE = (20, 130, 160, 255)
DETAIL_LINE = (55, 55, 65, 255)
SCREW = (100, 100, 110, 255)
PORT_COLOR = (50, 50, 60, 255)
PORT_INNER = (35, 35, 45, 255)
VENT_SLOT = (45, 45, 55, 255)
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


def create_front_texture(width=16, height=16):
    """Create the watching face: a wide visor across the middle with a cyan lens and a scan line.

    This is the face that points in the FACING direction and watches the space in front.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    add_body(draw, width, height)

    # Housing panel around the visor
    draw.rectangle([3, 3, width - 4, height - 4], fill=BODY_LIGHT)

    # Metal visor frame, wider than high: it looks across the room
    draw.rectangle([2, 5, 13, 10], outline=METAL_LIGHT)
    draw.line([(3, 10), (12, 10)], fill=METAL_MID)

    # Dark visor glass
    draw.rectangle([3, 6, 12, 9], fill=VISOR_DARK)

    # The lens: a deep cyan disc with a bright core and a highlight
    draw.rectangle([6, 6, 9, 9], fill=LENS_DEEP)
    draw.rectangle([7, 6, 8, 9], fill=LENS_CYAN)
    draw.rectangle([6, 7, 9, 8], fill=LENS_CYAN)
    img.putpixel((7, 7), LENS_BRIGHT)

    # The scan line sweeping across the visor
    draw.line([(3, 8), (5, 8)], fill=SCAN_LINE)
    draw.line([(10, 8), (12, 8)], fill=SCAN_LINE)

    # Status LED and panel detail below the visor
    img.putpixel((12, 12), LENS_CYAN)
    draw.line([(3, 12), (10, 12)], fill=DETAIL_LINE)

    return img


def create_back_texture(width=16, height=16):
    """Create the back panel texture with connection ports.

    This face is opposite the watching direction.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    add_body(draw, width, height)

    # Two connection ports
    draw.rectangle([4, 3, 11, 6], fill=PORT_COLOR)
    draw.rectangle([5, 4, 10, 5], fill=PORT_INNER)
    draw.rectangle([4, 9, 11, 12], fill=PORT_COLOR)
    draw.rectangle([5, 10, 10, 11], fill=PORT_INNER)

    # Port screws
    for x in (3, 12):
        draw.rectangle([x, 4, x, 5], fill=SCREW)
        draw.rectangle([x, 10, x, 11], fill=SCREW)

    # Label strip with a cyan mark, so the back tells it apart from the Block Observer
    draw.rectangle([5, 7, 10, 8], fill=BODY_LIGHT)
    draw.rectangle([7, 7, 8, 8], fill=SCAN_LINE)

    return img


def create_side_texture(width=16, height=16):
    """Create the side panel texture with indicator lights.

    Used for both east and west faces.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    add_body(draw, width, height)

    # Vertical groove line
    draw.line([(8, 3), (8, 12)], fill=DETAIL_LINE)

    # Status indicator lights - left side
    draw.rectangle([4, 4, 5, 5], fill=LENS_CYAN)
    draw.rectangle([4, 7, 5, 8], fill=SIDE_INDICATOR_OFF)
    draw.rectangle([4, 10, 5, 11], fill=SIDE_INDICATOR_OFF)

    # Ventilation slots - right side
    for y in (4, 6, 8, 10):
        draw.rectangle([10, y, 13, y], fill=VENT_SLOT)

    return img


def create_top_texture(width=16, height=16):
    """Create the top surface texture with a ventilation grid.

    This is the top face of the block.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    add_body(draw, width, height)

    # Ventilation grid - vertical slots, crosswise to the Block Observer's
    for x in (4, 6, 9, 11):
        draw.rectangle([x, 4, x, 11], fill=VENT_SLOT)

    # Corner screws
    for x, y in ((3, 3), (11, 3), (3, 11), (11, 11)):
        draw.ellipse([x, y, x + 1, y + 1], outline=SCREW)

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

    # Center label
    draw.rectangle([6, 7, 9, 8], fill=BODY_LIGHT)

    return img


def main():
    """Generate all Entity Observer textures and save them."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {
        'entity_observer_front': create_front_texture,
        'entity_observer_back': create_back_texture,
        'entity_observer_side': create_side_texture,
        'entity_observer_top': create_top_texture,
        'entity_observer_bottom': create_bottom_texture,
    }

    descriptions = {
        'entity_observer_front': 'Front watching face with a cyan visor lens',
        'entity_observer_back': 'Back panel with connection ports',
        'entity_observer_side': 'Side panel with indicator lights and vents',
        'entity_observer_top': 'Top surface with ventilation grid',
        'entity_observer_bottom': 'Bottom surface with mounting feet',
    }

    for name, func in textures.items():
        img = func()
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        print(f"- {output_path}: {descriptions[name]}")

    print("\nEntity Observer textures generated successfully!")


if __name__ == '__main__':
    main()
