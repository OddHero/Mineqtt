#!/usr/bin/env python3
"""Generate procedural temperature sensor textures for Mineqtt mod.

Generates 2 textures for the Temperature Sensor block:
- temperature_sensor_block.png: Multi-face body texture (dark gray electronic device with blue/teal accents)
- temperature_sensor_display.png: Display screen showing temperature reading in blue/teal digits

The block model uses UV mapping to extract different regions of the body texture
for different faces (base, body, sides, top, bottom). The display texture shows
a digital temperature readout on the front face.
"""
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Color palette - cool blue/teal tones for temperature sensing
DARK_BORDER = (40, 40, 50, 255)
BODY_DARK = (55, 60, 70, 255)
BODY_MID = (65, 72, 85, 255)
BODY_LIGHT = (75, 85, 100, 255)
BODY_HIGHLIGHT = (85, 95, 115, 255)
METAL_LIGHT = (180, 180, 200, 255)
METAL_MID = (140, 150, 170, 255)
DETAIL_LINE = (50, 55, 65, 255)
SCREW_COLOR = (100, 105, 115, 255)

# Temperature sensor accent colors - cool blue/teal
TEAL_ACCENT = (60, 160, 170, 255)
TEAL_BRIGHT = (80, 200, 210, 255)
TEAL_DIM = (40, 120, 130, 255)
BLUE_DIGIT = (100, 200, 255, 255)
BLUE_DIGIT_BRIGHT = (150, 220, 255, 255)
BLUE_DIGIT_DIM = (60, 140, 190, 255)
DISPLAY_BG = (20, 25, 35, 255)
DISPLAY_BG_LIGHT = (30, 35, 50, 255)
LED_BLUE = (50, 180, 255, 255)
LED_BLUE_GLOW = (30, 140, 200, 255)
LED_BLUE_GLOW_DIM = (20, 100, 150, 255)

# Display side colors (thin strips visible on display sides/top/bottom)
DISPLAY_SIDE = (25, 30, 40, 255)
DISPLAY_SIDE_TOP = (35, 40, 55, 255)
DISPLAY_SIDE_BOTTOM = (15, 20, 30, 255)


def draw_digit(draw, x, y, digit, color, highlight_color=None):
    """Draw a 3x4 pixel digital-style digit at position (x, y).
    
    Each digit is drawn as a 3x4 pixel pattern using 7-segment style.
    """
    if highlight_color is None:
        highlight_color = color

    # 7-segment digit patterns (each segment is a list of pixel offsets)
    # Segments: a(top), b(top-right), c(bottom-right), d(bottom), e(bottom-left), f(top-left), g(middle)
    segments = {
        'a': [(1, 0), (2, 0), (0, 0)],
        'b': [(3, 0), (3, 1)],
        'c': [(3, 2), (3, 3)],
        'd': [(1, 3), (2, 3), (0, 3)],
        'e': [(0, 2), (0, 3)],
        'f': [(0, 0), (0, 1)],
        'g': [(1, 1), (2, 1), (0, 1)],
    }

    # Digit to segment mapping
    digit_map = {
        '0': ['a', 'b', 'c', 'd', 'e', 'f'],
        '1': ['b', 'c'],
        '2': ['a', 'b', 'd', 'e', 'g'],
        '3': ['a', 'b', 'c', 'd', 'g'],
        '4': ['b', 'c', 'f', 'g'],
        '5': ['a', 'c', 'd', 'f', 'g'],
        '6': ['a', 'c', 'd', 'e', 'f', 'g'],
        '7': ['a', 'b', 'c'],
        '8': ['a', 'b', 'c', 'd', 'e', 'f', 'g'],
        '9': ['a', 'b', 'c', 'd', 'f', 'g'],
        '.': [(2, 3)],
        '°': [(1, 0), (2, 0), (0, 0), (0, 1), (2, 2), (1, 3), (0, 3)],  # degree symbol
        '-': [(1, 1), (2, 1), (0, 1)],  # minus sign
    }

    segs = digit_map.get(str(digit), [])
    for seg_name in segs:
        for dx, dy in segments[seg_name]:
            px, py = x + dx, y + dy
            if 0 <= px < 16 and 0 <= py < 16:
                img_pixel = img.getpixel((px, py))
                # Only draw if background is dark (don't overwrite lighter pixels)
                if img_pixel[0] < 60:
                    img.putpixel((px, py), color)


