"""用开源权重只定位棋盘框（懒兜底），验证它和自研模型给出的框是否一致。

这个脚本刻意**逐行复刻 App 里的 BoardLocator**，而不是调用 ultralytics 的 predict：
predict 自己做 letterbox、自己做 NMS、还可能做多尺度 TTA，真机上都不是这么跑的。
只有把「分步减半 -> letterbox 到 114 灰边 -> CHW 0..1 -> YOLOv8 通道主序解码 ->
同类 NMS -> 换算回帧像素」全手写一遍，这里得到的结论才能代表真机。

判定标准不是"跑通"，而是：
  1. 自研模型给出合格框的图上，两个框的 IoU 必须很高（否则就是引入了偏移）
  2. 自研模型给不出合格框的图上，它必须给出一个尺寸正确的框
"""
import argparse
import glob
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, ".")
from cell_scores import detect                     # noqa: E402
from _diag_jj_full import app_letterbox, to_frame  # noqa: E402

BOARD_ID = 14
FILL = 114
CLASSES = 15


def letterbox(img, size):
    """与 Kotlin Letterbox.of + OnnxDetector.downscale/letterboxed 等价。"""
    w, h = img.size
    s = min(size / w, size / h)
    sw, sh = max(1, min(size, round(w * s))), max(1, min(size, round(h * s)))
    # 分步减半（每步 <= 2 倍），与 Downscale.plan 同构
    cur = img
    while min(cur.size[0] / sw, cur.size[1] / sh) > 2.0:
        nw = max(cur.size[0] // 2, sw)
        nh = max(cur.size[1] // 2, sh)
        if (nw, nh) == cur.size:
            break
        cur = cur.resize((nw, nh), Image.BILINEAR)
    if cur.size != (sw, sh):
        cur = cur.resize((sw, sh), Image.BILINEAR)

    canvas = Image.new("RGB", (size, size), (FILL, FILL, FILL))
    padx, pady = (size - sw) / 2, (size - sh) / 2
    canvas.paste(cur, (int(round(padx)), int(round(pady))))
    arr = np.asarray(canvas, dtype=np.float32) / 255.0
    chw = np.transpose(arr, (2, 0, 1))[None]
    return chw, (sw / w, sh / h, padx, pady)


def decode_v8(raw, classes, thresh):
    """YOLOv8 通道主序 [1, 4+nc, anchors] -> [(score, x0,y0,x1,y1)]。"""
    _, ch, anchors = raw.shape
    assert ch == 4 + classes, f"输出通道 {ch} != 4+{classes}"
    boxes = raw[0, :4, :]
    scores = raw[0, 4:, :]
    cls = scores.argmax(axis=0)
    best = scores.max(axis=0)
    keep = best >= thresh
    out = []
    for i in np.nonzero(keep)[0]:
        cx, cy, bw, bh = boxes[:, i]
        out.append((float(best[i]), int(cls[i]),
                    float(cx - bw / 2), float(cy - bh / 2),
                    float(cx + bw / 2), float(cy + bh / 2)))
    return out


def nms_by_class(dets, iou_thresh, limit_of):
    keep = []
    for c in set(d[1] for d in dets):
        group = sorted([d for d in dets if d[1] == c], key=lambda d: -d[0])
        kept = 0
        while group and kept < limit_of(c):
            cur = group.pop(0)
            keep.append(cur)
            kept += 1
            group = [d for d in group if iou(cur[2:6], d[2:6]) < iou_thresh]
    return keep


def iou(a, b):
    """两个框都按 (x0,y0,x1,y1) 顺序取；调用方负责只传这 4 个数。"""
    ax0, ay0, ax1, ay1 = a
    bx0, by0, bx1, by1 = b
    iw = max(0.0, min(ax1, bx1) - max(ax0, bx0))
    ih = max(0.0, min(ay1, by1) - max(ay0, by0))
    inter = iw * ih
    ua = (ax1 - ax0) * (ay1 - ay0) + (bx1 - bx0) * (by1 - by0) - inter
    return inter / ua if ua > 0 else 0.0


def locate(sess, img, size, thresh):
    """返回帧像素空间的棋盘框 (x0,y0,x1,y1,score)，没有则 None。"""
    chw, (sx, sy, padx, pady) = letterbox(img, size)
    name = sess.get_inputs()[0].name
    raw = sess.run(None, {name: chw})[0]
    dets = [d for d in decode_v8(raw, CLASSES, thresh) if d[1] == BOARD_ID]
    if not dets:
        return None
    dets = nms_by_class(dets, 0.5, lambda c: 1)
    best = max(dets, key=lambda d: d[0])
    return ((best[2] - padx) / sx, (best[3] - pady) / sy,
            (best[4] - padx) / sx, (best[5] - pady) / sy, best[0])


def self_board_box(sess_a, img):
    """自研模型 + 形状/尺寸两道过滤后的框（与 BoardRecognizer 一致）。"""
    chw, meta, _ = app_letterbox(img)
    framed = to_frame(detect(sess_a, chw, 0.12), meta)
    boards = [d for d in framed if d["cls"] == BOARD_ID]
    if not boards:
        return None
    pcs = [d for d in framed if d["cls"] != BOARD_ID]
    ws = sorted(d["x1"] - d["x0"] for d in pcs)
    hs = sorted(d["y1"] - d["y0"] for d in pcs)
    if not ws:
        return None
    n = len(ws)
    pw = ws[n // 2] if n % 2 else (ws[n // 2 - 1] + ws[n // 2]) / 2
    ph = hs[n // 2] if n % 2 else (hs[n // 2 - 1] + hs[n // 2]) / 2
    for d in sorted(boards, key=lambda d: -d["c"]):
        w, h = d["x1"] - d["x0"], d["y1"] - d["y0"]
        if not (0.6 <= w / h <= 1.6):
            continue
        if not (6.8 <= w / pw <= 9.5 and 7.8 <= h / ph <= 10.5):
            continue
        return (d["x0"], d["y0"], d["x1"], d["y1"], d["c"])
    return None


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", nargs="+", default=["D:/date/*.jpg"])
    ap.add_argument("--a", default="../app/src/main/assets/detector_xqlink_v5.onnx")
    ap.add_argument("--b", nargs="+",
                    default=["D:/date/_oss/detect-ultra.onnx", "D:/date/_oss/detect-ultra-320.onnx"])
    ap.add_argument("--thresh", type=float, default=0.10)
    args = ap.parse_args()

    files = []
    for pat in args.images:
        files += sorted(glob.glob(pat))
    sess_a = ort.InferenceSession(args.a, providers=["CPUExecutionProvider"])

    for path_b in args.b:
        size = 320 if "320" in path_b else 640
        sess_b = ort.InferenceSession(path_b, providers=["CPUExecutionProvider"])
        print(f"\n########## 兜底模型 {path_b.split('/')[-1]} (imgsz={size})")
        ious, miss, n_ref = [], 0, 0
        rescued = []
        for f in files:
            img = Image.open(f).convert("RGB")
            ref = self_board_box(sess_a, img)
            got = locate(sess_b, img, size, args.thresh)
            if ref is not None:
                n_ref += 1
                if got is None:
                    miss += 1
                else:
                    v = iou(ref[:4], got[:4])
                    ious.append(v)
                    if v < 0.90:
                        print(f"  ! IoU 偏低 {v:.3f} {f.split('/')[-1][:22]} "
                              f"ref {ref[0]:.0f},{ref[1]:.0f} {ref[2]-ref[0]:.0f}x{ref[3]-ref[1]:.0f}"
                              f"  got {got[0]:.0f},{got[1]:.0f} {got[2]-got[0]:.0f}x{got[3]-got[1]:.0f}")
            else:
                if got is not None:
                    rescued.append((f.split('/')[-1][:24], got[4],
                                    got[2] - got[0], got[3] - got[1]))
        ious.sort()
        if ious:
            k = len(ious)
            print(f"  有对照框 {n_ref}/{len(files)} 张，兜底没框 {miss} 张")
            print(f"  IoU 中位 {ious[k//2]:.4f}  最低 {ious[0]:.4f}  "
                  f"<0.95 的 {sum(1 for v in ious if v < 0.95)} 张")
        print(f"  自研无框、兜底给出框: {len(rescued)} 张")
        for name, c, w, h in rescued:
            print(f"    {name:<26} conf {c:.3f}  {w:.0f}x{h:.0f}")
