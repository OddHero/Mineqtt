import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# =============================================================================
# Cron Timer Block Texture Generator
# Generates 16x16 pixel textures for the cron timer block (off and on states)
# =============================================================================

def generate_cron_timer_off():
    """Generate the cron timer block texture in the OFF state."""
    img = Image.new('RGBA', (16, 16), (60, 60, 70, 255))
    draw = ImageDraw.Draw(img)

    # Dark border
    for x in range(16):
        img.putpixel((x, 0), (40, 40, 50, 255))
        img.putpixel((x, 15), (40, 40, 50, 255))
        img.putpixel((0, x), (40, 40, 50, 255))
        img.putpixel((15, x), (40, 40, 50, 255))

    # Inner background - slightly lighter
    for y in range(1, 15):
        for x in range(1, 15):
            img.putpixel((x, y), (70, 70, 80, 255))

    # Clock face - circle outline
    clock_center_x = 7.5
    clock_center_y = 7.5
    clock_radius = 4

    # Draw clock circle outline
    for angle in range(0, 360, 3):
        import math
        rad = math.radians(angle)
        x = int(clock_center_x + clock_radius * math.cos(rad))
        y = int(clock_center_y + clock_radius * math.sin(rad))
        if 0 <= x < 16 and 0 <= y < 16:
            img.putpixel((x, y), (180, 180, 200, 255))

    # Clock center dot
    img.putpixel((7, 7), (180, 180, 200, 255))
    img.putpixel((8, 7), (180, 180, 200, 255))
    img.putpixel((7, 8), (180, 180, 200, 255))
    img.putpixel((8, 8), (180, 180, 200, 255))

    # Hour hand (short, pointing to ~10 o'clock)
    img.putpixel((7, 7), (200, 200, 220, 255))
    img.putpixel((6, 6), (200, 200, 220, 255))
    img.putpixel((5, 5), (200, 200, 220, 255))

    # Minute hand (longer, pointing to ~2 o'clock)
    img.putpixel((7, 7), (200, 200, 220, 255))
    img.putpixel((8, 6), (200, 200, 220, 255))
    img.putpixel((9, 5), (200, 200, 220, 255))
    img.putpixel((10, 4), (200, 200, 220, 255))

    # Clock tick marks at 12, 3, 6, 9 positions
    # 12 o'clock
    img.putpixel((7, 3), (160, 160, 180, 255))
    img.putpixel((8, 3), (160, 160, 180, 255))
    # 3 o'clock
    img.putpixel((11, 7), (160, 160, 180, 255))
    img.putpixel((11, 8), (160, 160, 180, 255))
    # 6 o'clock
    img.putpixel((7, 11), (160, 160, 180, 255))
    img.putpixel((8, 11), (160, 160, 180, 255))
    # 9 o'clock
    img.putpixel((3, 7), (160, 160, 180, 255))
    img.putpixel((3, 8), (160, 160, 180, 255))

    # Status LED - gray (off state)
    img.putpixel((12, 12), (100, 100, 110, 255))
    img.putpixel((13, 12), (100, 100, 110, 255))
    img.putpixel((12, 13), (80, 80, 90, 255))
    img.putpixel((13, 13), (80, 80, 90, 255))

    # Small cron text indicator lines at bottom
    for x in range(3, 13):
        img.putpixel((x, 14), (50, 50, 60, 255))

    img.save('../common/src/main/resources/assets/mineqtt/textures/block/cron_timer.png')
    print('Cron timer OFF texture created')


def generate_cron_timer_on():
    """Generate the cron timer block texture in the ON state."""
    img = Image.new('RGBA', (16, 16), (60, 60, 70, 255))
    draw = ImageDraw.Draw(img)

    # Dark border
    for x in range(16):
        img.putpixel((x, 0), (40, 40, 50, 255))
        img.putpixel((x, 15), (40, 40, 50, 255))
        img.putpixel((0, x), (40, 40, 50, 255))
        img.putpixel((15, x), (40, 40, 50, 255))

    # Inner background - slightly lighter
    for y in range(1, 15):
        for x in range(1, 15):
            img.putpixel((x, y), (70, 70, 80, 255))

    # Clock face - circle outline
    clock_center_x = 7.5
    clock_center_y = 7.5
    clock_radius = 4

    # Draw clock circle outline
    for angle in range(0, 360, 3):
        import math
        rad = math.radians(angle)
        x = int(clock_center_x + clock_radius * math.cos(rad))
        y = int(clock_center_y + clock_radius * math.sin(rad))
        if 0 <= x < 16 and 0 <= y < 16:
            img.putpixel((x, y), (200, 200, 220, 255))

    # Clock center dot - glowing
    img.putpixel((7, 7), (255, 255, 255, 255))
    img.putpixel((8, 7), (255, 255, 255, 255))
    img.putpixel((7, 8), (255, 255, 255, 255))
    img.putpixel((8, 8), (255, 255, 255, 255))

    # Hour hand (short, pointing to ~10 o'clock) - brighter
    img.putpixel((7, 7), (255, 255, 255, 255))
    img.putpixel((6, 6), (255, 255, 255, 255))
    img.putpixel((5, 5), (255, 255, 255, 255))

    # Minute hand (longer, pointing to ~2 o'clock) - brighter
    img.putpixel((7, 7), (255, 255, 255, 255))
    img.putpixel((8, 6), (255, 255, 255, 255))
    img.putpixel((9, 5), (255, 255, 255, 255))
    img.putpixel((10, 4), (255, 255, 255, 255))

    # Clock tick marks at 12, 3, 6, 9 positions - brighter
    # 12 o'clock
    img.putpixel((7, 3), (200, 200, 220, 255))
    img.putpixel((8, 3), (200, 200, 220, 255))
    # 3 o'clock
    img.putpixel((11, 7), (200, 200, 220, 255))
    img.putpixel((11, 8), (200, 200, 220, 255))
    # 6 o'clock
    img.putpixel((7, 11), (200, 200, 220, 255))
    img.putpixel((8, 11), (200, 200, 220, 255))
    # 9 o'clock
    img.putpixel((3, 7), (200, 200, 220, 255))
    img.putpixel((3, 8), (200, 200, 220, 255))

    # Status LED - green (on state)
    img.putpixel((12, 12), (0, 255, 100, 255))
    img.putpixel((13, 12), (0, 255, 100, 255))
    img.putpixel((12, 13), (0, 200, 80, 255))
    img.putpixel((13, 13), (0, 200, 80, 255))

    # Glow effect around LED
    img.putpixel((11, 12), (0, 180, 70, 255))
    img.putpixel((14, 12), (0, 180, 70, 255))
    img.putpixel((11, 13), (0, 150, 60, 255))
    img.putpixel((14, 13), (0, 150, 60, 255))

    # Small cron text indicator lines at bottom - brighter
    for x in range(3, 13):
        img.putpixel((x, 14), (60, 60, 70, 255))

    img.save('../common/src/main/resources/assets/mineqtt/textures/block/cron_timer_on.png')
    print('Cron timer ON texture created')


if __name__ == '__main__':
    generate_cron_timer_off()
    generate_cron_timer_on()
    print('All cron timer textures generated successfully!')