def create_body_texture(width=16, height=16):
    """Create the multi-face body texture for the temperature sensor.
    
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
    # This is the largest visible face
    draw.rectangle([1, 1, width - 2, height - 2], fill=BODY_DARK)

    # Inner panel
    draw.rectangle([2, 2, width - 3, height - 3], fill=BODY_MID)

    # === Top region (y=0-3) - base top and body top ===
    # Base top (5-11, 0-2)
    draw.rectangle([5, 0, 10, 2], fill=BODY_LIGHT)
    # Ventilation lines on base top
    draw.line([(6, 1), (9, 1)], fill=DETAIL_LINE)

    # Body top (4-12, 2-8) - x maps to y in UV, y maps to x
    # Fill body top area
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
    # Base west (0-2, 5-11)
    for y in range(5, 11):
        img.putpixel((0, y), BODY_LIGHT)
        img.putpixel((1, y), BODY_LIGHT)
    # Connection detail on base west
    draw.rectangle([0, 7, 1, 8], fill=TEAL_DIM)

    # === Right region (x=14-15) - base east side ===
    # Base east (14-16, 5-11)
    for y in range(5, 11):
        img.putpixel((14, y), BODY_LIGHT)
        img.putpixel((15, y), BODY_LIGHT)
    # Connection detail on base east
    draw.rectangle([14, 7, 15, 8], fill=TEAL_DIM)

    # === Body side regions (x=2-7 and x=8-13, y=4-11) ===
    # West body side (2-8, 4-12)
    for y in range(4, 12):
        for x in range(2, 4):
            img.putpixel((x, y), BODY_LIGHT)
    # Indicator light on west side
    draw.rectangle([2, 6, 3, 7], fill=LED_BLUE)
    draw.rectangle([2, 8, 3, 9], fill=TEAL_DIM)

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
    # The display texture overlays this, but we draw the bezel frame
    draw.rectangle([4, 5, 11, 5], fill=BODY_HIGHLIGHT)
    draw.rectangle([4, 10, 11, 11], fill=BODY_HIGHLIGHT)
    draw.rectangle([4, 5, 4, 10], fill=BODY_HIGHLIGHT)
    draw.rectangle([11, 5, 11, 10], fill=BODY_HIGHLIGHT)

    # Display screen area (5-10, 6-9) - dark screen background
    draw.rectangle([5, 6, 10, 9], fill=DISPLAY_BG)

    # Temperature icon (thermometer symbol) above display
    # Thermometer body
    draw.rectangle([7, 3, 8, 3], fill=TEAL_ACCENT)
    draw.rectangle([7, 2, 8, 2], fill=TEAL_BRIGHT)
    # Thermometer bulb
    draw.ellipse([6, 1, 9, 3], outline=TEAL_ACCENT)
    img.putpixel((7, 1), TEAL_ACCENT)
    img.putpixel((8, 1), TEAL_ACCENT)
    img.putpixel((7, 2), TEAL_BRIGHT)
    img.putpixel((8, 2), TEAL_BRIGHT)

    # Status LED indicator (top-left of body)
    draw.rectangle([5, 5, 5, 5], fill=LED_BLUE)

    # Small detail lines below display
    draw.line([(6, 11), (10, 11)], fill=DETAIL_LINE)

    # Corner screws on body
    draw.ellipse([4, 4, 5, 5], outline=SCREW_COLOR)
    draw.ellipse([10, 4, 11, 5], outline=SCREW_COLOR)
    draw.ellipse([4, 10, 5, 11], outline=SCREW_COLOR)
    draw.ellipse([10, 10, 11, 11], outline=SCREW_COLOR)

    # === Base front face (5-11, 5-11) - overlaps with body ===
    # The base is at the back (z=14-16), so its north face is visible
    # but mostly occluded by the body. The UV maps to the same region.
    # Base south face (cullface) uses same UV - visible from behind
    # Add subtle base details in the corners not covered by body
    # Base extends to y=5-11, x=5-11 but body covers 4-12, 4-12
    # So base front is mostly hidden, but base sides are visible

    # Base side details (already drawn above in left/right regions)
    # Add mounting bracket details on base sides
    draw.rectangle([0, 5, 1, 5], fill=SCREW_COLOR)
    draw.rectangle([0, 10, 1, 10], fill=SCREW_COLOR)
    draw.rectangle([14, 5, 15, 5], fill=SCREW_COLOR)
    draw.rectangle([14, 10, 15, 10], fill=SCREW_COLOR)

    return img


def create_display_texture(width=16, height=16):
    """Create the display screen texture showing a temperature reading.
    
    The display shows "22°C" in blue/teal digital digits on a dark background.
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

    # === Digital temperature reading: "22°C" ===
    # Digits are drawn in the 6x4 area (x: 5-10, y: 6-9)
    # "2" at x=5, "2" at x=7, "°" at x=9, "C" implied by context

    # First "2" digit (x=5-7, y=6-9)
    # Top bar
    img.putpixel((5, 6), BLUE_DIGIT)
    img.putpixel((6, 6), BLUE_DIGIT)
    # Top right
    img.putpixel((7, 6), BLUE_DIGIT)
    img.putpixel((7, 7), BLUE_DIGIT)
    # Middle bar
    img.putpixel((5, 8), BLUE_DIGIT)
    img.putpixel((6, 8), BLUE_DIGIT)
    img.putpixel((7, 8), BLUE_DIGIT)
    # Bottom left
    img.putpixel((5, 8), BLUE_DIGIT)
    img.putpixel((5, 9), BLUE_DIGIT)
    # Bottom bar
    img.putpixel((5, 9), BLUE_DIGIT)
    img.putpixel((6, 9), BLUE_DIGIT)
    img.putpixel((7, 9), BLUE_DIGIT)

    # Second "2" digit (x=8-10, y=6-9)
    # Top bar
    img.putpixel((8, 6), BLUE_DIGIT)
    img.putpixel((9, 6), BLUE_DIGIT)
    img.putpixel((10, 6), BLUE_DIGIT)
    # Top right
    img.putpixel((10, 7), BLUE_DIGIT)
    # Middle bar
    img.putpixel((8, 8), BLUE_DIGIT)
    img.putpixel((9, 8), BLUE_DIGIT)
    img.putpixel((10, 8), BLUE_DIGIT)
    # Bottom left
    img.putpixel((8, 8), BLUE_DIGIT)
    img.putpixel((8, 9), BLUE_DIGIT)
    # Bottom bar
    img.putpixel((8, 9), BLUE_DIGIT)
    img.putpixel((9, 9), BLUE_DIGIT)
    img.putpixel((10, 9), BLUE_DIGIT)

    # Digit highlight pixels for glow effect
    img.putpixel((6, 6), BLUE_DIGIT_BRIGHT)
    img.putpixel((9, 6), BLUE_DIGIT_BRIGHT)
    img.putpixel((6, 8), BLUE_DIGIT_BRIGHT)
    img.putpixel((9, 8), BLUE_DIGIT_BRIGHT)
    img.putpixel((6, 9), BLUE_DIGIT_BRIGHT)
    img.putpixel((9, 9), BLUE_DIGIT_BRIGHT)

    # === Degree symbol "°" (small circle at top right of display) ===
    # Positioned at x=10, y=6 area
    img.putpixel((10, 6), BLUE_DIGIT_DIM)

    # === Small "C" for Celsius (bottom right corner of display) ===
    # Simple C shape
    img.putpixel((9, 7), BLUE_DIGIT_DIM)
    img.putpixel((10, 7), BLUE_DIGIT_DIM)

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
    img.putpixel((6, 6), (40, 50, 70, 255))
    img.putpixel((7, 7), (35, 45, 65, 255))

    return img


def main():
    """Generate temperature sensor textures and save them."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {
        'temperature_sensor_block': create_body_texture,
        'temperature_sensor_display': create_display_texture,
    }

    descriptions = {
        'temperature_sensor_block': 'Multi-face body texture with blue/teal accents',
        'temperature_sensor_display': 'Display screen showing 22°C in blue digits',
    }

    for name, func in textures.items():
        img = func()
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        print(f"- {output_path}: {descriptions[name]}")

    print("\nTemperature sensor textures generated successfully!")


if __name__ == '__main__':
    main()
