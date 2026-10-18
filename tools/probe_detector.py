#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""检测模型体检：看现有 detector.onnx 的 14 类输出到底能不能直接用。

对比两种预处理：
  stretch  —— 直接拉伸到 640x640（现状产线做法）
  letterbox—— 保持长宽比 + 灰度补边（YOLO 官方做法）

输出每类的框数与置信度分布，并把框画到图上。
"""

import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

LABELS = ["n", "b", "a", "k", "r", "c", "p", "R", "N", "A", "K", "B", "C", "P", "0"]
BOARD_ID = 14
SIZE = 640

# 标准开局，row 0 = 顶部（黑方底线）
INITIAL_ROWS = [
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


def preprocess(path, mode):
    img = Image.open(path).convert("RGB")
    w, h = img.size
    if mode == "stretch":
        inp = img.resize((SIZE, SIZE), Image.BILINEAR)
        meta = {"scale": (SIZE / w, SIZE / h), "pad": (0, 0), "size": (w, h)}
    else:
        scale = min(SIZE / w, SIZE / h)
        nw, nh = int(round(w * scale)), int(round(h * scale))
        canvas = Image.new("RGB", (SIZE, SIZE), (114, 114, 114))
        canvas.paste(img.resize((nw, nh), Image.BILINEAR), ((SIZE - nw) // 2, (SIZE - nh) // 2))
        inp = canvas
        meta = {"scale": (scale, scale), "pad": ((SIZE - nw) // 2, (SIZE - nh) // 2), "size": (w, h)}
    arr = np.asarray(inp, dtype=np.float32) / 255.0
    chw = arr.transpose(2, 0, 1)[None, ...]
    return chw, meta


def nms(dets, thr=0.5):
    """按类别分组做 NMS。dets: list of dict(x0,y0,x1,y1,c,cls)"""
    keep = []
    for cls in set(d["cls"] for d in dets):
        group = sorted([d for d in dets if d["cls"] == cls], key=lambda d: -d["c"])
        while group:
            cur = group.pop(0)
            keep.append(cur)
            rest = []
            for o in group:
                ix = max(0.0, min(cur["x1"], o["x1"]) - max(cur["x0"], o["x0"]))
                iy = max(0.0, min(cur["y1"], o["y1"]) - max(cur["y0"], o["y0"]))
                inter = ix * iy
                a1 = (cur["x1"] - cur["x0"]) * (cur["y1"] - cur["y0"])
                a2 = (o["x1"] - o["x0"]) * (o["y1"] - o["y0"])
                union = a1 + a2 - inter
                if union <= 0 or inter / union < thr:
                    rest.append(o)
            group = rest
    return keep


def run(sess, chw, conf_thr=0.25):
    """兼容三种输出布局：
      v5        [1, N, 5+nc]   xywh + obj + cls
      v8        [1, N, 4+nc]   xywh + cls（无 obj，已 sigmoid）
      v8 转置    [1, 4+nc, N]
    """
    in_name = sess.get_inputs()[0].name
    out = np.asarray(sess.run(None, {in_name: chw})[0], dtype=np.float32)
    if out.ndim == 3:
        out = out[0]

    ncls = len(LABELS)
    cands = (4 + ncls, 5 + ncls)
    # 「通道」那一维一定等于 4+nc 或 5+nc，锚点那一维通常远大得多。
    # 四种实见布局：(N,5+nc) (N,4+nc) (5+nc,N) (4+nc,N)
    if out.shape[1] not in cands and out.shape[0] in cands:
        out = out.T
    if out.shape[1] not in cands:
        raise SystemExit(f"无法识别的输出布局，列数 {out.shape[1]} 不属于 {cands}")

    if out.shape[1] == 5 + ncls:
        obj = out[:, 4]
        cls_scores = out[:, 5:]
        conf = obj * cls_scores.max(axis=1)
    else:
        cls_scores = out[:, 4:]
        conf = cls_scores.max(axis=1)

    best = cls_scores.argmax(axis=1)
    idx = np.where(conf >= conf_thr)[0]
    dets = []
    for i in idx:
        cx, cy, w, h = out[i, :4]
        dets.append({
            "x0": float(cx - w / 2), "y0": float(cy - h / 2),
            "x1": float(cx + w / 2), "y1": float(cy + h / 2),
            "c": float(conf[i]), "cls": int(best[i]),
        })
    return nms(dets), out


def to_frame(dets, meta):
    """模型输入空间 -> 原始帧像素"""
    sx, sy = meta["scale"]
    px, py = meta["pad"]
    out = []
    for d in dets:
        out.append({
            **d,
            "x0": (d["x0"] - px) / sx, "y0": (d["y0"] - py) / sy,
            "x1": (d["x1"] - px) / sx, "y1": (d["y1"] - py) / sy,
        })
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--out", default="out")
    args = ap.parse_args()

    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)

    report = {}
    for path in args.images:
        name = Path(path).name
        entry = {}
        for mode in ("stretch", "letterbox"):
            chw, meta = preprocess(path, mode)
            dets, _ = run(sess, chw, args.conf)
            pieces = [d for d in dets if d["cls"] != BOARD_ID]
            boards = [d for d in dets if d["cls"] == BOARD_ID]
            counts = {}
            for d in pieces:
                counts[LABELS[d["cls"]]] = counts.get(LABELS[d["cls"]], 0) + 1
            confs = [round(d["c"], 3) for d in pieces]
            entry[mode] = {
                "pieces": len(pieces),
                "boards": len(boards),
                "board_conf": round(max([b["c"] for b in boards]), 3) if boards else 0.0,
                "counts": dict(sorted(counts.items())),
                "conf_min": min(confs) if confs else 0.0,
                "conf_med": float(np.median(confs)) if confs else 0.0,
            }
            drawn = to_frame(pieces, meta)
            img = Image.open(path).convert("RGB")
            dr = ImageDraw.Draw(img)
            for d in drawn:
                col = (220, 30, 30) if LABELS[d["cls"]].isupper() else (30, 90, 220)
                dr.rectangle([d["x0"], d["y0"], d["x1"], d["y1"]], outline=col, width=3)
                dr.text((d["x0"] + 4, d["y0"] + 2), LABELS[d["cls"]], fill=col)
            for b in to_frame(boards, meta):
                dr.rectangle([b["x0"], b["y0"], b["x1"], b["y1"]], outline=(20, 160, 60), width=5)
            img.save(outdir / f"{Path(path).stem}_{mode}.jpg", quality=85)
        report[name] = entry
        print(f"\n=== {name} ===")
        for mode, e in entry.items():
            print(f"  {mode:9s} 棋子 {e['pieces']:2d} 个 | 棋盘 {e['boards']} 个 conf={e['board_conf']}"
                  f" | 中位置信 {e['conf_med']:.3f} | {e['counts']}")

    (outdir / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), "utf-8")
    print(f"\n报告 -> {outdir / 'report.json'}")


if __name__ == "__main__":
    main()
