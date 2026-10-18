#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""盘面装配（v2 参考实现）——产线 Kotlin 依此实现。

链路：letterbox -> 一次检测（15 类） -> 棋子框中心一维周期拟合出真实格距与相位
      -> 用「棋盘框中心 == (列4, 行4.5)」把折叠相位锚定到棋盘左上角
      -> 交点上吸附 -> 冲突消解 -> FEN

为什么不用 min 平移（旧版做法）：
    周期拟合的相位是模格距的等价类，必须先锚定才能得到行列索引。
    旧版用「检出格坐标的最小行/列」当 0 位移，一旦最外列（或最上行）没有棋子
    （中局/残局极常见，例如红方 9 路兵已过河），整个盘面就整体错位一整列/行。
    实测 19 张真实对局截图：棋盘框中心稳定落在 (列 4.03±0.01, 行 4.47±0.04)，
    用它可以无歧义锚定，且与棋子分布无关。

用法：
    python assemble.py --model detector.onnx --images "D:/date/*.jpg"
    python assemble.py --model detector.onnx --images imgs/*.jpg --truth truth.tsv
"""
import argparse
import glob
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame

LIMITS = {"r": 2, "n": 2, "c": 2, "a": 2, "b": 2, "k": 1, "p": 5}
RESIDUAL_MAX = 0.30          # 吸附残差上限（格），超过认为不是格点
BOARD_CENTER_COL = 4.0       # 棋盘框中心对应的列索引（9 列 0..8 的中位）
BOARD_CENTER_ROW = 4.5       # 棋盘框中心对应的行索引（10 行 0..9 的中位）


def fit_axis(vals, p_lo, p_hi, step=0.05):
    """一维周期拟合 -> (pitch, phase, rms, 集中度)。phase 折叠在 [0, pitch)。"""
    vals = np.asarray(vals, dtype=np.float64)
    best = None
    for p in np.arange(p_lo, p_hi, step):
        m = np.exp(2j * np.pi * vals / p).mean()
        if abs(m) < 1e-6:
            continue
        phase = np.angle(m) / (2 * np.pi) * p
        r = (vals - phase) / p
        r = r - np.round(r)
        res = float(np.sqrt((r ** 2).mean()))
        if best is None or res < best[2]:
            best = (float(p), float(phase), res, float(abs(m)))
    return best


def anchor_phase(px, ox, py, oy, board):
    """把折叠相位锚定到棋盘左上角交点，返回 (左上角 x, 左上角 y, kc, kr)。

    仅用棋盘框几何，作为规则搜索的弱先验 / 回退。
    实测 19 张真实对局：框中心稳定在 (列 4.03±0.01, 行 4.47±0.04)；
    但个别分辨率下会偏到 4.4，直接取整会错一整行 —— 所以主锚定改用棋盘规则（见 anchor_by_rules）。
    """
    bw_cx = (board["x0"] + board["x1"]) / 2.0
    bh_cy = (board["y0"] + board["y1"]) / 2.0
    kc = round((bw_cx - ox) / px - BOARD_CENTER_COL)
    kr = round((bh_cy - oy) / py - BOARD_CENTER_ROW)
    return ox + kc * px, oy + kr * py, kc, kr


def _legality(cands, kc, kr):
    """给某个 (kc,kr) 偏移打分，分越低越合法。

    只用象棋规则，不依赖皮肤/分辨率：
      1. 将/帥 必在列 4；必在行 0 或 9；两者分处两端
      2. 仕/士、相/象 必与己方将同半区（行的上半或下半）
      3. 兵/卒 必在己方将一侧，最多跨河一格
      4. 每类子数不超限
    """
    ks, placed = {}, 0
    for (r, c), lst in cands.items():
        if not (0 <= r - kr < 10 and 0 <= c - kc < 9):
            continue
        placed += 1
        lab = max(lst, key=lambda t: t[0])[1]
        if lab in "kK":
            ks[lab] = (r - kr, c - kc)

    score = 0.0
    score += (len(cands) - placed) * 8        # 越界/误检越多越可疑

    for lab, (r, c) in ks.items():
        if c != 4:
            score += 60
        if r not in (0, 9):
            score += 60
    if len(ks) == 2:
        rows = sorted(v[0] for v in ks.values())
        if rows[1] - rows[0] != 9:
            score += 80

    # 士象与己方将同半区
    for (r, c), lst in cands.items():
        rr, cc = r - kr, c - kc
        if not (0 <= rr < 10 and 0 <= cc < 9):
            continue
        lab = max(lst, key=lambda t: t[0])[1]
        low = lab.lower()
        if low in "ab":
            key = "k" if lab.islower() else "K"
            if key in ks:
                if (0 if rr <= 4 else 1) != (0 if ks[key][0] <= 4 else 1):
                    score += 40
        if low == "p":
            key = "k" if lab.islower() else "K"
            if key in ks:
                kr_row = ks[key][0]
                # 兵卒朝对方走：与己方将同半区，或跨过河界一格
                forward = 1 if kr_row <= 4 else -1
                if (rr - kr_row) * forward < -1:
                    score += 20

    # 超限
    counts = {}
    for (r, c), lst in cands.items():
        rr, cc = r - kr, c - kc
        if not (0 <= rr < 10 and 0 <= cc < 9):
            continue
        lab = max(lst, key=lambda t: t[0])[1]
        counts[lab] = counts.get(lab, 0) + 1
    for key, n in counts.items():
        lim = LIMITS[key.lower()]
        if n > lim:
            score += (n - lim) * 15

    return score


def anchor_by_rules(cands_unshifted, px, ox, py, oy, board):
    """在候选偏移里搜一个让盘面最合法（且与棋盘框几何最一致）的锚定。"""
    fc = [c for (r, c) in cands_unshifted]
    fr = [r for (r, c) in cands_unshifted]
    bw_cx = (board["x0"] + board["x1"]) / 2.0
    bh_cy = (board["y0"] + board["y1"]) / 2.0
    geo_c = (bw_cx - ox) / px - BOARD_CENTER_COL     # 期望的 kc
    geo_r = (bh_cy - oy) / py - BOARD_CENTER_ROW     # 期望的 kr

    best = None
    for kc in range(min(fc) - 8, max(fc) + 1):
        if not (geo_c - 2.2 <= kc <= geo_c + 2.2):
            continue
        for kr in range(min(fr) - 9, max(fr) + 1):
            if not (geo_r - 2.2 <= kr <= geo_r + 2.2):
                continue
            s = _legality(cands_unshifted, kc, kr)
            s += 1.5 * abs(kc - geo_c) + 1.5 * abs(kr - geo_r)   # 几何一致性
            if best is None or s < best[0]:
                best = (s, kc, kr)
    if best is None:
        return (*anchor_phase(px, ox, py, oy, board), 0.0)
    s, kc, kr = best
    return ox + kc * px, oy + kr * py, kc, kr, s


def snap(pieces, px, ox, py, oy, limit=RESIDUAL_MAX):
    """框中心吸附到最近交点。返回 {(row,col): [(conf, label, residual), ...]} 与丢弃计数。"""
    cells, dropped = {}, 0
    for d in pieces:
        cx = (d["x0"] + d["x1"]) / 2.0
        cy = (d["y0"] + d["y1"]) / 2.0
        fc = (cx - ox) / px
        fr = (cy - oy) / py
        col, row = int(round(fc)), int(round(fr))
        res = max(abs(fc - col), abs(fr - row))
        if res > limit:
            dropped += 1
            continue
        cells.setdefault((row, col), []).append((d["c"], LABELS[d["cls"]], res))
    return cells, dropped


def resolve(cells):
    """同格取置信最高；越界丢弃；同类超限按置信裁剪。"""
    board, log = {}, []
    for cell, cands in cells.items():
        if not (0 <= cell[0] < 10 and 0 <= cell[1] < 9):
            continue                      # 棋盘外的误检（玩家头像/按钮）
        cands = sorted(cands, key=lambda t: -t[0])
        board[cell] = cands[0]
        if len(cands) > 1:
            log.append(f"同格冲突 {cell}: " + ", ".join(f"{l}({c:.2f}/r{r:.2f})" for c, l, r in cands))

    for key in "rnbakcpRNBAKCP":
        letter = key.lower()
        hits = [(cell, v) for cell, v in board.items() if v[1] == key]
        if len(hits) > LIMITS[letter]:
            hits.sort(key=lambda kv: -kv[1][0])
            for cell, v in hits[LIMITS[letter]:]:
                log.append(f"超限 {key} {len(hits)}>{LIMITS[letter]}，丢弃 {cell} conf={v[0]:.2f}")
                del board[cell]
    return board, log


def render(board):
    return ["".join(board[(r, c)][1] if (r, c) in board else "." for c in range(9)) for r in range(10)]


def to_fen(rows):
    out = []
    for line in rows:
        s, n = "", 0
        for ch in line:
            if ch == ".":
                n += 1
            else:
                s += (str(n) if n else "") + ch
                n = 0
        if n:
            s += str(n)
        out.append(s)
    return "/".join(out) + " w"


def flip180(rows):
    return [line[::-1] for line in reversed(rows)]


def side_to_move(rows):
    """谁是下方：下方那侧的子用小写。返回 'w'(红走) 或 'b'(黑走) 的提示行。

    规则：黑方 5 个卒初始在行 3，红方 5 个兵初始在行 6。
    中局时看行 0/9 的底线上是哪些子：帅在行 9、将在行 0 视为「黑在上」。
    """
    red_k_row = [r for r, line in enumerate(rows) if "K" in line]
    blk_k_row = [r for r, line in enumerate(rows) if "k" in line]
    if red_k_row and blk_k_row:
        if red_k_row[0] > blk_k_row[0]:
            return True   # 红在下（黑在上）——标准的黑先
    return None


def pick_font(size):
    for p in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


def viz(path, board, px, ox, py, oy, pcs, outdir):
    img = Image.open(path).convert("RGB")
    x0 = int(max(0, ox - px * 0.85))
    y0 = int(max(0, oy - py * 0.85))
    x1 = int(min(img.width, ox + 8 * px + px * 0.85))
    y1 = int(min(img.height, oy + 9 * py + py * 0.85))
    crop = img.crop((x0, y0, x1, y1))
    sc = max(1, round(1080 / crop.width))
    crop = crop.resize((crop.width * sc, crop.height * sc), Image.LANCZOS)
    dr = ImageDraw.Draw(crop)
    f = pick_font(30)
    fn = pick_font(16)

    def tx(v):
        return (v - x0) * sc

    # 格线（比格点更容易肉眼判断是否贴合）
    for r in range(10):
        gy = tx(oy + r * py)
        dr.line([tx(ox), gy, tx(ox + 8 * px), gy], fill=(0, 200, 90), width=1)
    for c in range(9):
        gx = tx(ox + c * px)
        dr.line([gx, tx(oy), gx, tx(oy + 9 * py)], fill=(0, 200, 90), width=1)

    # 每颗棋子的检出框
    for d in pcs:
        if LABELS[d["cls"]] == "0":
            continue
        col = (255, 40, 40) if LABELS[d["cls"]].isupper() else (40, 90, 255)
        dr.rectangle([tx(d["x0"]), tx(d["y0"]), tx(d["x1"]), tx(d["y1"])], outline=col, width=2)

    # 组装结果：在格子上写识别类别
    for (r, c), (conf, lab, res) in board.items():
        gx, gy = tx(ox + c * px), tx(oy + r * py)
        dr.text((gx - 9, gy - 12), lab, font=f, fill=(255, 255, 0))
        dr.text((gx - 16, gy + 12), f"{conf:.2f}", font=fn, fill=(0, 255, 255))

    outdir.mkdir(parents=True, exist_ok=True)
    out = outdir / f"{Path(path).stem[:10]}_asm.png"
    crop.save(out)
    return out


def parse_fen(fen):
    """FEN 前 10 段 -> 10 行盘面字符串。"""
    rows = []
    for part in fen.strip().split("/")[:10]:
        line, n = "", 0
        for ch in part:
            if ch.isdigit():
                n = n * 10 + int(ch)
                line += "." * n
                n = 0
            else:
                line += ch
        rows.append(line.ljust(9, ".")[:9])
    return rows


def load_truth(path):
    """TSV：文件名<TAB>FEN（也接受用 | 分隔的 10 行）。"""
    truth = {}
    text = Path(path).read_text(encoding="utf-8-sig")
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        key, val = parts[0].strip(), parts[1].strip()
        if "/" in val:
            truth[key] = parse_fen(val)
        else:
            truth[key] = val.split("|")
    return truth


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--truth", default=None, help="TSV: 文件名<TAB>10行盘面")
    ap.add_argument("--out", default=None, help="可视化输出目录")
    ap.add_argument("--json", default=None, help="结果汇总 json")
    args = ap.parse_args()

    import onnxruntime as ort
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])

    truth = load_truth(args.truth) if args.truth else {}

    paths = []
    for pat in args.images:
        paths.extend(p for p in glob.glob(pat)
                     if Path(p).suffix.lower() in (".jpg", ".jpeg", ".png", ".bmp", ".webp"))
    paths = sorted(set(paths))
    outdir = Path(args.out) if args.out else None

    summary, tot_cells, tot_bad = [], 0, 0
    for path in paths:
        name = Path(path).name
        chw, meta = preprocess(path, "letterbox")
        dets = to_frame(run(sess, chw, args.conf)[0], meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pieces = [d for d in dets if d["cls"] != BOARD_ID]
        if not boards or not pieces:
            print(f"{name}: 检出不足")
            continue

        board_det = max(boards, key=lambda d: d["c"])
        bw, bh = board_det["x1"] - board_det["x0"], board_det["y1"] - board_det["y0"]
        xs = [(d["x0"] + d["x1"]) / 2 for d in pieces]
        ys = [(d["y0"] + d["y1"]) / 2 for d in pieces]
        px, ox, rx, mx = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
        py, oy, ry, my = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
        # 吸附（此时行列为「相对折叠相位」的整数索引，未锚定）
        cells, dropped = snap(pieces, px, ox, py, oy)
        ox, oy, kc, kr, anchor_score = anchor_by_rules(cells, px, ox, py, oy, board_det)
        # 锚定后重新裁剪掉越界项
        cells = {(r - kr, c - kc): v for (r, c), v in cells.items()}
        board, log = resolve(cells)
        rows = render(board)

        def cell_diff(a, b):
            return sum(1 for r in range(10) for c in range(9)
                       if (a[r][c] if r < len(a) and c < len(a[r]) else ".") !=
                          (b[r][c] if r < len(b) and c < len(b[r]) else "."))

        truth_rows = truth.get(name)
        if truth_rows:
            n0, n1 = cell_diff(rows, truth_rows), cell_diff(flip180(rows), truth_rows)
            flipped = n1 < n0
            if flipped:
                rows = flip180(rows)
            nbad = min(n0, n1)
            tot_cells += 90
            tot_bad += nbad
            verdict = f"真值差异 {nbad}/90" + ("（已翻转180°比对）" if flipped else "")
        else:
            nbad, verdict = None, "无真值（仅看落盘与几何）"

        print(f"\n=== {name} ===")
        print(f"  检出 {len(pieces)} | 落盘 {len(board)} | 残差 {rx:.3f}/{ry:.3f} "
              f"| 集中度 {mx:.2f}/{my:.2f} | 锚定 k=({kc},{kr}) | 吸附丢弃 {dropped}")
        print(f"  格距 {px:.1f} x {py:.1f} | 框/格距 {bw / px:.2f} x {bh / py:.2f}")
        for line in rows:
            print("    " + line)
        print(f"  FEN: {to_fen(rows)}")
        print(f"  {verdict}")
        for line in log:
            print(f"    · {line}")

        if outdir:
            viz(path, board, px, ox, py, oy, pieces, outdir)

        summary.append({
            "image": name, "detected": len(pieces), "placed": len(board),
            "dropped_snap": dropped, "diff": nbad,
            "pitch": [round(px, 2), round(py, 2)],
            "residual": [round(rx, 4), round(ry, 4)],
            "concentration": [round(mx, 3), round(my, 3)],
            "anchor": [kc, kr], "fen": to_fen(rows),
        })

    print()
    if tot_cells:
        print(f"===== 真值对账：{tot_bad} 格错 / {tot_cells} 格 "
              f"({100 * (1 - tot_bad / tot_cells):.1f}% 逐格正确) =====")
    if args.json:
        Path(args.json).write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"汇总 -> {args.json}")


if __name__ == "__main__":
    main()
