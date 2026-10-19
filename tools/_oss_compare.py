"""对照实验：用 GitHub 现成权重（DeepXiangQi detect-ultra.pt）走一遍和 App 相同的几何链路。

目的不是"跑通"，而是回答一个问题：
    换一个现成权重，能不能让棋盘框从 0.2~0.4 回到 0.85+，且落格正确？
所以必须输出 FEN 并和已知答案对比，不能只看检出数量。
"""
import argparse
import glob
import sys

import numpy as np
from ultralytics import YOLO

sys.path.insert(0, ".")
from assemble import LABELS, fit_axis, snap          # noqa: E402
from anchor_ab import anchor_by_box                   # noqa: E402
from assemble_v3 import clip_to_board                 # noqa: E402

BOARD_ID = 14

# DeepXiangQi 类序 -> 用户 Labels.ALL 索引（'n','b','a','k','r','c','p','R','N','A','K','B','C','P','0'）
REMAP = {0: 2, 1: 1, 2: 5, 3: 3, 4: 0, 5: 6, 6: 4,
         7: 9, 8: 11, 9: 12, 10: 10, 11: 8, 12: 13, 13: 7,
         14: BOARD_ID}


def to_framed(result, names):
    """ultralytics 结果 -> [{'cls','c','x0','x1','y0','y1'}]，类别已映射到用户体系。"""
    out = []
    for b in result.boxes:
        src = int(b.cls)
        x0, y0, x1, y1 = [float(v) for v in b.xyxy[0].tolist()]
        out.append({"cls": REMAP[src], "c": float(b.conf),
                    "x0": x0, "y0": y0, "x1": x1, "y1": y1})
    return out


def fen_from_cells(cells, kc, kr):
    grid = [["." for _ in range(9)] for _ in range(10)]
    for (r, c), cands in cells.items():
        rr, cc = int(round(r - kr)), int(round(c - kc))
        if 0 <= rr < 10 and 0 <= cc < 9:
            grid[rr][cc] = sorted(cands, key=lambda t: -t[0])[0][1]
    rows = []
    for row in grid:
        s, empty = "", 0
        for ch in row:
            if ch == ".":
                empty += 1
            else:
                if empty:
                    s += str(empty); empty = 0
                s += ch
        rows.append(s + (str(empty) if empty else ""))
    return "/".join(rows)


def run(framed, bd, tag):
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    pieces = [d for d in framed if d["cls"] != BOARD_ID]
    kept, dropped = clip_to_board(pieces, bd)
    ux, uy = bw / 8, bh / 9
    sized = [d for d in kept
             if 0.45 <= (d["x1"] - d["x0"]) / ux <= 1.8 and 0.45 <= (d["y1"] - d["y0"]) / uy <= 1.8]
    if len(sized) < 3:
        print(f"  [{tag}] 子太少 {len(sized)}")
        return
    xs = [(d["x0"] + d["x1"]) / 2 for d in sized]
    ys = [(d["y0"] + d["y1"]) / 2 for d in sized]
    px, ox, rx, _ = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, ry, _ = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    cells, drop2 = snap(sized, px, ox, py, oy)
    _, _, kc, kr = anchor_by_box(px, ox, py, oy, bd)
    n = sum(len(v) for v in cells.values())
    print(f"  [{tag}] 检出 {len(pieces)} 裁框后 {len(kept)} 尺寸合格 {len(sized)} "
          f"落格 {n}  残差 {rx:.3f}/{ry:.3f}  锚定 ({kc},{kr})  pitch {px:.1f}/{py:.1f}")
    print(f"  [{tag}] FEN {fen_from_cells(cells, kc, kr)}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="D:/date/_oss/detect-ultra.pt")
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    a = ap.parse_args()

    model = YOLO(a.model)
    names = model.names
    files = []
    for pat in a.images:
        files += sorted(glob.glob(pat))
    for f in files:
        r = model.predict(f, imgsz=640, conf=a.conf, verbose=False)[0]
        framed = to_framed(r, names)
        boards = [d for d in framed if d["cls"] == BOARD_ID]
        name = f.replace("\\", "/").split("/")[-1][:12]
        print(f"\n{name}")
        if not boards:
            print(f"  没有棋盘框（原始检出 {len(framed)}）")
            continue
        bd = max(boards, key=lambda d: d["c"])
        w, h = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
        print(f"  棋盘框 conf {bd['c']:.3f}  {w:.0f}x{h:.0f} 宽高比 {w/h:.3f}")
        bd = {"x0": bd["x0"], "y0": bd["y0"], "x1": bd["x1"], "y1": bd["y1"], "c": bd["c"], "cls": BOARD_ID}
        run(framed, bd, "oss")
