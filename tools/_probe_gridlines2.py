"""探针：完全不训练、不依赖模型，只用传统 CV 从截图里找象棋棋盘的格线网格。

两条关键事实（实测自 JJ象棋 1440x3168 截图）：
  1. 格线是**严格水平/垂直**的（HoughLinesP 的角度只有 0.0 和 -90.0），
     所谓「斜」是木纹纹理的视觉错觉。所以不需要透视校正。
  2. 格线会被棋子打断，但打断处两侧仍各留一截。所以不能用「连续长直线」，
     要用「允许间隙的线段 + 一维周期拟合」。

做法：
  Canny -> HoughLinesP（按角度分流）-> 对线段坐标做（格距, 相位）二维扫描，
  目标函数 = 对齐到网格的线段总长度（长度加权，长线更有说服力）。
  最后用「命中格线数 + 等距残差」判断是不是真棋盘。
"""
import argparse
import glob
import sys

import cv2
import numpy as np

# 象棋：9 条竖线、10 条横线
COLS, ROWS = 9, 10


def hough_segments(gray):
    """返回 (竖直线的 x 列表, 竖线长度；水平线的 y 列表, 水平线长度)。"""
    edges = cv2.Canny(gray, 50, 150)
    lines = cv2.HoughLinesP(
        edges, 1, np.pi / 180, threshold=40,
        minLineLength=max(30, gray.shape[1] // 40), maxLineGap=12
    )
    vx, vy = [], []
    if lines is None:
        return vx, vy
    for xa, ya, xb, yb in lines.reshape(-1, 4):
        ang = np.degrees(np.arctan2(yb - ya, xb - xa))
        length = float(np.hypot(xb - xa, yb - ya))
        if abs(abs(ang) - 90.0) <= 4.0:            # 竖线
            vx.append(((xa + xb) / 2.0, length))
        elif abs(ang) <= 4.0:                      # 横线
            vy.append(((ya + yb) / 2.0, length))
    return vx, vy


def fit_period(coords, lengths, lo, hi):
    """(格距, 相位) 扫描：最大化对齐到网格的线段长度总和。

    返回 (pitch, phase, hit_lines, hit_len_ratio)。
    hit_lines 指有多少条等距线上落了线（用线段加权，不只是计数）。
    """
    if len(coords) < 3:
        return None
    cs = np.asarray(coords, dtype=np.float64)
    ls = np.asarray(lengths, dtype=np.float64)
    best = None
    # 格距粗扫 + 相位细扫；相位取落点残差的众数来加速
    for pitch in np.arange(lo, hi, max(0.5, (hi - lo) / 600.0)):
        phase = (cs % pitch)
        # 取加权圆均值作为相位初值
        ang = phase / pitch * 2 * np.pi
        sx, sy = (ls * np.cos(ang)).sum(), (ls * np.sin(ang)).sum()
        if sx == 0 and sy == 0:
            continue
        ph = (np.arctan2(sy, sx) / (2 * np.pi) % 1.0) * pitch
        d = np.abs(((cs - ph) % pitch + pitch * 0.5) % pitch - pitch * 0.5)
        on = d <= pitch * 0.22
        if not on.any():
            continue
        hit_len = ls[on].sum()
        # 命中的「不同格线」条数：把落点量化后去重
        idx = np.round(((cs[on] - ph) % pitch) / pitch).astype(int)
        # 修正：落在 pitch 附近应归 0
        idx = np.round(((cs[on] - ph) + pitch * 0.5) // pitch).astype(int)
        n_lines = len(set(idx.tolist()))
        score = hit_len * (1.0 + 0.02 * n_lines)
        if best is None or score > best[0]:
            best = (score, float(pitch), float(ph), n_lines, float(hit_len / ls.sum()))
    if best is None:
        return None
    return best[1], best[2], best[3], best[4]


def analyze(path, want_cols=COLS, want_rows=ROWS):
    img = cv2.imread(path)
    if img is None:
        return None
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    H, W = gray.shape
    vx, vy = hough_segments(gray)

    # 棋盘占画面 70%~95% 宽。格距的物理范围：宽/(9*1.6) ~ 宽/(9*0.85)
    fx = fit_period([c for c, _ in vx], [l for _, l in vx], W / (want_cols * 1.7), W / (want_cols * 0.75))
    fy = fit_period([c for c, _ in vy], [l for _, l in vy], H / (want_rows * 1.9), H / (want_rows * 0.85))

    out = {"W": W, "H": H, "nv": len(vx), "nh": len(vy), "fx": fx, "fy": fy}
    if fx and fy:
        px, ox, nx, rlx = fx[0], fx[1], fx[2], fx[3]
        py, oy, ny, rly = fy[0], fy[1], fy[2], fy[3]
        out.update(px=px, py=py, ox=ox, oy=oy, nx=nx, ny=ny, rlx=rlx, rly=rly)
        # 棋盘外框：首/末格线各向外半格
        out["box"] = (ox - px * 0.5, oy - py * 0.5,
                      ox + px * (want_cols - 1) + px * 0.5,
                      oy + py * (want_rows - 1) + py * 0.5)
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--quiet", action="store_true")
    a = ap.parse_args()
    rows = []
    for pat in a.images:
        for f in sorted(glob.glob(pat)):
            r = analyze(f)
            name = f.replace("\\", "/").split("/")[-1][:14]
            if r is None:
                print(f"{name}  读不出来")
                continue
            if not a.quiet:
                print(f"\n{name}  {r['W']}x{r['H']}  竖线段 {r['nv']} 横线段 {r['nh']}")
            if r.get("fx") and r.get("fy"):
                print(f"  竖: pitch {r['px']:.1f} 相位 {r['ox']:6.1f} 格线 {r['nx']:2d}/9  长度占比 {r['rlx']:.2f}")
                print(f"  横: pitch {r['py']:.1f} 相位 {r['oy']:6.1f} 格线 {r['ny']:2d}/10 长度占比 {r['rly']:.2f}")
                b = r["box"]
                print(f"  棋盘框 x[{b[0]:.0f},{b[2]:.0f}] y[{b[1]:.0f},{b[3]:.0f}]  "
                      f"{b[2]-b[0]:.0f}x{b[3]-b[1]:.0f}  宽高比 {(b[2]-b[0])/(b[3]-b[1]):.3f}")
            else:
                print(f"  拟合失败 竖={r['fx']} 横={r['fy']}")
            rows.append((name, r))
