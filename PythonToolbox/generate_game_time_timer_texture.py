#!/usr/bin/env python3
"""Generate procedural game time timer textures for Mineqtt mod.

Generates 2 textures for the Game Time Timer block:
- game_time_timer.png: OFF state (muted purple, dark display)
- game_time_timer_on.png: ON state (glowing purple, illuminated display)

The block is a timer that triggers actions based on Minecraft game time (day/night cycle).
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

# OFF state - muted purple/violet
OFF_DISPLAY_BG = (25, 20, 35, 255)
OFF_DISPLAY_BORDER = (55, 45, 70, 255)
OFF_DIGIT = (80, 70, 100, 255)
OFF_DIGIT_DIM = (60, 52, 75, 255)
OFF_SUN_MOON = (100, 90, 120, 255)
OFF_SUN_MOON_HL = (120, 110, 140, 255)
OFF_SUN_MOON_SH = (80, 70, 100, 255)
OFF_STAR = (70, 65, 85, 255)
OFF_LED = (100, 100, 110, 255)
OFF_LED_SH = (80, 80, 90, 255)
OFF_HIGHLIGHT = (80, 75, 95, 255)
OFF_SHADOW = (55, 50, 65, 255)
OFF_SEPARATOR = (45, 40, 55, 255)

# ON state - vibrant purple/violet with glow
ON_DISPLAY_BG = (15, 10, 25, 255)
ON_DISPLAY_BORDER = (90, 70, 130, 255)
ON_DIGIT = (180, 140, 255, 255)
ON_DIGIT_BRIGHT = (220, 180, 255, 255)
ON_DIGIT_GLOW = (140, 100, 220, 255)
ON_SUN_MOON = (180, 150, 255, 255)
ON_SUN_MOON_HL = (220, 190, 255, 255)
ON_SUN_MOON_SH = (140, 110, 220, 255)
ON_STAR = (200, 180, 255, 255)
ON_LED = (0, 255, 100, 255)
ON_LED_SH = (0, 200, 80, 255)
ON_HIGHLIGHT = (120, 100, 160, 255)
ON_SHADOW = (70, 60, 100, 255)
ON_SEPARATOR = (100, 80, 140, 255)


def put(img, x, y, color):
    """Safely put a pixel."""
    if 0 <= x < 16 and 0 <= y < 16:
        img.putpixel((x, y), color)


def draw_sun_moon_icon(img, cx, cy, main, hl, sh, is_night=True):
    """Draw a sun/moon icon at center (cx, cy).
    
    If is_night=True, draws a crescent moon with stars.
    If is_night=False, draws a sun with rays.
    """
    if is_night:
        # Crescent moon (right-facing crescent)
        # Moon body - circle outline
        r = 2
        for angle in range(0, 360, 5):
            rad = math.radians(angle)
            px = int(cx + r * math.cos(rad))
            py = int(cy + r * math.sin(rad))
            put(img, px, py, main)

        # Fill moon body
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                dist = math.sqrt(dx * dx + dy * dy)
                if dist <= 2:
                    put(img, cx + dx, cy + dy, main)

        # Crescent cutout (left side, creating crescent shape)
        for dy in range(-2, 3):
            for dx in range(-2, 1):
                dist = math.sqrt((dx + 1) ** 2 + dy * dy)
                if dist <= 1.5:
                    put(img, cx + dx, cy + dy, sh)

        # Moon highlight
        put(img, cx + 1, cy - 1, hl)
        put(img, cx + 2, cy, hl)

        # Small stars around moon
        put(img, cx - 3, cy - 2, hl)
        put(img, cx + 3, cy - 1, hl)
        put(img, cx - 2, cy + 2, hl)
    else:
        # Sun body
        r = 2
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                dist = math.sqrt(dx * dx + dy * dy)
                if dist <= 2:
                    put(img, cx + dx, cy + dy, main)

        # Sun highlight
        put(img, cx, cy - 1, hl)
        put(img, cx - 1, cy, hl)

        # Sun rays
        put(img, cx, cy - 3, hl)
        put(img, cx, cy + 3, hl)
        put(img, cx - 3, cy, hl)
        put(img, cx + 3, cy, hl)
        put(img, cx - 2, cy - 2, sh)
        put(img, cx + 2, cy - 2, sh)
        put(img, cx - 2, cy + 2, sh)
        put(img, cx + 2, cy + 2, sh)


def draw_7_segment_digit(img, digit, x, y, color, bright_color=None):
    """Draw a 3-wide 4-tall 7-segment digit at position (x, y).
    
    Segments:
      a
     f g
     b c
      d
    """
    segs = {
        'a': [(x, y), (x + 1, y), (x + 2, y)],
        'b': [(x + 3, y), (x + 3, y + 1)],
        'c': [(x + 3, y + 2), (x + 3, y + 3)],
        'd': [(x, y + 3), (x + 1, y + 3), (x + 2, y + 3)],
        'e': [(x, y + 2), (x, y + 3)],
        'f': [(x, y), (x, y + 1)],
        'g': [(x, y + 1), (x + 1, y + 1), (x + 2, y + 1)],
    }

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
    }

    active_segs = digit_map.get(str(digit), [])

    for seg_name in active_segs:
        for px, py in segs[seg_name]:
            c = bright_color if bright_color and seg_name in ['a', 'd'] else color
            put(img, px, py, c)


def draw_colon(img, x, y, color):
    """Draw a colon separator at position (x, y)."""
    put(img, x, y + 1, color)
    put(img, x, y + 2, color)


def generate_game_time_timer_off():
    """Generate the game time timer block texture in the OFF state."""
    img = Image.new('RGBA', (16, 16), (60, 60, 70, 255))

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

    # Top highlight and bottom shadow
    for x in range(1, 15):
        put(img, x, 1, OFF_HIGHLIGHT)
        put(img, x, 14, OFF_SHADOW)

    # Digital display area (rows 2-5, cols 2-13)
    # Display background
    for y in range(2, 6):
        for x in range(2, 14):
            put(img, x, y, OFF_DISPLAY_BG)

    # Display border
    for x in range(2, 14):
        put(img, x, 2, OFF_DISPLAY_BORDER)
        put(img, x, 5, OFF_DISPLAY_BORDER)
    for y in range(2, 6):
        put(img, 2, y, OFF_DISPLAY_BORDER)
        put(img, 13, y, OFF_DISPLAY_BORDER)

    # Display digits showing "12:00" (Minecraft noon)
    # Digit '1' at x=3, y=3
    draw_7_segment_digit(img, '1', 3, 3, OFF_DIGIT)
    # Digit '2' at x=7, y=3
    draw_7_segment_digit(img, '2', 7, 3, OFF_DIGIT)
    # Colon at x=6, y=3
    draw_colon(img, 6, 3, OFF_DIGIT_DIM)
    # Digit '0' at x=10, y=3
    draw_7_segment_digit(img, '0', 10, 3, OFF_DIGIT)

    # Sun/Moon icon below display (centered, night theme)
    draw_sun_moon_icon(img, 7, 9, OFF_SUN_MOON, OFF_SUN_MOON_HL, OFF_SUN_MOON_SH, is_night=True)

    # Small day/night cycle indicator arc at bottom
    # Arc representing the cycle
    arc_cx, arc_cy = 7.5, 12.5
    arc_r = 2
    for angle in range(180, 360, 10):
        rad = math.radians(angle)
        px = int(arc_cx + arc_r * math.cos(rad))
        py = int(arc_cy + arc_r * math.sin(rad))
        put(img, px, py, OFF_SUN_MOON_SH)

    # Status LED - gray (off state)
    put(img, 12, 12, OFF_LED)
    put(img, 13, 12, OFF_LED)
    put(img, 12, 13, OFF_LED_SH)
    put(img, 13, 13, OFF_LED_SH)

    img.save('../common/src/main/resources/assets/mineqtt/textures/block/game_time_timer.png')
    print('Game time timer OFF texture created')


def generate_game_time_timer_on():
    """Generate the game time timer block texture in the ON state."""
    img = Image.new('RGBA', (16, 16), (60, 60, 70, 255))

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

    # Top highlight and bottom shadow - brighter
    for x in range(1, 15):
        put(img, x, 1, ON_HIGHLIGHT)
        put(img, x, 14, ON_SHADOW)

    # Digital display area (rows 2-5, cols 2-13)
    # Display background - darker for contrast with glow
    for y in range(2, 6):
        for x in range(2, 14):
            put(img, x, y, ON_DISPLAY_BG)

    # Display border - glowing
    for x in range(2, 14):
        put(img, x, 2, ON_DISPLAY_BORDER)
        put(img, x, 5, ON_DISPLAY_BORDER)
    for y in range(2, 6):
        put(img, 2, y, ON_DISPLAY_BORDER)
        put(img, 13, y, ON_DISPLAY_BORDER)

    # Display glow effect around border
    for x in range(1, 15):
        put(img, x, 1, ON_DIGIT_GLOW)
        put(img, x, 6, ON_DIGIT_GLOW)
    for y in range(1, 7):
        put(img, 1, y, ON_DIGIT_GLOW)
        put(img, 14, y, ON_DIGIT_GLOW)

    # Display digits showing "12:00" - glowing
    draw_7_segment_digit(img, '1', 3, 3, ON_DIGIT, ON_DIGIT_BRIGHT)
    draw_7_segment_digit(img, '2', 7, 3, ON_DIGIT, ON_DIGIT_BRIGHT)
    draw_colon(img, 6, 3, ON_DIGIT)
    draw_7_segment_digit(img, '0', 10, 3, ON_DIGIT, ON_DIGIT_BRIGHT)

    # Sun/Moon icon below display - glowing (day theme for ON state)
    draw_sun_moon_icon(img, 7, 9, ON_SUN_MOON, ON_SUN_MOON_HL, ON_SUN_MOON_SH, is_night=False)

    # Day/night cycle indicator arc - brighter
    arc_cx, arc_cy = 7.5, 12.5
    arc_r = 2
    for angle in range(180, 360, 10):
        rad = math.radians(angle)
        px = int(arc_cx + arc_r * math.cos(rad))
        py = int(arc_cy + arc_r * math.sin(rad))
        put(img, px, py, ON_SUN_MOON)

    # Stars in ON state
    put(img, 3, 11, ON_STAR)
    put(img, 12, 11, ON_STAR)

    # Status LED - green (on state)
    put(img, 12, 12, ON_LED)
    put(img, 13, 12, ON_LED)
    put(img, 12, 13, ON_LED_SH)
    put(img, 13, 13, ON_LED_SH)

    # LED glow effect
    put(img, 11, 12, (0, 180, 70, 255))
    put(img, 14, 12, (0, 180, 70, 255))
    put(img, 11, 13, (0, 150, 60, 255))
    put(img, 14, 13, (0, 150, 60, 255))

    img.save('../common/src/main/resources/assets/mineqtt/textures/block/game_time_timer_on.png')
    print('Game time timer ON texture created')


if __name__ == '__main__':
    generate_game_time_timer_off()
    generate_game_time_timer_on()
    print('All game time timer textures generated successfully!')
