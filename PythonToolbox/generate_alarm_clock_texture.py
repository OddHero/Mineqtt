#!/usr/bin/env python3
"""Generate procedural alarm clock textures for Mineqtt mod.

Generates 2 textures for the Alarm Clock block:
- alarm_clock.png: OFF state (muted, dark display)
- alarm_clock_on.png: ON state (glowing display, bright amber tones)

The block is a classic alarm clock with two bells on top and a digital display.
"""
import math
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# =============================================================================
# Color Palette
# =============================================================================

# Standard Mineqtt borders
DARK_BORDER = (40, 40, 50, 255)
INNER_BG = (70, 70, 80, 255)

# OFF state - muted amber/brown
OFF_CLOCK_BODY = (120, 100, 75, 255)
OFF_CLOCK_TOP = (140, 118, 90, 255)
OFF_CLOCK_BOTTOM = (100, 82, 62, 255)
OFF_CLOCK_LEFT = (95, 78, 58, 255)
OFF_CLOCK_RIGHT = (135, 112, 85, 255)
OFF_BELL = (160, 135, 100, 255)
OFF_BELL_HL = (190, 162, 122, 255)
OFF_BELL_SH = (130, 108, 80, 255)
OFF_BELL_RIM = (110, 92, 68, 255)
OFF_BAR = (145, 122, 92, 255)
OFF_DISPLAY_BG = (30, 30, 35, 255)
OFF_DISPLAY_BORDER = (55, 50, 42, 255)
OFF_DIGIT = (90, 85, 70, 255)
OFF_COLON = (80, 75, 62, 255)
OFF_FOOT = (105, 88, 68, 255)
OFF_FOOT_SH = (85, 70, 52, 255)
OFF_LEVER = (145, 122, 92, 255)
OFF_LED = (100, 100, 110, 255)
OFF_LED_SH = (80, 80, 90, 255)

# ON state - vibrant amber/orange
ON_CLOCK_BODY = (155, 125, 75, 255)
ON_CLOCK_TOP = (185, 150, 92, 255)
ON_CLOCK_BOTTOM = (130, 105, 60, 255)
ON_CLOCK_LEFT = (120, 98, 55, 255)
ON_CLOCK_RIGHT = (175, 142, 88, 255)
ON_BELL = (220, 170, 60, 255)
ON_BELL_HL = (255, 200, 90, 255)
ON_BELL_SH = (190, 145, 48, 255)
ON_BELL_RIM = (170, 130, 42, 255)
ON_BAR = (200, 158, 72, 255)
ON_DISPLAY_BG = (12, 10, 6, 255)
ON_DISPLAY_BORDER = (75, 65, 48, 255)
ON_DIGIT = (255, 185, 50, 255)
ON_DIGIT_BRIGHT = (255, 225, 100, 255)
ON_DIGIT_GLOW = (255, 155, 30, 255)
ON_COLON = (255, 200, 80, 255)
ON_FOOT = (140, 115, 70, 255)
ON_FOOT_SH = (115, 92, 55, 255)
ON_LEVER = (200, 160, 80, 255)
ON_LED = (0, 255, 100, 255)
ON_LED_SH = (0, 200, 80, 255)


def put(img, x, y, color):
    """Safely put a pixel."""
    if 0 <= x < 16 and 0 <= y < 16:
        img.putpixel((x, y), color)


def draw_bell(img, cx, cy, main, hl, sh, rim):
    """Draw a classic alarm clock bell at (cx, cy).
    
    Bell shape: dome on top, flared bottom, clapper below.
    """
    # Dome - filled circle top half
    # Row cy-2: 2 pixels
    put(img, cx - 1, cy - 2, hl)
    put(img, cx, cy - 2, hl)
    put(img, cx + 1, cy - 2, main)
    # Row cy-1: 4 pixels
    put(img, cx - 2, cy - 1, main)
    put(img, cx - 1, cy - 1, hl)
    put(img, cx, cy - 1, main)
    put(img, cx + 1, cy - 1, main)
    put(img, cx + 2, cy - 1, main)
    # Row cy: 5 pixels (rim)
    put(img, cx - 2, cy, rim)
    put(img, cx - 1, cy, rim)
    put(img, cx, cy, rim)
    put(img, cx + 1, cy, rim)
    put(img, cx + 2, cy, rim)
    # Row cy+1: clapper
    put(img, cx, cy + 1, sh)


