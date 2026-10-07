#!/usr/bin/env python3
"""Generate procedural humidity sensor textures for Mineqtt mod.

Generates 2 textures for the Humidity Sensor block:
- humidity_sensor_block.png: Multi-face body texture (dark gray electronic device with green/teal accents)
- humidity_sensor_display.png: Display screen showing humidity reading in green/teal digits

The block model uses UV mapping to extract different regions of the body texture
for different faces (base, body, sides, top, bottom). The display texture shows
a digital humidity readout on the front face.
"""
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Color palette - green/teal tones for humidity/moisture sensing
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (55, 60, 65, 255)
BODY_MID = (65, 72, 78, 255)
BODY_LIGHT = (75, 85, 90, 255)
BODY_HIGHLIGHT = (85, 95, 100, 255)
METAL_LIGHT = (180, 180, 200, 255)
METAL_MID = (140, 150, 170, 255)
DETAIL_LINE = (50, 55, 60, 255)
SCREW_COLOR = (100, 105, 115, 255)

# Humidity sensor accent colors - green/teal for moisture
GREEN_ACCENT = (60, 170, 120, 255)
GREEN_BRIGHT = (80, 210, 150, 255)
GREEN_DIM = (40, 130, 90, 255)
TEAL_DIGIT = (80, 200, 180, 255)
TEAL_DIGIT_BRIGHT = (120, 230, 210, 255)
TEAL_DIGIT_DIM = (50, 150, 130, 255)
DISPLAY_BG = (20, 30, 28, 255)
DISPLAY_BG_LIGHT = (30, 40, 38, 255)
LED_GREEN = (50, 200, 120, 255)
LED_GREEN_GLOW = (30, 160, 90, 255)
LED_GREEN_GLOW_DIM = (20, 120, 60, 255)

# Display side colors (thin strips visible on display sides/top/bottom)
DISPLAY_SIDE = (25, 35, 32, 255)
DISPLAY_SIDE_TOP = (35, 45, 42, 255)
DISPLAY_SIDE_BOTTOM = (15, 25, 22, 255)


