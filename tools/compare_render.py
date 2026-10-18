#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真值/结果并排比对：左＝原图棋盘区，右＝按 FEN 重绘的盘面（带行列坐标）。

用于肉眼逐格核对识别结果，比在原图上叠框更直观。

用法：
    python compare_render.py --image one.jpg --fen "xxx w" --out cmp.png
    python compare_render.py --json D:/date/_v3.json --index 12 --out cmp.png
"""
import argparse
import json
import sys
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from assemble import parse_fen
from fen_render import draw_board


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--image", default=None)
    ap.add_argument("--fen", default=None)
    ap.add_argument("--json", default=None)
    ap.add_argument("--index", type=int, default=0)
    ap.add_argument("--out", required=True)
    ap.add_argument("--dir", default="D:/date")
    args = ap.parse_args()

    if args.json:
        data = json.loads(Path(args.json).read_text(encoding="utf-8"))
        item = data[args.index]
        fen = item["fen"]
        img_path = str(Path(args.dir) / item["image"])
        print(f"{item['image']}  落盘 {item['placed']}")
    else:
        fen, img_path = args.fen, args.image

    img = Image.open(img_path).convert("RGB")
    imgs = sorted(Path(args.dir).glob("微信图片_*.jpg"))
    w, h = img.size
    crop = img.crop((0, int(h * 0.20), w, int(h * 0.75)))
    crop.thumbnail((640, 760))

    board = draw_board(parse_fen(fen), cell=64, margin=40)
    board.thumbnail((640, 760))

    W = crop.width + board.width + 20
    H = max(crop.height, board.height)
    sheet = Image.new("RGB", (W, H), (245, 245, 245))
    sheet.paste(crop, (0, 0))
    sheet.paste(board, (crop.width + 20, 0))
    sheet.save(args.out)
    print("FEN:", fen)
    print("->", args.out)


if __name__ == "__main__":
    main()
