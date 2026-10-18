#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证「检测即盘面」：框中心 -> 周期拟合出真实网格 -> 交点吸附 -> 组装盘面。

关键修正（相对 box 假设版）：
  模型的棋盘框与格距没有固定倍数关系（实测框宽≈7.95 列距、框高≈9.1~9.8 行距），
  所以不能按「框宽/9」反推格距。改为只用棋子框中心做一维周期拟合：
  找 pitch 与 phase 使所有点落在整数格点的残差最小。棋子本身站在格点上，
  这是最强的几何证据，且不依赖任何皮肤假设。

评估：与标准开局逐格对比。
"""

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame

OPENING_BLACK_TOP = [
    "rnbakabnr", ".........", ".c.....c.", "p.p.p.p.p", ".........",
    ".........", "P.P.P.P.P", ".C.....C.", ".........", "RNBAKABNR",
]
OPENING_RED_TOP = [
    "RNBAKABNR", ".........", ".C.....C.", "P.P.P.P.P", ".........",
    ".........", "p.p.p.p.p", ".c.....c.", ".........", "rnbakabnr",
]

LIMITS = {"r": 2, "n": 2, "c": 2, "a": 2, "b": 2, "k": 1, "p": 5}


def fit_axis(vals, p_lo, p_hi, step=0.05):
    """一维周期拟合：返回 (pitch, phase, rms_residual, 相位集中度)。

    用圆均值求相位（对缺失格点不敏感），穷举 pitch 取残差最小者。
    """
    vals = np.asarray(vals, dtype=np.float64)
    best = None
    for p in np.arange(p_lo, p_hi, step):
        m = np.exp(2j * np.pi * vals / p).mean()
        if abs(m) < 1e-6:
            continue
        phase = np.angle(m) / (2 * np.pi) * p
        r = (vals - phase) / p
        r = r - np.round(r)
        res = float(np.sqrt((r ** 2).mean()))
        if best is None or res < best[2]:
            best = (float(p), float(phase), res, float(abs(m)))
    return best


def snap(pieces, px, ox, py, oy):
    cells = {}
    for d in pieces:
        cx = (d["x0"] + d["x1"]) / 2.0
        cy = (d["y0"] + d["y1"]) / 2.0
        fc = (cx - ox) / px
        fr = (cy - oy) / py
        col = int(round(fc))
        row = int(round(fr))
        res = max(abs(fc - col), abs(fr - row))
        cells.setdefault((row, col), []).append((d["c"], LABELS[d["cls"]], res))
    return cells


def shift_and_resolve(cells):
    """把行列编号平移到 0..8 / 0..9，再做冲突消解与同类上限裁剪。"""
    if not cells:
        return {}, [], (0, 0)
    rs = [r for r, _ in cells]
    cs = [c for _, c in cells]
    r0, c0 = min(rs), min(cs)
    shifted = {(r - r0, c - c0): v for (r, c), v in cells.items()}
    dr, dc = len(set(rs)), len(set(cs))

    log = []
    board = {}
    for cell, cands in shifted.items():
        if not (0 <= cell[0] < 10 and 0 <= cell[1] < 9):
            log.append(f"越界 {cell} 丢弃")
            continue
        cands = sorted(cands, key=lambda t: -t[0])
        board[cell] = cands[0]
        if len(cands) > 1:
            log.append(f"同格冲突 {cell}: " + ", ".join(f"{l}({c:.2f}/r{r:.2f})" for c, l, r in cands))

    for side_red in (True, False):
        for letter in "rncabkp":
            key = letter.upper() if side_red else letter
            limit = LIMITS[letter]
            hits = [(cell, v) for cell, v in board.items() if v[1] == key]
            if len(hits) > limit:
                hits.sort(key=lambda kv: -kv[1][0])
                for cell, _ in hits[limit:]:
                    log.append(f"超限 {key} {len(hits)}>{limit}，丢弃 {cell} conf={board[cell][0]:.2f}")
                    del board[cell]
    return board, log, (dr, dc)


def render(board):
    return ["".join(board[(r, c)][1] if (r, c) in board else "." for c in range(9)) for r in range(10)]


def diff(rows, truth):
    return [(r, c, truth[r][c], rows[r][c]) for r in range(10) for c in range(9) if rows[r][c] != truth[r][c]]


def parse_fen(fen):
    rows = []
    for rank in fen.strip().split("/"):
        line = ""
        for ch in rank:
            line += "." * int(ch) if ch.isdigit() else ch
        rows.append(line.ljust(9, "."))
    return rows


def load_truth(path):
    """TSV：文件名<TAB>FEN。没有真值的图不进表。"""
    truth = {}
    if not path:
        return truth
    for line in Path(path).read_text("utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "\t" not in line:
            continue
        name, fen = line.split("\t", 1)
        truth[name.strip()] = parse_fen(fen)
    return truth


def flip180(rows):
    return [row[::-1] for row in reversed(rows)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--truth", default="truth.tsv")
    ap.add_argument("--out", default="out")
    args = ap.parse_args()
    truth = load_truth(args.truth)

    import onnxruntime as ort
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)

    summary = []
    for path in args.images:
        name = Path(path).name
        chw, meta = preprocess(path, "letterbox")
        dets = to_frame(run(sess, chw, args.conf)[0], meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pieces = [d for d in dets if d["cls"] != BOARD_ID]
        if not boards or not pieces:
            print(f"{name}: 检出不足（棋盘 {len(boards)}，棋子 {len(pieces)}）")
            continue

        board = max(boards, key=lambda d: d["c"])
        bw = board["x1"] - board["x0"]
        bh = board["y1"] - board["y0"]
        xs = [(d["x0"] + d["x1"]) / 2 for d in pieces]
        ys = [(d["y0"] + d["y1"]) / 2 for d in pieces]

        # 搜索范围以「框宽/9」「框高/10」为初值放宽（真实格距比它大 10% 左右）
        px, ox, rx, mx = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
        py, oy, ry, my = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)

        cells = snap(pieces, px, ox, py, oy)
        resolved, log, spans = shift_and_resolve(cells)
        rows = render(resolved)

        truth_rows = truth.get(name)
        if truth_rows:
            d0 = diff(rows, truth_rows)
            d1 = diff(flip180(rows), truth_rows)
            if len(d1) < len(d0):
                rows, bad, flipped = flip180(rows), d1, True
            else:
                bad, flipped = d0, False
            basis = "登记真值"
        else:
            b_black = diff(rows, OPENING_BLACK_TOP)
            b_red = diff(rows, OPENING_RED_TOP)
            flipped = len(b_red) < len(b_black)
            bad = b_red if flipped else b_black
            basis = "标准开局假设（无登记真值）"

        print(f"\n=== {name} ===")
        print(f"  检出 {len(pieces)} 子 | 框 {bw:.0f}x{bh:.0f}px")
        print(f"  拟合格距 x={px:.2f}px (残差 {rx:.3f} 格, 集中度 {mx:.2f}) "
              f"y={py:.2f}px (残差 {ry:.3f} 格, 集中度 {my:.2f})")
        print(f"  框宽/格距={bw / px:.2f} 格, 框高/格距={bh / py:.2f} 格")
        print(f"  落盘 {len(resolved)} 子, 覆盖 {spans[1]} 列 x {spans[0]} 行, 方向={'红在上' if flipped else '黑在上'}")
        print(f"  验收基准：{basis}")
        print(f"  与真值差异 {len(bad)} 格 / 90")
        for r, c, want, got in bad:
            print(f"    ({r},{c}) 期望 {want} 实得 {got}")
        for line in log:
            print(f"    · {line}")

        img = Image.open(path).convert("RGB")
        dr = ImageDraw.Draw(img)
        for r in range(10):
            for c in range(9):
                x1, y1 = ox + c * px, oy + r * py
                dr.ellipse([x1 - 3, y1 - 3, x1 + 3, y1 + 3], outline=(0, 180, 90), width=2)
                if (r, c) in resolved:
                    conf, lab, res = resolved[(r, c)]
                    col = (220, 30, 30) if lab.isupper() else (30, 80, 220)
                    dr.text((x1 + 6, y1 - 14), f"{lab}{conf:.1f}", fill=col)
        img.save(outdir / f"{Path(path).stem}_fit.jpg", quality=85)

        summary.append({
            "image": name, "detected": len(pieces), "placed": len(resolved),
            "flipped": flipped, "diff": len(bad),
            "pitch_x": round(px, 2), "pitch_y": round(py, 2),
            "res_x": round(rx, 4), "res_y": round(ry, 4),
            "board_w_over_pitch": round(bw / px, 2), "board_h_over_pitch": round(bh / py, 2),
        })

    total = sum(s["diff"] for s in summary)
    cells = len(summary) * 90
    print(f"\n===== 汇总：{len(summary)} 张图，{total} 格错 / {cells} 格 "
          f"({100 * (1 - total / cells):.1f}% 逐格正确) =====")
    (outdir / "assemble_fit_summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False), "utf-8")


if __name__ == "__main__":
    main()
