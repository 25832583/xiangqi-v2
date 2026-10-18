"""FEN 合法性体检（强约束版）。

除了将帅在位、子数超限，还检查**绝对点位约束**——
士只能在自己九宫的 5 个点，象只能在 7 个点，兵卒不能在自己底线。
这些是象棋规则硬约束，能大量抓出「类别认错」类错误（位置不会错，吸附残差 <0.11 格）。
"""
import json
import sys
from pathlib import Path

LIMITS = {"r": 2, "n": 2, "c": 2, "a": 2, "b": 2, "k": 1, "p": 5}

# 黑方（行 0..4）的合法点位；红方是行号镜像（r -> 9-r）
ADVISOR_BLACK = {(0, 3), (0, 5), (1, 4), (2, 3), (2, 5)}
BISHOP_BLACK = {(0, 2), (0, 6), (2, 0), (2, 4), (2, 8), (4, 2), (4, 6)}


def mirror(cells):
    return {(9 - r, c) for r, c in cells}


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


def check(fen):
    rows = parse(fen)
    errs = []
    if len(rows) != 10 or any(len(r) != 9 for r in rows):
        return [f"行数/列数异常 {len(rows)}"], 0, None

    pos = {}
    for r, line in enumerate(rows):
        for c, ch in enumerate(line):
            if ch != ".":
                pos.setdefault(ch, []).append((r, c))

    # 先定红黑谁在上：帅/将在各自九宫内，行小的一侧在上
    kk0 = pos.get("k", [(0, 4)])[0][0]
    KK0 = pos.get("K", [(9, 4)])[0][0]
    red_bottom = KK0 > kk0

    # 九宫：列 3..5，行 0..2 或 7..9（**不是**只有中线和底线——
    # 帅可以在九宫内左右、上下走，残局里常见，早期版本这里误报过多）
    red_palace_rows = range(7, 10) if red_bottom else range(0, 3)
    blk_palace_rows = range(0, 3) if red_bottom else range(7, 10)
    for key, name, prow in (("k", "黑将", blk_palace_rows),
                            ("K", "红帅", red_palace_rows)):
        got = pos.get(key, [])
        if not got:
            errs.append(f"{name}缺失")
        elif len(got) > 1:
            errs.append(f"{name}多个 {got}")
        else:
            r, c = got[0]
            if c not in (3, 4, 5) or r not in prow:
                errs.append(f"{name}不在九宫({r},{c})")

    kk = kk0
    KK = KK0

    # 士象：绝对点位（先按红黑谁在上决定镜像方向）
    if red_bottom:
        advisor_ok = {"a": ADVISOR_BLACK, "A": mirror(ADVISOR_BLACK)}
        bishop_ok = {"b": BISHOP_BLACK, "B": mirror(BISHOP_BLACK)}
    else:
        advisor_ok = {"a": mirror(ADVISOR_BLACK), "A": ADVISOR_BLACK}
        bishop_ok = {"b": mirror(BISHOP_BLACK), "B": BISHOP_BLACK}
    for table in (advisor_ok, bishop_ok):
        for key, valid in table.items():
            for cell in pos.get(key, []):
                if cell not in valid:
                    errs.append(f"{key}({cell[0]},{cell[1]}) 不是合法点位")

    # 兵卒：不能在自己底线（红兵在红方底线 = 行 9 或行 0，取决于谁在下）
    red_bottom_row = 9 if red_bottom else 0
    blk_bottom_row = 0 if red_bottom else 9
    for r, c in pos.get("P", []):
        if r == red_bottom_row:
            errs.append(f"P({r},{c}) 在己方底线")
    for r, c in pos.get("p", []):
        if r == blk_bottom_row:
            errs.append(f"p({r},{c}) 在己方底线")

    for letter, lim in LIMITS.items():
        for key in (letter, letter.upper()):
            n = len(pos.get(key, []))
            if n > lim:
                errs.append(f"{key} {n}>{lim} 超限")

    total = sum(len(v) for v in pos.values())
    if total > 32:
        errs.append(f"总子数 {total} > 32")
    return errs, total, red_bottom


def main():
    data = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    bad = 0
    for item in data:
        errs, total, red_bottom = check(item["fen"])
        if errs:
            bad += 1
        print(f"{'OK ' if not errs else 'BAD'} {item['image'][:10]}  子{total:2d} "
              f"落盘{item['placed']:2d} {'红在下' if red_bottom else '红在上'}  {item['fen']}")
        for e in errs:
            print(f"      × {e}")
    print(f"\n{len(data) - bad}/{len(data)} 张盘面通过强约束")


if __name__ == "__main__":
    main()
