"""消融：同一个权重（旧 xqlink v5），分别换上旧 App 的两个做法，看掉多少。

回答的问题：用户实测「旧版识别不准」，但现在测 xqlink 却是 49/49。
差别只可能在链路，所以把旧 App 的两处做法单独还原出来量化：

  1. 预处理 stretch  vs  letterbox
     旧 `ImageOps.fitSquare` = `Bitmap.createScaledBitmap(bmp, 640, 640, true)`，
     把裁片硬拉成正方形。裁片约 9 格宽 × 10 格高 → 纵向被压缩 10%，
     棋子变椭圆，字形也跟着变形。

  2. 网格 bins  vs  fit
     旧 `BinnedGridMapper` 用写死的 columnSpan=9 / rowSpan=11 / phase 0/0.5 分箱，
     不做任何拟合。它的类注释自己写了「换 App／换皮肤就失准」，
     `SamplingGrid` 里还记着「假定值偏小 ⇒ 标号按线性漂移，裁片下三分之一整体偏一格」。

四种组合各跑一遍 49 张残局，按逐格真值打分。

用法：
    python old_pipeline_ab.py --model ../app/src/main/assets/detector_xqlink_v5.onnx \
        --truth ../testsets/jj-endgame/truth.tsv --images "../testsets/jj-endgame/img/*.jpg"
"""
import argparse
import glob
import math
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

from anchor_ab import anchor_by_box
from assemble import BOARD_CENTER_COL, BOARD_CENTER_ROW, fit_axis, resolve, snap
from fen_lint import check
from PIL import Image

from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame

# ---- 旧 App 的写死配置（config/RecognitionConfig.kt 的默认值）----
OLD_COL_SPAN = 9.0      # GridConfig.columnSpan
OLD_ROW_SPAN = 11.0     # GridConfig.rowSpan
OLD_COL_PHASE = 0.0     # GridConfig.columnPhaseOffset
OLD_ROW_PHASE = 0.5     # GridConfig.rowPhaseOffset
CROP_MARGIN_CELLS = 0.5  # cropRect：棋盘框四周各扩半格再拉伸到 640


def old_bins(pieces, bd):
    """还原旧 BinnedGridMapper：对「框外扩半格再拉伸成正方形」的裁片做写死分箱。

    返回 {(行, 列): (conf, label)}，坐标已是屏幕方向（row 0 = 画面顶部）。
    """
    px = (bd["x1"] - bd["x0"]) / 8.0          # 一个格距的像素宽
    py = (bd["y1"] - bd["y0"]) / 9.0
    cx0 = bd["x0"] - CROP_MARGIN_CELLS * px
    cy0 = bd["y0"] - CROP_MARGIN_CELLS * py
    cw = (bd["x1"] - bd["x0"]) + 2 * CROP_MARGIN_CELLS * px
    ch = (bd["y1"] - bd["y0"]) + 2 * CROP_MARGIN_CELLS * py

    out = {}
    for d in pieces:
        cx = (d["x0"] + d["x1"]) / 2.0
        cy = (d["y0"] + d["y1"]) / 2.0
        xn = (cx - cx0) / cw                 # 裁片内归一化坐标
        yn = (cy - cy0) / ch
        if not (0.0 <= xn <= 1.0 and 0.0 <= yn <= 1.0):
            continue                          # 出裁片 = 旧代码的 map 返回 null
        col = math.floor(xn * OLD_COL_SPAN - OLD_COL_PHASE)
        row = math.floor(yn * OLD_ROW_SPAN - OLD_ROW_PHASE)
        if not (0 <= col < 9 and 0 <= row < 10):
            continue
        out[(row, col)] = LABELS[d["cls"]]
    return out


def new_fit(pieces, bd):
    """现链路：裁到棋盘框 -> 周期拟合格距相位 -> 框中心定整数偏移 -> 吸附。"""
    px0 = (bd["x1"] - bd["x0"]) / 8.0
    py0 = (bd["y1"] - bd["y0"]) / 9.0
    x_lo, x_hi = bd["x0"] - 0.55 * px0, bd["x1"] + 0.55 * px0
    y_lo, y_hi = bd["y0"] - 0.55 * py0, bd["y1"] + 0.55 * py0
    kept = []
    for d in pieces:
        cx, cy = (d["x0"] + d["x1"]) / 2.0, (d["y0"] + d["y1"]) / 2.0
        if x_lo <= cx <= x_hi and y_lo <= cy <= y_hi:
            kept.append(d)
    if not kept:
        return {}
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    xs = [(d["x0"] + d["x1"]) / 2.0 for d in kept]
    ys = [(d["y0"] + d["y1"]) / 2.0 for d in kept]
    px, ox, _, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, _, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    cells, _ = snap(kept, px, ox, py, oy)
    ox, oy, kc, kr = anchor_by_box(px, ox, py, oy, bd)
    board, _ = resolve({(r - kr, c - kc): v for (r, c), v in cells.items()})
    return {k: v[1] for k, v in board.items()}


