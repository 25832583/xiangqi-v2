"""混合方案验证：棋盘框取开源权重，棋子分类取自研 xqlink_v5。

依据来自对照实验：
  - 棋盘框：开源权重的下限高（深色 3D 皮肤 0.21 -> 0.88），自研上限略高但会崩
  - 棋子分类：自研更准（开源把浅色皮肤的两个红兵认成红马，且置信 0.94）

所以各取所长。这里验证混合后的 FEN 是否等于"两个模型里对的那个"。
"""
import argparse
import glob
import sys

import onnxruntime as ort
from PIL import Image
from ultralytics import YOLO

sys.path.insert(0, ".")
from _oss_ab import geometry, run_a, run_b   # noqa: E402
from _oss_compare import to_framed           # noqa: E402
from cell_scores import detect               # noqa: E402
from _diag_jj_full import app_letterbox, to_frame   # noqa: E402

BOARD_ID = 14


def run_hybrid(sess, model, path, piece_conf=0.25):
    """框来自开源模型，棋子来自自研模型。"""
    # A 的棋子
    chw, meta, _ = app_letterbox(Image.open(path).convert("RGB"))
    fa = to_frame(detect(sess, chw, piece_conf), meta)
    pieces_a = [d for d in fa if d["cls"] != BOARD_ID]

    # B 的棋盘框
    r = model.predict(path, imgsz=640, conf=0.12, verbose=False)[0]
    fb = to_framed(r, model.names)
    boards = [d for d in fb if d["cls"] == BOARD_ID]
    if not boards:
        return "无框", None
    bd = max(boards, key=lambda d: d["c"])
    merged = pieces_a + [bd]
    g = geometry(merged, bd)
    return bd["c"], g


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

    print(f"{'图':<26}{'A落子':>6}{'B落子':>6}{'混落子':>7}  混合FEN 与谁相同")
    print("-" * 100)
    same_a = same_b = diff = 0
    for f in files:
        name = f.replace("\\", "/").split("/")[-1][:24]
        _, ga, _ = run_a(sess, f)
        _, gb, _ = run_b(model, f)
        cb, gh = run_hybrid(sess, model, f)
        if ga is None or gb is None or gh is None:
            print(f"{name:<26} 链路失败")
            continue
        fa, fb_, fh = ga[5], gb[5], gh[5]
        who = "A" if fh == fa and fh != fb_ else ("B" if fh == fb_ and fh != fa else ("同" if fh == fa == fb_ else "★两头都不是"))
        if fh == fa: same_a += 1
        if fh == fb_: same_b += 1
        if fh != fa and fh != fb_: diff += 1
        print(f"{name:<26}{ga[0]:>6}{gb[0]:>6}{gh[0]:>7}  {who}")

    print(f"\n混合 = A 的 FEN: {same_a}   = B 的 FEN: {same_b}   两者都不是: {diff}")
