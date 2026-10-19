#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""棋盘框「候选 0 个」时，看模型到底吐了什么。

门槛放到 0.01，把全部棋盘类框连同宽高比、尺寸、中心位置一起打出来，
用来分清是「模型完全没检到」还是「检到了但被门槛/宽高比滤掉」。
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from cell_scores import detect
from probe_detector import BOARD_ID, LABELS
from _diag_jj_full import app_letterbox


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--image", required=True)
    args = ap.parse_args()

    img = Image.open(args.image).convert("RGB")
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    chw, meta, steps = app_letterbox(img)
    w, h = img.size
    print(f"输入 {w}x{h}  降采样 {len(steps)} 步")

    # 0.01 门槛，看全量
    raw = detect(sess, chw, 0.01)
    sx = meta["size"][0] / (meta["size"][0] * meta["scale"][0])
    sc = meta["scale"][0]
    px, py = meta["pad"]

    def to_frame(d):
        return (
            (d["x0"] - px) / sc, (d["y0"] - py) / sc,
            (d["x1"] - px) / sc, (d["y1"] - py) / sc,
        )

    boards = [d for d in raw if d["cls"] == BOARD_ID]
    print(f"\n棋盘类候选（门槛 0.01）：{len(boards)} 个")
    for d in sorted(boards, key=lambda z: -z["c"]):
        x0, y0, x1, y1 = to_frame(d)
        bw, bh = x1 - x0, y1 - y0
        asp = bw / bh if bh else 0
        flag = "OK" if (asp >= 0.6 and asp <= 1.6) else "宽高比不合格"
        print(f"  conf {d['c']:.3f}  {bw:.0f}x{bh:.0f}  aspect {asp:.3f}  "
              f"x[{x0:.0f},{x1:.0f}] y[{y0:.0f},{y1:.0f}]  {flag}")

    print(f"\n全部类别分布（门槛 0.01）：{len(raw)} 个框")
    from collections import Counter
    cnt = Counter(LABELS[d["cls"]] for d in raw)
    print("  " + "  ".join(f"{k}:{v}" for k, v in cnt.most_common()))

    print("\n棋子框尺寸（按棋盘宽/8、高/9 折算）：")
    if boards:
        bd = max(boards, key=lambda z: z["c"])
        x0, y0, x1, y1 = to_frame(bd)
        px_, py_ = (x1 - x0) / 8, (y1 - y0) / 9
    else:
        # 没有棋盘框时用整图估（JJ 皮肤棋盘宽约 0.87*W）
        px_, py_ = w * 0.87 / 8, w * 0.87 / 9
    print(f"  参考格距 {px_:.1f} x {py_:.1f}")
    rows = []
    for d in raw:
        if d["cls"] == BOARD_ID:
            continue
        fx0, fy0, fx1, fy1 = to_frame(d)
        rows.append(((fx1 - fx0) / px_, (fy1 - fy0) / py_, d["c"], LABELS[d["cls"]]))
    for a, b, c, n in sorted(rows, key=lambda z: -z[0])[:8]:
        print(f"  最大: {n} {a:.2f}x{b:.2f} 格  conf {c:.3f}")
    for a, b, c, n in sorted(rows, key=lambda z: z[0])[:4]:
        print(f"  最小: {n} {a:.2f}x{b:.2f} 格  conf {c:.3f}")


if __name__ == "__main__":
    main()
