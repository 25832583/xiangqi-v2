#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""逐格打印类别得分分布：某一格认成什么、第二名差多少。

用途：用户报「X 认成 Y」时，先分清是哪一类错，判据完全不同 ——

  - 第一名 0.9、第二名 0.1   → 模型确定地认错，只能靠数据（补样本重训）
  - 第一名 0.48、第二名 0.45 → 边缘平票，可以用规则/上下文把它改对
  - 框根本不在那一格         → 不是分类问题，是吸附/装配问题

只做诊断，不改任何东西。

用法：
    python cell_scores.py --model ../app/src/main/assets/detector_xqlink_v5.onnx \
                          --image shot.jpg --topk 3
"""
import argparse
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
from anchor_ab import anchor_by_box
from assemble import fit_axis, snap
from assemble_v3 import clip_to_board
from probe_detector import BOARD_ID, LABELS, nms, preprocess, to_frame

CN = {
    "k": "黑将", "a": "黑士", "b": "黑象", "n": "黑马", "r": "黑车", "c": "黑炮", "p": "黑卒",
    "K": "红帅", "A": "红仕", "B": "红相", "N": "红马", "R": "红车", "C": "红炮", "P": "红兵",
    "0": "棋盘",
}


def detect(sess, chw, conf_thr):
    """返回 (保留的检测框, 每个框的完整类别分数向量)。

    与 probe_detector.run 的差别只有一处：把 NMS 前的类分向量跟着框带下来，
    否则只能看到 argmax，看不出「差多少」。
    """
    name = sess.get_inputs()[0].name
    out = np.asarray(sess.run(None, {name: chw})[0], dtype=np.float32)
    if out.ndim == 3:
        out = out[0]
    ncls = len(LABELS)
    if out.shape[1] not in (4 + ncls, 5 + ncls) and out.shape[0] in (4 + ncls, 5 + ncls):
        out = out.T
    if out.shape[1] == 5 + ncls:
        conf = out[:, 4] * out[:, 5:].max(axis=1)
        cls_scores = out[:, 5:]
    else:
        cls_scores = out[:, 4:]
        conf = cls_scores.max(axis=1)
    best = cls_scores.argmax(axis=1)

    dets = []
    for i in np.where(conf >= conf_thr)[0]:
        cx, cy, w, h = out[i, :4]
        dets.append({
            "x0": float(cx - w / 2), "y0": float(cy - h / 2),
            "x1": float(cx + w / 2), "y1": float(cy + h / 2),
            "c": float(conf[i]), "cls": int(best[i]),
            "_scores": cls_scores[i].copy(),
        })
    kept = nms([{k: v for k, v in d.items() if k != "_scores"} for d in dets])
    # nms 返回的是浅拷贝后的新 dict，按 (cls, 中心, 分数) 回连原始分数向量
    merged = []
    for k in kept:
        for d in dets:
            if d["cls"] == k["cls"] and abs(d["c"] - k["c"]) < 1e-6 and abs(d["x0"] - k["x0"]) < 1e-3:
                merged.append({**k, "_scores": d["_scores"]})
                break
    return merged


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--image", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--topk", type=int, default=3)
    ap.add_argument("--only", default="", help="只看这些种类，逗号分隔，如 B,C")
    args = ap.parse_args()

    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    chw, meta = preprocess(args.image, "letterbox")
    pieces = detect(sess, chw, args.conf)
    framed = to_frame(pieces, meta)
    for d, s in zip(framed, pieces):
        d["_scores"] = s["_scores"]

    bd = next((d for d in framed if d["cls"] == BOARD_ID), None)
    if bd is None:
        raise SystemExit("没检出棋盘框（class 14），无法装配")

    kept, _ = clip_to_board(framed, bd)
    xs = [(d["x0"] + d["x1"]) / 2 for d in kept]
    ys = [(d["y0"] + d["y1"]) / 2 for d in kept]
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    px, ox, _, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, _, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    cells, dropped = snap(kept, px, ox, py, oy)
    ox, oy, kc, kr = anchor_by_box(px, ox, py, oy, bd)

    only = {s.strip() for s in args.only.split(",") if s.strip()}
    rows = []
    for (r, c), cands in cells.items():
        cell = (int(round(r - kr)), int(round(c - kc)))
        # 同格多个候选时按置信排序全部列出：平票恰恰是要看的现象
        rows.append((cell, sorted(cands, key=lambda t: -t[0])))
    rows.sort()

    print(f"图片 {args.image}  {meta['size'][0]}x{meta['size'][1]}  "
          f"格距 {px:.1f}x{py:.1f}px  丢弃 {dropped} 框")
    print(f"{'格(行,列)':<12}{'判定':<6}{'conf':>6}   前 {args.topk} 名")
    print("-" * 78)
    for (cell, cands) in rows:
        conf, label, res = cands[0]
        if only and label not in only:
            continue
        d = next((x for x in kept if x["c"] == conf and x["cls"] == LABELS.index(label)), None)
        tops = ""
        if d is not None:
            s = d["_scores"]
            order = np.argsort(-s)[:args.topk]
            tops = "  ".join(f"{CN.get(LABELS[i], LABELS[i])} {s[i]:.3f}" for i in order)
        extra = ""
        if len(cands) > 1:
            extra = "  ← 同格还有 " + ", ".join(f"{CN.get(l, l)}({c:.2f})" for c, l, _ in cands[1:])
        print(f"{str(cell):<12}{CN.get(label, label):<6}{conf:>6.3f}   {tops}   res={res:.2f}{extra}")

    print()
    print("提示：第一名远高于第二名 = 模型确定地认错（要数据）；"
          "两者接近 = 边缘平票（规则可救）；框不在预期格 = 装配问题")


if __name__ == "__main__":
    main()
