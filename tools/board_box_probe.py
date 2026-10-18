#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""棋盘框探针：检查棋盘框检测是否可靠，以及它隐含的格距与「棋子中心周期拟合」是否一致。

用途：残局/稀疏盘面下，棋子中心做周期拟合会失准（样本少、分布偏）；
若棋盘框稳定，应改为「框宽/8、框高/9」直接求格距。

用法：
    python board_box_probe.py --model xxx.onnx --images "D:/date/*.jpg"
"""
import argparse
import glob

import numpy as np
import onnxruntime as ort

from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.10)
    args = ap.parse_args()

    files = []
    for pat in args.images:
        files.extend(sorted(glob.glob(pat)))
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])

    print(f"{'图像':22s} {'框数':>4} {'框conf':>6} {'子数':>4}  "
          f"{'框宽/8':>7} {'框高/9':>7}  {'框中心列,行':>12}")
    for f in files:
        chw, meta = preprocess(f, "letterbox")
        dets, _ = run(sess, chw, conf_thr=args.conf)
        dets = to_frame(dets, meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pieces = [d for d in dets if d["cls"] != BOARD_ID]
        name = f.replace("\\", "/").split("/")[-1][9:23]
        if not boards:
            print(f"{name:22s} {0:>4} {'-':>6} {len(pieces):>4}  "
                  f"{'-':>7} {'-':>7}  {'-':>12}")
            continue
        b = max(boards, key=lambda d: d["c"])
        w = b["x1"] - b["x0"]
        h = b["y1"] - b["y0"]
        px = w / 8.0
        py = h / 9.0
        cx = (b["x0"] + b["x1"]) / 2.0
        cy = (b["y0"] + b["y1"]) / 2.0
        # 框中心反推「列/行」索引（左上交点为 (0,0)），仅看比例是否稳定
        colf = (cx - b["x0"]) / px
        rowf = (cy - b["y0"]) / py
        print(f"{name:22s} {len(boards):>4} {b['c']:6.2f} {len(pieces):>4}  "
              f"{px:7.1f} {py:7.1f}  {colf:5.2f},{rowf:5.2f}")


if __name__ == "__main__":
    main()
