#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""降采样方式对识别结果的影响 —— 复现「真机认成炮、离线认对了」。

背景：1280x2816 的帧 letterbox 到 640 时倍率只有 0.227（4.4 倍降采样）。
  - PIL `resize(BILINEAR)` 在缩放比 > 1 时会按 support 扩大采样窗，**等效抗混叠**；
  - Android `Canvas.drawBitmap(FILTER_BITMAP)` 只有 2x2 双线性，**严重混叠**。

同一个 ONNX、同一张图，只有重采样不同，跑出来对比逐格类别。

用法：
    python _diag_scale.py
"""
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from cell_scores import CN, detect
from probe_detector import BOARD_ID, LABELS

HERE = Path(__file__).parent
MODEL = str(HERE.parent / "app/src/main/assets/detector_xqlink_v5.onnx")
IMG = str(HERE / "out/_board.jpg")
SIZE = 640
FILL = 114


def letterbox_np(img_rgb, interp, pad_mode="center"):
    h, w = img_rgb.shape[:2]
    s = min(SIZE / w, SIZE / h)
    nw, nh = int(round(w * s)), int(round(h * s))
    resized = cv2.resize(img_rgb, (nw, nh), interpolation=interp)
    canvas = np.full((SIZE, SIZE, 3), FILL, dtype=np.uint8)
    x0, y0 = (SIZE - nw) // 2, (SIZE - nh) // 2
    canvas[y0:y0 + nh, x0:x0 + nw] = resized
    arr = canvas.astype(np.float32) / 255.0
    return arr.transpose(2, 0, 1)[None, ...], s, (x0, y0), (w, h)


def letterbox_steps(img_rgb, steps=2):
    """分步减半缩放，逼近 area 平均 —— 这是 App 侧要改成的那种做法。"""
    img = img_rgb
    h, w = img.shape[:2]
    s = min(SIZE / w, SIZE / h)
    target_w, target_h = int(round(w * s)), int(round(h * s))
    while max(w // 2, 1) >= target_w and max(h // 2, 1) >= target_h:
        w, h = w // 2, h // 2
        img = cv2.resize(img, (w, h), interpolation=cv2.INTER_AREA)
    img = cv2.resize(img, (target_w, target_h), interpolation=cv2.INTER_AREA)
    canvas = np.full((SIZE, SIZE, 3), FILL, dtype=np.uint8)
    x0, y0 = (SIZE - target_w) // 2, (SIZE - target_h) // 2
    canvas[y0:y0 + target_h, x0:x0 + target_w] = img
    arr = canvas.astype(np.float32) / 255.0
    return arr.transpose(2, 0, 1)[None, ...], s, (x0, y0), (target_w, target_h)


def to_frame(dets, scale, pad, orig_wh):
    x0p, y0p = pad
    out = []
    for d in dets:
        out.append({
            **{k: v for k, v in d.items() if k != "_scores"},
            "x0": (d["x0"] - x0p) / scale, "y0": (d["y0"] - y0p) / scale,
            "x1": (d["x1"] - x0p) / scale, "y1": (d["y1"] - y0p) / scale,
        })
    return out


def run_case(sess, chw, scale, pad, orig_wh, name):
    from assemble import fit_axis, snap
    from assemble_v3 import clip_to_board
    from anchor_ab import anchor_by_box

    raw = detect(sess, chw, 0.25)
    framed = to_frame(raw, scale, pad, orig_wh)
    bd = next((d for d in framed if d["cls"] == BOARD_ID), None)
    if bd is None:
        print("%-22s 没检出棋盘框" % name)
        return {}
    kept, _ = clip_to_board(framed, bd)
    xs = [(k["x0"] + k["x1"]) / 2 for k in kept]
    ys = [(k["y0"] + k["y1"]) / 2 for k in kept]
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    px, ox, _, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, _, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    cells, _ = snap(kept, px, ox, py, oy)
    _, _, kc, kr = anchor_by_box(px, ox, py, oy, bd)
    grid = {}
    for (r, c), cands in cells.items():
        conf, label, _ = sorted(cands, key=lambda t: -t[0])[0]
        grid[(int(round(r - kr)), int(round(c - kc)))] = (label, conf)
    ranks = []
    for row in range(10):
        s, empty = "", 0
        for col in range(9):
            hit = grid.get((row, col))
            if hit is None:
                empty += 1
            else:
                if empty:
                    s += str(empty); empty = 0
                s += hit[0]
        if empty:
            s += str(empty)
        ranks.append(s)
    print("%-22s %s" % (name, "/".join(ranks)))
    for cell in [(3, 5), (7, 0)]:
        if cell in grid:
            print("      %s -> %s %.3f" % (cell, CN.get(grid[cell][0], grid[cell][0]), grid[cell][1]))
    return grid


def main():
    sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
    rgb = np.asarray(Image.open(IMG).convert("RGB"))
    print("原图 %dx%d，letterbox 倍率 %.3f" % (rgb.shape[1], rgb.shape[0],
                                              min(SIZE / rgb.shape[1], SIZE / rgb.shape[0])))

    cases = [
        ("PIL BILINEAR(离线现状)", None),
        ("cv2 INTER_LINEAR(≈安卓)", cv2.INTER_LINEAR),
        ("cv2 INTER_NEAREST(最差)", cv2.INTER_NEAREST),
        ("cv2 INTER_AREA(抗混叠)", cv2.INTER_AREA),
    ]
    ref = None
    for name, interp in cases:
        if interp is None:
            pil = Image.open(IMG).convert("RGB")
            s = min(SIZE / pil.width, SIZE / pil.height)
            nw, nh = int(round(pil.width * s)), int(round(pil.height * s))
            canvas = Image.new("RGB", (SIZE, SIZE), (FILL, FILL, FILL))
            canvas.paste(pil.resize((nw, nh), Image.BILINEAR), ((SIZE - nw) // 2, (SIZE - nh) // 2))
            chw = (np.asarray(canvas, dtype=np.float32) / 255.0).transpose(2, 0, 1)[None, ...]
            g = run_case(sess, chw, s, ((SIZE - nw) // 2, (SIZE - nh) // 2), (pil.width, pil.height), name)
        else:
            chw, s, pad, wh = letterbox_np(rgb, interp)
            g = run_case(sess, chw, s, pad, wh, name)
        if ref is None:
            ref = g
        else:
            diff = [(k, ref[k][0], g.get(k, ("?", 0))[0]) for k in ref
                    if g.get(k, ("?", 0))[0] != ref[k][0]]
            if diff:
                print("      ↑ 与离线不一致：%s" % ", ".join(
                    "%s %s→%s" % (k, a, b) for k, a, b in diff))
    print()
    chw, s, pad, wh = letterbox_steps(rgb)
    run_case(sess, chw, s, pad, wh, "分步减半+AREA")


if __name__ == "__main__":
    main()
