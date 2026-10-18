#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""在真值上评测识别链路：检出/装配/分类逐格算准不准。

和 assemble.py --truth 的区别：这里用 v3 流程（裁到棋盘框 + 框中心定锚），
并且分开统计「漏子 / 多子 / 分类错」——这三类的修法完全不同。

用法：
    python eval_gt.py --model ../app/src/main/assets/detector_xqlink_v5.onnx \
                      --images "D:/date/微信图片_*.jpg" --truth D:/date/_gt/truth.tsv
"""
import argparse
import glob
import sys
from collections import Counter
from pathlib import Path

import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
from assemble import parse_fen
from assemble_v3 import assemble_v3
from probe_detector import BOARD_ID, preprocess, run, to_frame


def cells_of(fen):
    return {(r, c): ch
            for r, row in enumerate(parse_fen(fen))
            for c, ch in enumerate(row) if ch != "."}


def load_truth(path):
    out = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            out[parts[0].strip()] = parts[1].strip()
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--truth", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    truth = load_truth(args.truth)
    paths = []
    for pat in args.images:
        paths.extend(sorted(glob.glob(pat)))
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])

    tp = fp = fn = right = total = 0
    exact = 0
    conf_hist = Counter()      # (真值, 预测) -> 次数
    bad_imgs = []

    for p in paths:
        name = Path(p).name
        if name not in truth:
            continue
        gt = cells_of(truth[name])
        chw, meta = preprocess(p, "letterbox")
        dets = to_frame(run(sess, chw, args.conf)[0], meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pieces = [d for d in dets if d["cls"] != BOARD_ID]
        if not boards:
            bad_imgs.append((name, "无棋盘框", 0, 0))
            fn += len(gt)
            total += len(gt)
            continue
        board, _ = assemble_v3(pieces, max(boards, key=lambda d: d["c"]))
        pred = {k: v[1] for k, v in board.items()}

        hit = 0
        for k, ch in gt.items():
            total += 1
            if k not in pred:
                fn += 1
                conf_hist[(ch, "漏")] += 1
            elif pred[k] == ch:
                tp += 1
                hit += 1
            else:
                conf_hist[(ch, pred[k])] += 1
        fp += len(set(pred) - set(gt))
        right += hit
        exact += (hit == len(gt) and len(pred) == len(gt))
        if hit != len(gt) or len(pred) != len(gt):
            bad_imgs.append((name, f"{hit}/{len(gt)}", len(pred), len(gt)))

    prec = tp / max(tp + fp, 1)
    rec = tp / max(tp + fn, 1)
    print(f"\n{Path(args.model).name}")
    print(f"  图 {len(truth)} 张，真值棋子 {total} 颗")
    print(f"  逐颗准确 {tp}/{total} = {tp / max(total, 1) * 100:.1f}%   "
          f"查准 {prec * 100:.1f}%  查全 {rec * 100:.1f}%   F1 {2 * prec * rec / max(prec + rec, 1e-9) * 100:.1f}%")
    print(f"  漏子 {fn}   多子 {fp}   整盘全对 {exact}/{len(truth)}")
    if conf_hist:
        print("  错误明细（真值→预测）：")
        for (a, b), n in conf_hist.most_common(15):
            print(f"    {a}→{b}  {n}")
    if args.verbose and bad_imgs:
        print("  有错的图：")
        for row in bad_imgs[:30]:
            print("   ", row)


if __name__ == "__main__":
    main()
