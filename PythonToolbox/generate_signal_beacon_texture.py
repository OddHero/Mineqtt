#!/usr/bin/env python3
"""Generate procedural Signal Beacon textures for Mineqtt mod.

The Signal Beacon is a lens in a brushed metal frame that sits on a vanilla beacon and colours its beam. The block
state's colour (one of the 16 dyes) and lit pick the textures; the textures are opaque, so the block needs no
translucent render layer on any Minecraft version:
- signal_beacon_top_<dye>: the lens seen from above, a glowing disc in the dye's beam colour with a glint
- signal_beacon_side_<dye>: the frame with a window onto the lit lens
- signal_beacon_top_off / signal_beacon_side_off: the same with a dark, smoky lens (off, or between two blinks)
- signal_beacon_bottom: the frame's base plate with the opening the beam comes up through

The colours are vanilla's beam colours (DyeColor#getTextureDiffuseColor), so the block shows the beam's colour;
verifySignalBeacon checks the same table against the game.
"""
import os
from PIL import Image, ImageDraw

os.chdir(os.path.dirname(os.path.abspath(__file__)))

# The beam colour of each dye, in dye id order (DyeColor#getTextureDiffuseColor without its alpha).
DYES = {
    'white': 0xF9FFFE,
    'orange': 0xF9801D,
    'magenta': 0xC74EBD,
    'light_blue': 0x3AB3DA,
    'yellow': 0xFED83D,
    'lime': 0x80C71F,
    'pink': 0xF38BAA,
    'gray': 0x474F52,
    'light_gray': 0x9D9D97,
    'cyan': 0x169C9C,
    'purple': 0x8932B8,
    'blue': 0x3C44AA,
    'brown': 0x835432,
    'green': 0x5E7C16,
    'red': 0xB02E26,
    'black': 0x1D1D21,
}

# Color palette: the brushed metal frame and the dark lens of an unlit beacon
DARK_BORDER = (40, 40, 50, 255)
METAL_DARK = (110, 112, 122, 255)
METAL_MID = (145, 147, 158, 255)
METAL_LIGHT = (178, 180, 194, 255)
SOCKET = (32, 32, 40, 255)
SMOKE_DARK = (34, 34, 42, 255)
SMOKE_MID = (52, 52, 62, 255)
SMOKE_GLINT = (88, 88, 100, 255)


def rgb(packed):
    return (packed >> 16) & 0xFF, (packed >> 8) & 0xFF, packed & 0xFF


def shade(color, factor):
    """A colour scaled towards black (factor < 1) or towards white (factor > 1), opaque."""
    r, g, b = color[:3]
    if factor <= 1:
        return int(r * factor), int(g * factor), int(b * factor), 255
    t = factor - 1
    return int(r + (255 - r) * t), int(g + (255 - g) * t), int(b + (255 - b) * t), 255


def frame():
    """Brushed metal with a dark outline: the frame every face starts from."""
    img = Image.new('RGBA', (16, 16), METAL_MID)
    draw = ImageDraw.Draw(img)
    for y in range(16):
        streak = METAL_LIGHT if y % 3 == 0 else (METAL_DARK if y % 3 == 2 else METAL_MID)
        draw.line([0, y, 15, y], fill=streak)
    draw.rectangle([0, 0, 15, 15], outline=DARK_BORDER)
    return img


def lens_colors(packed):
    """The lens's rim, body, core and glint for a lit dye, or the smoky lens for None."""
    if packed is None:
        return SMOKE_DARK, SMOKE_MID, SMOKE_MID, SMOKE_GLINT
    base = rgb(packed)
    return shade(base, 0.6), shade(base, 1.0), shade(base, 1.35), shade(base, 1.75)


def top(packed):
    """The lens from above: a disc (12 pixels across) in a socket, brighter towards its middle, with a glint."""
    img = frame()
    draw = ImageDraw.Draw(img)
    rim, body, core, glint = lens_colors(packed)
    draw.rectangle([2, 2, 13, 13], fill=SOCKET)
    draw.ellipse([2, 2, 13, 13], fill=rim)
    draw.ellipse([3, 3, 12, 12], fill=body)
    draw.ellipse([5, 5, 10, 10], fill=core)
    draw.point((5, 4), fill=glint)
    draw.point((4, 5), fill=glint)
    draw.point((6, 5), fill=glint)
    return img


def side(packed):
    """The frame from the side: a window (10 x 8 pixels) onto the lens, its top edge brightest."""
    img = frame()
    draw = ImageDraw.Draw(img)
    rim, body, core, glint = lens_colors(packed)
    draw.rectangle([2, 3, 13, 12], fill=SOCKET)
    draw.rectangle([3, 4, 12, 11], fill=body)
    draw.line([3, 4, 12, 4], fill=core)
    draw.line([3, 11, 12, 11], fill=rim)
    draw.point((4, 5), fill=glint)
    draw.point((5, 5), fill=glint)
    return img


def bottom():
    """The base plate: the frame with a dark round opening the beam comes up through."""
    img = frame()
    draw = ImageDraw.Draw(img)
    draw.ellipse([4, 4, 11, 11], fill=SOCKET)
    return img


def main():
    """Generate all Signal Beacon textures and save them."""
    base_path = '../common/src/main/resources/assets/mineqtt/textures/block/'

    textures = {'signal_beacon_bottom': (bottom(), 'Base plate with the beam opening'),
                'signal_beacon_top_off': (top(None), 'Lens from above, dark (off)'),
                'signal_beacon_side_off': (side(None), 'Frame with a dark window (off)')}
    for name, packed in DYES.items():
        textures[f'signal_beacon_top_{name}'] = (top(packed), f'Lens from above, lit {name}')
        textures[f'signal_beacon_side_{name}'] = (side(packed), f'Frame with a lit {name} window')

    for name, (img, description) in textures.items():
        output_path = f'{base_path}{name}.png'
        img.save(output_path)
        print(f"- {output_path}: {description}")

    print("\nSignal Beacon textures generated successfully!")


if __name__ == '__main__':
    main()
