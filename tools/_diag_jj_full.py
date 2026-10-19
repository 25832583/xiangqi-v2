#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按 App 真实预处理路径（逐级减半 -> letterbox）复算整条识别链路。

与 _diag_redskin_geom.py 的区别只有一处：那张走的是「一次双线性缩放」，
真机走的是 Downscale.plan 的逐级减半。两者的检测结果不同（误检数量差别很大），
而用户报的就是真机上的失败，所以必须按真机路径复算。

用法：
    python _diag_jj_full.py --model ../app/src/main/assets/detector_xqlink_v5.onnx \
                            --image out/_board_jj.jpg [--frame 1280x2816]
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from anchor_ab import anchor_by_box
from assemble import fit_axis, snap
from assemble_v3 import clip_to_board
from cell_scores import detect
from probe_detector import BOARD_ID, LABELS

SIZE = 640
CN = {
    "k": "黑将", "a": "黑士", "b": "黑象", "n": "黑马", "r": "黑车", "c": "黑炮", "p": "黑卒",
    "K": "红帅", "A": "红仕", "B": "红相", "N": "红马", "R": "红车", "C": "红炮", "P": "红兵",
}


def app_letterbox(img):
    """复刻 Downscale.plan + OnnxDetector.letterboxed。返回 (chw, meta, steps)。"""
    w, h = img.size
    scale = min(SIZE / w, SIZE / h)
    nw, nh = int(round(w * scale)), int(round(h * scale))
    steps = []
    cw, ch = w, h
    while min(cw / nw, ch / nh) > 2:
        cw, ch = max(cw // 2, nw), max(ch // 2, nh)
        steps.append((cw, ch))
    if (cw, ch) != (nw, nh):
        steps.append((nw, nh))

    cur = img
    for (sw, sh) in steps:
        cur = cur.resize((sw, sh), Image.BILINEAR)

    px, py = (SIZE - nw) // 2, (SIZE - nh) // 2
    canvas = Image.new("RGB", (SIZE, SIZE), (114, 114, 114))
    canvas.paste(cur, (px, py))
    arr = np.asarray(canvas, dtype=np.float32) / 255.0
    chw = arr.transpose(2, 0, 1)[None, ...]
    return chw, {"scale": (scale, scale), "pad": (px, py), "size": (w, h)}, steps


def to_frame(dets, meta):
    sx, sy = meta["scale"]
    px, py = meta["pad"]
    return [{
        **d,
        "x0": (d["x0"] - px) / sx, "y0": (d["y0"] - py) / sy,
        "x1": (d["x1"] - px) / sx, "y1": (d["y1"] - py) / sy,
    } for d in dets]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--image", required=True)
    ap.add_argument("--frame", default="", help="先缩放到这个尺寸再跑，如 1280x2816")
    ap.add_argument("--conf", type=float, default=0.25)
    args = ap.parse_args()

    img = Image.open(args.image).convert("RGB")
    if args.frame:
        w, h = (int(x) for x in args.frame.lower().split("x"))
        img = img.resize((w, h), Image.BICUBIC)
    print(f"输入 {img.size[0]}x{img.size[1]}")

    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    chw, meta, steps = app_letterbox(img)
    print(f"降采样 {len(steps)} 步 -> {meta['scale'][0] * img.size[0]:.0f}x{meta['scale'][0] * img.size[1]:.0f}，补边 {meta['pad']}")

    dets = detect(sess, chw, args.conf)
    framed = to_frame(dets, meta)

    boards_all = [d for d in framed if d["cls"] == BOARD_ID]
    print(f"棋盘框候选 {len(boards_all)} 个：" + ", ".join(
        f"conf {d['c']:.3f} 宽高比 {(d['x1'] - d['x0']) / (d['y1'] - d['y0']):.2f}" for d in boards_all))
    aspect_ok = [d for d in boards_all if 0.6 <= (d["x1"] - d["x0"]) / (d["y1"] - d["y0"]) <= 1.6]
    if not aspect_ok:
        print("=> NoBoard（宽高比全部不合格或没有框）")
        return
    bd = max(aspect_ok, key=lambda d: d["c"])
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    print(f"选中棋盘框 x[{bd['x0']:.0f},{bd['x1']:.0f}] y[{bd['y0']:.0f},{bd['y1']:.0f}] "
          f"{bw:.0f}x{bh:.0f} conf {bd['c']:.3f}")

    pieces = [d for d in framed if d["cls"] != BOARD_ID]
    kept, dropped = clip_to_board(pieces, bd)
    print(f"棋子 {len(pieces)} 个，裁到框内 {len(kept)} 个（丢 {dropped}）")

    # 复刻 PieceSizeFilter：格子尺寸用棋盘框推（8 个横向间距、9 个纵向间距）
    ux, uy = bw / 8, bh / 9
    sized = []
    for d in kept:
        rx = (d["x1"] - d["x0"]) / ux
        ry = (d["y1"] - d["y0"]) / uy
        if 0.45 <= rx <= 1.8 and 0.45 <= ry <= 1.8:
            sized.append(d)
        else:
            print(f"  尺寸过滤：{LABELS[d['cls']]} conf {d['c']:.3f} 尺寸 {rx:.2f}x{ry:.2f} 格 -> 丢弃")
    kept = sized
    if len(kept) < 3:
        print("=> TooFewPieces")
        return

    xs = [(d["x0"] + d["x1"]) / 2 for d in kept]
    ys = [(d["y0"] + d["y1"]) / 2 for d in kept]
    px, ox, rx, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, ry, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    print(f"拟合 横 pitch {px:.2f} 残差 {rx:.3f} | 纵 pitch {py:.2f} 残差 {ry:.3f}")
    if rx > 0.20 or ry > 0.20:
        print(f"=> PoorGeometry（残差上限 0.20）")
        return

    cells, drop2 = snap(kept, px, ox, py, oy)
    ox, oy, kc, kr = anchor_by_box(px, ox, py, oy, bd)
    print(f"吸附丢 {drop2}，锚定 ({kc},{kr})")

    rows = []
    for (r, c), cands in cells.items():
        cell = (int(round(r - kr)), int(round(c - kc)))
        top = sorted(cands, key=lambda t: -t[0])[0]
        rows.append((cell, top, len(cands)))
    rows.sort()
    print("\n格(行,列)  判定     conf  同格")
    print("-" * 44)
    for (cell, top, n) in rows:
        conf, label, res = top
        print(f"{str(cell):<11}{CN.get(label, label):<7}{conf:>6.3f}" + (f"  ← {n} 个" if n > 1 else ""))

    # 打印最终 FEN（标准方向：row 9 在最下面）
    grid = [["." for _ in range(9)] for _ in range(10)]
    for (cell, top, _n) in rows:
        r, c = cell
        if 0 <= r < 10 and 0 <= c < 9:
            grid[r][c] = top[1]
    fen_rows = []
    for row in grid:
        out, empty = "", 0
        for ch in row:
            if ch == ".":
                empty += 1
            else:
                if empty:
                    out += str(empty)
                    empty = 0
                out += ch
        if empty:
            out += str(empty)
        fen_rows.append(out)
    print("\nFEN: " + "/".join(fen_rows))
    print("（框 0 行在顶部；若红方在下，则与平台显示的上下相反）")


if __name__ == "__main__":
    main()
