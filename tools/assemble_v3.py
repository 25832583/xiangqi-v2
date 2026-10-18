#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""装配 v3：先按棋盘框裁剪，再拟合格距/相位，用框中心定整数偏移。

相对 v2（assemble.py）的三处改动：
  1. 拟合前把检出裁到棋盘框内（棋盘上方的装饰棋子、被吃子托盘会污染周期拟合）；
  2. 整数偏移改用棋盘框中心直接解（不再靠棋规合法性搜索）；
  3. 吸附丢弃阈值按「框内」重算，越界项在装配阶段直接排除。

用法：
    python assemble_v3.py --model xxx.onnx --images "D:/date/*.jpg" --ab
"""
import argparse
import glob
import json
import sys
from pathlib import Path

import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
from anchor_ab import anchor_by_box
from assemble import BOARD_CENTER_COL, BOARD_CENTER_ROW, fit_axis, render, resolve, snap, to_fen
from fen_lint import check
from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame


def clip_to_board(pieces, bd, pad=0.55, px=None, py=None):
    """保留落在棋盘框内的棋子框中心（向外放宽 pad 个格距，容忍边缘棋子略出框）。"""
    px = px if px is not None else (bd["x1"] - bd["x0"]) / 8.0
    py = py if py is not None else (bd["y1"] - bd["y0"]) / 9.0
    x_lo, x_hi = bd["x0"] - pad * px, bd["x1"] + pad * px
    y_lo, y_hi = bd["y0"] - pad * py, bd["y1"] + pad * py
    keep, out = [], 0
    for d in pieces:
        cx, cy = (d["x0"] + d["x1"]) / 2, (d["y0"] + d["y1"]) / 2
        if x_lo <= cx <= x_hi and y_lo <= cy <= y_hi:
            keep.append(d)
        else:
            out += 1
    return keep, out


def assemble_v3(pieces, bd):
    """返回 (board, meta)。board: {(row,col): (conf,label,res)}"""
    kept, out = clip_to_board(pieces, bd)
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    if not kept:
        return {}, dict(out_of_board=out, kept=0)
    xs = [(d["x0"] + d["x1"]) / 2 for d in kept]
    ys = [(d["y0"] + d["y1"]) / 2 for d in kept]
    px, ox, rx, mx = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, ry, my = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    cells, dropped = snap(kept, px, ox, py, oy)
    ox, oy, kc, kr = anchor_by_box(px, ox, py, oy, bd)
    board, log = resolve({(r - kr, c - kc): v for (r, c), v in cells.items()})
    return board, dict(out_of_board=out, kept=len(kept), dropped=dropped,
                       px=px, py=py, rx=rx, ry=ry, mx=mx, my=my, kc=kc, kr=kr, log=log)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--json", default=None)
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    paths = []
    for pat in args.images:
        paths.extend(sorted(glob.glob(pat)))
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])

    ok = n = 0
    summary = []
    print(f"{'图像':18s}{'检出':>4}{'框外':>4}{'框内':>4}{'落盘':>4}  {'lint':>4}  FEN")
    for p in paths:
        chw, meta = preprocess(p, "letterbox")
        dets = to_frame(run(sess, chw, args.conf)[0], meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pieces = [d for d in dets if d["cls"] != BOARD_ID]
        if not boards or not pieces:
            continue
        bd = max(boards, key=lambda d: d["c"])
        board, m = assemble_v3(pieces, bd)
        fen = to_fen(render(board))
        errs, _, _ = check(fen)
        n += 1
        ok += not errs
        summary.append(dict(image=Path(p).name, detected=len(pieces), placed=len(board),
                            fen=fen, errors=errs, **{k: v for k, v in m.items() if k != "log"}))
        if not args.quiet:
            print(f"{Path(p).name[9:22]:18s}{len(pieces):>4}{m.get('out_of_board',0):>4}"
                  f"{m.get('kept',0):>4}{len(board):>4}  {'OK' if not errs else 'BAD':>4}  {fen}")
            if errs:
                print(f"{'':18s}  × " + " / ".join(errs))
    print(f"\n强约束通过 {ok}/{n}")
    if args.json:
        Path(args.json).write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
