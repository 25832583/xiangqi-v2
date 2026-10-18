#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把离线识别结果逐格标注回原图，用于人眼核对「模型认的」与「真实盘面」。

用法：
    python _diag_annotate.py [图片路径]
输出 out/_annotated.png
"""
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).parent))
from anchor_ab import anchor_by_box
from assemble import fit_axis, snap
from assemble_v3 import clip_to_board
from cell_scores import detect
from probe_detector import BOARD_ID, LABELS, preprocess, to_frame

HERE = Path(__file__).parent
MODEL = str(HERE.parent / "app/src/main/assets/detector_xqlink_v5.onnx")
IMG = sys.argv[1] if len(sys.argv) > 1 else str(HERE / "out/_board.jpg")

CN = {
    "k": "黑将", "a": "黑士", "b": "黑象", "n": "黑马", "r": "黑车", "c": "黑炮", "p": "黑卒",
    "K": "红帅", "A": "红仕", "B": "红相", "N": "红马", "R": "红车", "C": "红炮", "P": "红兵",
}
FONT = "C:/Windows/Fonts/msyh.ttc"


def main():
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    chw, meta = preprocess(IMG, "letterbox")
    pieces = detect(sess, chw, 0.25)
    framed = to_frame(pieces, meta)
    bd = next(d for d in framed if d["cls"] == BOARD_ID)
    kept, dropped = clip_to_board(framed, bd)
    xs = [(d["x0"] + d["x1"]) / 2 for d in kept]
    ys = [(d["y0"] + d["y1"]) / 2 for d in kept]
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    px, ox, _, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, _, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    cells, _ = snap(kept, px, ox, py, oy)
    nx, ny, kc, kr = anchor_by_box(px, ox, py, oy, bd)

    src = Image.open(IMG).convert("RGB")
    Y0 = 210
    img = Image.new("RGB", (src.width, src.height + Y0), (24, 24, 24))
    img.paste(src, (0, Y0))
    dr = ImageDraw.Draw(img)
    f_piece = ImageFont.truetype(FONT, 34)
    f_small = ImageFont.truetype(FONT, 26)
    f_head = ImageFont.truetype(FONT, 40)

    grid = {}
    for (r, c), cands in cells.items():
        conf, label, res = sorted(cands, key=lambda t: -t[0])[0]
        grid[(int(round(r - kr)), int(round(c - kc)))] = (label, conf, len(cands))

    RED, BLUE = (255, 122, 102), (169, 192, 214)
    for (row, col), (label, conf, n) in sorted(grid.items()):
        cx = ox + (col + kc) * px
        cy = oy + (row + kr) * py + Y0
        rad = int(min(px, py) * 0.52)
        color = RED if label.isupper() else BLUE
        dr.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], outline=color, width=4)
        txt = CN.get(label, label)
        tw = dr.textlength(txt, font=f_piece)
        dr.text((cx - tw / 2, cy - rad - 46), txt, font=f_piece, fill=color,
                stroke_width=3, stroke_fill=(20, 20, 20))
        tag = "%.2f" % conf + ("" if n == 1 else " x%d" % n)
        tw = dr.textlength(tag, font=f_small)
        dr.text((cx - tw / 2, cy + rad + 6), tag, font=f_small, fill=color,
                stroke_width=3, stroke_fill=(20, 20, 20))

    # FEN 按 10 行 9 列重排
    ranks = []
    for row in range(10):
        s, empty = "", 0
        for col in range(9):
            hit = grid.get((row, col))
            if hit is None:
                empty += 1
            else:
                if empty:
                    s += str(empty)
                    empty = 0
                s += hit[0]
        if empty:
            s += str(empty)
        ranks.append(s)
    fen = "/".join(ranks) + " w - - 0 1"

    dr.text((24, 14), "离线识别  %d 子  丢弃 %d 框  格距 %.0fx%.0f" %
            (len(grid), dropped, px, py), font=f_head, fill=(235, 235, 235))
    dr.text((24, 70), fen[:44], font=f_small, fill=(150, 235, 150))
    dr.text((24, 108), fen[44:], font=f_small, fill=(150, 235, 150))
    dr.text((24, 152), "红字=红方  蓝字=黑方  下方数字=置信度", font=f_small, fill=(190, 190, 190))

    out = HERE / "out/_annotated.png"
    img.save(out)
    print("saved", out)
    print(fen)
    print("cells", len(grid), "conf min %.3f" % min(v[1] for v in grid.values()))


if __name__ == "__main__":
    main()
