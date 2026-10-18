"""放大棋盘区域，叠加「拟合格距+相位」直接推出的格网，人工核对几何是否贴合。

要点：周期拟合得到的相位是折叠到 [0, pitch) 的值，不是棋盘左上角。
所以格网必须按足够大的 r/c 范围铺满全图（超出图像的自然裁掉），再按棋盘框裁剪放大。
"""
import argparse
import glob
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame
from assemble_fit import fit_axis


def pick_font(size):
    for p in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--out", default="_check")
    ap.add_argument("--idx", type=int, default=-1)
    args = ap.parse_args()

    import onnxruntime as ort
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)

    paths = []
    for pat in args.images:
        paths.extend(glob.glob(pat))
    paths = sorted(set(paths))
    if args.idx >= 0:
        paths = [paths[args.idx]]

    fnum = pick_font(26)
    for path in paths:
        chw, meta = preprocess(path, "letterbox")
        dets = to_frame(run(sess, chw, args.conf)[0], meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pcs = [d for d in dets if d["cls"] != BOARD_ID]
        if not boards or not pcs:
            print(Path(path).name, "跳过")
            continue
        b = max(boards, key=lambda d: d["c"])
        bw, bh = b["x1"] - b["x0"], b["y1"] - b["y0"]
        xs = [(d["x0"] + d["x1"]) / 2 for d in pcs]
        ys = [(d["y0"] + d["y1"]) / 2 for d in pcs]
        px, ox, rx, mx = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
        py, oy, ry, my = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)

        # 用棋盘框把相位锚定到棋盘内：棋盘中心约在 (行4.5, 列4)
        bcx, bcy = (b["x0"] + b["x1"]) / 2, (b["y0"] + b["y1"]) / 2
        kc = round((bcx - ox) / px - 4.0)
        kr = round((bcy - oy) / py - 4.5)
        ox_b, oy_b = ox + kc * px, oy + kr * py   # 锚定后的「左上角交点」

        img0 = Image.open(path).convert("RGB")
        x0 = int(max(0, ox_b - px * 0.9))
        y0 = int(max(0, oy_b - py * 0.9))
        x1 = int(min(img0.width, ox_b + 8 * px + px * 0.9))
        y1 = int(min(img0.height, oy_b + 9 * py + py * 0.9))
        crop = img0.crop((x0, y0, x1, y1))
        scale = max(1, round(1000 / crop.width))
        crop = crop.resize((crop.width * scale, crop.height * scale), Image.LANCZOS)
        dr = ImageDraw.Draw(crop)

        def tx(v):
            return (v - x0) * scale

        for r in range(-1, 11):
            for c in range(-1, 10):
                gx, gy = tx(ox_b + c * px), tx(oy_b + r * py)
                if not (-20 < gx < crop.width + 20 and -20 < gy < crop.height + 20):
                    continue
                inside = 0 <= r < 10 and 0 <= c < 9
                dr.ellipse([gx - 5, gy - 5, gx + 5, gy + 5],
                           outline=(0, 230, 110) if inside else (255, 200, 0), width=2)
                if c == 0:
                    dr.text((gx - 32, gy - 13), str(r), font=fnum, fill=(0, 240, 255))
                if r == 9:
                    dr.text((gx - 8, gy + 9), str(c), font=fnum, fill=(0, 240, 255))

        # 每颗棋子落到的格 + 位置
        out_of, seen = [], {}
        for d in pcs:
            cx, cy = (d["x0"] + d["x1"]) / 2, (d["y0"] + d["y1"]) / 2
            fc, fr = (cx - ox_b) / px, (cy - oy_b) / py
            col, row = int(round(fc)), int(round(fr))
            res = max(abs(fc - col), abs(fr - row))
            key = (row, col)
            lab = LABELS[d["cls"]]
            if key not in seen or d["c"] > seen[key][0]:
                seen[key] = (d["c"], lab, res)
            if not (0 <= row < 10 and 0 <= col < 9):
                out_of.append((key, lab, round(d["c"], 2)))

        for (row, col), (conf, lab, res) in seen.items():
            if not (0 <= row < 10 and 0 <= col < 9):
                continue
            gx, gy = tx(ox_b + col * px), tx(oy_b + row * py)
            colr = (255, 60, 60) if lab.isupper() else (70, 120, 255)
            dr.text((gx - 11, gy - 12), lab, font=fnum, fill=colr)

        name = Path(path).stem[:10]
        crop.save(outdir / f"{name}_zoom.png")
        print(f"{name}: 检出 {len(pcs)} | 唯一格 {len(seen)} | 越界 {len(out_of)} | "
              f"残差 {rx:.3f}/{ry:.3f} | 锚定偏移 kc={kc} kr={kr}")
        if out_of:
            print(f"   越界项: {out_of[:8]}")


if __name__ == "__main__":
    main()
