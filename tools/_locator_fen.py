"""用兜底框替换自研框，验证 FEN 不变 —— 这是「懒兜底不会退化」的唯一证据。

前面的 `_locator_ab.py` 只看框的 IoU，而框只是中间量：真正要证明的是
**换成兜底框之后落格结果一模一样**。IoU 0.77 不代表盘面错，也不代表不错。

所以这里做三件事：
  1. 自研框合格的图：用兜底框跑一遍完整几何链路，FEN 必须与自研完全相同
  2. 自研框不合格的那张：兜底框必须给出一个合法的 32 子盘面
  3. 逐张统计兜底框与自研框的尺寸比，看它是否会撞上 App 里的 sizePlausible 窗口

注意棋子置信门槛必须是 0.25（App 的 confidenceThreshold）。用 0.12 会多出一堆
弱假框，再配上偏大的兜底框（裁剪窗口跟着变宽），拟合残差会被这些框顶爆 ——
那是脚本自己的口径错，不是产线行为。
"""
import glob
import sys

import onnxruntime as ort
from PIL import Image

sys.path.insert(0, ".")
from _locator_ab import locate, self_board_box          # noqa: E402
from _oss_ab import geometry                            # noqa: E402
from cell_scores import detect                          # noqa: E402
from _diag_jj_full import app_letterbox, to_frame       # noqa: E402

BOARD_ID = 14
PIECE_CONF = 0.25   # App 的 confidenceThreshold；棋盘类走 0.12


def bits(s):
    return sum(1 for ch in s if ch.isalpha())


def median(values):
    v = sorted(values)
    n = len(v)
    if n == 0:
        return 0.0
    return v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2


NORMALIZE = "--raw" not in sys.argv


def as_det(t):
    return {"cls": BOARD_ID, "c": t[4], "x0": t[0], "y0": t[1], "x1": t[2], "y1": t[3]}


def normalize(t, piece_w, piece_h):
    """把「棋盘外框」归一成「格线框」，绕中心缩放。

    兜底模型的框含木纹边框，实测比主模型的格线框大 19%（75 张：宽 9.34~9.96 倍子宽、
    高 10.38~11.63 倍子高，而格线框恒为 8 x 9 倍）。不归一化的话，App 里那条
    sizePlausible 会把 57/75 张真框判成「不像棋盘」。
    """
    x0, y0, x1, y1 = t[:4]
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    sx = 8 * piece_w / (x1 - x0) if piece_w > 0 else 1.0
    sy = 9 * piece_h / (y1 - y0) if piece_h > 0 else 1.0
    sx, sy = max(0.78, min(1.0, sx)), max(0.78, min(1.0, sy))
    w, h = (x1 - x0) * sx, (y1 - y0) * sy
    return (cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, t[4])


if __name__ == "__main__":
    onnx_a = sys.argv[1] if len(sys.argv) > 1 else "../app/src/main/assets/detector_xqlink_v5.onnx"
    onnx_b = sys.argv[2] if len(sys.argv) > 2 else "D:/date/_oss/detect-ultra-640.onnx"
    files = sorted(glob.glob("D:/date/*.jpg"))
    sess_a = ort.InferenceSession(onnx_a, providers=["CPUExecutionProvider"])
    sess_b = ort.InferenceSession(onnx_b, providers=["CPUExecutionProvider"])

    same = diff = rescued = bad = 0
    ratios = []
    for f in files:
        name = f.replace("\\", "/").split("/")[-1][:22]
        img = Image.open(f).convert("RGB")
        chw, meta, _ = app_letterbox(img)
        raw = to_frame(detect(sess_a, chw, 0.12), meta)
        pieces = [d for d in raw if d["cls"] != BOARD_ID and d["c"] >= PIECE_CONF]

        ref = self_board_box(sess_a, img)
        got = locate(sess_b, img, 640, 0.10)
        if got is None:
            print(f"{name:<24} 兜底无框")
            bad += 1
            continue
        pw = median([d["x1"] - d["x0"] for d in pieces])
        ph = median([d["y1"] - d["y0"] for d in pieces])
        got = normalize(got, pw, ph) if NORMALIZE else got
        bd_b = as_det(got)
        g_b = geometry(pieces + [bd_b], bd_b)
        if g_b is None:
            print(f"{name:<24} 兜底框几何失败")
            bad += 1
            continue

        if ref is None:
            rescued += 1
            print(f"{name:<24} 自研无框 -> 兜底 {bits(g_b[5])} 子 conf {got[4]:.3f} {g_b[5]}")
            continue

        bd_a = as_det(ref)
        g_a = geometry(pieces + [bd_a], bd_a)
        ratios.append(((got[2] - got[0]) / (ref[2] - ref[0]),
                       (got[3] - got[1]) / (ref[3] - ref[1])))
        if g_a is None:
            print(f"{name:<24} 自研框几何也失败，对照不成立，跳过")
            continue
        if g_a[5] == g_b[5]:
            same += 1
        else:
            diff += 1
            print(f"{name:<24} ✗ FEN 不同  框比 {ratios[-1][0]:.2f}x{ratios[-1][1]:.2f}")
            print(f"    自研 {g_a[5]}")
            print(f"    兜底 {g_b[5]}")

    if ratios:
        rx = sorted(r[0] for r in ratios)
        ry = sorted(r[1] for r in ratios)
        n = len(rx)
        print(f"\n兜底框/自研框 尺寸比：宽 中位 {rx[n//2]:.3f}（{rx[0]:.2f}~{rx[-1]:.2f}）  "
              f"高 中位 {ry[n//2]:.3f}（{ry[0]:.2f}~{ry[-1]:.2f}）")
    print(f"自研框合格：FEN 相同 {same}  不同 {diff}")
    print(f"自研框不合格：兜底救回 {rescued}   兜底也失败/无框 {bad}")
