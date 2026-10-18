#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""单图调试可视化：把格线、检出框、类别、落盘结果画在一起，便于肉眼定位问题。

用法：
    python debug_viz.py --model xxx.onnx --image one.jpg --out out.png [--conf 0.15]
    python debug_viz.py --model xxx.onnx --image one.jpg --out out.png --raw   # 只画检出，不装配
"""
import argparse
import sys
from pathlib import Path

import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).parent))
from anchor_ab import anchor_by_box
from assemble import fit_axis, resolve, snap
from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame


def font(sz):
    for p in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"):
        try:
            return ImageFont.truetype(p, sz)
        except Exception:
            pass
    return ImageFont.load_default()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--image", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--scale", type=float, default=1.0)
    args = ap.parse_args()

    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    chw, meta = preprocess(args.image, "letterbox")
    dets = to_frame(run(sess, chw, args.conf)[0], meta)
    boards = [d for d in dets if d["cls"] == BOARD_ID]
    pieces = [d for d in dets if d["cls"] != BOARD_ID]

    img = Image.open(args.image).convert("RGB")
    dr = ImageDraw.Draw(img)
    f_big, f_sm = font(34), font(20)

    for d in pieces:
        red = LABELS[d["cls"]].isupper()
        col = (255, 60, 60) if red else (60, 120, 255)
        dr.rectangle([d["x0"], d["y0"], d["x1"], d["y1"]], outline=col, width=3)
        dr.text((d["x0"], d["y0"] - 34), LABELS[d["cls"]], font=f_big, fill=col)
        dr.text((d["x0"] + 24, d["y0"] - 26), f"{d['c']:.2f}", font=f_sm, fill=col)

    info = f"检出 {len(pieces)} 框 {len(boards)}"
    if boards:
        bd = max(boards, key=lambda d: d["c"])
        dr.rectangle([bd["x0"], bd["y0"], bd["x1"], bd["y1"]], outline=(0, 255, 0), width=4)
        bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
        xs = [(d["x0"] + d["x1"]) / 2 for d in pieces]
        ys = [(d["y0"] + d["y1"]) / 2 for d in pieces]
        px, ox, rx, mx = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
        py, oy, ry, my = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
        cells, dropped = snap(pieces, px, ox, py, oy)
        ox, oy, kc, kr = anchor_by_box(px, ox, py, oy, bd)
        cells2 = {(r - kr, c - kc): v for (r, c), v in cells.items()}
        board, _ = resolve(cells2)
        for r in range(10):
            gy = oy + r * py
            dr.line([ox, gy, ox + 8 * px, gy], fill=(0, 255, 120), width=2)
        for c in range(9):
            gx = ox + c * px
            dr.line([gx, oy, gx, oy + 9 * py], fill=(0, 255, 120), width=2)
        for (r, c), (conf, lab, res) in board.items():
            gx, gy = ox + c * px, oy + r * py
            dr.text((gx - 12, gy - 16), lab, font=f_big, fill=(255, 255, 0))
        info += (f" | 落盘 {len(board)} 丢弃 {dropped} | 格距 {px:.1f}x{py:.1f} "
                 f"残差 {rx:.3f}/{ry:.3f} | k=({kc},{kr})")
    else:
        board = {}

    dr.text((10, 10), info, font=f_sm, fill=(255, 255, 0))
    if args.scale != 1.0:
        img = img.resize((int(img.width * args.scale), int(img.height * args.scale)))
    img.save(args.out)
    print(info)
    print("->", args.out)


if __name__ == "__main__":
    main()
