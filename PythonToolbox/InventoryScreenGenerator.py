from PIL import Image, ImageDraw, ImageFont
import random
import os

def create_minecraft_inventory():
    # Create a 256x256 pixel image
    width, height = 256, 256
    image = Image.new('RGBA', (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    # Define colors
    chest_color = (139, 69, 19)  # Brown
    slot_color = (100, 100, 100)  # Gray
    slot_border = (50, 50, 50)   # Dark gray
    text_color = (255, 255, 255)  # White

    # Draw chest background
    draw.rectangle([0, 0, width, height], fill=chest_color)

    # Draw inventory slots (9x3 grid)
    slot_width = 32
    slot_height = 32
    slot_padding = 4
    start_x = 16
    start_y = 16

    # Draw slots
    for row in range(3):
        for col in range(9):
            x1 = start_x + col * (slot_width + slot_padding)
            y1 = start_y + row * (slot_height + slot_padding)
            x2 = x1 + slot_width
            y2 = y1 + slot_height

            # Draw slot background
            draw.rectangle([x1, y1, x2, y2], fill=slot_color)

            # Draw slot border
            draw.rectangle([x1, y1, x2, y2], outline=slot_border, width=1)

    # Add some items to the inventory
    items = ['dirt', 'stone', 'wood', 'iron', 'gold', 'diamond', 'coal',
             'apple', 'bread', 'potion', 'sword', 'pickaxe', 'axe', 'hoe',
             'shovel', 'helmet', 'chest', 'legs', 'boots', 'book', 'redstone']

    # Add items to slots
    item_positions = []
    for row in range(3):
        for col in range(9):
            if random.random() > 0.3:  # 70% chance of having an item
                item = random.choice(items)
                x1 = start_x + col * (slot_width + slot_padding)
                y1 = start_y + row * (slot_height + slot_padding)

                # Draw item (simple representation)
                item_x = x1 + slot_width // 2
                item_y = y1 + slot_height // 2

                # Draw a simple item representation
                if item in ['dirt', 'stone', 'wood']:
                    draw.rectangle([item_x-8, item_y-8, item_x+8, item_y+8], fill=(100, 70, 50))
                elif item in ['iron', 'gold']:
                    draw.rectangle([item_x-8, item_y-8, item_x+8, item_y+8], fill=(180, 180, 180))
                elif item == 'diamond':
                    draw.polygon([(item_x, item_y-8), (item_x+8, item_y), (item_x, item_y+8), (item_x-8, item_y)], fill=(100, 200, 255))
                elif item == 'coal':
                    draw.rectangle([item_x-8, item_y-8, item_x+8, item_y+8], fill=(30, 30, 30))
                elif item in ['apple', 'bread']:
                    draw.ellipse([item_x-8, item_y-8, item_x+8, item_y+8], fill=(255, 100, 100))
                elif item in ['potion', 'sword', 'pickaxe', 'axe']:
                    draw.rectangle([item_x-6, item_y-10, item_x+6, item_y+10], fill=(150, 150, 150))
                else:
                    draw.rectangle([item_x-8, item_y-8, item_x+8, item_y+8], fill=(120, 120, 120))

    # Save the image
    image.save('minecraft_chest_inventory.png')
    print("Minecraft chest inventory saved as 'minecraft_chest_inventory.png'")

    # Display image info
    print(f"Image size: {image.size}")
    print(f"Image mode: {image.mode}")

if __name__ == "__main__":
    create_minecraft_inventory()