def create_body_texture(width=16, height=16):
    """Create the multi-face body texture for the humidity sensor.
    
    This single texture contains all face regions mapped via UV coordinates:
    - Center area (4-12, 4-12): Main body front face
    - Base area (5-11, 5-11): Base front face  
    - Left strip (0-2, 5-11): West side of base
    - Right strip (14-16, 5-11): East side of base
    - Top strip (5-11, 0-2): Top of base
    - Bottom strip (5-11, 14-16): Bottom of base
    - Body sides (2-8, 4-12 and 8-14, 4-12): West/east body faces
    - Body top (4-12, 2-8): Top of body
    - Body bottom (4-12, 8-14): Bottom of body
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # === Dark border on all edges ===
    draw.rectangle([0, 0, width - 1, 0], fill=DARK_BORDER)
    draw.rectangle([0, height - 1, width - 1, height - 1], fill=DARK_BORDER)
    draw.rectangle([0, 0, 0, height - 1], fill=DARK_BORDER)
    draw.rectangle([width - 1, 0, width - 1, height - 1], fill=DARK_BORDER)

    # === Main body area (4-12, 4-12) - front face ===
    draw.rectangle([1, 1, width - 2, height - 2], fill=BODY_DARK)

    # Inner panel
    draw.rectangle([2, 2, width - 3, height - 3], fill=BODY_MID)

    # === Top region (y=0-3) - base top and body top ===
    # Base top (5-11, 0-2)
    draw.rectangle([5, 0, 10, 2], fill=BODY_LIGHT)
    # Ventilation lines on base top
    draw.line([(6, 1), (9, 1)], fill=DETAIL_LINE)

    # Body top (4-12, 2-8) - x maps to y in UV, y maps to x
    for y in range(2, 4):
        for x in range(4, 12):
            img.putpixel((x, y), BODY_LIGHT)
    # Ventilation grid on body top
    for x in range(5, 11):
        img.putpixel((x, 3), DETAIL_LINE)

    # === Bottom region (y=13-15) - base bottom and body bottom ===
    # Base bottom (5-11, 14-16)
    draw.rectangle([5, 14, 10, 15], fill=BODY_LIGHT)
    # Mounting feet on base bottom
    draw.rectangle([5, 14, 6, 15], fill=SCREW_COLOR)
    draw.rectangle([9, 14, 10, 15], fill=SCREW_COLOR)

    # Body bottom (4-12, 8-14) - partial, y=13 is body bottom
    for x in range(4, 12):
        img.putpixel((x, 13), BODY_LIGHT)

    # === Left region (x=0-1) - base west side ===
    for y in range(5, 11):
        img.putpixel((0, y), BODY_LIGHT)
        img.putpixel((1, y), BODY_LIGHT)
    # Connection detail on base west
    draw.rectangle([0, 7, 1, 8], fill=GREEN_DIM)

    # === Right region (x=14-15) - base east side ===
    for y in range(5, 11):
        img.putpixel((14, y), BODY_LIGHT)
        img.putpixel((15, y), BODY_LIGHT)
    # Connection detail on base east
    draw.rectangle([14, 7, 15, 8], fill=GREEN_DIM)

    # === Body side regions (x=2-7 and x=8-13, y=4-11) ===
    # West body side (2-8, 4-12)
    for y in range(4, 12):
        for x in range(2, 4):
            img.putpixel((x, y), BODY_LIGHT)
    # Indicator light on west side
    draw.rectangle([2, 6, 3, 7], fill=LED_GREEN)
    draw.rectangle([2, 8, 3, 9], fill=GREEN_DIM)

    # East body side (8-14, 4-12)
    for y in range(4, 12):
        for x in range(12, 14):
            img.putpixel((x, y), BODY_LIGHT)
    # Ventilation slots on east side
    for y in [5, 7, 9]:
        draw.rectangle([12, y, 13, y], fill=DETAIL_LINE)

    # === Main front face details (4-12, 4-12) ===
    # Outer frame of the device
    draw.rectangle([4, 4, 11, 11], outline=BODY_HIGHLIGHT)

    # Display bezel area (where display sits, UV 5-11, 6-10)
    draw.rectangle([4, 5, 11, 5], fill=BODY_HIGHLIGHT)
    draw.rectangle([4, 10, 11, 11], fill=BODY_HIGHLIGHT)
    draw.rectangle([4, 5, 4, 10], fill=BODY_HIGHLIGHT)
    draw.rectangle([11, 5, 11, 10], fill=BODY_HIGHLIGHT)

    # Display screen area (5-10, 6-9) - dark screen background
    draw.rectangle([5, 6, 10, 9], fill=DISPLAY_BG)

    # Humidity icon (water drop symbol) above display
    # Water drop shape at top center
    img.putpixel((7, 1), GREEN_ACCENT)
    img.putpixel((8, 1), GREEN_ACCENT)
    img.putpixel((6, 2), GREEN_ACCENT)
    img.putpixel((7, 2), GREEN_BRIGHT)
    img.putpixel((8, 2), GREEN_BRIGHT)
    img.putpixel((9, 2), GREEN_ACCENT)
    img.putpixel((6, 3), GREEN_ACCENT)
    img.putpixel((7, 3), GREEN_ACCENT)
    img.putpixel((8, 3), GREEN_ACCENT)
    img.putpixel((9, 3), GREEN_ACCENT)
    img.putpixel((7, 4), GREEN_DIM)
    img.putpixel((8, 4), GREEN_DIM)

    # Status LED indicator (top-left of body)
    draw.rectangle([5, 5, 5, 5], fill=LED_GREEN)

    # Small detail lines below display
    draw.line([(6, 11), (10, 11)], fill=DETAIL_LINE)

    # Corner screws on body
    draw.ellipse([4, 4, 5, 5], outline=SCREW_COLOR)
    draw.ellipse([10, 4, 11, 5], outline=SCREW_COLOR)
    draw.ellipse([4, 10, 5, 11], outline=SCREW_COLOR)
    draw.ellipse([10, 10, 11, 11], outline=SCREW_COLOR)

    # Base side details - mounting bracket details on base sides
    draw.rectangle([0, 5, 1, 5], fill=SCREW_COLOR)
    draw.rectangle([0, 10, 1, 10], fill=SCREW_COLOR)
    draw.rectangle([14, 5, 15, 5], fill=SCREW_COLOR)
    draw.rectangle([14, 10, 15, 10], fill=SCREW_COLOR)

    return img


def create_display_texture(width=16, height=16):
    """Create the display screen texture showing a humidity reading.
    
    The display shows "45%" in green/teal digital digits on a dark background.
    UV mapping:
    - North face (front): UV 5-11, 6-10 (6x4 pixel main display area)
    - South face (back): UV 5-11, 6-10
    - West face (thin strip): UV 0-0.5, 6-10
    - East face (thin strip): UV 15.5-16, 6-10
    - Top face (thin strip): UV 5-11, 0-0.5
    - Bottom face (thin strip): UV 5-11, 15.5-16
    """
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # === Main display area (5-11, 6-10) ===
    # Dark screen background
    draw.rectangle([5, 6, 10, 9], fill=DISPLAY_BG)

    # Screen inner glow (slightly lighter edges)
    draw.rectangle([5, 6, 10, 6], fill=DISPLAY_BG_LIGHT)
    draw.rectangle([5, 9, 10, 9], fill=DISPLAY_BG_LIGHT)
    draw.rectangle([5, 6, 5, 9], fill=DISPLAY_BG_LIGHT)
    draw.rectangle([10, 6, 10, 9], fill=DISPLAY_BG_LIGHT)

    # === Digital humidity reading: "45%" ===
    # Digits are drawn in the 6x4 area (x: 5-10, y: 6-9)
    # "4" at x=5-7, "5" at x=8-10, "%" symbol integrated

    # First digit "4" (x=5-7, y=6-9) - 7-segment: b, c, f, g
    # Top-left (f segment)
    img.putpixel((5, 6), TEAL_DIGIT)
    img.putpixel((5, 7), TEAL_DIGIT)
    # Top-right (b segment)
    img.putpixel((7, 6), TEAL_DIGIT)
    img.putpixel((7, 7), TEAL_DIGIT)
    # Middle bar (g segment)
    img.putpixel((5, 8), TEAL_DIGIT)
    img.putpixel((6, 8), TEAL_DIGIT)
    img.putpixel((7, 8), TEAL_DIGIT)
    # Bottom-right (c segment)
    img.putpixel((7, 8), TEAL_DIGIT)
    img.putpixel((7, 9), TEAL_DIGIT)

    # Second digit "5" (x=8-10, y=6-9) - 7-segment: a, c, d, f, g
    # Top bar (a segment)
    img.putpixel((8, 6), TEAL_DIGIT)
    img.putpixel((9, 6), TEAL_DIGIT)
    img.putpixel((10, 6), TEAL_DIGIT)
    # Top-left (f segment)
    img.putpixel((8, 6), TEAL_DIGIT)
    img.putpixel((8, 7), TEAL_DIGIT)
    # Middle bar (g segment)
    img.putpixel((8, 8), TEAL_DIGIT)
    img.putpixel((9, 8), TEAL_DIGIT)
    img.putpixel((10, 8), TEAL_DIGIT)
    # Bottom-right (c segment)
    img.putpixel((10, 8), TEAL_DIGIT)
    img.putpixel((10, 9), TEAL_DIGIT)
    # Bottom bar (d segment)
    img.putpixel((8, 9), TEAL_DIGIT)
    img.putpixel((9, 9), TEAL_DIGIT)
    img.putpixel((10, 9), TEAL_DIGIT)

    # Digit highlight pixels for glow effect
    img.putpixel((6, 8), TEAL_DIGIT_BRIGHT)
    img.putpixel((9, 8), TEAL_DIGIT_BRIGHT)
    img.putpixel((6, 6), TEAL_DIGIT_BRIGHT)
    img.putpixel((9, 6), TEAL_DIGIT_BRIGHT)
    img.putpixel((9, 9), TEAL_DIGIT_BRIGHT)

    # === Percent symbol "%" ===
    # Drawn as two diagonal slashes with circles
    # Left slash and circle
    img.putpixel((5, 6), TEAL_DIGIT_DIM)
    img.putpixel((6, 7), TEAL_DIGIT_DIM)
    # Right slash and circle  
    img.putpixel((10, 7), TEAL_DIGIT_DIM)
    img.putpixel((9, 9), TEAL_DIGIT_DIM)

    # === Small water drop icon in bottom-left of display ===
    img.putpixel((5, 7), GREEN_ACCENT)
    img.putpixel((6, 7), GREEN_DIM)

    # === Display side strips ===
    # West side strip (x=0-1, y=6-10) - thin edge of display
    for y in range(6, 10):
        img.putpixel((0, y), DISPLAY_SIDE)
        img.putpixel((1, y), DISPLAY_SIDE)

    # East side strip (x=14-15, y=6-10)
    for y in range(6, 10):
        img.putpixel((14, y), DISPLAY_SIDE)
        img.putpixel((15, y), DISPLAY_SIDE)

    # Top strip (x=5-10, y=0-1) - top edge of display
    for x in range(5, 11):
        img.putpixel((x, 0), DISPLAY_SIDE_TOP)
        img.putpixel((x, 1), DISPLAY_SIDE_TOP)

    # Bottom strip (x=5-10, y=14-15) - bottom edge of display
    for x in range(5, 11):
        img.putpixel((x, 14), DISPLAY_SIDE_BOTTOM)
        img.putpixel((x, 15), DISPLAY_SIDE_BOTTOM)

    # === Screen reflection effect ===
    # Subtle diagonal highlight on the display
    img.putpixel((6, 6), (40, 55, 50, 255))
    img.putpixel((7, 7), (35, 50, 45, 255))

    return img


def main():
    """Generate humidity sensor textures and save them."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {
        'humidity_sensor_block': create_body_texture,
        'humidity_sensor_display': create_display_texture,
    }

    descriptions = {
        'humidity_sensor_block': 'Multi-face body texture with green/teal accents',
        'humidity_sensor_display': 'Display screen showing 45% in green/teal digits',
    }

    for name, func in textures.items():
        img = func()
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        print(f"- {output_path}: {descriptions[name]}")

    print("\nHumidity sensor textures generated successfully!")


if __name__ == '__main__':
    main()
