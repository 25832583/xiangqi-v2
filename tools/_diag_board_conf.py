#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""棋盘框置信度在不同输入路径下会掉到多少。

背景：JJ象棋「32强战」皮肤的棋盘框离线只有 conf 0.30（常规皮肤 0.83~0.94），
而 App 的门槛是 0.25。只高出 0.05 的余量，任何一点劣化都会变成 NoBoard。
这里把 App 真实走的预处理（逐级减半 -> letterbox）复刻出来，量余量还剩多少。
"""
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
from probe_detector import BOARD_ID, LABELS, nms

SIZE = 640


def infer(sess, img, tag):
    """img: PIL RGB，任意尺寸 -> 按 App 的方式 letterbox 到 640 后推理。"""
    w, h = img.size
    scale = min(SIZE / w, SIZE / h)
    nw, nh = int(round(w * scale)), int(round(h * scale))

    # Downscale.plan 的逐级减半
    steps = []
    cw, ch = w, h
    while min(cw / nw, ch / nh) > 2:
        cw, ch = max(cw // 2, nw), max(ch // 2, nh)
        steps.append((cw, ch))
    if (cw, ch) != (nw, nh):
        steps.append((nw, nh))

    cur = img
    for (sw, sh) in steps:
        cur = cur.resize((sw, sh), Image.BILINEAR)

    canvas = Image.new("RGB", (SIZE, SIZE), (114, 114, 114))
    canvas.paste(cur, ((SIZE - nw) // 2, (SIZE - nh) // 2))
    arr = np.asarray(canvas, dtype=np.float32) / 255.0
    chw = arr.transpose(2, 0, 1)[None, ...]

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
        conf = out[:, 4:]
        cls_scores = out[:, 4:]
    best = cls_scores.argmax(axis=1)

    dets = []
    for i in np.where(conf >= 0.05)[0]:
        cx, cy, bw, bh = out[i, :4]
        dets.append({
            "x0": float(cx - bw / 2), "y0": float(cy - bh / 2),
            "x1": float(cx + bw / 2), "y1": float(cy + bh / 2),
            "c": float(conf[i]), "cls": int(best[i]),
        })
    kept = nms(dets)
    boards = [d for d in kept if d["cls"] == BOARD_ID]
    pieces = [d for d in kept if d["cls"] != BOARD_ID]
    b = max(boards, key=lambda d: d["c"]) if boards else None
    if b:
        area = (b["x1"] - b["x0"]) * (b["y1"] - b["y0"]) / (nw * nh)
        print(f"  {tag:26s} 步数 {len(steps)} 缩到 {nw}x{nh} | 棋盘 conf {b['c']:.3f} "
              f"占画布 {area * 100:.0f}% | 棋子 {len(pieces)} | conf>=0.25 的棋盘框 {sum(1 for d in boards if d['c'] >= 0.25)}")
    else:
        print(f"  {tag:26s} 步数 {len(steps)} 缩到 {nw}x{nh} | 没有棋盘框 | 棋子 {len(pieces)}")
    return b["c"] if b else 0.0


def main():
    model, image = sys.argv[1], sys.argv[2]
    sess = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    img = Image.open(image).convert("RGB")
    print(f"原图 {img.size[0]}x{img.size[1]}")
    infer(sess, img, "原图（离线诊断）")

    # 真机抓帧通常是这个量级：等比放大后重跑，看清晰度变化的影响
    for (tw, th) in [(1080, 2378), (1280, 2818)]:
        big = img.resize((tw, th), Image.BICUBIC)
        infer(sess, big, f"放大到 {tw}x{th}")

    # JPEG 再压一道：真机帧是无损位图，这一项只是给出最坏情况
    for q in (75, 55):
        big = img.resize((1080, 2378), Image.BICUBIC)
        buf = Path("/tmp/_q.jpg")
        big.save(buf, quality=q)
        infer(sess, Image.open(buf).convert("RGB"), f"1080 宽 + JPEG q{q}")


if __name__ == "__main__":
    main()
