"""把新的「四角框」着法标记按 Kotlin 侧的真实参数画一遍，用来先看效果再装机。

参数全部对齐 MiniBoardView / MarkerGeometry：
  cell  = min(W/9, H/10)      —— 按列数/行数算，四周留半格给四角框
  半径  = 0.42 cell, 框半边长 = 0.47 cell, 臂长 = 0.56 * 半边长
  stroke: 格线 0.9dp / 棋子 1.1dp / 标记 1.5dp
density 取 3（xxhdpi）。棋盘从 FEN 解析，不手写，免得又对错列。
"""
from PIL import Image, ImageDraw, ImageFont

DENSITY = 3
W, H = int(272 * DENSITY), int(200 * DENSITY)   # 面板 300dp 减两侧 14dp 内边距

BG = (26, 26, 26)
GRID = (74, 74, 74)
PIECE_FILL = (25, 25, 25)
RED = (255, 122, 102)
BLACK = (169, 192, 214)
MARKER = (76, 125, 255)

FONT_PATH = "C:/Windows/Fonts/msyh.ttc"

RED_NAME = {"k": "帅", "a": "仕", "b": "相", "n": "马", "r": "车", "c": "炮", "p": "兵"}
BLACK_NAME = {"k": "将", "a": "士", "b": "象", "n": "马", "r": "车", "c": "炮", "p": "卒"}

START = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR"


def parse(fen):
    board = {}
    for row, rank in enumerate(fen.split("/")):
        col = 0
        for ch in rank:
            if ch.isdigit():
                col += int(ch)
                continue
            if ch.isupper():
                board[(row, col)] = (RED_NAME[ch.lower()], RED)
            else:
                board[(row, col)] = (BLACK_NAME[ch], BLACK)
            col += 1
    return board


def dp(v):
    return v * DENSITY


BOLD = ImageFont.truetype(FONT_PATH, 10)


def draw_board(img, board, move, flipped=False):
    draw = ImageDraw.Draw(img)
    cell = min(W / 9, H / 10)
    grid_w, grid_h = cell * 8, cell * 9
    x0, y0 = (W - grid_w) / 2, (H - grid_h) / 2

    # 与 BoardOrientation 同一套映射：翻的是坐标，不是画布（转画布会把汉字转倒）
    drow = (lambda r: 9 - r) if flipped else (lambda r: r)
    dcol = (lambda c: 8 - c) if flipped else (lambda c: c)
    cx = lambda col: x0 + dcol(col) * cell
    cy = lambda row: y0 + drow(row) * cell

    lw = max(1, round(dp(0.9)))
    for row in range(10):
        draw.line([(x0, cy(row)), (x0 + grid_w, cy(row))], fill=GRID, width=lw)
    for col in range(9):
        x = cx(col)
        if col in (0, 8):
            draw.line([(x, y0), (x, y0 + grid_h)], fill=GRID, width=lw)
        else:
            # 河界在 row 4 与 5 之间；翻过之后 cy(4) 在下面，必须按大小取
            y_a, y_b = sorted((cy(4), cy(5)))
            draw.line([(x, y0), (x, y_a)], fill=GRID, width=lw)
            draw.line([(x, y_b), (x, y0 + grid_h)], fill=GRID, width=lw)
    for top in (0, 7):
        draw.line([(cx(3), cy(top)), (cx(5), cy(top + 2))], fill=GRID, width=lw)
        draw.line([(cx(5), cy(top)), (cx(3), cy(top + 2))], fill=GRID, width=lw)

    radius = cell * 0.42
    font = ImageFont.truetype(FONT_PATH, int(cell * 0.62))
    for (row, col), (char, tint) in board.items():
        x, y = cx(col), cy(row)
        draw.ellipse([x - radius, y - radius, x + radius, y + radius],
                     fill=PIECE_FILL, outline=tint, width=max(1, round(dp(1.1))))
        draw.text((x, y), char, font=font, fill=tint, anchor="mm")

    half = cell * 0.47
    arm = half * 2 * 0.28
    mw = max(1, round(dp(1.5)))
    for col, row in ((move[0], move[1]), (move[2], move[3])):  # (col, row)
        ox, oy = cx(col), cy(row)
        for sx in (-1, 1):
            for sy in (-1, 1):
                px, py = ox + sx * half, oy + sy * half
                draw.line([(px, py), (px - sx * arm, py)], fill=MARKER, width=mw)
                draw.line([(px, py), (px, py - sy * arm)], fill=MARKER, width=mw)


def main():
    panels = [
        # 元组是 (起点列, 起点行, 终点列, 终点行, 是否翻 180°)
        ("马二进三（走日，两格以上）", (1, 9, 2, 7)),
        ("兵七进一（走一格，最挤的情况）", (2, 6, 2, 5)),
        # 红在上 = 黑方用户的视角（平台上自己永远在下方）。棋盘、棋子、四角框
        # 必须一起翻，只有标记翻了会画到反方向去。
        ("红在上（黑方视角）· 炮八平五", (1, 7, 4, 7), True),
    ]
    gap, label_h = 24, 44
    out = Image.new("RGB", (W * len(panels) + gap * (len(panels) + 1), H + label_h + gap * 2), (12, 12, 12))
    title = ImageFont.truetype(FONT_PATH, 20)
    dr = ImageDraw.Draw(out)
    for i, panel in enumerate(panels):
        label, move = panel[0], panel[1]
        flipped = len(panel) > 2 and panel[2]
        ox = gap + i * (W + gap)
        canvas = Image.new("RGB", (W, H), BG)
        draw_board(canvas, parse(START), move, flipped)
        out.paste(canvas, (ox, gap))
        dr.text((ox, gap + H + 8), label, font=title, fill=(220, 220, 220))
    out.save("out/_marker_preview.png")
    print("saved", out.size)


if __name__ == "__main__":
    main()
