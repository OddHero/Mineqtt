import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# 16x16 block texture for light remote
img = Image.new('RGBA', (16, 16), (60, 60, 70, 255))
draw = ImageDraw.Draw(img)

# Border
for x in range(16):
    img.putpixel((x, 0), (40, 40, 50, 255))
    img.putpixel((x, 15), (40, 40, 50, 255))
    img.putpixel((0, x), (40, 40, 50, 255))
    img.putpixel((15, x), (40, 40, 50, 255))

# Light bulb icon in center (yellow circle)
for y in range(4, 10):
    for x in range(5, 11):
        dx = x - 7.5
        dy = y - 6.5
        if dx * dx + dy * dy <= 7:
            img.putpixel((x, y), (255, 220, 80, 255))

# Power button indicator (green dot at bottom)
img.putpixel((7, 12), (0, 255, 100, 255))
img.putpixel((8, 12), (0, 255, 100, 255))
img.putpixel((7, 13), (0, 200, 80, 255))
img.putpixel((8, 13), (0, 200, 80, 255))

# Small antenna/signal lines at top
img.putpixel((7, 2), (150, 150, 170, 255))
img.putpixel((8, 2), (150, 150, 170, 255))
img.putpixel((6, 3), (120, 120, 140, 255))
img.putpixel((9, 3), (120, 120, 140, 255))

img.save('../common/src/main/resources/assets/mineqtt/textures/block/light_remote_block.png')
print('Block texture created')

# GUI background texture (256x256)
gui = Image.new('RGBA', (256, 256), (198, 198, 198, 255))
draw = ImageDraw.Draw(gui)

# Main panel area (176x230 centered-ish)
panel_x = 0
panel_y = 0
panel_w = 176
panel_h = 230

# Draw border
draw.rectangle([panel_x, panel_y, panel_x + panel_w - 1, panel_y + panel_h - 1], outline=(0, 0, 0, 255))
draw.rectangle([panel_x + 1, panel_y + 1, panel_x + panel_w - 2, panel_y + panel_h - 2], outline=(85, 85, 85, 255))

# Inner fill
draw.rectangle([panel_x + 2, panel_y + 2, panel_x + panel_w - 3, panel_y + panel_h - 3], fill=(198, 198, 198, 255))

# Top bar (darker)
draw.rectangle([panel_x + 3, panel_y + 3, panel_x + panel_w - 4, panel_y + 16], fill=(180, 180, 180, 255))

# Topic slot area background
draw.rectangle([panel_x + 6, panel_y + 22, panel_x + 170, panel_y + 42], fill=(139, 139, 139, 255))

# Slot outlines for topic slots
for sx, sy in [(7, 25), (43, 25)]:
    draw.rectangle([panel_x + sx, panel_y + sy, panel_x + sx + 17, panel_y + sy + 17], outline=(0, 0, 0, 255))
    draw.rectangle([panel_x + sx + 1, panel_y + sy + 1, panel_x + sx + 16, panel_y + sy + 16],
                   fill=(139, 139, 139, 255))

# Player inventory area
inv_y = 83
draw.rectangle([panel_x + 6, panel_y + inv_y - 1, panel_x + 170, panel_y + inv_y + 54], fill=(139, 139, 139, 255))
for row in range(3):
    for col in range(9):
        sx = 7 + col * 18
        sy = inv_y + row * 18
        draw.rectangle([panel_x + sx, panel_y + sy, panel_x + sx + 17, panel_y + sy + 17], outline=(0, 0, 0, 255))
        draw.rectangle([panel_x + sx + 1, panel_y + sy + 1, panel_x + sx + 16, panel_y + sy + 16],
                       fill=(139, 139, 139, 255))

# Hotbar
hotbar_y = 141
draw.rectangle([panel_x + 6, panel_y + hotbar_y - 1, panel_x + 170, panel_y + hotbar_y + 18], fill=(139, 139, 139, 255))
for col in range(9):
    sx = 7 + col * 18
    draw.rectangle([panel_x + sx, panel_y + hotbar_y, panel_x + sx + 17, panel_y + hotbar_y + 17],
                   outline=(0, 0, 0, 255))
    draw.rectangle([panel_x + sx + 1, panel_y + hotbar_y + 1, panel_x + sx + 16, panel_y + hotbar_y + 16],
                   fill=(139, 139, 139, 255))

gui.save('../common/src/main/resources/assets/mineqtt/textures/gui/light_remote/topic_screen.png')
print('GUI texture created')
