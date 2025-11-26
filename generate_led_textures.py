#!/usr/bin/env python3
"""
Generate all RGB LED Block textures
Run this script from the project root directory
"""
import os
from PIL import Image, ImageDraw

# Ensure output directory exists
output_dir = 'common/src/main/resources/assets/mineqtt/textures/block'
os.makedirs(output_dir, exist_ok=True)

def generate_socket_texture():
    """Generate the metallic socket base texture"""
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

    # Save
    output_path = os.path.join(output_dir, 'rgb_led_socket.png')
    img.save(output_path)
    print(f"✓ Socket texture saved to {output_path}")

def generate_bulb_texture():
    """Generate the white glass bulb texture (will be tinted in-game)"""
    size = 16
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # White/light gray glass color (will be tinted by the game)
    glass_color = (240, 240, 240, 255)
    highlight_color = (255, 255, 255, 255)

    # Fill with glass color
    draw.rectangle([0, 0, size-1, size-1], fill=glass_color)

    # Add glass highlight (top-left corner shine)
    draw.ellipse([2, 2, 7, 7], fill=highlight_color)

    # Add slight shadow on bottom-right corner
    shadow_color = (200, 200, 200, 255)
    draw.line([size-3, size-1, size-1, size-1], fill=shadow_color, width=2)
    draw.line([size-1, size-3, size-1, size-1], fill=shadow_color, width=2)

    # Make corners slightly transparent for rounded effect
    pixels = img.load()
    for x in [0, 1, size-2, size-1]:
        for y in [0, 1, size-2, size-1]:
            if (x in [0, size-1]) and (y in [0, size-1]):
                r, g, b, a = pixels[x, y]
                pixels[x, y] = (r, g, b, int(a * 0.5))

    # Save
    output_path = os.path.join(output_dir, 'rgb_led_bulb.png')
    img.save(output_path)
    print(f"✓ Bulb texture saved to {output_path}")

if __name__ == "__main__":
    print("Generating RGB LED Block textures...")
    try:
        generate_socket_texture()
        generate_bulb_texture()
        print("\n✅ All textures generated successfully!")
        print("\nThe RGB LED block will now appear as a light bulb in a socket.")
        print("The bulb color will change based on MQTT messages!")
    except Exception as e:
        print(f"\n❌ Error generating textures: {e}")
        import traceback
        traceback.print_exc()

