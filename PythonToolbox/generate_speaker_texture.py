#!/usr/bin/env python3
"""Generate procedural Speaker textures for Mineqtt mod.

Generates 3 textures for the Speaker block, which sounds all around it and so has no facing. It wears the devices'
housing with a note-block wood frame and amber accents:
- top: The speaker cone seen from above, a dark diaphragm in a metal ring with a dust cap
- side: A perforated grille with a status light (used for all four sides)
- bottom: Bottom surface with mounting feet

The block model (minecraft:block/cube_bottom_top) maps these as:
- up -> top, down -> bottom, north/south/east/west -> side
"""
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Color palette: the devices' housing, a note block's wood and an amber light
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (60, 60, 70, 255)
BODY_MID = (70, 70, 80, 255)
BODY_LIGHT = (80, 80, 90, 255)
METAL_LIGHT = (180, 180, 200, 255)
METAL_MID = (160, 160, 180, 255)
WOOD_DARK = (88, 56, 36, 255)
WOOD_MID = (116, 76, 48, 255)
CONE_DEEP = (22, 22, 28, 255)
CONE_DARK = (34, 34, 42, 255)
CONE_MID = (48, 48, 58, 255)
GRILLE_HOLE = (24, 24, 30, 255)
AMBER = (240, 170, 40, 255)
AMBER_BRIGHT = (255, 220, 120, 255)
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
    """Create the speaker cone: a dark diaphragm in rings, held by a metal ring, with a dust cap in the middle.

    This is the top face, where the Speaker sounds.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    add_body(draw, width, height)

    # Wooden frame, as a note block's
    draw.rectangle([2, 2, 13, 13], fill=WOOD_MID)
    draw.rectangle([2, 2, 13, 2], fill=WOOD_DARK)
    draw.rectangle([2, 13, 13, 13], fill=WOOD_DARK)

    # Metal ring and the cone in rings
    draw.ellipse([2, 2, 13, 13], fill=METAL_MID)
    draw.arc([2, 2, 13, 13], start=180, end=270, fill=METAL_LIGHT)
    draw.ellipse([3, 3, 12, 12], fill=CONE_DARK)
    draw.ellipse([4, 4, 11, 11], outline=CONE_MID)
    draw.ellipse([5, 5, 10, 10], fill=CONE_DEEP)

    # Dust cap with a highlight
    draw.rectangle([7, 6, 8, 9], fill=CONE_MID)
    draw.rectangle([6, 7, 9, 8], fill=CONE_MID)
    img.putpixel((7, 7), METAL_MID)

    # Corner screws
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        img.putpixel((x, y), SCREW)

    return img


def create_side_texture(width=16, height=16):
    """Create the side panel texture: a perforated grille in a wooden frame and an amber status light.

    Used for all four sides.
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    add_body(draw, width, height)

    # Wooden frame and the grille's metal sheet
    draw.rectangle([3, 3, width - 4, height - 4], fill=WOOD_MID)
    draw.rectangle([4, 4, 11, 10], fill=BODY_LIGHT)

    # Perforations in a staggered grid
    for y in range(5, 10):
        start = 5 if y % 2 == 1 else 6
        for x in range(start, 11, 2):
            img.putpixel((x, y), GRILLE_HOLE)

    # Status light and panel detail below the grille
    img.putpixel((11, 12), AMBER)
    img.putpixel((12, 12), AMBER_BRIGHT)
    img.putpixel((9, 12), SIDE_INDICATOR_OFF)
    draw.line([(4, 12), (7, 12)], fill=WOOD_DARK)

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

    # Center label with an amber mark, so the bottom tells it apart from the other devices
    draw.rectangle([6, 7, 9, 8], fill=BODY_LIGHT)
    draw.rectangle([7, 7, 8, 8], fill=AMBER)
    draw.line([(6, 9), (9, 9)], fill=DETAIL_LINE)

    return img


def main():
    """Generate all Speaker textures and save them."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {
        'speaker_top': create_top_texture,
        'speaker_side': create_side_texture,
        'speaker_bottom': create_bottom_texture,
    }

    descriptions = {
        'speaker_top': 'Top speaker cone in a metal ring and a wooden frame',
        'speaker_side': 'Side grille with an amber status light',
        'speaker_bottom': 'Bottom surface with mounting feet',
    }

    for name, func in textures.items():
        img = func()
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        print(f"- {output_path}: {descriptions[name]}")

    print("\nSpeaker textures generated successfully!")


if __name__ == '__main__':
    main()
