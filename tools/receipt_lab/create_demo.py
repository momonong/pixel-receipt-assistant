"""Generate visibly synthetic local fixtures; never an accuracy acceptance dataset."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

parser = argparse.ArgumentParser()
parser.add_argument("directory", type=Path)
args = parser.parse_args()
args.directory.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype("C:/Windows/Fonts/consola.ttf", 48)
rows = ["LAB DEMO STORE", "2026/09/11", "ITEM QTY PRICE AMOUNT", "TEA 3 33 100", "GIFT 1 40 40",
        "BREAD 1 20 20", "DISCOUNT 5", "TOTAL 155", "SYNTHETIC TEST - NOT A REAL RECEIPT"]
for name, lines in (("DEMO-clear.png", rows), ("DEMO-blank.png", []),
                    ("DEMO-page2.png", rows + ["PAGE 2"])):
    image = Image.new("RGB", (1700, 1450), "white")
    draw = ImageDraw.Draw(image)
    for index, line in enumerate(lines):
        draw.text((70, 70 + index * 110), line, font=font, fill="black")
    image.save(args.directory / name)
