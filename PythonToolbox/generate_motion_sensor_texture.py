#!/usr/bin/env python3
"""Generate procedural motion sensor dome textures"""
from PIL import Image, ImageDraw
import math

def create_motion_sensor_texture(width=16, height=16):
    """Create a dome security camera texture"""
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Create dome security camera appearance
    # Dark metallic dome with lens

    # Background - metallic dark gray
    draw.rectangle([0, 0, width, height], fill=(40, 40, 45, 255))

    # Add subtle gradient effect with darker corners
    for x in range(width):
        for y in range(height):
            # Distance from center
            cx, cy = width // 2, height // 2
            dist = math.sqrt((x - cx) ** 2 + (y - cy) ** 2)
            max_dist = math.sqrt(cx ** 2 + cy ** 2)

            # Calculate brightness based on distance
            brightness_factor = 1.0 - (dist / max_dist) * 0.3

            if brightness_factor > 0.7:
                # Dome surface - slightly lighter in center
                r, g, b = int(50 * brightness_factor), int(50 * brightness_factor), int(55 * brightness_factor)
                img.putpixel((x, y), (r, g, b, 255))

    # Draw lens (dark glass circle in center)
    cx, cy = width // 2, height // 2
    radius = width // 4

    # Lens outer ring
    draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                 fill=(20, 20, 25, 255), outline=(30, 30, 35, 255))

    # Lens highlight (glass reflection)
    hl_radius = radius - 2
    draw.ellipse([cx - hl_radius + 1, cy - hl_radius + 1, cx + hl_radius - 1, cy + hl_radius - 1],
                 fill=(60, 60, 70, 255))

    # Inner lens (dark glass)
    inner_radius = radius - 3
    draw.ellipse([cx - inner_radius, cy - inner_radius, cx + inner_radius, cy + inner_radius],
                 fill=(10, 10, 15, 255))

    # Light reflection on lens (small highlight)
    hl_small = 2
    draw.ellipse([cx - hl_small, cy - hl_small - 1, cx + hl_small - 1, cy - hl_small],
                 fill=(100, 100, 110, 255))

    # Mount bracket lines (connection to mount)
    bracket_y = cy + radius + 1
    draw.line([cx - 2, bracket_y, cx - 2, bracket_y + 2], fill=(50, 50, 55, 255), width=1)
    draw.line([cx + 2, bracket_y, cx + 2, bracket_y + 2], fill=(50, 50, 55, 255), width=1)

    return img

def create_mount_texture(width=16, height=16):
    """Create mounting bracket texture"""
    img = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Metallic dark gray mount
    draw.rectangle([0, 0, width, height], fill=(45, 45, 50, 255))

    # Mounting plate details
    draw.rectangle([2, 2, width - 2, height - 2], outline=(55, 55, 60, 255), width=1)

    # Screw holes
    for x in [4, width - 4]:
        for y in [4, height - 4]:
            draw.ellipse([x - 1, y - 1, x + 1, y + 1], fill=(20, 20, 25, 255))

    return img

# Generate textures
motion_sensor = create_motion_sensor_texture()
motion_sensor.save('C:\\repos\\Mineqtt\\common\\src\\main\\resources\\assets\\mineqtt\\textures\\block\\motion_sensor_dome.png')

mount = create_mount_texture()
mount.save('C:\\repos\\Mineqtt\\common\\src\\main\\resources\\assets\\mineqtt\\textures\\block\\motion_sensor_mount.png')

print("Textures generated successfully!")
print("- motion_sensor_dome.png: Dome security camera texture")
print("- motion_sensor_mount.png: Mounting bracket texture")

