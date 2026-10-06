"""Generate half-pixel advances for the official Minecraft 26.3 default font.

Run with a local official client.jar, include/unifont.json and font/unifont.zip.
Only numeric glyph advances are bundled in the plugin; no glyph images are copied.
Pillow is needed for this maintenance script, not for building or running the plugin.
"""
import argparse
import gzip
import io
import json
import math
from pathlib import Path
import zipfile

from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("client_jar", type=Path)
parser.add_argument("unifont_json", type=Path)
parser.add_argument("unifont_zip", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()

# Two bytes per code point: normal advance and additional bold advance, in half pixels.
metrics = bytearray(bytes((12, 2)) * 0x110000)
provider = next(p for p in json.loads(args.unifont_json.read_text())["providers"]
                if p.get("hex_file") == "minecraft:font/unifont.zip")
overrides = [(ord(r["from"]), ord(r["to"]), r["left"], r["right"])
             for r in provider["size_overrides"]]
with zipfile.ZipFile(args.unifont_zip) as archive:
    for name in archive.namelist():
        if not name.endswith(".hex"):
            continue
        for line in archive.read(name).decode("ascii").splitlines():
            number, pixels = line.split(":", 1)
            codepoint = int(number, 16)
            digits_per_row = len(pixels) // 16
            bit_width = digits_per_row * 4
            mask = 0
            for offset in range(0, len(pixels), digits_per_row):
                mask |= int(pixels[offset:offset + digits_per_row], 16)
            if mask:
                left = bit_width - mask.bit_length()
                right = bit_width - (mask & -mask).bit_length()
            else:
                left, right = 0, bit_width
            for first, last, override_left, override_right in overrides:
                if first <= codepoint <= last:
                    left, right = override_left, override_right
                    break
            advance = (right - left + 1) // 2 + 1
            metrics[codepoint * 2:codepoint * 2 + 2] = bytes((advance * 2, 1))

with zipfile.ZipFile(args.client_jar) as archive:
    providers = json.loads(archive.read("assets/minecraft/font/include/default.json"))["providers"]
    # Earlier font providers have priority over later providers and the Unicode fallback.
    for provider in reversed(providers):
        rows = provider["chars"]
        path = "assets/minecraft/textures/" + provider["file"].split(":", 1)[1]
        image = Image.open(io.BytesIO(archive.read(path))).convert("RGBA")
        cell_width = image.width // len(rows[0])
        cell_height = image.height // len(rows)
        scale = provider.get("height", 8) / cell_height
        alpha = image.getchannel("A")
        for y, row in enumerate(rows):
            for x, character in enumerate(row):
                codepoint = ord(character)
                if codepoint == 0:
                    continue
                bounds = alpha.crop((x * cell_width, y * cell_height,
                                     (x + 1) * cell_width, (y + 1) * cell_height)).getbbox()
                drawn_width = bounds[2] if bounds else 0
                advance = math.floor(drawn_width * scale + 0.5) + 1
                metrics[codepoint * 2:codepoint * 2 + 2] = bytes((advance * 2, 2))
    spaces = json.loads(archive.read("assets/minecraft/font/include/space.json"))["providers"][0]
    for character, advance in spaces["advances"].items():
        codepoint = ord(character)
        metrics[codepoint * 2:codepoint * 2 + 2] = bytes((int(advance * 2), 2))

args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_bytes(gzip.compress(metrics, compresslevel=9, mtime=0))
print(f"Generated {args.output}: {args.output.stat().st_size} compressed bytes")
