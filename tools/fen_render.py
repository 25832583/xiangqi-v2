"""把 FEN 渲染成标准象棋盘面图，与原截图并排，用于人工核对真值。

红色系 = 红方，蓝色系 = 黑方，与截图配色一致，便于对照。
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

CN = {"k": "将", "a": "士", "b": "象", "n": "马", "r": "车", "c": "炮", "p": "卒"}
CN_RED = {"K": "帥", "A": "仕", "B": "相", "N": "傌", "R": "俥", "C": "炮", "P": "兵"}


def font(size):
    for p in (r"C:\Windows\Fonts\simhei.ttf", r"C:\Windows\Fonts\msyh.ttc"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


def parse(fen):
    rows = []
    for part in fen.split()[0].split("/"):
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


def draw_board(rows, cell=84, margin=52):
    w = margin * 2 + cell * 8
    h = margin * 2 + cell * 9
    img = Image.new("RGB", (w, h), (238, 206, 166))
    dr = ImageDraw.Draw(img)
    line_col = (110, 78, 48)

    for r in range(10):
        y = margin + r * cell
        dr.line([margin, y, w - margin, y], fill=line_col, width=2)
    for c in range(9):
        x = margin + c * cell
        if c in (0, 8):
            dr.line([x, margin, x, h - margin], fill=line_col, width=2)
        else:
            dr.line([x, margin, x, margin + 4 * cell], fill=line_col, width=2)
            dr.line([x, margin + 5 * cell, x, h - margin], fill=line_col, width=2)
    # 九宫斜线
    for hx in (margin + 3 * cell, margin + 5 * cell):
        dr.line([hx, margin, hx, margin + 2 * cell], fill=line_col, width=2)
        dr.line([hx + 2 * cell, margin, hx + 2 * cell, margin + 2 * cell], fill=line_col, width=2)
        dr.line([hx, margin + 7 * cell, hx, h - margin], fill=line_col, width=2)
        dr.line([hx + 2 * cell, margin + 7 * cell, hx + 2 * cell, h - margin], fill=line_col, width=2)
    # 河界
    fy = margin + 4 * cell
    f = font(34)
    dr.text((margin + cell * 1.6, fy + cell / 2), "楚 河", font=f, fill=line_col, anchor="mm")
    dr.text((margin + cell * 6.4, fy + cell / 2), "汉 界", font=f, fill=line_col, anchor="mm")

    fp = font(int(cell * 0.56))
    rad = cell * 0.42
    for r in range(10):
        for c in range(9):
            ch = rows[r][c] if c < len(rows[r]) else "."
            if ch == ".":
                continue
            x = margin + c * cell
            y = margin + r * cell
            red = ch.isupper()
            fill = (250, 238, 214)
            dr.ellipse([x - rad, y - rad, x + rad, y + rad], fill=fill,
                       outline=(150, 40, 30) if red else (40, 50, 120), width=3)
            txt = CN_RED.get(ch) or CN.get(ch.lower(), ch)
            dr.text((x, y), txt, font=fp, anchor="mm",
                    fill=(180, 25, 20) if red else (20, 30, 100))
    # 行列坐标（调试用）
    fs = font(18)
    for c in range(9):
        dr.text((margin + c * cell, margin - 26), str(c), font=fs, fill=(120, 120, 120), anchor="mm")
    for r in range(10):
        dr.text((margin - 26, margin + r * cell), str(r), font=fs, fill=(120, 120, 120), anchor="mm")
    return img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--images", required=True, help="真实截图目录")
    ap.add_argument("--json", required=True, help="assemble.py 的汇总 json")
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default=None, help="只处理文件名前缀匹配的")
    args = ap.parse_args()

    data = json.loads(Path(args.json).read_text(encoding="utf-8"))
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)
    srcdir = Path(args.images)

    for item in data:
        name = item["image"]
        if args.only and not name.startswith(args.only):
            continue
        src = srcdir / name
        if not src.exists():
            continue
        rows = parse(item["fen"])
        bd = draw_board(rows)
        shot = Image.open(src).convert("RGB")
        # 缩放到同一高度
        H = 900
        shot = shot.resize((int(shot.width * H / shot.height), H), Image.LANCZOS)
        bd = bd.resize((int(bd.width * H / bd.height), H), Image.LANCZOS)
        canvas = Image.new("RGB", (shot.width + bd.width + 24, H), (25, 25, 25))
        canvas.paste(shot, (0, 0))
        canvas.paste(bd, (shot.width + 24, 0))
        out = outdir / f"{Path(name).stem[:10]}_cmp.png"
        canvas.save(out)
        print(f"{out.name}  {item['fen']}")


if __name__ == "__main__":
    main()
