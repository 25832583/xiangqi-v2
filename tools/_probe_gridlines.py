"""探针：不做任何训练，只用传统 CV 从截图里找象棋棋盘的 9 竖 10 横格线。

思路（针对屏幕截图，不是拍摄照片）：
  截图里的棋盘格线是严格水平/垂直的细长直线，而 UI 文字、按钮、棋子笔画
  不会连成跨越几百像素的直线。所以用「长核形态学开运算」就能把格线单独留下，
  再按行/列投影找峰，峰的等距性就是棋盘格线的确证。

不需要模型、不需要样本、不依赖棋子检出。
"""
import argparse
import glob
import sys

import cv2
import numpy as np


def find_lines(gray, min_len_ratio=0.30, kern_ratio=0.20):
    """返回竖线 x 坐标列表、横线 y 坐标列表（整数，已去重）。"""
    h, w = gray.shape[:2]
    # 自适应阈值：格线在深色皮肤上是亮的，在浅色皮肤上是暗的，两种都要能提出来
    edges = cv2.Canny(gray, 40, 120)

    kv = max(9, int(w * kern_ratio))
    kh = max(9, int(h * kern_ratio))
    vert = cv2.morphologyEx(edges, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (1, kv)))
    hori = cv2.morphologyEx(edges, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (kh, 1)))

    min_v = int(h * min_len_ratio)
    min_h = int(w * min_len_ratio)

    def centers(mask, axis, min_run):
        # 投影后取连续非零区段的中心
        proj = np.count_nonzero(mask, axis=axis)
        idx = np.flatnonzero(proj >= min_run)
        if idx.size == 0:
            return []
        groups, cur = [], [idx[0]]
        for i in idx[1:]:
            if i - cur[-1] <= 2:
                cur.append(i)
            else:
                groups.append(cur)
                cur = [i]
        groups.append(cur)
        return [int(round(sum(g) / len(g))) for g in groups]

    xs = centers(vert, 0, min_v)
    ys = centers(hori, 1, min_h)
    return xs, ys, vert, hori


def best_grid(coords, total, want, pitch_lo, pitch_hi):
    """在一维坐标里找最像 `want` 条等距线的窗口。

    返回 (起点, 格距, 命中线数, 期望线数)。命中数优先，线数只作同分时的次序，
    因为残局/被 UI 遮住的棋盘确实会少几条线 —— 只要还够拟合，就应该能出来。
    """
    best = (0, 0.0, 0, 0)
    if len(coords) < 3:
        return best
    cs = sorted(coords)
    for i in range(len(cs)):
        for j in range(i + 1, len(cs)):
            span = cs[j] - cs[i]
            for n in range(want - 1, 3, -1):  # n = 间隔数
                p = span / n
                if p < pitch_lo or p > pitch_hi:
                    continue
                pred = [cs[i] + k * p for k in range(n + 1)]
                hit = sum(1 for pv in pred if any(abs(c - pv) <= p * 0.30 for c in cs))
                if (hit, n) > (best[2], best[3]):
                    best = (cs[i], p, hit, n + 1)
    return best


def analyze(path, verbose=True):
    img = cv2.imread(path)
    if img is None:
        return None
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    H, W = gray.shape
    xs, ys, vert, hori = find_lines(gray)

    # 棋盘占画面宽度的大头。格距的合理范围：宽/8 的 0.6~1.6 倍之间都算
    gx = best_grid(xs, W, 9, W / 30.0, W / 5.0)
    gy = best_grid(ys, H, 10, H / 34.0, H / 5.5)

    if verbose:
        print(f"  {path.split('/')[-1][:12]} {W}x{H}")
        print(f"    竖直候选 {len(xs)} 条: {xs[:14]}")
        print(f"    水平候选 {len(ys)} 条: {ys[:14]}")
        print(f"    最佳竖网 起点 {gx[0]} 格距 {gx[1]:.1f} 命中 {gx[2]}/{gx[3]}")
        print(f"    最佳横网 起点 {gy[0]} 格距 {gy[1]:.1f} 命中 {gy[2]}/{gy[3]}")
    return gx, gy, (W, H), (xs, ys)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", nargs="+", required=True)
    a = ap.parse_args()
    for pat in a.images:
        for f in sorted(glob.glob(pat)):
            r = analyze(f)
            if r is None:
                print(f"  {f} 读不出来")
