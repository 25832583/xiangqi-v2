#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证「检测即盘面」：框中心 -> 棋盘交点吸附 -> 组装 9x10 盘面。

与旧链路的区别：不使用任何单格分类器、不判墨色、不做公式格距反推。
类别直接来自检测模型，位置只做一次最近交点吸附。

评估口径：与标准开局逐格对比（labeled_opening 全是开局截图）。
"""

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame

# 标准开局：row 0 = 顶部。黑方在上（jj 类平台常见）
OPENING_BLACK_TOP = [
    "rnbakabnr",
    ".........",
    ".c.....c.",
    "p.p.p.p.p",
    ".........",
    ".........",
    "P.P.P.P.P",
    ".C.....C.",
    ".........",
    "RNBAKABNR",
]
OPENING_RED_TOP = [
    "RNBAKABNR",
    ".........",
    ".C.....C.",
    "P.P.P.P.P",
    ".........",
    ".........",
    "p.p.p.p.p",
    ".c.....c.",
    ".........",
    "rnbakabnr",
]


def board_grid(board):
    """棋盘框 -> (原点, 格距)。框按训练约定已外扩半格，故宽=9 格、高=10 格。"""
    x0, y0, x1, y1 = board["x0"], board["y0"], board["x1"], board["y1"]
    px = (x1 - x0) / 9.0
    py = (y1 - y0) / 10.0
    return (x0 + 0.5 * px, y0 + 0.5 * py), (px, py)


def snap(pieces, origin, pitch):
    """每个框中心吸附到最近交点。返回 {cell: [(conf, label, residual), ...]}"""
    ox, oy = origin
    px, py = pitch
    cells = {}
    for d in pieces:
        cx = (d["x0"] + d["x1"]) / 2.0
        cy = (d["y0"] + d["y1"]) / 2.0
        fc = (cx - ox) / px
        fr = (cy - oy) / py
        col = int(round(fc))
        row = int(round(fr))
        if not (0 <= row < 10 and 0 <= col < 9):
            continue
        res = max(abs(fc - col), abs(fr - row))
        cells.setdefault((row, col), []).append((d["c"], LABELS[d["cls"]], res))
    return cells


def resolve(cells, max_pieces=32):
    """冲突消解：同格取置信最高；同类超物理上限时按置信度裁剪。"""
    board = {}
    log = []
    for cell, cands in cells.items():
        cands = sorted(cands, key=lambda t: -t[0])
        board[cell] = cands[0]
        if len(cands) > 1:
            log.append(f"冲突 {cell}: " + ", ".join(f"{l}({c:.2f}/r{r:.2f})" for c, l, r in cands))

    # 每方每类上限（象棋固定：车马炮各2、仕相2、兵5、将1）
    limits = {"r": 2, "n": 2, "c": 2, "a": 2, "b": 2, "k": 1, "p": 5}
    for side_red in (True, False):
        for letter in "rncabkp":
            key = letter.upper() if side_red else letter
            limit = limits[letter]
            hits = [(cell, v) for cell, v in board.items() if v[1] == key]
            if len(hits) > limit:
                hits.sort(key=lambda kv: -kv[1][0])
                for cell, _ in hits[limit:]:
                    log.append(f"超限({key} {len(hits)}>{limit}) 丢弃 {cell} conf={board[cell][0]:.2f}")
                    del board[cell]
    return board, log


def render(board):
    rows = []
    for r in range(10):
        line = ""
        for c in range(9):
            line += board[(r, c)][1] if (r, c) in board else "."
        rows.append(line)
    return rows


def diff(rows, truth):
    bad = []
    for r in range(10):
        for c in range(9):
            got = rows[r][c]
            want = truth[r][c]
            if got != want:
                bad.append((r, c, want, got))
    return bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--out", default="out")
    args = ap.parse_args()

    import onnxruntime as ort
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)

    summary = []
    for path in args.images:
        name = Path(path).name
        chw, meta = preprocess(path, "letterbox")
        dets, _ = run(sess, chw, args.conf)
        dets = to_frame(dets, meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pieces = [d for d in dets if d["cls"] != BOARD_ID]
        if not boards:
            print(f"{name}: 未检出棋盘")
            continue
        board = max(boards, key=lambda d: d["c"])
        origin, pitch = board_grid(board)
        cells = snap(pieces, origin, pitch)
        resolved, log = resolve(cells)
        rows = render(resolved)

        b_black = diff(rows, OPENING_BLACK_TOP)
        b_red = diff(rows, OPENING_RED_TOP)
        flipped = len(b_red) < len(b_black)
        bad = b_red if flipped else b_black

        residuals = [v[2] for v in resolved.values()]
        print(f"\n=== {name} ===")
        print(f"  检出 {len(pieces)} 子 / 棋盘 conf={board['c']:.3f} | 落盘 {len(resolved)} 子"
              f" | 方向={'红在上' if flipped else '黑在上'}")
        print(f"  吸附残差 中位 {np.median(residuals):.3f} 格, 最大 {max(residuals):.3f} 格")
        print(f"  与开局真值差异 {len(bad)} 格 / 90")
        for r, c, want, got in bad:
            print(f"    ({r},{c}) 期望 {want} 实得 {got}")
        for line in log:
            print(f"    · {line}")

        # 可视化：画出吸附后的交点
        img = Image.open(path).convert("RGB")
        dr = ImageDraw.Draw(img)
        ox, oy = origin
        px, py = pitch
        for r in range(10):
            for c in range(9):
                x, y = ox + c * px, oy + r * py
                if (r, c) in resolved:
                    conf, lab, res = resolved[(r, c)]
                    col = (220, 30, 30) if lab.isupper() else (30, 80, 220)
                    dr.ellipse([x - 6, y - 6, x + 6, y + 6], outline=col, width=3)
                    dr.text((x + 8, y - 6), lab, fill=col)
                else:
                    dr.ellipse([x - 2, y - 2, x + 2, y + 2], fill=(0, 180, 90))
        img.save(outdir / f"{Path(path).stem}_snap.jpg", quality=85)

        summary.append({
            "image": name, "detected": len(pieces), "placed": len(resolved),
            "flipped": flipped, "diff": len(bad), "residual_med": float(np.median(residuals)),
        })

    total = sum(s["diff"] for s in summary)
    cells = len(summary) * 90
    print(f"\n===== 汇总：{len(summary)} 张图，{total} 格错 / {cells} 格 "
          f"({100 * (1 - total / cells):.1f}% 逐格正确) =====")
    (outdir / "assemble_summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), "utf-8")


if __name__ == "__main__":
    main()
