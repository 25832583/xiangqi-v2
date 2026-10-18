"""离线探测皮卡鱼（PC 版）：UCI 选项、深度/线程/哈希对结论的影响、WDL 胜和负概率。

用途：调 App 里的 EngineConfig 之前，先在 PC 上把「改哪个旋钮有用、改多少有区别」
量出来，而不是靠猜。arm64 的手机包在 PC 上跑不了，所以这里用同版本的 Windows 构建。

只读诊断，不参与识别/分析链路，也不改 App 任何代码。

    D:/envs/xiangqi-cv/Scripts/python.exe _engine_probe.py options
    D:/envs/xiangqi-cv/Scripts/python.exe _engine_probe.py depth   --fen "<FEN>" 8 12 16 20
    D:/envs/xiangqi-cv/Scripts/python.exe _engine_probe.py multipv --fen "<FEN>" 18 5
    D:/envs/xiangqi-cv/Scripts/python.exe _engine_probe.py hash    --fen "<FEN>" 18 32 128 256
"""

from __future__ import annotations

import argparse
import queue
import subprocess
import threading
import time
from pathlib import Path

ENGINE = Path("D:/Software/pikafish-win/Pikafish-Windows-x86-64-universal.exe")
ROOT = Path(__file__).resolve().parent
START = "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"


