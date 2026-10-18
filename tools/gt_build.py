#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""给真实截图建真值（GT）—— 不靠模型分类，靠「棋子精灵聚类 + 人眼标类」。

思路
----
同一款 App 的同一套皮肤里，棋子精灵图是像素级一致的；而模型在**定位**上很准
（吸附残差 0.01~0.05 格、棋盘框 0.97 置信），错的几乎都是**分类**。

所以把问题拆成两步：
  1. 定位：两个模型（旧 yolov5 15 类 vs 微调 yolov8n）的检出求并集，取并集是为了
     提高召回（任一模型看见就算候选），多出来的假阳性交给第二步当独立簇剔除。
  2. 分类：按格子中心裁出等尺寸小块，做无监督聚类。同一颗棋子的块会落进同一簇，
     每簇只要人眼看一次代表图就能定类。

再做一次**召回审计**：拿每类棋子的簇心去比所有「两个模型都说空」的格子，
同一皮肤的精灵是像素级一致的，真漏了子会以 ~0.99 相似度冒出来。

用法
----
    python gt_build.py --stage extract  --images "D:/date/*.jpg" --cache D:/date/_gt/cache.npz
    python gt_build.py --stage cluster  --cache D:/date/_gt/cache.npz --sheet D:/date/_gt/sheet
    python gt_build.py --stage assemble --cache D:/date/_gt/cache.npz --labels labels.json --truth truth.tsv
