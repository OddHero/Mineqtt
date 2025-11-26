#!/usr/bin/env python3
"""
Generate RGB LED Socket Texture
Creates a simple metallic socket texture for the light bulb base
"""
from PIL import Image, ImageDraw

# Create 16x16 texture
size = 16
img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# Dark gray socket color
socket_color = (60, 60, 60, 255)
highlight_color = (90, 90, 90, 255)
shadow_color = (40, 40, 40, 255)

# Fill with socket color
draw.rectangle([0, 0, size-1, size-1], fill=socket_color)

# Add some highlights (top-left)
draw.line([0, 0, size-1, 0], fill=highlight_color, width=1)
draw.line([0, 0, 0, size-1], fill=highlight_color, width=1)

# Add some shadows (bottom-right)
draw.line([0, size-1, size-1, size-1], fill=shadow_color, width=1)
draw.line([size-1, 0, size-1, size-1], fill=shadow_color, width=1)

# Add texture detail - horizontal lines for threading
for y in range(2, size-2, 3):
    draw.line([2, y, size-3, y], fill=(50, 50, 50, 255), width=1)

# Save the texture
output_path = 'common/src/main/resources/assets/mineqtt/textures/block/rgb_led_socket.png'
img.save(output_path)
print(f"Socket texture saved to {output_path}")