def draw_digit_3w(img, digit, x, y, color):
    """Draw a 3-wide 7-segment digit at position (x, y)."""
    # Segment pixel offsets (3 wide x 4 tall)
    segs = {
        'a': [(0, 0), (1, 0), (2, 0)],
        'b': [(3, 0), (3, 1)],
        'c': [(3, 2), (3, 3)],
        'd': [(0, 3), (1, 3), (2, 3)],
        'e': [(0, 2), (0, 3)],
        'f': [(0, 0), (0, 1)],
        'g': [(0, 1), (1, 1), (2, 1)],
    }
    mapping = {
        '0': 'ab cdef', '1': 'bc', '2': 'abdeg', '3': 'abcdg',
        '4': 'bcfg', '5': 'acdfg', '6': 'acdefg', '7': 'abc',
        '8': 'abcdefg', '9': 'abcdfg',
    }
    active = mapping.get(str(digit), '')
    for name, pixels in segs.items():
        if name in active:
            for dx, dy in pixels:
                put(img, x + dx, y + dy, color)


def generate_alarm_clock_off():
    """Generate the alarm clock block texture in the OFF state."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Dark border on all four edges
    for x in range(16):
        put(img, x, 0, DARK_BORDER)
        put(img, x, 15, DARK_BORDER)
        put(img, 0, x, DARK_BORDER)
        put(img, 15, x, DARK_BORDER)

    # Inner background
    for y in range(1, 15):
        for x in range(1, 15):
            put(img, x, y, INNER_BG)

    # === Connecting Bar between bells (y=1) ===
    for x in range(5, 11):
        put(img, x, 1, OFF_BAR)

    # === Left Bell centered at (4, 2) ===
    draw_bell(img, 4, 2, OFF_BELL, OFF_BELL_HL, OFF_BELL_SH, OFF_BELL_RIM)

    # === Right Bell centered at (11, 2) ===
    draw_bell(img, 11, 2, OFF_BELL, OFF_BELL_HL, OFF_BELL_SH, OFF_BELL_RIM)

    # === Clock Body: x=2..13, y=4..12 ===
    # Top row (y=4)
    for x in range(2, 14):
        put(img, x, 4, OFF_CLOCK_TOP)
    # Bottom row (y=12)
    for x in range(2, 14):
        put(img, x, 12, OFF_CLOCK_BOTTOM)
    # Middle rows (y=5..11)
    for y in range(5, 12):
        put(img, 2, y, OFF_CLOCK_LEFT)
        for x in range(3, 13):
            put(img, x, y, OFF_CLOCK_BODY)
        put(img, 13, y, OFF_CLOCK_RIGHT)

    # === Digital Display: x=3..12, y=5..9 ===
    # Display background
    for y in range(5, 10):
        for x in range(3, 13):
            put(img, x, y, OFF_DISPLAY_BG)
    # Display border
    for x in range(3, 13):
        put(img, x, 5, OFF_DISPLAY_BORDER)
        put(img, x, 9, OFF_DISPLAY_BORDER)
    for y in range(5, 10):
        put(img, 3, y, OFF_DISPLAY_BORDER)
        put(img, 12, y, OFF_DISPLAY_BORDER)

    # Display digits "07:30" at y=6
    # '0' at x=4, '7' at x=8, ':' at x=11, '3' at x=12 (too far)
    # Compact: '0' at x=4, '7' at x=8, ':' at x=11
    # Only 2 digits + colon fit with 3-wide. Let's show "07:" 
    # Or use smaller digits. Let's do 2-wide digits:
    # '0' at x=4, '7' at x=7, ':' at x=9, '3' at x=10
    # 2-wide segments
    segs2 = {
        'a': [(1, 0)], 'b': [(2, 0)], 'c': [(2, 2)], 'd': [(1, 3)],
        'e': [(0, 2)], 'f': [(0, 0)], 'g': [(1, 1)],
    }
    dmap = {
        '0': 'ab cdef', '1': 'bc', '2': 'abdeg', '3': 'abcdg',
        '4': 'bcfg', '5': 'acdfg', '6': 'acdefg', '7': 'abc',
        '8': 'abcdefg', '9': 'abcdfg',
    }

    def draw2(d, x, y, c):
        active = dmap.get(str(d), '')
        for name, pixels in segs2.items():
            if name in active:
                for dx, dy in pixels:
                    put(img, x + dx, y + dy, c)

    # "07:30" with 2-wide digits
    draw2('0', 4, 6, OFF_DIGIT)
    draw2('7', 7, 6, OFF_DIGIT)
    put(img, 9, 7, OFF_COLON)
    put(img, 9, 8, OFF_COLON)
    draw2('3', 10, 6, OFF_DIGIT)

    # === Feet at bottom (y=14) ===
    put(img, 3, 14, OFF_FOOT)
    put(img, 4, 14, OFF_FOOT)
    put(img, 11, 14, OFF_FOOT)
    put(img, 12, 14, OFF_FOOT)

    # === Side Levers ===
    put(img, 1, 7, OFF_LEVER)
    put(img, 1, 8, OFF_LEVER)
    put(img, 14, 7, OFF_LEVER)
    put(img, 14, 8, OFF_LEVER)

    # === Status LED ===
    put(img, 13, 11, OFF_LED)
    put(img, 13, 12, OFF_LED_SH)

    img.save('../common/src/main/resources/assets/mineqtt/textures/block/alarm_clock.png')
    print('Alarm clock OFF texture created')


def generate_alarm_clock_on():
    """Generate the alarm clock block texture in the ON/active state."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Dark border on all four edges
    for x in range(16):
        put(img, x, 0, DARK_BORDER)
        put(img, x, 15, DARK_BORDER)
        put(img, 0, x, DARK_BORDER)
        put(img, 15, x, DARK_BORDER)

    # Inner background
    for y in range(1, 15):
        for x in range(1, 15):
            put(img, x, y, INNER_BG)

    # === Connecting Bar between bells (y=1) ===
    for x in range(5, 11):
        put(img, x, 1, ON_BAR)

    # === Left Bell centered at (4, 2) ===
    draw_bell(img, 4, 2, ON_BELL, ON_BELL_HL, ON_BELL_SH, ON_BELL_RIM)

    # === Right Bell centered at (11, 2) ===
    draw_bell(img, 11, 2, ON_BELL, ON_BELL_HL, ON_BELL_SH, ON_BELL_RIM)

    # === Clock Body: x=2..13, y=4..12 ===
    for x in range(2, 14):
        put(img, x, 4, ON_CLOCK_TOP)
    for x in range(2, 14):
        put(img, x, 12, ON_CLOCK_BOTTOM)
    for y in range(5, 12):
        put(img, 2, y, ON_CLOCK_LEFT)
        for x in range(3, 13):
            put(img, x, y, ON_CLOCK_BODY)
        put(img, 13, y, ON_CLOCK_RIGHT)

    # === Digital Display: x=3..12, y=5..9 ===
    for y in range(5, 10):
        for x in range(3, 13):
            put(img, x, y, ON_DISPLAY_BG)
    for x in range(3, 13):
        put(img, x, 5, ON_DISPLAY_BORDER)
        put(img, x, 9, ON_DISPLAY_BORDER)
    for y in range(5, 10):
        put(img, 3, y, ON_DISPLAY_BORDER)
        put(img, 12, y, ON_DISPLAY_BORDER)

    # Display digits "07:30" with 2-wide digits
    segs2 = {
        'a': [(1, 0)], 'b': [(2, 0)], 'c': [(2, 2)], 'd': [(1, 3)],
        'e': [(0, 2)], 'f': [(0, 0)], 'g': [(1, 1)],
    }
    dmap = {
        '0': 'ab cdef', '1': 'bc', '2': 'abdeg', '3': 'abcdg',
        '4': 'bcfg', '5': 'acdfg', '6': 'acdefg', '7': 'abc',
        '8': 'abcdefg', '9': 'abcdfg',
    }

    def draw2(d, x, y, c):
        active = dmap.get(str(d), '')
        for name, pixels in segs2.items():
            if name in active:
                for dx, dy in pixels:
                    put(img, x + dx, y + dy, c)

    draw2('0', 4, 6, ON_DIGIT)
    draw2('7', 7, 6, ON_DIGIT)
    put(img, 9, 7, ON_COLON)
    put(img, 9, 8, ON_COLON)
    draw2('3', 10, 6, ON_DIGIT)

    # Glow effect around digits
    for py in range(6, 9):
        for px in range(4, 12):
            current = img.getpixel((px, py))
            for adx, ady in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                nx, ny = px + adx, py + ady
                if 0 <= nx < 16 and 0 <= ny < 16:
                    neighbor = img.getpixel((nx, ny))
                    if neighbor[0] > 200 and current[0] < 20:
                        put(img, px, py, ON_DIGIT_GLOW)

    # Brighten top row of digits
    for x in range(4, 12):
        current = img.getpixel((x, 6))
        if current[0] > 200:
            put(img, x, 6, ON_DIGIT_BRIGHT)

    # === Feet at bottom (y=14) ===
    put(img, 3, 14, ON_FOOT)
    put(img, 4, 14, ON_FOOT)
    put(img, 11, 14, ON_FOOT)
    put(img, 12, 14, ON_FOOT)

    # === Side Levers ===
    put(img, 1, 7, ON_LEVER)
    put(img, 1, 8, ON_LEVER)
    put(img, 14, 7, ON_LEVER)
    put(img, 14, 8, ON_LEVER)

    # === Status LED (green, glowing) ===
    put(img, 13, 11, ON_LED)
    put(img, 13, 12, ON_LED_SH)
    put(img, 12, 11, (0, 180, 70, 255))
    put(img, 14, 11, (0, 180, 70, 255))
    put(img, 12, 12, (0, 150, 60, 255))
    put(img, 14, 12, (0, 150, 60, 255))

    img.save('../common/src/main/resources/assets/mineqtt/textures/block/alarm_clock_on.png')
    print('Alarm clock ON texture created')


if __name__ == '__main__':
    generate_alarm_clock_off()
    generate_alarm_clock_on()
    print('All alarm clock textures generated successfully!')
