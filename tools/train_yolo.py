#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""训练 YOLOv8 棋子检测模型（15 类），并导出 ONNX 供 Android 端使用。

用法（在 D:/envs/xiangqi-cv 环境下）：
    python tools/train_yolo.py --data D:/datasets/xiangqi-yolo/xiangqi.yaml --epochs 100

产出：
    runs/xiangqi/weights/best.pt        PyTorch 权重（继续训练用）
    runs/xiangqi/weights/best.onnx      部署用，输入 [1,3,640,640]，输出 YOLOv8 布局
    runs/xiangqi/weights/best.xml       可选 NCNN

注意：
  - 类别顺序必须与 Android 端 Labels.ALL 一致（n b a k r c p R N A K B C P 0）
  - 导出的 ONNX 会被 YoloDecoder 按张量形状自动识别，无需改代码
"""

import argparse
import os
import shutil
import sys
from pathlib import Path

CLASSES = ["n", "b", "a", "k", "r", "c", "p", "R", "N", "A", "K", "B", "C", "P", "0"]


def check_env():
    import torch

    print(f"torch {torch.__version__}  cuda={torch.version.cuda}")
    if not torch.cuda.is_available():
        print("[!] CUDA 不可用，将退回 CPU（会很慢）")
        return "cpu"
    idx = 0
    p = torch.cuda.get_device_properties(idx)
    print(f"GPU {torch.cuda.get_device_name(idx)}  {p.total_memory / 1024 ** 3:.1f}GB  sm_{p.major}{p.minor}")
    return str(idx)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True, help="data.yaml 路径")
    ap.add_argument("--model", default="yolov8n.pt", help="预训练权重（会自动下载）")
    ap.add_argument("--epochs", type=int, default=100)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--device", default="auto")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--project", default="runs")
    ap.add_argument("--name", default="xiangqi")
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--no-export", action="store_true")
    args = ap.parse_args()

    device = check_env() if args.device == "auto" else args.device

    from ultralytics import YOLO

    model = YOLO(args.model)
    results = model.train(
        data=args.data,
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=device,
        workers=args.workers,
        project=args.project,
        name=args.name,
        exist_ok=True,
        resume=args.resume,
        patience=25,          # 25 轮无提升就早停，省时间
        save_period=-1,
        plots=True,
        # 棋盘是刚体，翻转变换要谨慎：上下翻转会改变红黑视角，但红黑双方本来就会换边，
        # 所以允许上下翻转；左右镜像会破坏「楚河汉界」的语义（左右对称性弱），关掉
        flipud=0.5,
        fliplr=0.0,
        mosaic=1.0,
        close_mosaic=10,
        degrees=4.0,          # 手机截图可能有轻微旋转
        scale=0.5,
        translate=0.1,
        hsv_h=0.02,
        hsv_s=0.5,
        hsv_v=0.4,
    )
    print(f"训练完成: {results.save_dir}")

    best = Path(results.save_dir) / "weights" / "best.pt"
    if not best.exists():
        print("[!] 没找到 best.pt，跳过导出")
        return

    # 用验证集报一次逐类指标，确认没有哪类彻底塌掉
    metrics = YOLO(str(best)).val(data=args.data, imgsz=args.imgsz, device=device)
    try:
        names = metrics.names
        print("逐类 mAP50:")
        for i, ap in enumerate(metrics.box.ap50):
            print(f"  {names[i]:>3s} (id={i}): {ap:.3f}")
    except Exception as e:
        print("逐类指标读取失败:", e)

    if args.no_export:
        return

    onnx_path = YOLO(str(best)).export(
        format="onnx",
        imgsz=args.imgsz,
        opset=12,
        simplify=False,
        dynamic=False,
        half=False,
    )
    dst = Path("app/src/main/assets/detector_yolov8.onnx")
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(onnx_path, dst)
    print(f"ONNX 已导出 -> {onnx_path}")
    print(f"已复制到 -> {dst}")
    print("验证：用 tools/assemble_fit.py --model app/src/main/assets/detector_yolov8.onnx")


if __name__ == "__main__":
    sys.exit(main())
