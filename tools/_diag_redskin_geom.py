#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""暗红皮肤（JJ象棋）离线复算：棋盘框、拟合、锚定偏差、逐格映射。

只读诊断：复刻 BoardRecognizer 的几何链路，把中间量全打出来。
"""
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
from anchor_ab import anchor_by_box
from assemble import fit_axis, snap
from assemble_v3 import clip_to_board
from cell_scores import detect
from probe_detector import BOARD_ID, LABELS, to_frame
from cell_scores import preprocess

CN = {
    "k": "黑将", "a": "黑士", "b": "黑象", "n": "黑马", "r": "黑车", "c": "黑炮", "p": "黑卒",
    "K": "红帅", "A": "红仕", "B": "红相", "N": "红马", "R": "红车", "C": "红炮", "P": "红兵",
    "0": "棋盘",
}


def main():
    model = sys.argv[1]
    image = sys.argv[2]
    sess = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    chw, meta = preprocess(image, "letterbox")
    pieces = detect(sess, chw, 0.25)
    framed = to_frame(pieces, meta)

    fw, fh = meta["size"]
    bd = next((d for d in framed if d["cls"] == BOARD_ID), None)
    print(f"帧 {fw}x{fh}")
    if bd is None:
        print("没有棋盘框 -> RecognitionOutcome.NoBoard")
        return
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    print(f"棋盘框 x[{bd['x0']:.1f},{bd['x1']:.1f}] y[{bd['y0']:.1f},{bd['y1']:.1f}] "
          f"宽高 {bw:.1f}x{bh:.1f} 宽高比 {bw / bh:.3f} conf {bd['c']:.3f}")
    print(f"框中心 ({bd['x0'] + bw / 2:.1f},{bd['y0'] + bh / 2:.1f})")

    kept, dropped = clip_to_board(framed, bd)
    print(f"裁到框内后棋子 {len(kept)} 颗（丢弃 {dropped}）")

    xs = [(d["x0"] + d["x1"]) / 2 for d in kept]
    ys = [(d["y0"] + d["y1"]) / 2 for d in kept]
    px, ox, rx, nx = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, ry, ny = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    print(f"横向 pitch {px:.2f}px phase {ox:.2f} 残差 {rx:.3f} 格 (n={nx})")
    print(f"纵向 pitch {py:.2f}px phase {oy:.2f} 残差 {ry:.3f} 格 (n={ny})")
    print(f"格距等比 横/纵 = {px / py:.3f}")

    # BoardBoxAnchor 的原始量
    col_raw = (bd["x0"] + bw / 2 - ox) / px - 4.0
    row_raw = (bd["y0"] + bh / 2 - oy) / py - 4.5
    print(f"锚定原始量 colRaw={col_raw:+.3f} rowRaw={row_raw:+.3f} "
          f"-> 平移 ({round(col_raw)},{round(row_raw)}) 偏差 {abs(col_raw - round(col_raw)) + abs(row_raw - round(row_raw)):.3f}")
    print(f"  含义：框中心对应格坐标 ({col_raw + 4:.2f},{row_raw + 4.5:.2f})")

    cells, drop2 = snap(kept, px, ox, py, oy)
    ox, oy, kc, kr = anchor_by_box(px, ox, py, oy, bd)
    print(f"实际锚定 colOffset={kc} rowOffset={kr}（snap 丢弃 {drop2}）")

    # 框到棋盘可视边界的留白，按格距折算
    first = min(ys)
    last = max(ys)
    print(f"纵向：框上留白 {(first - py / 2 - bd['y0']) / py:.2f} 格，"
          f"框下留白 {(bd['y1'] - (last + py / 2)) / py:.2f} 格")
    xfirst, xlast = min(xs), max(xs)
    print(f"横向：框左留白 {(xfirst - px / 2 - bd['x0']) / px:.2f} 格，"
          f"框右留白 {(bd['x1'] - (xlast + px / 2)) / px:.2f} 格")

    rows = []
    for (r, c), cands in cells.items():
        cell = (int(round(r - kr)), int(round(c - kc)))
        top = sorted(cands, key=lambda t: -t[0])[0]
        rows.append((cell, top, len(cands)))
    rows.sort()
    print("\n格(行,列)  判定     conf  同格候选")
    print("-" * 46)
    for (cell, top, n) in rows:
        conf, label, res = top
        flag = "" if n == 1 else f"  ← 同格 {n} 个"
        print(f"{str(cell):<11}{CN.get(label, label):<7}{conf:>6.3f}{flag}")


if __name__ == "__main__":
    main()
