"""用已验证的 v5 权重给真实截图打伪标签（YOLO 格式），用于真实域微调。

过滤策略（关键）：
  1. 框中心必须吸附到棋盘格点上，残差 <= RESIDUAL_MAX
  2. 吸附后的格子必须落在 0..9 / 0..8
  3. 同一格只保留置信最高的那个（吃掉重影）
  4. 同类超限的按置信裁剪（吃掉误检）
  也就是说：只有「参与最终盘面」的框才写成标签，棋盘外的玩家头像/按钮误检会被自动丢掉。

用法：
  python pseudo_label.py --model v5.onnx --images "D:/date/*.jpg" --out D:/datasets/xiangqi-yolo
"""
import argparse
import glob
from pathlib import Path

import numpy as np

from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame
from assemble import (LIMITS, RESIDUAL_MAX, anchor_by_rules, fit_axis, resolve, snap)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--out", required=True, help="数据集根目录（images/train, labels/train）")
    ap.add_argument("--conf", type=float, default=0.30)
    ap.add_argument("--split", default="train")
    ap.add_argument("--copy-images", action="store_true")
    args = ap.parse_args()

    import shutil
    import onnxruntime as ort

    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    root = Path(args.out)
    img_dir = root / "images" / args.split
    lbl_dir = root / "labels" / args.split
    img_dir.mkdir(parents=True, exist_ok=True)
    lbl_dir.mkdir(parents=True, exist_ok=True)

    paths = []
    for pat in args.images:
        paths.extend(p for p in glob.glob(pat)
                     if Path(p).suffix.lower() in (".jpg", ".jpeg", ".png", ".bmp"))
    paths = sorted(set(paths))

    for path in paths:
        chw, meta = preprocess(path, "letterbox")
        dets = to_frame(run(sess, chw, args.conf)[0], meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pieces = [d for d in dets if d["cls"] != BOARD_ID]
        if not boards or len(pieces) < 16:
            print(f"{Path(path).name}: 跳过（棋盘 {len(boards)}，棋子 {len(pieces)}）")
            continue

        b = max(boards, key=lambda d: d["c"])
        bw, bh = b["x1"] - b["x0"], b["y1"] - b["y0"]
        xs = [(d["x0"] + d["x1"]) / 2 for d in pieces]
        ys = [(d["y0"] + d["y1"]) / 2 for d in pieces]
        px, ox, rx, mx = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
        py, oy, ry, my = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
        if max(rx, ry) > 0.22 or min(mx, my) < 0.75:
            print(f"{Path(path).name}: 几何不稳 (残差 {rx:.3f}/{ry:.3f}, 集中度 {mx:.2f}/{my:.2f})，跳过")
            continue

        cells, dropped = snap(pieces, px, ox, py, oy)
        ox, oy, kc, kr, score = anchor_by_rules(cells, px, ox, py, oy, b)
        shifted = {(r - kr, c - kc): v for (r, c), v in cells.items()}
        board, log = resolve(shifted)

        # 保留哪些框：落在 board 里的那些（同格已取最高分）
        keep = {(r, c): v for (r, c), v in board.items()}

        from PIL import Image
        W, H = Image.open(path).size
        lines = []
        # 棋盘框
        lines.append(f"{BOARD_ID} {(b['x0'] + b['x1']) / 2 / W:.6f} {(b['y0'] + b['y1']) / 2 / H:.6f} "
                     f"{bw / W:.6f} {bh / H:.6f}")
        used = 0
        for (r, c), (conf, lab, res) in keep.items():
            # 找回原框：该格上置信最高的那个检测
            best_d, best_res = None, 9.0
            for d in pieces:
                cx = (d["x0"] + d["x1"]) / 2
                cy = (d["y0"] + d["y1"]) / 2
                fc = (cx - ox) / px - c
                fr = (cy - oy) / py - r
                e = max(abs(fc), abs(fr))
                if e < 0.35 and LABELS[d["cls"]] == lab and e < best_res:
                    best_d, best_res = d, e
            if best_d is None:
                continue
            cls_id = LABELS.index(lab)
            w = best_d["x1"] - best_d["x0"]
            h = best_d["y1"] - best_d["y0"]
            lines.append(f"{cls_id} {(best_d['x0'] + best_d['x1']) / 2 / W:.6f} "
                         f"{(best_d['y0'] + best_d['y1']) / 2 / H:.6f} {w / W:.6f} {h / H:.6f}")
            used += 1

        stem = Path(path).stem
        (lbl_dir / f"{stem}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        if args.copy_images:
            shutil.copy2(path, img_dir / Path(path).name)
        print(f"{stem}: 写 {used} 个棋子 + 1 个棋盘  (锚定 k=({kc},{kr}), 残差 {rx:.3f}/{ry:.3f})")


if __name__ == "__main__":
    main()
