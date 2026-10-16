#!/usr/bin/env python3
"""Generate procedural Container Observer textures for the Mineqtt mod.

Generates 5 textures for the directional Container Observer block, in the Block Observer's housing:
- front: The reading face that touches the container, a 3x3 grid of slot readers (points in FACING direction)
- back: The face players see, a small display with fill bars
- side: Side panels with a level gauge and status lights (used for east and west faces)
- top: Top surface with ventilation slots
- bottom: Bottom surface with mounting feet

The block model maps these as:
- north/south/east/west/up/down -> front/back/side/side/top/bottom, rotated by FACING
"""
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Color palette: the Block Observer's housing, with a cyan accent for the container reading.
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (60, 60, 70, 255)
BODY_MID = (70, 70, 80, 255)
BODY_LIGHT = (80, 80, 90, 255)
METAL_LIGHT = (180, 180, 200, 255)
METAL_MID = (160, 160, 180, 255)
SLOT_FRAME = (120, 120, 135, 255)
SLOT_INNER = (35, 35, 45, 255)
READER_CYAN = (0, 220, 230, 255)
READER_CYAN_DIM = (0, 140, 150, 255)
SCREEN_DARK = (20, 30, 35, 255)
SCREEN_EDGE = (30, 45, 50, 255)
BAR_FULL = (0, 230, 120, 255)
BAR_HIGH = (0, 190, 100, 255)
BAR_OFF = (40, 60, 60, 255)
DETAIL_LINE = (55, 55, 65, 255)
VENT_SLOT = (45, 45, 55, 255)
SCREW = (100, 100, 110, 255)
FOOT_COLOR = (50, 50, 60, 255)
FOOT_RUBBER = (35, 35, 45, 255)
LED_GREEN = (0, 255, 100, 255)
LED_OFF = (80, 80, 90, 255)


def base(width=16, height=16):
    """The housing every face shares: a dark border, the body and an inner panel."""
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 0, width - 1, height - 1], fill=DARK_BORDER)
    draw.rectangle([1, 1, width - 2, height - 2], fill=BODY_DARK)
    draw.rectangle([2, 2, width - 3, height - 3], fill=BODY_MID)
    return img, draw


def create_front_texture():
    """The reading face, pressed against the container: a 3x3 grid of slot readers, one lit."""
    img, draw = base()
    draw.rectangle([2, 2, 13, 13], fill=BODY_LIGHT)
    for row in range(3):
        for col in range(3):
            x = 3 + col * 4
            y = 3 + row * 4
            draw.rectangle([x, y, x + 2, y + 2], fill=SLOT_FRAME)
            draw.point((x + 1, y + 1), fill=SLOT_INNER)
    # The reader sweeping the slots: the middle slot lit, its row dimly.
    draw.point((8, 8), fill=READER_CYAN)
    draw.point((4, 8), fill=READER_CYAN_DIM)
    draw.point((12, 8), fill=READER_CYAN_DIM)
    return img


def create_back_texture():
    """The face players see: a display with four rising fill bars, three of them lit."""
    img, draw = base()
    draw.rectangle([3, 3, 12, 10], fill=SCREEN_EDGE)
    draw.rectangle([4, 4, 11, 9], fill=SCREEN_DARK)
    bar_colours = [BAR_FULL, BAR_FULL, BAR_HIGH, BAR_OFF]
    for index, colour in enumerate(bar_colours):
        x = 5 + index * 2
        height = 2 + index
        draw.rectangle([x, 9 - height + 1, x, 9], fill=colour)
    # Status light and screws under the display.
    draw.point((4, 12), fill=LED_GREEN)
    draw.line([(6, 12), (11, 12)], fill=DETAIL_LINE)
    draw.point((3, 3), fill=SCREW)
    draw.point((12, 3), fill=SCREW)
    return img


def create_side_texture():
    """The side panels: a vertical level gauge and two status lights."""
    img, draw = base()
    # Level gauge, filled two thirds.
    draw.rectangle([4, 3, 6, 12], fill=SCREEN_EDGE)
    draw.rectangle([5, 4, 5, 11], fill=BAR_OFF)
    draw.rectangle([5, 7, 5, 11], fill=BAR_FULL)
    draw.line([(8, 3), (8, 12)], fill=DETAIL_LINE)
    draw.rectangle([10, 4, 11, 5], fill=LED_GREEN)
    draw.rectangle([10, 7, 11, 8], fill=LED_OFF)
    draw.rectangle([10, 10, 12, 10], fill=VENT_SLOT)
    draw.rectangle([10, 12, 12, 12], fill=VENT_SLOT)
    return img


def create_top_texture():
    """The top surface: ventilation slots and corner screws."""
    img, draw = base()
    for y in (4, 6, 8, 10):
        draw.rectangle([4, y, 11, y], fill=VENT_SLOT)
    draw.rectangle([4, 12, 11, 12], fill=READER_CYAN_DIM)
    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):
        draw.point((x, y), fill=SCREW)
    return img


def create_bottom_texture():
    """The bottom surface: four mounting feet and a label."""
    img, draw = base()
    for x, y in ((3, 3), (10, 3), (3, 10), (10, 10)):
        draw.rectangle([x, y, x + 2, y + 2], fill=FOOT_COLOR)
        draw.point((x + 1, y + 1), fill=FOOT_RUBBER)
    draw.rectangle([6, 7, 9, 8], fill=BODY_LIGHT)
    return img


def main():
    """Generate all Container Observer textures and save them."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {
        'container_observer_front': (create_front_texture, 'Reading face with its grid of slot readers'),
        'container_observer_back': (create_back_texture, 'Display with fill bars'),
        'container_observer_side': (create_side_texture, 'Side panel with a level gauge and status lights'),
        'container_observer_top': (create_top_texture, 'Top surface with ventilation slots'),
        'container_observer_bottom': (create_bottom_texture, 'Bottom surface with mounting feet'),
    }

    for name, (func, description) in textures.items():
        img = func()
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        print(f"- {output_path}: {description}")

    print("\nContainer Observer textures generated successfully!")


if __name__ == '__main__':
    main()
