"""双模型全量对照：自研 xqlink_v5(ONNX) vs 开源 DeepXiangQi(detect-ultra.pt)。

两张皮各自的预处理都按"部署时会怎么跑"来：
  A 走 App 的真机路径（逐级减半 -> letterbox），B 走 ultralytics 原生。
这样比较的是用户实际会看到的结果，而不是强行拉到同一预处理下的假公平。

输出每张图的：棋盘框置信 / 落格数 / 残差 / FEN，最后统计两边的失败率。
"""
import argparse
import glob
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image
from ultralytics import YOLO

sys.path.insert(0, ".")
from assemble import fit_axis, snap          # noqa: E402
from anchor_ab import anchor_by_box          # noqa: E402
from assemble_v3 import clip_to_board        # noqa: E402
from cell_scores import detect               # noqa: E402
from _diag_jj_full import app_letterbox, to_frame   # noqa: E402
from _oss_compare import REMAP, fen_from_cells, to_framed   # noqa: E402

BOARD_ID = 14
CONF_BOARD = 0.25   # App 里 board 类走的是 boardConfThreshold=0.12，这里两套都按自己口径记


def geometry(framed, bd):
    """返回 (落格数, 候选数, 残差x, 残差y, 锚定, FEN)，失败返回 None。"""
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    pieces = [d for d in framed if d["cls"] != BOARD_ID]
    kept, _ = clip_to_board(pieces, bd)
    ux, uy = bw / 8, bh / 9
    sized = [d for d in kept
             if 0.45 <= (d["x1"] - d["x0"]) / ux <= 1.8 and 0.45 <= (d["y1"] - d["y0"]) / uy <= 1.8]
    if len(sized) < 3:
        return None
    xs = [(d["x0"] + d["x1"]) / 2 for d in sized]
    ys = [(d["y0"] + d["y1"]) / 2 for d in sized]
    px, ox, rx, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, ry, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    if rx > 0.20 or ry > 0.20:
        return None
    cells, _ = snap(sized, px, ox, py, oy)
    _, _, kc, kr = anchor_by_box(px, ox, py, oy, bd)
    n_cells = sum(1 for k in cells if 0 <= round(k[0] - kr) < 10 and 0 <= round(k[1] - kc) < 9)
    return n_cells, sum(len(v) for v in cells.values()), rx, ry, (kc, kr), fen_from_cells(cells, kc, kr)


def run_a(sess, path):
    img = Image.open(path).convert("RGB")
    chw, meta, _ = app_letterbox(img)
    framed = to_frame(detect(sess, chw, 0.12), meta)
    boards = [d for d in framed if d["cls"] == BOARD_ID]
    if not boards:
        return "无框", None, None
    bd = max(boards, key=lambda d: d["c"])
    g = geometry(framed, bd)
    return bd["c"], g, bd


def run_b(model, path):
    r = model.predict(path, imgsz=640, conf=0.12, verbose=False)[0]
    framed = to_framed(r, model.names)
    boards = [d for d in framed if d["cls"] == BOARD_ID]
    if not boards:
        return "无框", None, None
    bd = max(boards, key=lambda d: d["c"])
    g = geometry(framed, bd)
    return bd["c"], g, bd


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", nargs="+", default=["D:/date/*.jpg"])
    ap.add_argument("--onnx", default="../app/src/main/assets/detector_xqlink_v5.onnx")
    ap.add_argument("--pt", default="D:/date/_oss/detect-ultra.pt")
    a = ap.parse_args()

    files = []
    for pat in a.images:
        files += sorted(glob.glob(pat))
    sess = ort.InferenceSession(a.onnx, providers=["CPUExecutionProvider"])
    model = YOLO(a.pt)

    print(f"{'图':<13}{'A框conf':>8}{'A落子':>6}  {'B框conf':>8}{'B落子':>6}  {'FEN一致':>7}")
    print("-" * 62)
    stats = {"both": 0, "onlyB": 0, "onlyA": 0, "none": 0, "same_fen": 0, "diff_fen": 0, "n": 0}
    diffs = []
    for f in files:
        name = f.replace("\\", "/").split("/")[-1][:12]
        ca, ga, ba = run_a(sess, f)
        cb, gb, bb = run_b(model, f)
        na = ga[0] if ga else 0
        nb = gb[0] if gb else 0
        sa = f"{ca:.2f}" if isinstance(ca, float) else ca
        sb = f"{cb:.2f}" if isinstance(cb, float) else cb
        mark = "?"
        if ga and gb:
            stats["both"] += 1
            if ga[5] == gb[5]:
                stats["same_fen"] += 1; mark = "是"
            else:
                stats["diff_fen"] += 1; mark = "否"
                diffs.append((name, ga[5], gb[5]))
        elif gb:
            stats["onlyB"] += 1; mark = "-"
        elif ga:
            stats["onlyA"] += 1; mark = "-"
        else:
            stats["none"] += 1; mark = "-"
        stats["n"] += 1
        print(f"{name:<13}{sa:>8}{na:>6}  {sb:>8}{nb:>6}  {mark:>7}")

    print("\n=== 汇总 ===")
    print(f"共 {stats['n']} 张；两边都出 FEN {stats['both']}，仅开源 {stats['onlyB']}，"
          f"仅自研 {stats['onlyA']}，都失败 {stats['none']}")
    print(f"两边都出 FEN 时完全一致 {stats['same_fen']} / 不一致 {stats['diff_fen']}")
    if diffs:
        print("\n不一致清单（前 10 条）：")
        for n, fa, fb in diffs[:10]:
            print(f"  {n}\n   A: {fa}\n   B: {fb}")
