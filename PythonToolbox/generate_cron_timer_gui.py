import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# =============================================================================
# Cron Timer GUI Background Texture Generator
# Generates a 176x220 GUI background without player inventory
# =============================================================================

def generate_cron_timer_gui():
    """Generate the cron timer GUI background texture."""
    width = 176
    height = 220
    
    # Create the image with a dark background
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    
    # Main panel - dark gray background
    draw.rectangle([0, 0, width - 1, height - 1], fill=(55, 55, 65, 255))
    
    # Outer border - darker
    for x in range(width):
        img.putpixel((x, 0), (35, 35, 45, 255))
        img.putpixel((x, height - 1), (35, 35, 45, 255))
    for y in range(height):
        img.putpixel((0, y), (35, 35, 45, 255))
        img.putpixel((width - 1, y), (35, 35, 45, 255))
    
    # Inner border - lighter accent
    for x in range(1, width - 1):
        img.putpixel((x, 1), (75, 75, 85, 255))
    for y in range(1, height - 1):
        img.putpixel((1, y), (75, 75, 85, 255))
    
    # Title bar area - slightly lighter
    draw.rectangle([2, 2, width - 3, 18], fill=(65, 65, 75, 255))
    
    # Title bar bottom line
    for x in range(2, width - 2):
        img.putpixel((x, 18), (45, 45, 55, 255))
    
    # Input field area background
    draw.rectangle([6, 22, width - 7, 40], fill=(40, 40, 50, 255))
    # Input field border
    draw.rectangle([6, 22, width - 7, 40], outline=(85, 85, 95, 255))
    
    # Button area background (right side)
    draw.rectangle([width - 76, 22, width - 7, 40], fill=(50, 50, 60, 255))
    
    # Second button area
    draw.rectangle([width - 76, 42, width - 7, 58], fill=(50, 50, 60, 255))
    
    # Separator line below input area
    for x in range(4, width - 4):
        img.putpixel((x, 62), (75, 75, 85, 255))
    
    # Status panel area - darker background
    draw.rectangle([6, 66, width - 7, height - 7], fill=(45, 45, 55, 255))
    # Status panel border
    draw.rectangle([6, 66, width - 7, height - 7], outline=(65, 65, 75, 255))
    
    # Decorative clock icon in the top-left corner
    # Clock circle
    clock_center_x = 12
    clock_center_y = 10
    clock_radius = 5
    for angle in range(0, 360, 5):
        import math
        rad = math.radians(angle)
        x = int(clock_center_x + clock_radius * math.cos(rad))
        y = int(clock_center_y + clock_radius * math.sin(rad))
        if 2 <= x < width - 2 and 2 <= y <= 18:
            img.putpixel((x, y), (180, 180, 200, 255))
    
    # Clock center
    img.putpixel((12, 10), (200, 200, 220, 255))
    
    # Hour hand
    img.putpixel((11, 9), (200, 200, 220, 255))
    img.putpixel((10, 8), (200, 200, 220, 255))
    
    # Minute hand
    img.putpixel((13, 9), (200, 200, 220, 255))
    img.putpixel((14, 8), (200, 200, 220, 255))
    img.putpixel((15, 7), (200, 200, 220, 255))
    
    # Status LED indicator area (top-right)
    img.putpixel((width - 12, 10), (0, 255, 100, 255))
    img.putpixel((width - 11, 10), (0, 255, 100, 255))
    img.putpixel((width - 12, 11), (0, 200, 80, 255))
    img.putpixel((width - 11, 11), (0, 200, 80, 255))
    
    # Glow effect
    img.putpixel((width - 13, 10), (0, 180, 70, 128))
    img.putpixel((width - 10, 10), (0, 180, 70, 128))
    
    img.save('../common/src/main/resources/assets/mineqtt/textures/gui/cron_timer/background.png')
    print('Cron timer GUI background created (176x220)')

if __name__ == '__main__':
    generate_cron_timer_gui()
    print('Cron timer GUI texture generation complete!')