def old_app(sess, path):
    """完整还原旧 App 的链路，一步不改：

    1. 棋盘框：整帧 `createScaledBitmap(frame, 640, 640)` —— 1280x2816 硬拉成正方形，
       纵向压 2.2 倍。模型在这种画面上找棋盘框。
    2. 裁片：框四周各扩半格，再 `createScaledBitmap(crop, 640, 640)`，
       裁片本身约 9 格宽 x 10 格高，纵向再压 ~10%。
    3. 检子：在拉伸后的裁片上跑同一个权重。
    4. 上盘：`BinnedGridMapper` 写死 columnSpan=9 / rowSpan=11 / phase 0,0.5。
    """
    img = Image.open(path).convert("RGB")
    W, H = img.size

    chw, meta = preprocess(path, "stretch")          # 第 1 步：整帧拉伸
    dets = to_frame(run(sess, chw, 0.10)[0], meta)
    boards = [d for d in dets if d["cls"] == BOARD_ID]
    if not boards:
        return {}
    bd = max(boards, key=lambda d: d["c"])

    px = (bd["x1"] - bd["x0"]) / 8.0                 # 第 2 步：框外扩半格
    py = (bd["y1"] - bd["y0"]) / 9.0
    x0 = max(0.0, bd["x0"] - CROP_MARGIN_CELLS * px)
    y0 = max(0.0, bd["y0"] - CROP_MARGIN_CELLS * py)
    x1 = min(float(W), bd["x1"] + CROP_MARGIN_CELLS * px)
    y1 = min(float(H), bd["y1"] + CROP_MARGIN_CELLS * py)
    if x1 - x0 < 8 or y1 - y0 < 8:
        return {}
    crop = img.crop((int(x0), int(y0), int(x1), int(y1))).resize((640, 640), Image.BILINEAR)
    arr = np.asarray(crop, dtype=np.float32) / 255.0
    # to_frame 是 (x - pad) / scale，scale 必须写成「模型像素 / 裁片像素」。
    # 一开始写成 (x1-x0)/640（方向反了），所有棋子被压进裁片左上角，整批全废 —— 见文件头注记。
    cmeta = {"scale": (640.0 / (x1 - x0), 640.0 / (y1 - y0)), "pad": (0, 0),
             "size": (x1 - x0, y1 - y0)}
    pd = to_frame(run(sess, arr.transpose(2, 0, 1)[None], 0.25)[0], cmeta)
    pieces = [{"x0": d["x0"] + x0, "y0": d["y0"] + y0,
               "x1": d["x1"] + x0, "y1": d["y1"] + y0,
               "c": d["c"], "cls": d["cls"]}
              for d in pd if d["cls"] != BOARD_ID]

    return old_bins(pieces, bd)                      # 第 4 步：写死分箱


def load_truth(path):
    out = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        name, fen = line.split("\t")
        d = {}
        for r, row in enumerate(fen.split()[0].split("/")):
            c = 0
            for ch in row:
                if ch.isdigit():
                    c += int(ch)
                else:
                    d[(r, c)] = ch
                    c += 1
        out[Path(name).name] = d
    return out


def score(pred, truth):
    """逐格统计。返回 (正确格, 交集格, 漏, 多, 整盘全对)。"""
    tp = fp = fn = ok = 0
    exact = 0
    for name, t in truth.items():
        p = pred.get(name, {})
        keys = set(t) | set(p)
        wrong = 0
        for k in keys:
            a, b = t.get(k), p.get(k)
            if a is None and b is None:
                continue
            if a is None:
                fp += 1
                wrong += 1
            elif b is None:
                fn += 1
                wrong += 1
            else:
                tp += 1
                if a == b:
                    ok += 1
                else:
                    wrong += 1
        exact += (wrong == 0)
    return ok, tp + fp + fn, fn, fp, exact


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--truth", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    args = ap.parse_args()

    paths = []
    for pat in args.images:
        paths.extend(sorted(glob.glob(pat)))
    truth = load_truth(args.truth)
    paths = [p for p in paths if Path(p).name in truth]
    print(f"{len(paths)} 张图，真值 {sum(len(v) for v in truth.values())} 颗子\n")

    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])

    def one(prep, grid):
        pred = {}
        for p in paths:
            chw, meta = preprocess(p, prep)
            dets = to_frame(run(sess, chw, args.conf)[0], meta)
            boards = [d for d in dets if d["cls"] == BOARD_ID]
            if not boards:
                continue
            bd = max(boards, key=lambda d: d["c"])
            pieces = [d for d in dets if d["cls"] != BOARD_ID]
            fn = old_bins if grid == "bins" else new_fit
            pred[Path(p).name] = fn(pieces, bd)
        return score(pred, truth)

    plans = [
        ("旧 App 原样（拉伸+写死分箱）", lambda: score(
            {Path(p).name: old_app(sess, p) for p in paths}, truth)),
        ("整帧拉伸 + 拟合（对照，旧代码无此路径）", lambda: one("stretch", "fit")),
        ("letterbox + 写死分箱（对照，两者混搭）", lambda: one("letterbox", "bins")),
        ("现链路（letterbox+拟合）", lambda: one("letterbox", "fit")),
    ]
    print(f"{'链路':<30}{'逐颗正确':>18}{'漏':>6}{'多':>6}{'整盘全对':>10}")
    print("-" * 72)
    results = []
    for title, fn in plans:
        s = fn()
        results.append(s)
        print(f"{title:<28}{s[0]:5d}/{s[1]:<6d}={s[0]/max(1,s[1])*100:5.1f}%"
              f"{s[2]:6d}{s[3]:6d}{s[4]:6d}/{len(paths)}")

    base = results[3][0]
    print("\n相对现链路的损失：")
    for (title, _), s in zip(plans[:3], results[:3]):
        print(f"  {title:<30}-{base - s[0]:5d} 颗 (-{(base - s[0]) / base * 100:4.1f}%)")


if __name__ == "__main__":
    main()
