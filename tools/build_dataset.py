#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成中国象棋棋盘训练数据（YOLO 标注），目标是「手机竖屏截图」这个真实分布。

与旧版 synth_dataset.py 的关键区别：
  旧版：正方形画布，棋盘几乎填满整幅 → 与真机截图分布不符。
  本版：竖屏画布（9:16~9:20），棋盘只占宽度 60%~88%，四周有 UI 装饰（状态栏/按钮条/侧栏），
        与 JJ象棋这类 App 的截图一致。棋盘出现位置、大小、角度、遮挡都在随机化范围内。

类别顺序（必须与 Android 端 Labels.ALL 一致）：
  0..14 = n b a k r c p R N A K B C P 0
  小写=黑方，大写=红方，'0'=棋盘整体框
"""

import argparse
import math
import os
import random
from pathlib import Path

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

CLASSES = ["n", "b", "a", "k", "r", "c", "p", "R", "N", "A", "K", "B", "C", "P", "0"]
CLASS_ID = {c: i for i, c in enumerate(CLASSES)}
BOARD_CLASS_ID = CLASS_ID["0"]

PIECE_TEXT = {
    "R": ["车", "車", "俥"], "N": ["马", "馬", "傌"], "A": ["仕", "仕"],
    "K": ["帅", "帥"], "B": ["相", "相"], "C": ["炮", "炮"], "P": ["兵", "兵"],
    "n": ["马", "馬", "傌"], "b": ["象", "象"], "a": ["士", "士"],
    "k": ["将", "將"], "r": ["车", "車", "俥"], "c": ["炮", "砲"], "p": ["卒", "卒"],
}

INITIAL_FEN = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"

_FONT_DIR = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts"
FONT_CANDIDATES = [
    "STXINGKA.TTF", "STKAITI.TTF", "STLITI.TTF", "STXINWEI.TTF", "STCAIYUN.TTF",
    "STHUPO.TTF", "STFANGSO.TTF", "STSONG.TTF", "STZHONGS.TTF", "STXIHEI.TTF",
    "simkai.ttf", "simfang.ttf", "SIMLI.TTF", "SIMYOU.TTF", "simhei.ttf",
    "msyh.ttc", "msyhbd.ttc", "Deng.ttf", "NotoSerifSC-VF.ttf", "NotoSansSC-VF.ttf",
]

_font_cache = {}


def load_fonts():
    fonts = []
    for name in FONT_CANDIDATES:
        path = _FONT_DIR / name
        if not path.exists():
            continue
        try:
            ImageFont.truetype(str(path), 20, index=0)
            fonts.append((str(path), 0))
        except Exception:
            continue
    if not fonts:
        raise RuntimeError("未找到中文字体")
    return fonts


def get_font(path_index, size):
    key = (path_index, size)
    if key not in _font_cache:
        path, index = path_index
        _font_cache[key] = ImageFont.truetype(path, size, index=index)
    return _font_cache[key]


def parse_fen(fen):
    cells = [[None] * 9 for _ in range(10)]
    for i, rank in enumerate(fen.split("/")):
        col = 0
        for ch in rank:
            if ch.isdigit():
                col += int(ch)
            else:
                cells[i][col] = ch
                col += 1
    return cells


def random_layout(rng):
    cells = parse_fen(INITIAL_FEN)
    is_endgame = rng.random() < 0.35
    remove_prob = rng.uniform(0.5, 0.78) if is_endgame else 0.18
    for r in range(10):
        for c in range(9):
            ch = cells[r][c]
            if ch is None:
                continue
            if ch in "kK":
                if rng.random() < 0.02:
                    cells[r][c] = None
            elif ch in "aAnN":
                p = remove_prob if is_endgame else 0.04
                if rng.random() < p:
                    cells[r][c] = None
            elif rng.random() < remove_prob:
                cells[r][c] = None
    if rng.random() < 0.5:
        cells = [row[::-1] for row in reversed(cells)]
    return cells


def wood_texture(w, h, rng, nprng):
    base = np.array(rng.choice(
        [[232, 202, 158], [210, 176, 128], [196, 158, 110], [240, 214, 172],
         [222, 190, 145], [200, 168, 118]]), dtype=np.float32)
    img = np.zeros((h, w, 3), dtype=np.float32)
    img[:] = base
    for _ in range(rng.randint(6, 18)):
        amp = rng.uniform(2, 8)
        phase = rng.uniform(0, math.tau)
        freq = rng.uniform(0.004, 0.02)
        rows = np.arange(h, dtype=np.float32)
        img += np.sin(rows * freq * math.tau + phase)[:, None, None] * amp
    img += nprng.normal(0, rng.uniform(3, 8), size=img.shape).astype(np.float32)
    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")


def make_canvas(w, h, rng, nprng):
    """整幅截图背景：木纹 / 纯色 / 深色主题。"""
    style = rng.random()
    if style < 0.5:
        return wood_texture(w, h, rng, nprng)
    if style < 0.8:
        c = tuple(int(v) for v in rng.choice(
            [[222, 190, 150], [205, 178, 130], [190, 160, 110], [225, 200, 160]]))
    else:
        c = tuple(int(v) for v in rng.choice(
            [[245, 235, 205], [40, 44, 52], [30, 32, 38], [235, 225, 200]]))
    return Image.new("RGB", (w, h), c)


def draw_ui_chrome(draw, w, h, rng):
    """画状态栏/按钮条/侧栏之类的干扰元素，迫使模型不依赖「棋盘填满画面」。"""
    dark = rng.random() < 0.5
    chrome = (28, 30, 36) if dark else (238, 236, 232)
    top_h = int(h * rng.uniform(0.03, 0.075))
    draw.rectangle([0, 0, w, top_h], fill=chrome)
    for i in range(rng.randint(2, 4)):          # 状态栏图标
        x = w - int(w * 0.05) * (i + 1)
        draw.rectangle([x, top_h * 0.35, x + w * 0.025, top_h * 0.65],
                       fill=(150, 150, 155) if dark else (120, 120, 125))

    if rng.random() < 0.75:                      # 底部操作条
        bot_h = int(h * rng.uniform(0.06, 0.13))
        draw.rectangle([0, h - bot_h, w, h], fill=chrome)
        n_btn = rng.randint(2, 5)
        bw = w / (n_btn + 1)
        for i in range(n_btn):
            cx = bw * (i + 1)
            r = bot_h * rng.uniform(0.18, 0.3)
            cy = h - bot_h / 2
            col = tuple(int(v) for v in rng.choice(
                [[200, 60, 50], [240, 240, 240], [90, 150, 220], [120, 200, 120]]))
            draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)

    if rng.random() < 0.35:                      # 侧边玩家信息栏
        side = rng.choice(["left", "right"])
        sw = int(w * rng.uniform(0.08, 0.16))
        x0 = 0 if side == "left" else w - sw
        y0 = top_h + h * rng.uniform(0.02, 0.1)
        sh = h * rng.uniform(0.15, 0.35)
        draw.rounded_rectangle([x0, y0, x0 + sw, y0 + sh], radius=int(w * 0.015), fill=chrome)
        for i in range(rng.randint(1, 3)):
            cy = y0 + sh * (i + 1) / 4
            rr = sw * 0.22
            draw.ellipse([x0 + sw / 2 - rr, cy - rr, x0 + sw / 2 + rr, cy + rr],
                         fill=(160, 130, 100))


def draw_board_lines(draw, bx, by, cell_w, cell_h, rng):
    line_color = tuple(int(v) for v in rng.choice(
        [[70, 42, 20], [60, 38, 16], [45, 45, 45], [80, 50, 24]]))
    width = max(1, int(round(cell_w * 0.045)))
    for col in range(9):
        x = bx + col * cell_w
        if col in (0, 8):
            # 边线贯通整盘
            draw.rectangle([x - width, by - width, x + width, by + 9 * cell_h + width], fill=line_color)
        else:
            # 内陆竖线在楚河汉界处断开
            draw.line([x, by, x, by + 4 * cell_h], fill=line_color, width=width)
            draw.line([x, by + 5 * cell_h, x, by + 9 * cell_h], fill=line_color, width=width)
    for row in range(10):
        y = by + row * cell_h
        draw.line([bx, y, bx + 8 * cell_w, y], fill=line_color, width=width)
    for (cx0, ry0) in [(3, 0), (3, 7)]:
        x0, y0 = bx + cx0 * cell_w, by + ry0 * cell_h
        x1, y1 = bx + (cx0 + 2) * cell_w, by + (ry0 + 2) * cell_h
        draw.line([x0, y0, x1, y1], fill=line_color, width=width)
        draw.line([x1, y0, x0, y1], fill=line_color, width=width)
    river_y = by + 4.5 * cell_h
    fs = max(10, int(cell_h * 0.5))
    try:
        font = ImageFont.truetype(str(_FONT_DIR / "STXINGKA.TTF"), fs)
    except Exception:
        font = ImageFont.load_default()
    for label, cx in [("楚 河", bx + 1.6 * cell_w), ("汉 界", bx + 6.4 * cell_w)]:
        draw.text((cx, river_y), label, font=font, fill=line_color, anchor="mm")


def make_piece(radius, text, font, style, rng):
    radius = int(radius)
    d = max(8, radius * 2)
    sprite = Image.new("RGBA", (d, d), (0, 0, 0, 0))
    dr = ImageDraw.Draw(sprite)
    edge = tuple(int(v) for v in rng.choice(
        [[90, 50, 20], [70, 38, 15], [40, 40, 40], [110, 70, 35]]))
    edge_w = max(2, int(radius * 0.10))

    palette = {
        "wood_red": ((232, 200, 155), (150, 20, 20)),
        "wood_black": ((232, 200, 155), (25, 25, 25)),
        "red_bg": ((176, 30, 26), (245, 238, 220)),
        "black_bg": ((42, 40, 38), (240, 236, 228)),
        "white_red": ((248, 240, 220), (150, 20, 20)),
        "white_black": ((248, 240, 220), (25, 25, 25)),
        "dark_wood_gold": ((128, 82, 40), (240, 210, 120)),
        "blue_bg": ((36, 62, 120), (230, 235, 245)),
    }
    base, text_color = palette.get(style, ((215, 180, 130), (40, 30, 20)))

    dr.ellipse([1, 1, d - 1, d - 1], fill=base, outline=edge, width=edge_w)
    if rng.random() < 0.7:
        inset = max(1, int(radius * 0.18))
        dr.ellipse([inset, inset, d - inset, d - inset],
                   outline=tuple(int(min(255, v * 0.65)) for v in edge), width=max(1, edge_w // 2))

    hl = Image.new("RGBA", (d, d), (0, 0, 0, 0))
    hd = ImageDraw.Draw(hl)
    hr = int(radius * 0.92)
    hd.ellipse([-hr * 0.25, -hr * 0.35, hr * 0.85, hr * 0.75],
               fill=(255, 255, 255, rng.randint(28, 60)))
    hl = hl.filter(ImageFilter.GaussianBlur(radius * 0.12))
    # 关键：高光必须裁进棋子圆内，否则会在透明背景上留下一块白色方形
    circ = Image.new("L", (d, d), 0)
    ImageDraw.Draw(circ).ellipse([0, 0, d - 1, d - 1], fill=255)
    hl.putalpha(ImageChops.multiply(hl.getchannel("A"), circ))
    sprite = Image.alpha_composite(sprite, hl)

    # 文字：直接在最终尺寸上画。旧写法先画进 256x256 再缩到 40px，字会被缩没。
    side = max(10, int(radius * 1.58))
    mask = Image.new("L", (side, side), 0)
    fsize = max(6, int(side * 0.82))
    ImageDraw.Draw(mask).text((side // 2, side // 2), text,
                              font=get_font(font, fsize), fill=255, anchor="mm")
    if mask.getbbox() is None:
        # 该字体缺这个字（美术字体常见），退回黑体兜底，避免生成无字棋子
        for fb in ("msyh.ttc", "simhei.ttf", "simsun.ttc"):
            p = _FONT_DIR / fb
            if p.exists():
                mask = Image.new("L", (side, side), 0)
                ImageDraw.Draw(mask).text((side // 2, side // 2), text,
                                          font=get_font((str(p), 0), fsize),
                                          fill=255, anchor="mm")
                if mask.getbbox() is not None:
                    break
    txt = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    txt.paste(tuple(text_color) + (255,), (0, 0), mask)
    sprite.alpha_composite(txt, (d // 2 - side // 2, d // 2 - side // 2))
    return sprite


def piece_style_for(label, rng):
    if rng.random() < 0.06:   # 少数样本红黑底色反常，逼模型别只靠颜色
        return rng.choice(["red_bg", "black_bg", "blue_bg", "dark_wood_gold"])
    if label.isupper():
        return rng.choice(["wood_red", "red_bg", "white_red"])
    return rng.choice(["wood_black", "black_bg", "white_black"])


def norm_box(cw, ch, cls, x0, y0, x1, y1):
    x0, x1 = max(0.0, x0), min(float(cw), x1)
    y0, y1 = max(0.0, y0), min(float(ch), y1)
    return (cls, ((x0 + x1) / 2) / cw, ((y0 + y1) / 2) / ch,
            max(0.0, x1 - x0) / cw, max(0.0, y1 - y0) / ch)


def augment(img, rng, nprng):
    arr = np.asarray(img, dtype=np.float32)
    if rng.random() < 0.7:
        arr *= rng.uniform(0.82, 1.2)
    arr += rng.uniform(-20, 20)
    if rng.random() < 0.4:
        arr[..., 0] += rng.uniform(-12, 12)
        arr[..., 1] += rng.uniform(-8, 8)
        arr[..., 2] += rng.uniform(-8, 8)
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))

    if rng.random() < 0.35:
        img = img.filter(ImageFilter.GaussianBlur(rng.uniform(0.3, 1.2)))
    if rng.random() < 0.25:
        img = img.filter(ImageFilter.UnsharpMask(radius=2, percent=rng.randint(30, 90)))
    if rng.random() < 0.3:
        a = np.asarray(img).copy()
        m = nprng.random(a.shape[:2]) < rng.uniform(0.001, 0.006)
        a[m] = nprng.choice([0, 255], size=int(m.sum()))[:, None]
        img = Image.fromarray(a)
    return img


def synthesize(out_img, out_lbl, fonts, rng, nprng, base_w=720):
    # 手机竖屏画幅
    aspect = rng.choice([16 / 9, 20 / 9, 19.5 / 9, 2.1])
    w = base_w
    h = int(w * aspect)
    img = make_canvas(w, h, rng, nprng)
    dr = ImageDraw.Draw(img, "RGBA")
    draw_ui_chrome(dr, w, h, rng)

    # 棋盘只占一部分宽度，位置随机
    span_w = w * rng.uniform(0.60, 0.88)
    cell_w = span_w / 8.0
    cell_h = cell_w * rng.uniform(0.96, 1.06)
    board_h = 9 * cell_h
    bx = rng.uniform(w * 0.02, max(w * 0.021, w - span_w - w * 0.02))
    # 棋盘基本垂直居中，只做小幅抖动（真实 App 截图里棋盘很少贴顶或贴底）
    lo = h * 0.10
    hi = max(lo + 1.0, h * 0.86 - board_h)
    center_y = (h - board_h) / 2 + rng.uniform(-h * 0.06, h * 0.06)
    by = min(max(center_y, lo), hi)

    draw_board_lines(dr, bx, by, cell_w, cell_h, rng)

    cells = random_layout(rng)
    radius = min(cell_w, cell_h) * rng.uniform(0.38, 0.47)
    boxes = [norm_box(w, h, BOARD_CLASS_ID, bx - cell_w * 0.5, by - cell_h * 0.5,
                      bx + 8 * cell_w + cell_w * 0.5, by + board_h + cell_h * 0.5)]

    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    off = max(1, int(radius * 0.16))
    for r in range(10):
        for c in range(9):
            if cells[r][c] is None:
                continue
            cx, cy = bx + c * cell_w, by + r * cell_h
            sd.ellipse([cx - radius + off, cy - radius + off,
                        cx + radius + off, cy + radius + off],
                       fill=(0, 0, 0, rng.randint(70, 130)))
    img = Image.alpha_composite(img.convert("RGBA"),
                                shadow.filter(ImageFilter.GaussianBlur(radius * 0.25))).convert("RGB")
    img = img.convert("RGBA")

    for r in range(10):
        for c in range(9):
            ch = cells[r][c]
            if ch is None:
                continue
            cx, cy = bx + c * cell_w, by + r * cell_h
            sprite = make_piece(radius, rng.choice(PIECE_TEXT[ch]), rng.choice(fonts),
                                piece_style_for(ch, rng), rng)
            img.alpha_composite(sprite, (int(cx - radius), int(cy - radius)))
            boxes.append(norm_box(w, h, CLASS_ID[ch], cx - radius, cy - radius,
                                  cx + radius, cy + radius))

    img = augment(img.convert("RGB"), rng, nprng)
    img.save(out_img, "JPEG", quality=rng.randint(86, 96))
    with open(out_lbl, "w", encoding="utf-8") as f:
        for cls, cx, cy, bw, bh in boxes:
            f.write(f"{cls} {cx:.6f} {cy:.6f} {bw:.6f} {bh:.6f}\n")
    return sum(1 for r in range(10) for c in range(9) if cells[r][c] is not None)


def write_data_yaml(out_dir, dataset_root):
    yml = Path(out_dir) / "xiangqi.yaml"
    names = "\n".join(f"  {i}: {c}" for i, c in enumerate(CLASSES))
    yml.write_text(
        f"# 中国象棋棋子检测（15 类）\n"
        f"# 类别顺序必须与 Android Labels.ALL 一致\n"
        f"path: {Path(dataset_root).as_posix()}\n"
        f"train: images/train\n"
        f"val: images/val\n"
        f"nc: {len(CLASSES)}\n"
        f"names:\n{names}\n", encoding="utf-8")
    return yml


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="D:/datasets/xiangqi-yolo")
    ap.add_argument("--count", type=int, default=1500, help="训练集张数")
    ap.add_argument("--val", type=int, default=150, help="验证集张数")
    ap.add_argument("--width", type=int, default=720, help="画布宽度（高度按比例算）")
    ap.add_argument("--seed", type=int, default=20261017)
    args = ap.parse_args()

    root = Path(args.out)
    rng = random.Random(args.seed)
    nprng = np.random.default_rng(args.seed)
    fonts = load_fonts()
    print(f"字体 {len(fonts)} 个 -> 输出 {root}")

    for split, n in (("train", args.count), ("val", args.val)):
        img_dir = root / "images" / split
        lbl_dir = root / "labels" / split
        img_dir.mkdir(parents=True, exist_ok=True)
        lbl_dir.mkdir(parents=True, exist_ok=True)
        total_pieces = 0
        for i in range(n):
            name = f"{split}_{i:06d}"
            total_pieces += synthesize(img_dir / f"{name}.jpg", lbl_dir / f"{name}.txt",
                                       fonts, rng, nprng, args.width)
            if (i + 1) % 200 == 0:
                print(f"  {split} {i + 1}/{n}")
        print(f"{split}: {n} 张, 平均 {total_pieces / max(1, n):.1f} 个棋子/张")

    yml = write_data_yaml(root, root)
    print(f"数据配置 -> {yml}")


if __name__ == "__main__":
    main()
