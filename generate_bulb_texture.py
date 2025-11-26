#!/usr/bin/env python3
"""
Generate RGB LED Bulb Texture
Creates a white/translucent glass bulb texture that can be tinted
"""
from PIL import Image, ImageDraw

# Create 16x16 texture
size = 16
img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)

# White/light gray glass color (will be tinted by the game)
glass_color = (240, 240, 240, 255)
highlight_color = (255, 255, 255, 255)
shadow_color = (200, 200, 200, 255)

# Fill with glass color
draw.rectangle([0, 0, size-1, size-1], fill=glass_color)

# Add glass highlight (top-left corner shine)
draw.ellipse([2, 2, 7, 7], fill=highlight_color)

# Add slight shadow on bottom-right corner
draw.line([size-3, size-1, size-1, size-1], fill=shadow_color, width=2)
draw.line([size-1, size-3, size-1, size-1], fill=shadow_color, width=2)

# Make corners slightly transparent for rounded effect
for x in [0, 1, size-2, size-1]:
    for y in [0, 1, size-2, size-1]:
        if (x in [0, size-1]) and (y in [0, size-1]):
            pixels = img.load()
            r, g, b, a = pixels[x, y]
            pixels[x, y] = (r, g, b, int(a * 0.5))

# Save the texture
output_path = 'common/src/main/resources/assets/mineqtt/textures/block/rgb_led_bulb.png'
img.save(output_path)
print(f"Bulb texture saved to {output_path}")