class Engine:
    """最小 UCI 客户端：读线程 + 队列，避免 readline 阻塞把整条命令挂死。"""

    def __init__(self, threads: int = 4, hash_mb: int = 256, wdl: bool = True):
        self.proc = subprocess.Popen(
            [str(ENGINE)],
            cwd=str(ENGINE.parent),
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        self.q: queue.Queue[str] = queue.Queue()
        threading.Thread(target=self._pump, daemon=True).start()
        self.send("uci")
        self.read_until("uciok")
        self.send(f"setoption name Threads value {threads}")
        self.send(f"setoption name Hash value {hash_mb}")
        self.send(f"setoption name UCI_ShowWDL value {'true' if wdl else 'false'}")
        self.send("isready")
        self.read_until("readyok")
        self.send("ucinewgame")

    def _pump(self) -> None:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            self.q.put(line.rstrip("\r\n"))

    def send(self, cmd: str) -> None:
        assert self.proc.stdin is not None
        self.proc.stdin.write(cmd + "\n")
        self.proc.stdin.flush()

    def read_until(self, token: str, timeout: float = 60.0) -> list[str]:
        out, deadline = [], time.time() + timeout
        while time.time() < deadline:
            try:
                line = self.q.get(timeout=0.2)
            except queue.Empty:
                continue
            out.append(line)
            if line.startswith(token):
                return out
        raise TimeoutError(f"等 {token} 超时，已收到 {len(out)} 行")

    def options(self) -> list[str]:
        self.send("uci")
        return [l for l in self.read_until("uciok") if l.startswith("option name")]

    def analyze(self, fen: str, depth: int, timeout: float = 180.0):
        """跑到指定深度，返回 (bestmove, 最后一次 info 解析结果, 耗时秒)。"""
        self.send(f"position fen {fen}")
        t0 = time.time()
        self.send(f"go depth {depth}")
        lines = self.read_until("bestmove", timeout=timeout)
        elapsed = time.time() - t0
        best = lines[-1].split()[1] if lines[-1].startswith("bestmove") else "?"
        infos = [parse(l) for l in lines if l.startswith("info ") and " pv " in l]
        infos = [i for i in infos if i]
        return best, (infos[-1] if infos else {}), elapsed

    def close(self) -> None:
        try:
            self.send("quit")
            self.proc.wait(timeout=5)
        except Exception:
            self.proc.kill()


def parse(line: str) -> dict:
    tok = line.split()
    d: dict = {"pv": []}
    for i, t in enumerate(tok):
        if t == "depth":
            d["depth"] = int(tok[i + 1])
        elif t == "multipv":
            d["multipv"] = int(tok[i + 1])
        elif t == "score":
            kind, val = tok[i + 1], int(tok[i + 2])
            d["score"] = ("mate" if kind == "mate" else "cp", val)
        elif t == "nodes":
            d["nodes"] = int(tok[i + 1])
        elif t == "nps":
            d["nps"] = int(tok[i + 1])
        elif t == "time":
            d["time"] = int(tok[i + 1])
        elif t == "wdl":
            d["wdl"] = tuple(int(x) for x in tok[i + 1:i + 4])
        elif t == "pv":
            d["pv"] = tok[i + 1:]
            break
    return d


def score_text(s) -> str:
    if not s:
        return "—"
    kind, val = s
    if kind == "mate":
        return f"杀 {val:+d}"
    return f"{val / 100:+.2f}"


def wdl_text(w) -> str:
    """WDL 是千分比：胜/和/负。"""
    if not w:
        return "—"
    total = sum(w) or 1
    return "胜%2d%% 和%2d%% 负%2d%%" % tuple(round(x * 100 / total) for x in w)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["options", "depth", "multipv", "hash"])
    ap.add_argument("--fen", default=START)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--hash", type=int, default=256)
    ap.add_argument("nums", nargs="*", default=[])
    args = ap.parse_args()

    if args.mode == "options":
        e = Engine()
        for line in e.options():
            print(line.replace("option name ", ""))
        e.close()
        return

    if args.mode == "depth":
        depths = [int(x) for x in args.nums] or [8, 12, 16, 20]
        e = Engine(threads=args.threads, hash_mb=args.hash)
        print(f"局面 {args.fen}")
        print(f"threads={args.threads} hash={args.hash}MB\n")
        print(f"{'深度':>4} {'评估':>8} {'和棋概率':>22} {'耗时':>7} {'节点':>10}  着法")
        for d in depths:
            best, info, sec = e.analyze(args.fen, d)
            pv = " ".join(info.get("pv", [])[:4])
            print(
                f"{info.get('depth', d):>4} {score_text(info.get('score')):>8} "
                f"{wdl_text(info.get('wdl')):>22} {sec:>6.1f}s {info.get('nodes', 0):>10,}  "
                f"{best}   ({pv})"
            )
        e.close()
        return

    if args.mode == "multipv":
        depth = int(args.nums[0]) if args.nums else 18
        n = int(args.nums[1]) if len(args.nums) > 1 else 5
        e = Engine(threads=args.threads, hash_mb=args.hash)
        e.send(f"setoption name MultiPV value {n}")
        e.send("isready")
        e.read_until("readyok")
        e.send(f"position fen {args.fen}")
        t0 = time.time()
        e.send(f"go depth {depth}")
        lines = e.read_until("bestmove", timeout=300)
        best_last: dict[int, dict] = {}
        for line in lines:
            if line.startswith("info ") and " pv " in line:
                i = parse(line)
                if i and i.get("depth", 0) >= depth - 1:
                    best_last[i.get("multipv", 1)] = i
        print(f"局面 {args.fen}")
        print(f"depth {depth} threads={args.threads} hash={args.hash}MB 耗时 {time.time() - t0:.1f}s\n")
        print(f"{'#':>2} {'评估':>8} {'胜和负概率':>26}   着法序列")
        for k in sorted(best_last):
            i = best_last[k]
            print(
                f"{k:>2} {score_text(i.get('score')):>8} {wdl_text(i.get('wdl')):>26}   "
                f"{' '.join(i.get('pv', [])[:6])}"
            )
        e.close()
        return

    if args.mode == "hash":
        depth = int(args.nums[0]) if args.nums else 18
        sizes = [int(x) for x in args.nums[1:]] or [16, 32, 128, 256]
        print(f"局面 {args.fen}")
        print(f"depth {depth} threads={args.threads}，每次都是全新进程（模拟冷启动）\n")
        print(f"{'Hash':>6} {'耗时':>8} {'节点':>12} {'NPS':>12}  着法")
        for h in sizes:
            e = Engine(threads=args.threads, hash_mb=h)
            best, info, sec = e.analyze(args.fen, depth)
            print(
                f"{h:>5}MB {sec:>7.2f}s {info.get('nodes', 0):>12,} "
                f"{info.get('nps', 0):>12,}  {best}"
            )
            e.close()


if __name__ == "__main__":
    main()
