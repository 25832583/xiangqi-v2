"""最简单可靠的核对图：把识别出的类别字母直接画在每颗棋子框中心。

不需要格线，不需要理解坐标系——字母落在棋子上且内容正确，就是对的。
"""
import argparse
import glob
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from probe_detector import BOARD_ID, LABELS, preprocess, run, to_frame


def pick_font(size, bold=False):
    cands = [r"C:\Windows\Fonts\arialbd.ttf", r"C:\Windows\Fonts\msyhbd.ttc",
             r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simhei.ttf"]
    for p in cands:
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--images", nargs="+", required=True)
    ap.add_argument("--conf", type=float, default=0.25)
    ap.add_argument("--out", default="D:/date/_simple")
    ap.add_argument("--scale", type=int, default=2)
    args = ap.parse_args()

    import onnxruntime as ort
    sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)

    paths = []
    for pat in args.images:
        paths.extend(p for p in glob.glob(pat)
                     if Path(p).suffix.lower() in (".jpg", ".jpeg", ".png", ".bmp", ".webp"))
    paths = sorted(set(paths))

    for path in paths:
        chw, meta = preprocess(path, "letterbox")
        dets = to_frame(run(sess, chw, args.conf)[0], meta)
        boards = [d for d in dets if d["cls"] == BOARD_ID]
        pcs = [d for d in dets if d["cls"] != BOARD_ID]
        if not boards:
            print(Path(path).name, "无棋盘")
            continue
        b = max(boards, key=lambda d: d["c"])
        img = Image.open(path).convert("RGB")
        x0 = int(max(0, b["x0"] - 30))
        y0 = int(max(0, b["y0"] - 30))
        x1 = int(min(img.width, b["x1"] + 30))
        y1 = int(min(img.height, b["y1"] + 30))
        crop = img.crop((x0, y0, x1, y1))
        sc = args.scale
        crop = crop.resize((crop.width * sc, crop.height * sc), Image.LANCZOS)
        dr = ImageDraw.Draw(crop)
        f = pick_font(int((b["x1"] - b["x0"]) / 9 * 0.55 * sc))

        for d in pcs:
            cx = ((d["x0"] + d["x1"]) / 2 - x0) * sc
            cy = ((d["y0"] + d["y1"]) / 2 - y0) * sc
            lab = LABELS[d["cls"]]
            col = (255, 0, 0) if lab.isupper() else (0, 60, 255)
            dr.text((cx, cy), lab, font=f, fill=col, anchor="mm",
                    stroke_width=max(2, sc), stroke_fill=(255, 255, 255))

        name = Path(path).stem[:10]
        out = outdir / f"{name}_simple.png"
        crop.save(out)
        print(f"{name} -> {out.name}  棋子 {len(pcs)}")


if __name__ == "__main__":
    main()
