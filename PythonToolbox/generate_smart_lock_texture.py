#!/usr/bin/env python3
"""Generate procedural Smart Lock textures for Mineqtt mod.

Generates 3 textures for the Smart Lock's plate, a small keypad (6 x 8 pixels on its front, 2 deep) that sits on the
face of the door, trapdoor, fence gate or container it locks. It wears the devices' housing with a brushed metal frame:
- front: The keypad with a status light: green while unlocked
- front_locked: The same keypad with the light red: locked
- side: Brushed metal for the plate's edges and back

The block models (smart_lock, smart_lock_locked) take only the middle of the front texture, the plate's face:
- north (the face) -> front, uv [5, 4, 11, 12]; every other face -> side
"""
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Color palette: the devices' housing, brushed metal, the keys and the two lights
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (60, 60, 70, 255)
BODY_MID = (70, 70, 80, 255)
METAL_DARK = (120, 122, 132, 255)
METAL_MID = (150, 152, 164, 255)
METAL_LIGHT = (180, 182, 196, 255)
KEY = (205, 208, 220, 255)
KEY_SHADE = (95, 97, 108, 255)
GREEN = (60, 200, 90, 255)
GREEN_BRIGHT = (150, 255, 170, 255)
RED = (210, 50, 45, 255)
RED_BRIGHT = (255, 140, 120, 255)

# The plate's face within the 16x16 texture (the model's uv): x 5..10, y 4..11 inclusive.
X0, Y0, X1, Y1 = 5, 4, 10, 11


def create_side_texture(width=16, height=16):
    """Brushed metal: horizontal streaks, darker towards the edges."""
    img = Image.new('RGBA', (width, height), METAL_MID)
    draw = ImageDraw.Draw(img)
    for y in range(height):
        shade = METAL_LIGHT if y % 3 == 0 else (METAL_DARK if y % 3 == 2 else METAL_MID)
        draw.line([0, y, width - 1, y], fill=shade)
    draw.rectangle([0, 0, width - 1, height - 1], outline=DARK_BORDER)
    return img


def create_front_texture(light, light_bright, width=16, height=16):
    """The keypad's face: a dark frame, the status light on top, two columns of three keys below."""
    img = create_side_texture(width, height)
    draw = ImageDraw.Draw(img)
    # Housing of the face
    draw.rectangle([X0, Y0, X1, Y1], fill=BODY_DARK, outline=DARK_BORDER)
    draw.rectangle([X0 + 1, Y0 + 1, X1 - 1, Y1 - 1], fill=BODY_MID)
    # Status light, two pixels wide, with a glint
    draw.point((X0 + 2, Y0 + 1), fill=light_bright)
    draw.point((X0 + 3, Y0 + 1), fill=light)
    # Keys: two columns, three rows
    for row in range(3):
        y = Y0 + 3 + row
        for column in (X0 + 1, X0 + 4):
            draw.point((column, y), fill=KEY)
        draw.point((X0 + 2, y), fill=KEY_SHADE)
        draw.point((X0 + 3, y), fill=KEY_SHADE)
    return img


def main():
    """Generate all Smart Lock textures and save them."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {
        'smart_lock_side': create_side_texture,
        'smart_lock_front': lambda: create_front_texture(GREEN, GREEN_BRIGHT),
        'smart_lock_front_locked': lambda: create_front_texture(RED, RED_BRIGHT),
    }

    descriptions = {
        'smart_lock_side': 'Brushed metal edges and back',
        'smart_lock_front': 'Keypad with a green light (unlocked)',
        'smart_lock_front_locked': 'Keypad with a red light (locked)',
    }

    for name, func in textures.items():
        img = func()
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        print(f"- {output_path}: {descriptions[name]}")

    print("\nSmart Lock textures generated successfully!")


if __name__ == '__main__':
    main()
