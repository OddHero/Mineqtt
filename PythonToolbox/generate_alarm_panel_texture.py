#!/usr/bin/env python3
"""Generate procedural Alarm Panel textures for Mineqtt mod.

The Alarm Panel is a wall keypad (10 x 12 pixels on its front, 2 deep) in the devices' housing with a brushed metal
frame: a small display, a status light and twelve keys. The block state's status picks the front texture:
- alarm_panel_front_disarmed: the light green, steady
- alarm_panel_front_arming: the light amber, blinking once a second (animated, 2 frames)
- alarm_panel_front_armed: the light red, steady
- alarm_panel_front_pending: the light amber, blinking fast (animated, 2 frames), the display amber too
- alarm_panel_front_triggered: the light and the display red, flashing (animated, 2 frames)
- alarm_panel_side: brushed metal for the edges and the back

The block models take only the middle of the front texture, the keypad's face:
- north (the face) -> front, uv [3, 2, 13, 14]; every other face -> side
Animated textures are vertical strips of 16x16 frames with a .png.mcmeta beside them.
"""
import json
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Color palette: the devices' housing, brushed metal, the keys, the display and the lights
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (55, 55, 65, 255)
BODY_MID = (68, 68, 78, 255)
METAL_DARK = (120, 122, 132, 255)
METAL_MID = (150, 152, 164, 255)
METAL_LIGHT = (180, 182, 196, 255)
KEY = (205, 208, 220, 255)
KEY_SHADE = (120, 122, 134, 255)
DISPLAY_OFF = (30, 42, 38, 255)
DISPLAY_ON = (90, 170, 120, 255)
LIGHT_OFF = (45, 45, 52, 255)
GREEN = (60, 200, 90, 255)
GREEN_BRIGHT = (150, 255, 170, 255)
AMBER = (230, 150, 30, 255)
AMBER_BRIGHT = (255, 210, 120, 255)
RED = (210, 50, 45, 255)
RED_BRIGHT = (255, 140, 120, 255)

# The keypad's face within the 16x16 texture (the model's uv): x 3..12, y 2..13 inclusive.
X0, Y0, X1, Y1 = 3, 2, 12, 13


def create_side_texture(width=16, height=16):
    """Brushed metal: horizontal streaks, darker towards the edges."""
    img = Image.new('RGBA', (width, height), METAL_MID)
    draw = ImageDraw.Draw(img)
    for y in range(height):
        shade = METAL_LIGHT if y % 3 == 0 else (METAL_DARK if y % 3 == 2 else METAL_MID)
        draw.line([0, y, width - 1, y], fill=shade)
    draw.rectangle([0, 0, width - 1, height - 1], outline=DARK_BORDER)
    return img


def create_frame(light, light_bright, display):
    """One frame of the face: a dark frame, the display and the status light on top, four rows of three keys below."""
    img = create_side_texture()
    draw = ImageDraw.Draw(img)
    draw.rectangle([X0, Y0, X1, Y1], fill=BODY_DARK, outline=DARK_BORDER)
    draw.rectangle([X0 + 1, Y0 + 1, X1 - 1, Y1 - 1], fill=BODY_MID)
    # The display, a strip of six pixels, with a few lit segments
    draw.rectangle([X0 + 1, Y0 + 1, X0 + 6, Y0 + 2], fill=DISPLAY_OFF)
    for x in (X0 + 2, X0 + 4):
        draw.point((x, Y0 + 1), fill=display)
        draw.point((x + 1, Y0 + 2), fill=display)
    # The status light, two pixels square, with a glint
    draw.rectangle([X0 + 7, Y0 + 1, X0 + 8, Y0 + 2], fill=light)
    draw.point((X0 + 7, Y0 + 1), fill=light_bright)
    # Twelve keys: three columns of two pixels, four rows
    for row in range(4):
        y = Y0 + 4 + row * 2
        for column in (X0 + 1, X0 + 4, X0 + 7):
            draw.point((column, y), fill=KEY)
            draw.point((column + 1, y), fill=KEY_SHADE)
    return img


def strip(frames):
    """Frames stacked into a vertical strip, as Minecraft animates a texture."""
    img = Image.new('RGBA', (16, 16 * len(frames)))
    for i, frame in enumerate(frames):
        img.paste(frame, (0, 16 * i))
    return img


def main():
    """Generate all Alarm Panel textures and save them, with the animations' .mcmeta files."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {
        'alarm_panel_side': (create_side_texture(), None, 'Brushed metal edges and back'),
        'alarm_panel_front_disarmed': (create_frame(GREEN, GREEN_BRIGHT, DISPLAY_ON), None, 'Keypad, green light (disarmed)'),
        'alarm_panel_front_arming': (strip([create_frame(AMBER, AMBER_BRIGHT, DISPLAY_ON), create_frame(LIGHT_OFF, LIGHT_OFF, DISPLAY_ON)]), 10,
                                     'Keypad, amber light blinking once a second (arming)'),
        'alarm_panel_front_armed': (create_frame(RED, RED_BRIGHT, DISPLAY_ON), None, 'Keypad, red light (armed)'),
        'alarm_panel_front_pending': (strip([create_frame(AMBER, AMBER_BRIGHT, AMBER), create_frame(LIGHT_OFF, LIGHT_OFF, DISPLAY_OFF)]), 4,
                                      'Keypad, amber light blinking fast (entry delay)'),
        'alarm_panel_front_triggered': (strip([create_frame(RED_BRIGHT, RED_BRIGHT, RED), create_frame(RED, RED, DISPLAY_OFF)]), 3,
                                        'Keypad, red light and display flashing (triggered)'),
    }

    for name, (img, frametime, description) in textures.items():
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        meta_path = output_path + '.mcmeta'
        if frametime is not None:
            with open(meta_path, 'w', encoding='utf-8', newline='\n') as meta:
                json.dump({'animation': {'frametime': frametime}}, meta, indent=2)
                meta.write('\n')
        elif os.path.exists(meta_path):
            os.remove(meta_path)
        print(f"- {output_path}: {description}")

    print("\nAlarm Panel textures generated successfully!")


if __name__ == '__main__':
    main()
