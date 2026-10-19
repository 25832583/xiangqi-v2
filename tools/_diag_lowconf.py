#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""棋盘框「没检出」时的两条兜底路线，用真实截图验证效果。

背景：JJ象棋「32强战」皮肤上棋盘框置信度只有 0.31~0.47（常规皮肤 0.83~0.94），
7 张新截图中 2 张掉到 0.25 门槛以下 —— 一张 0.229（框本身是对的，差 0.021），
一张 0.214 且框是残缺的（967x1104，真实约 1250x1360）。

验证两条路线：
  A. 低门槛取框 + 尺寸一致性校验（框宽 / 子宽中位 ≈ 8，框高 / 子高中位 ≈ 9）
  B. 无合格框时，用高置信棋子包络合成棋盘框

用法：
    python _diag_lowconf.py --model ../app/src/main/assets/detector_xqlink_v5.onnx \
                            --images "D:/date/1a0ff692*.jpg" "D:/date/da53b33b*.jpg"
"""
import argparse
import glob
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from anchor_ab import anchor_by_box
from assemble import anchor_by_rules, fit_axis, snap
from assemble_v3 import clip_to_board
from cell_scores import detect
from probe_detector import BOARD_ID, LABELS
from _diag_jj_full import CN, app_letterbox, to_frame

PIECE_CONF = 0.25
BOARD_CONF = 0.12
PIECE_FLOOR = 0.5          # 用于估子尺寸 / 合成框的最小置信


def median(v):
    return float(np.median(v)) if len(v) else 0.0


def size_ok(bd, w_med, h_med):
    """框宽够不够 8 格、高够不够 9 格。"""
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    rx, ry = bw / w_med, bh / h_med
    return (6.8 <= rx <= 9.5) and (7.8 <= ry <= 10.5), rx, ry


def synth_board(pieces, w_med, h_med):
    """用高置信棋子**中心**的包络合成棋盘框。

    关键事实：棋盘框的四条边正好压在首/末列与首/末行的**中心线**上
    （实测 1440x3168：框 x[90,1344] 对应列中心 95..1354，pitch 156.5；
    框高 1362 = 9 x 151.3）。所以「棋子中心包络」在首末列/行都有子时**就是**棋盘框。

    先按中位尺寸把异常框（棋盘上方的立牌/头像，常是 2 倍大）剔掉，
    否则单个离群框会把包络整体拉偏两格。
    """
    px = [d for d in pieces
          if d["c"] >= PIECE_FLOOR
          and 0.70 <= (d["x1"] - d["x0"]) / w_med <= 1.40
          and 0.70 <= (d["y1"] - d["y0"]) / h_med <= 1.40]
    if len(px) < 6:
        return None
    xs = [(d["x0"] + d["x1"]) / 2 for d in px]
    ys = [(d["y0"] + d["y1"]) / 2 for d in px]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    # 跨度不足 8/9 格说明最外列/行恰好没有子，此时按中位子尺寸把缺的一侧补出来
    # （方向无法从包络判断，交给锚定阶段的棋规打分决定）
    while (x1 - x0) / w_med < 7.6:
        x0 -= w_med
    while (y1 - y0) / h_med < 8.6:
        y0 -= h_med
    return {"x0": x0, "x1": x1, "y0": y0, "y1": y1, "c": 0.0, "cls": BOARD_ID}


def run_chain(framed, bd, tag, anchor_kind="box"):
    """完整链路：裁框 -> 尺寸过滤 -> 拟合 -> 吸附 -> 落格 -> FEN。"""
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    pieces = [d for d in framed if d["cls"] != BOARD_ID]
    kept, dropped = clip_to_board(pieces, bd)
    ux, uy = bw / 8, bh / 9
    kept = [d for d in kept
            if 0.45 <= (d["x1"] - d["x0"]) / ux <= 1.8 and 0.45 <= (d["y1"] - d["y0"]) / uy <= 1.8]
    if len(kept) < 3:
        print(f"  [{tag}] => 子太少 {len(kept)}")
        return
    xs = [(d["x0"] + d["x1"]) / 2 for d in kept]
    ys = [(d["y0"] + d["y1"]) / 2 for d in kept]
    px, ox, rx, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, ry, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    if rx > 0.20 or ry > 0.20:
        print(f"  [{tag}] => 残差过大 {rx:.3f}/{ry:.3f}")
        return
    cells, drop2 = snap(kept, px, ox, py, oy)
    if anchor_kind == "rules":
        _, _, kc, kr, score = anchor_by_rules(cells, px, ox, py, oy, bd)
        extra = f" 合法性罚分 {score:.2f}"
    else:
        _, _, kc, kr = anchor_by_box(px, ox, py, oy, bd)
        extra = ""
    grid = [["." for _ in range(9)] for _ in range(10)]
    for (r, c), cands in cells.items():
        rr, cc = int(round(r - kr)), int(round(c - kc))
        if 0 <= rr < 10 and 0 <= cc < 9:
            grid[rr][cc] = sorted(cands, key=lambda t: -t[0])[0][1]
    rows = []
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
        rows.append(out + (str(empty) if empty else ""))
    print(f"  [{tag}] 落 {sum(len(v) for v in cells.values())} 子 残差 {rx:.3f}/{ry:.3f} "
          f"锚定 ({kc},{kr}) pitch {px:.1f}/{py:.1f}{extra}")
    print(f"  [{tag}] FEN {('/'.join(rows))}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    args = ap.parse_args()

    paths = []
    for pat in args.images:
        paths.extend(sorted(glob.glob(pat)))

    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    for p in paths:
        img = Image.open(p).convert("RGB")
        print(f"\n=== {Path(p).name[:12]} {img.size[0]}x{img.size[1]}")
        chw, meta, _ = app_letterbox(img)
        raw = detect(sess, chw, BOARD_CONF)
        framed = to_frame(raw, meta)
        # 棋子类仍按原门槛
        framed = [d for d in framed if d["cls"] == BOARD_ID or d["c"] >= PIECE_CONF]

        pieces = [d for d in framed if d["cls"] != BOARD_ID]
        hi = [d for d in pieces if d["c"] >= PIECE_FLOOR]
        w_med = median([d["x1"] - d["x0"] for d in hi])
        h_med = median([d["y1"] - d["y0"] for d in hi])
        print(f"  棋子 {len(pieces)}（conf>={PIECE_FLOOR} 的 {len(hi)}），"
              f"子尺寸中位 {w_med:.1f}x{h_med:.1f}")

        boards = [d for d in framed if d["cls"] == BOARD_ID]
        print(f"  棋盘框候选（门槛 {BOARD_CONF}）：{len(boards)}")
        good = []
        for d in sorted(boards, key=lambda z: -z["c"]):
            ok, rx, ry = size_ok(d, w_med, h_med)
            bw, bh = d["x1"] - d["x0"], d["y1"] - d["y0"]
            print(f"    conf {d['c']:.3f} {bw:.0f}x{bh:.0f} 尺寸比 {rx:.2f}x{ry:.2f} "
                  f"{'合格' if ok else '不合格'}")
            if ok:
                good.append(d)

        if good:
            run_chain(framed, good[0], f"A 低门槛+校验 conf {good[0]['c']:.3f}", "box")
            run_chain(framed, good[0], f"A 低门槛+校验 conf {good[0]['c']:.3f}", "rules")
        else:
            sb = synth_board(pieces, w_med, h_med)
            if sb is None:
                print("  [B] 高置信棋子不足，无法合成")
                continue
            print(f"  [B] 合成框 x[{sb['x0']:.0f},{sb['x1']:.0f}] "
                  f"y[{sb['y0']:.0f},{sb['y1']:.0f}] "
                  f"{sb['x1'] - sb['x0']:.0f}x{sb['y1'] - sb['y0']:.0f}")
            run_chain(framed, sb, "B 包络合成", "box")
            run_chain(framed, sb, "B 包络合成", "rules")


if __name__ == "__main__":
    main()