"""
import argparse
import glob
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
from anchor_ab import anchor_by_box
from assemble import BOARD_CENTER_COL, BOARD_CENTER_ROW, fit_axis, to_fen
from assemble_v3 import clip_to_board
from fen_lint import check
from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame

PATCH = 48           # 聚类用小块边长（像素）；48 = 24×2，正好下采样成 24×24
GRID = 24            # 墨迹特征网格边长
HALF_CELLS = 0.30    # 裁块半宽，单位=格距。只取字形本体（棋子直径约 0.85 格，但外圈圆环
                     # 每颗都长一样，会把特征压平；字形约占 0.55 格）

MODELS = {
    "v5": "C:/Users/ASUS/Documents/Codex/2026-08-25/wo-z/outputs/xiangqi-assistant/app/src/main/assets/detector.onnx",
    "ft": str(Path(__file__).parent.parent / "app/src/main/assets/detector_yolov8_ft.onnx"),
}


# ---------------------------------------------------------------- 定位

def grid_for(dets_pieces, bd):
    """用两模型并集的框中心拟合共享格距/相位，再用棋盘框中心解整数偏移。"""
    kept, _ = clip_to_board(dets_pieces, bd)
    if len(kept) < 4:
        kept = dets_pieces
    bw, bh = bd["x1"] - bd["x0"], bd["y1"] - bd["y0"]
    xs = [(d["x0"] + d["x1"]) / 2 for d in kept]
    ys = [(d["y0"] + d["y1"]) / 2 for d in kept]
    px, ox, rx, mx = fit_axis(xs, bw / 9 * 0.95, bw / 9 * 1.30)
    py, oy, ry, my = fit_axis(ys, bh / 10 * 0.85, bh / 10 * 1.25)
    ox, oy, kc, kr = anchor_by_box(px, ox, py, oy, bd)
    # anchor_by_box 返回的相位已对应「棋盘列 0 / 行 0」，直接用
    return dict(px=px, py=py, ox=ox, oy=oy, rx=rx, ry=ry, mx=mx, my=my, kc=kc, kr=kr)


def to_cell(d, g, limit=0.30):
    """框中心 -> 格坐标。返回 (row, col, residual) 或 None。"""
    cx, cy = (d["x0"] + d["x1"]) / 2, (d["y0"] + d["y1"]) / 2
    fc, fr = (cx - g["ox"]) / g["px"], (cy - g["oy"]) / g["py"]
    col, row = int(round(fc)), int(round(fr))
    res = max(abs(fc - col), abs(fr - row))
    if res > limit or not (0 <= row <= 9 and 0 <= col <= 8):
        return None
    return row, col, res


def stage_extract(args):
    sess = {k: ort.InferenceSession(v, providers=["CPUExecutionProvider"]) for k, v in MODELS.items()}
    paths = []
    for pat in args.images:
        paths.extend(sorted(glob.glob(pat)))

    meta_img, patches, cells_meta = [], [], []
    for pi, p in enumerate(paths):
        img = Image.open(p).convert("RGB")
        per = {}
        bd = None
        for tag, s in sess.items():
            chw, meta = preprocess(p, "letterbox")
            dets = to_frame(run(s, chw, args.conf)[0], meta)
            boards = [d for d in dets if d["cls"] == BOARD_ID]
            per[tag] = [d for d in dets if d["cls"] != BOARD_ID]
            if boards:
                b = max(boards, key=lambda d: d["c"])
                if bd is None or b["c"] > bd["c"]:
                    bd = b
        if bd is None:
            print("!! 无棋盘框", p)
            continue

        union = per["v5"] + per["ft"]
        g = grid_for(union, bd)

        cand = {}      # (row,col) -> dict
        for tag in per:
            for d in per[tag]:
                r = to_cell(d, g)
                if r is None:
                    continue
                row, col, res = r
                e = cand.setdefault((row, col), dict(models=[], conf={}, cls={}, res={}))
                if tag not in e["models"]:
                    e["models"].append(tag)
                if tag not in e["conf"] or d["c"] > e["conf"][tag]:
                    e["conf"][tag] = d["c"]
                    e["cls"][tag] = LABELS[d["cls"]]
                e["res"][tag] = res

        meta_img.append(dict(image=Path(p).name, path=str(p), grid=g,
                             board=dict(x0=bd["x0"], y0=bd["y0"], x1=bd["x1"], y1=bd["y1"]),
                             n_v5=len(per["v5"]), n_ft=len(per["ft"])))
        for (row, col), e in sorted(cand.items()):
            cx, cy = g["ox"] + col * g["px"], g["oy"] + row * g["py"]
            hw, hh = HALF_CELLS * g["px"], HALF_CELLS * g["py"]
            # 用浮点 box 重采样：不同图的格距略有差异，这样裁出来的块尺度才是恒定的
            patch = img.resize((PATCH, PATCH), Image.LANCZOS,
                               box=(cx - hw, cy - hh, cx + hw, cy + hh))
            patches.append(np.asarray(patch, dtype=np.float32) / 255.0)
            cells_meta.append(dict(img=pi, row=row, col=col,
                                   models=",".join(sorted(e["models"])),
                                   cls_v5=e["cls"].get("v5", ""), cls_ft=e["cls"].get("ft", ""),
                                   conf_v5=e["conf"].get("v5", 0.0), conf_ft=e["conf"].get("ft", 0.0)))
        print(f"[{pi:2d}/{len(paths)}] {Path(p).name[9:23]} 子块 {len(cand):2d} "
              f"px={g['px']:.1f} 残差 {g['rx']:.3f}/{g['ry']:.3f}")

    out = Path(args.cache)
    out.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(out, patches=np.stack(patches), meta=json.dumps(meta_img, ensure_ascii=False),
                        cells=json.dumps(cells_meta, ensure_ascii=False))
    print(f"\n缓存 {len(patches)} 块 -> {out}")


# ---------------------------------------------------------------- 聚类

def load_cache(path):
    z = np.load(path, allow_pickle=False)
    return (z["patches"], json.loads(str(z["meta"])), json.loads(str(z["cells"])))


_DISK = None


def disk_mask(n=PATCH, radius=0.50):
    """圆盘掩码：只保留棋子本体，丢掉四角的棋盘纹理（木纹饱和度很高，会把墨迹特征搞脏）。"""
    global _DISK
    if _DISK is None or _DISK.shape[0] != n:
        yy, xx = np.mgrid[0:n, 0:n]
        c = (n - 1) / 2.0
        _DISK = (((yy - c) ** 2 + (xx - c) ** 2) <= (radius * n) ** 2).astype(np.float32)
    return _DISK


def ink_mask(p):
    """墨迹掩码：偏红（红字）或偏暗（黑字）。底盘奶油色 redness≈0.26/亮度≈0.67，不会误判。"""
    redness = p[..., 0] - (p[..., 1] + p[..., 2]) / 2
    return ((redness > 0.42) | (p.mean(-1) < 0.35)).astype(np.float32) * disk_mask()


def _shift(m, dy, dx):
    """整数平移（补零，不用 roll —— 回绕会把笔画搬到对侧）。"""
    n = m.shape[0]
    out = np.zeros_like(m)
    ys0, ys1 = max(0, dy), min(n, n + dy)
    xs0, xs1 = max(0, dx), min(n, n + dx)
    out[ys0:ys1, xs0:xs1] = m[ys0 - dy:ys1 - dy, xs0 - dx:xs1 - dx]
    return out


def feat(p):
    """小块 -> 单位向量：墨迹掩码按重心对齐后下采样到 24×24。

    这是第三版特征。前两版的教训：
      a) 灰度：JPEG 噪声 + 皮肤亮度 + 亚像素缩放，同字形只有 ~0.9 相似度，1109 块拆成 514 簇；
      b) 饱和度阈值：奶油底盘饱和度就有 0.49，整盘判成墨迹，807 块挤成一簇。
    换成「偏红 or 偏暗」的墨迹掩码后字形干净了，但**不比重心对齐**时同字形只有 <0.75，
    仍然全拆散（138 簇 / 最大簇 51）。棋盘框给的格线相位是像素级的，但裁剪起点会差 1~2 像素，
    对 48×48 的笔画来说这就是致命位移，所以先按墨迹重心做整数平移对齐。
    """
    m = ink_mask(p)
    ys, xs = np.nonzero(m > 0.5)
    if len(ys) > 8:
        c = (PATCH - 1) / 2.0
        m = _shift(m, int(round(c - ys.mean())), int(round(c - xs.mean())))
    b = PATCH // GRID
    v = m.reshape(GRID, b, GRID, b).mean(axis=(1, 3)).ravel()
    n = float(np.linalg.norm(v))
    return v / n if n > 1e-6 else v


def cluster(patches, thr):
    """贪心聚类（质心余弦相似度）。返回 labels(array), centroids, sizes。"""
    X = np.stack([feat(p) for p in patches])
    cents = []
    assign = np.full(len(X), -1, dtype=int)
    for i, x in enumerate(X):
        if cents:
            sims = X[cents] @ x      # 质心与当前块都是单位向量
            j = int(np.argmax(sims))
            if sims[j] >= thr:
                assign[i] = j
                continue
        assign[i] = len(cents)
        cents.append(i)
    # 用已归入的块重算质心后，再做一轮重分配
    cent_v = []
    for j in range(len(cents)):
        v = X[assign == j].mean(axis=0)
        cent_v.append(v / (np.linalg.norm(v) + 1e-9))
    cent_v = np.stack(cent_v)
    assign = np.argmax(X @ cent_v.T, axis=1)
    sizes = np.bincount(assign, minlength=len(cents))
    # 丢掉空簇，重编号
    keep = np.where(sizes > 0)[0]
    remap = {old: new for new, old in enumerate(keep)}
    assign = np.array([remap[a] for a in assign], dtype=int)
    return assign, cent_v[keep], sizes[keep]


def stage_cluster(args):
    patches, meta_img, cells = load_cache(args.cache)
    assign, cent, sizes = cluster(patches, args.thr)
    order = np.argsort(-sizes)
    print(f"{len(patches)} 块 -> {len(sizes)} 簇，最大簇 {sizes.max()}，单例簇 {(sizes == 1).sum()}")

    out = {"threshold": args.thr, "n_patches": int(len(patches)), "clusters": {}}
    for j in order:
        members = [i for i in range(len(assign)) if assign[i] == j]
        out["clusters"][str(int(j))] = dict(
            size=len(members),
            members=[dict(image=meta_img[cells[i]["img"]]["image"], row=cells[i]["row"],
                          col=cells[i]["col"], models=cells[i]["models"],
                          cls_v5=cells[i]["cls_v5"], cls_ft=cells[i]["cls_ft"]) for i in members],
        )
    Path(args.clusters).parent.mkdir(parents=True, exist_ok=True)
    Path(args.clusters).write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")

    # 联系表：每簇一行样本（判断簇是否纯净），簇号用大字号 —— 默认字体太小会读错号
    from PIL import ImageFont
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/arialbd.ttf", 17)
    except OSError:
        font = ImageFont.load_default()
    SHOW = 4
    COLS, ROWS, S = 3, 5, 100
    per = COLS * ROWS
    nsheet = 0
    for s in range((len(order) + per - 1) // per):
        sheet = Image.new("RGB", (COLS * SHOW * (S + 4), ROWS * (S + 26)), (20, 20, 20))
        dr = ImageDraw.Draw(sheet)
        for k, j in enumerate(order[s * per:(s + 1) * per]):
            members = [i for i in range(len(assign)) if assign[i] == j]
            gx, gy = (k % COLS) * SHOW * (S + 4), (k // COLS) * (S + 26)
            votes = {}
            for i in members:
                key = cells[i]["cls_v5"] or cells[i]["cls_ft"] or "-"
                votes[key] = votes.get(key, 0) + 1
            top = ",".join(f"{k2}x{v}" for k2, v in sorted(votes.items(), key=lambda kv: -kv[1])[:2])
            dr.text((gx + 2, gy + 3), f"#{j} n={len(members)} {top}", fill=(255, 235, 60), font=font)
            step = max(1, len(members) // SHOW)
            for t, i in enumerate(members[::step][:SHOW]):
                im = Image.fromarray((patches[i] * 255).astype(np.uint8)).resize((S, S), Image.LANCZOS)
                sheet.paste(im, (gx + t * (S + 4) + 2, gy + 24))
        fn = f"{args.sheet}{nsheet}.png"
        sheet.save(fn)
        nsheet += 1
        print("联系表 ->", fn)


RED_CHARS = set("RNBAKCP")


def is_red_patch(p):
    """这颗子是红字还是黑字：看墨迹像素的平均偏红度。黑字≈0，红字≈0.5。"""
    m = ink_mask(p) > 0.5
    if m.sum() < 20:
        return None
    redness = p[..., 0] - (p[..., 1] + p[..., 2]) / 2
    return float(redness[m].mean()) > 0.25


def stage_assemble(args):
    patches, meta_img, cells = load_cache(args.cache)
    raw = json.loads(Path(args.labels).read_text(encoding="utf-8"))
    lab = {int(k): v for k, v in raw.items() if not k.startswith("_")}

    assign, cent, sizes = cluster(patches, args.thr)
    X = np.stack([feat(p) for p in patches])
    red = [is_red_patch(p) for p in patches]

    # 没标过的簇（理论上不该有）就按最近已标簇心归类，并记下来
    known = [j for j in lab if j < len(cent)]
    unknown = sorted(set(assign.tolist()) - set(known))
    nearest = {}
    if unknown:
        K = np.stack([cent[j] for j in known])
        for j in unknown:
            sims = K @ cent[j]
            nearest[j] = (known[int(np.argmax(sims))], float(sims.max()))

    def resolve(i):
        j = int(assign[i])
        v = lab.get(j, lab.get(nearest.get(j, (-1, 0))[0], "?"))
        if v in (".", "空", "?"):
            return v, j
        if "|" in v:
            r, bk = v.split("|")
            return (r if red[i] else bk), j
        return v, j

    labels = [resolve(i) for i in range(len(patches))]

    # ---- 自动审计：单字标签若与墨色不符，要么我把簇号抄错了，要么该簇本身混了红黑
    warn = []
    for i, (l, j) in enumerate(labels):
        if l in (".", "空", "?"):
            continue
        if red[i] is not None and (l in RED_CHARS) != red[i]:
            warn.append(dict(image=meta_img[cells[i]["img"]]["image"], row=cells[i]["row"],
                             col=cells[i]["col"], cluster=j, label=l,
                             red=red[i], ink=float(ink_mask(patches[i]).mean())))
    print(f"簇 {len(known)} 个已标，未标簇 {len(unknown)} 个；墨色不符 {len(warn)} 处")
    for w in warn[:20]:
        print("  ⚠", w)

    rows_out, detail = [], []
    by_img = {}
    for i, c in enumerate(cells):
        by_img.setdefault(c["img"], []).append(i)

    bad = 0
    for pi, m in enumerate(meta_img):
        grid = {}
        for i in by_img.get(pi, []):
            l, j = labels[i]
            if l in (".", "空", "?", ""):
                continue
            grid[(cells[i]["row"], cells[i]["col"])] = l
            detail.append(dict(image=m["image"], row=cells[i]["row"], col=cells[i]["col"],
                               label=l, cluster=j, red=red[i],
                               ink=round(float(ink_mask(patches[i]).mean()), 3)))
        rows = ["".join(grid.get((r, c), ".") for c in range(9)) for r in range(10)]
        fen = to_fen(rows) + " w"
        errs, _, _ = check(fen)
        bad += bool(errs)
        rows_out.append(dict(image=m["image"], fen=fen, errors=errs, n=len(grid)))
        print(f"{m['image'][9:23]} {len(grid):2d}子 {'OK ' if not errs else 'BAD'} {fen}"
              + ("   × " + "/".join(errs) if errs else ""))

    print(f"\n强约束通过 {len(rows_out) - bad}/{len(rows_out)}")
    Path(args.out).write_text(json.dumps(dict(rows=rows_out, warn=warn, detail=detail),
                                         ensure_ascii=False, indent=1), encoding="utf-8")
    if args.truth:
        lines = ["# JJ象棋 残局闯关 真值（49 张，D:/date/微信图片_*.jpg）",
                 "# 做法：两模型检出求并集定位 -> 棋盘框定整数偏移 -> 棋子小块按墨迹字形聚类 -> 人工逐簇定类",
                 "# 格式：文件名<TAB>FEN（row 0 = 画面顶部，大写=红方）", ""]
        for r in rows_out:
            lines.append(f"{r['image']}\t{r['fen']}")
        Path(args.truth).write_text("\n".join(lines) + "\n", encoding="utf-8")
        print("真值 ->", args.truth)


def stage_audit(args):
    """漏检出审计：两个模型都说「空」的格子，用已标棋子的簇心再比一次。

    同一套皮肤里的棋子精灵是像素级一致的，真漏了子会以很高相似度冒出来。
    这一步只用来「提名单」，不看模型置信度，所以模型系统性漏检的情况也能兜住。
    """
    patches, meta_img, cells = load_cache(args.cache)
    raw = json.loads(Path(args.labels).read_text(encoding="utf-8"))
    lab = {int(k): v for k, v in raw.items() if not k.startswith("_")}
    assign, cent, sizes = cluster(patches, args.thr)

    piece_ids = [j for j in range(len(cent)) if lab.get(j, ".") not in (".", "空", "?")]
    P = np.stack([cent[j] for j in piece_ids])
    Pn = [lab[j] for j in piece_ids]

    cand = {}
    for c in cells:
        cand.setdefault(c["img"], set()).add((c["row"], c["col"]))

    hits = []
    for pi, m in enumerate(meta_img):
        img = Image.open(m["path"]).convert("RGB")
        g = m["grid"]
        hw, hh = HALF_CELLS * g["px"], HALF_CELLS * g["py"]
        have = cand.get(pi, set())
        for r in range(10):
            for c in range(9):
                if (r, c) in have:
                    continue
                cx, cy = g["ox"] + c * g["px"], g["oy"] + r * g["py"]
                patch = img.resize((PATCH, PATCH), Image.LANCZOS,
                                   box=(cx - hw, cy - hh, cx + hw, cy + hh))
                v = feat(np.asarray(patch, dtype=np.float32) / 255.0)
                sims = P @ v
                k = int(np.argmax(sims))
                hits.append(dict(image=m["image"], row=r, col=c, sim=float(sims[k]),
                                 guess=Pn[k], cluster=piece_ids[k]))
        if pi % 10 == 0:
            print(f"  ... {pi}/{len(meta_img)}")

    hits.sort(key=lambda h: -h["sim"])
    n_hi = sum(1 for h in hits if h["sim"] >= args.audit_thr)
    print(f"\n空网格子 {len(hits)} 个，相似度 >= {args.audit_thr} 的 {n_hi} 个（需人工确认）：")
    for h in hits[:40]:
        print(f"  {h['image'][9:23]} ({h['row']},{h['col']}) sim={h['sim']:.3f} 猜 {h['guess']} 簇{h['cluster']}")
    Path(args.audit_out).write_text(json.dumps(hits[:400], ensure_ascii=False, indent=1), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", required=True, choices=["extract", "cluster", "assemble", "audit"])
    ap.add_argument("--images", nargs="+", default=[])
    ap.add_argument("--cache", required=True)
    ap.add_argument("--conf", type=float, default=0.15)
    ap.add_argument("--thr", type=float, default=0.94)
    ap.add_argument("--clusters", default="D:/date/_gt/clusters.json")
    ap.add_argument("--sheet", default="D:/date/_gt/sheet")
    ap.add_argument("--labels", default="D:/date/_gt/labels.json")
    ap.add_argument("--out", default="D:/date/_gt/gt.json")
    ap.add_argument("--truth", default=None)
    ap.add_argument("--audit-thr", type=float, default=0.90)
    ap.add_argument("--audit-out", default="D:/date/_gt/audit.json")
    args = ap.parse_args()
    {"extract": stage_extract, "cluster": stage_cluster,
     "assemble": stage_assemble, "audit": stage_audit}[args.stage](args)


if __name__ == "__main__":
    main()
