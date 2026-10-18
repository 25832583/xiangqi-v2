#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""锚定策略 A/B：棋盘框中心定锚 vs 棋规合法性搜索。

同一批检测框，只换锚定方式，比较：
  - FEN 强约束通过率（fen_lint）
  - 落盘子数（越界丢弃越少越好）
  - 两种锚定的偏移差

用法：
    python anchor_ab.py --model xxx.onnx --images "D:/date/*.jpg"
"""
import argparse
import glob
import sys
from pathlib import Path

import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
from assemble import (BOARD_CENTER_COL, BOARD_CENTER_ROW, anchor_by_rules,
                      fit_axis, render, resolve, snap, to_fen)
from fen_lint import check
from probe_detector import BOARD_ID, preprocess, run, to_frame


def anchor_by_box(px, ox, py, oy, board):
    """用棋盘框中心定死整数偏移：框中心 == (列 4, 行 4.5)。

    相位由周期拟合给出（残差 ~0.01 格，足够准），这里只解整数 k。
    """
    bw_cx = (board["x0"] + board["x1"]) / 2.0
    bh_cy = (board["y0"] + board["y1"]) / 2.0
    kc = round((bw_cx - ox) / px - BOARD_CENTER_COL)
    kr = round((bh_cy - oy) / py - BOARD_CENTER_ROW)
    return ox + kc * px, oy + kr * py, kc, kr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    args = ap.parse_args()

    paths = []
    for pat in args.images:
        paths.extend(sorted(glob.glob(pat)))
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])

    stats = {"rules": [0, 0, 0], "box": [0, 0, 0]}   # [通过, 落盘总数, 图数]
    print(f"{'图像':20s} {'子':>3} {'规k':>9} {'框k':>9} {'规落':>4} {'框落':>4}  {'规lint':>6} {'框lint':>6}")
    for p in paths:
        chw, meta = preprocess(p, "letterbox")
        dets = to_frame(run(sess, chw, args.conf)[0], meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pieces = [d for d in dets if d["cls"] != BOARD_ID]
        if not boards or not pieces:
            continue
        bd = max(boards, key=lambda d: d["c"])
        bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
        xs = [(d["x0"] + d["x1"]) / 2 for d in pieces]
        ys = [(d["y0"] + d["y1"]) / 2 for d in pieces]
        px, ox, _, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
        py, oy, _, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
        cells, _ = snap(pieces, px, ox, py, oy)

        res = {}
        _, _, kc1, kr1, _ = anchor_by_rules(cells, px, ox, py, oy, bd)
        res["rules"] = {(r - kr1, c - kc1): v for (r, c), v in cells.items()}
        _, _, kc2, kr2 = anchor_by_box(px, ox, py, oy, bd)
        res["box"] = {(r - kr2, c - kc2): v for (r, c), v in cells.items()}

        line = {}
        for tag in ("rules", "box"):
            b, _ = resolve(res[tag])
            fen = to_fen(render(b))
            errs, _, _ = check(fen)
            ok = not errs
            stats[tag][0] += ok
            stats[tag][1] += len(b)
            stats[tag][2] += 1
            line[tag] = (ok, len(b), fen)

        print(f"{Path(p).name[9:22]:20s} {len(pieces):>3} "
              f"({kc1:>2},{kr1:>2}) ({kc2:>2},{kr2:>2}) {line['rules'][1]:>4} {line['box'][1]:>4}  "
              f"{'OK' if line['rules'][0] else 'BAD':>6} {'OK' if line['box'][0] else 'BAD':>6}")

    print()
    for tag in ("rules", "box"):
        ok, placed, n = stats[tag]
        print(f"{tag:>5}: 强约束通过 {ok}/{n} | 落盘合计 {placed}")


if __name__ == "__main__":
    main()
