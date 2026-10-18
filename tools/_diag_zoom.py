#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""放大取证：把要看的棋子裁出来放大，避免在低分辨率截图上看错字。"""
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
from anchor_ab import anchor_by_box
from assemble import fit_axis, snap
from assemble_v3 import clip_to_board
from cell_scores import detect
from probe_detector import BOARD_ID, preprocess, to_frame

BOARD = str(Path(__file__).parent / "out/_board.jpg")
MODEL = str(Path(__file__).parent.parent / "app/src/main/assets/detector_xqlink_v5.onnx")


def imread_any(p):
    """cv2.imread 对某些 Windows 路径会失败，用 fromfile+imdecode 兜底。"""
    img = cv2.imread(p)
    if img is None:
        img = cv2.imdecode(np.fromfile(p, dtype=np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise SystemExit("读不到图片: %s" % p)
    return img


def board_geom(img_path):
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    chw, meta = preprocess(img_path, "letterbox")
    pieces = detect(sess, chw, 0.25)
    framed = to_frame(pieces, meta)
    bd = next(d for d in framed if d["cls"] == BOARD_ID)
    bd_box = (bd["x0"], bd["y0"], bd["x1"], bd["y1"])
    kept, _ = clip_to_board(framed, bd)
    xs = [(d["x0"] + d["x1"]) / 2 for d in kept]
    ys = [(d["y0"] + d["y1"]) / 2 for d in kept]
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    px, ox, _, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, _, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    snap(kept, px, ox, py, oy)
    _, _, kc, kr = anchor_by_box(px, ox, py, oy, bd)
    return bd_box, px, ox, py, oy, kc, kr


def main():
    out = Path(__file__).parent / "out"
    out.mkdir(exist_ok=True)
    bd_box, px, ox, py, oy, kc, kr = board_geom(BOARD)
    print("board box", [round(v, 1) for v in bd_box], "pitch", round(px, 1), round(py, 1),
          "origin", round(ox, 1), round(oy, 1), "kcol", kc, "krow", kr)
    b = imread_any(BOARD)
    half = int(max(px, py) * 0.60)
    for name, r, c in [("car_row7col4", 7, 4), ("car_row3col5", 3, 5),
                       ("ele_row7col0", 7, 0), ("king_row9col4", 9, 4)]:
        cx = ox + (c + kc) * px
        cy = oy + (r + kr) * py
        x0, y0 = int(cx - half), int(cy - half)
        x1, y1 = int(cx + half), int(cy + half)
        crop = b[max(0, y0):y1, max(0, x0):x1]
        cv2.imwrite(str(out / ("_%s.png" % name)),
                    cv2.resize(crop, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_CUBIC))
        print(name, "center", round(cx), round(cy))


if __name__ == "__main__":
    main()
