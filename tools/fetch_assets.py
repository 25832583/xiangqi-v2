#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一条命令补齐运行时资产。

模型与引擎权重不入库（体积 + 许可，见 NOTICE.md），克隆后必须补齐才能跑。
本脚本按各自官方来源下载、解压、校验、放到正确路径。重复执行是幂等的。

用法：
    python tools/fetch_assets.py            补齐所有能自动获取的
    python tools/fetch_assets.py --check    只看缺什么，不下载
    python tools/fetch_assets.py --mirror https://gh-proxy.com/    GitHub 慢时套加速
    python tools/fetch_assets.py --dest /tmp/x                 下到别处（试跑用）

能自动拿到的：
    pikafish / pikafish.nnue   ← 皮卡鱼官方 release（GPL-3.0）
    detector_xqlink_v5.onnx    ← 本项目 release（Apache-2.0 基座微调）

拿不到的：
    board_locator_v1.onnx      上游仓库无许可文件，不得分发。缺了会自动退回
                               棋子包络反推，识别仍可用，只是特殊皮肤兜底弱一点。

关于 SSL：本机若走了会替换证书的代理，urllib 会报 CERTIFICATE_VERIFY_FAILED。
把系统根证书导出成 PEM 后指向它即可：
    export SSL_CERT_FILE=/path/to/bundle.pem
"""

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

# 下载缓存放在系统临时目录，绝不能落在 assets/ 里——那会被打进 APK
CACHE_DIR = Path(tempfile.gettempdir()) / "xiangqi-v2-assets-cache"

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
JNI_DIR = ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"

# 引擎与权重是绑定的一对，版本必须一起换
PIKAFISH_VERSION = "2026-09-06"
PIKAFISH_ARCHIVE = f"Pikafish.{PIKAFISH_VERSION}.7z"
PIKAFISH_URL = (
    "https://github.com/official-pikafish/Pikafish/releases/download/"
    f"Pikafish-{PIKAFISH_VERSION}/{PIKAFISH_ARCHIVE}"
)

# 引擎二进制在官方包里的位置**换过一次**：2026-01 及更早是 Android/pikafish-armv8，
# 2026-09 起改成包根目录的 Pikafish-Android-arm64-universal（多构建合成一个 universal）。
# 写死一条路径，官方下次再整理一次包就又失效，而且是报「解压失败」这种指错方向的话。
# 所以按顺序试，真找不到时把实际有的条目列出来。
PIKAFISH_ENGINE_MEMBERS = (
    "Pikafish-Android-arm64-universal",
    "Android/pikafish-armv8",
)
PIKAFISH_WEIGHTS_MEMBER = "pikafish.nnue"

DETECTOR_NAME = "detector_xqlink_v5.onnx"
DETECTOR_URL = (
    "https://github.com/25832583/xiangqi-v2/releases/download/assets-v1/" + DETECTOR_NAME
)

LOCATOR_NAME = "board_locator_v1.onnx"

# 参考校验值，取自本项目实测通过的那一版。
# 不匹配只警告不阻断——有人可能有意换版本，硬失败反而挡路。
KNOWN_MD5 = {
    "pikafish": "0363e738a7ef3dbb822d2a930ae042af",
    "pikafish.nnue": "43819bf90a990918a71897339b3b9cb9",
    DETECTOR_NAME: "d346664b010e398b1a63de0a89225192",
}


def md5_of(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def human(size: int) -> str:
    """十进制口径（1MB = 1e6），与 App 内显示、皮卡鱼官网一致。

    别用 1024：那会把 53.3MB 的权重量成 50.7MB，用户对着 App 里显示的数字
    会怀疑自己下错了文件。
    """
    for unit, base in (("GB", 1_000_000_000), ("MB", 1_000_000), ("KB", 1_000)):
        if size >= base:
            return f"{size / base:.1f}{unit}"
    return f"{size}B"


def download(url: str, dest: Path, mirror: str = "") -> None:
    full = (mirror.rstrip("/") + "/" + url) if mirror else url
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(full, headers={"User-Agent": "xiangqi-v2-fetch"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            total = int(resp.headers.get("Content-Length") or 0)
            done = 0
            with tmp.open("wb") as out:
                while True:
                    chunk = resp.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk)
                    done += len(chunk)
                    if total:
                        pct = done * 100 // total
                        sys.stdout.write(f"\r  {pct:3d}%  {human(done)}/{human(total)}")
                        sys.stdout.flush()
        sys.stdout.write("\r" + " " * 60 + "\r\n")
    except urllib.error.URLError as exc:
        tmp.unlink(missing_ok=True)
        reason = getattr(exc, "reason", exc)
        raise SystemExit(
            f"下载失败：{reason}\n"
            f"  URL: {full}\n"
            "  若是证书错误（CERTIFICATE_VERIFY_FAILED），先设 SSL_CERT_FILE 指向系统根证书 PEM。\n"
            "  若是连不上或极慢，试试 --mirror https://gh-proxy.com/"
        )
    tmp.replace(dest)


def _is_bsdtar(path: str) -> bool:
    """只有 bsdtar 能读 7z；GNU tar（Git 自带那个）不能，名字却一样。"""
    try:
        r = subprocess.run(
            [path, "--version"], capture_output=True, text=True, timeout=10
        )
        return "bsdtar" in (r.stdout + r.stderr).lower()
    except (OSError, subprocess.SubprocessError):
        return False


def _bsdtar_candidates():
    seen = set()
    for name in ("bsdtar", "tar"):
        path = shutil.which(name)
        if path and path not in seen and _is_bsdtar(path):
            seen.add(path)
            yield path
    # Windows 自带的 bsdtar 在 System32 里，通常不在 PATH 上
    if os.name == "nt":
        path = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "tar.exe"
        if path.exists() and str(path) not in seen and _is_bsdtar(str(path)):
            yield str(path)


def extract_members(archive: Path, members: list, out_dir: Path) -> str:
    """从 7z 里只解出需要的几个文件，返回用了哪个后端。"""
    out_dir.mkdir(parents=True, exist_ok=True)

    try:
        import py7zr

        with py7zr.SevenZipFile(archive) as z:
            z.extract(path=out_dir, targets=members)
        return "py7zr"
    except ImportError:
        pass

    for tar in _bsdtar_candidates():
        r = subprocess.run(
            [tar, "-xf", str(archive), "-C", str(out_dir), *members],
            capture_output=True,
        )
        if r.returncode == 0:
            return f"bsdtar ({tar})"

    for exe in ("7z", "7za", "7zz"):
        path = shutil.which(exe)
        if not path:
            continue
        r = subprocess.run(
            [path, "x", "-y", f"-o{out_dir}", str(archive), *members],
            capture_output=True,
        )
        if r.returncode == 0:
            return exe

    raise SystemExit(
        "解压 7z 失败：没有可用的解压后端。\n"
        "  最省事的办法：pip install py7zr\n"
        "  Windows 10+ 自带 bsdtar（System32\\tar.exe），若也失败说明归档损坏。\n"
        f"  归档位置：{archive}"
    )


def list_members(archive: Path):
    """列出归档里的路径；一个后端都拿不到时返回 None。

    存在的意义是**能说清楚「包里到底有什么」**：官方改过包结构一次，
    当时的故障表现是「解压失败」——把人指向解压后端，而真因是文件名变了。
    """
    try:
        import py7zr

        with py7zr.SevenZipFile(archive) as z:
            return z.getnames()
    except ImportError:
        pass
    except Exception:
        return None

    for tar in _bsdtar_candidates():
        r = subprocess.run(
            [tar, "-tf", str(archive)], capture_output=True, text=True, errors="replace"
        )
        if r.returncode == 0:
            return [line.strip() for line in r.stdout.splitlines() if line.strip()]

    for exe in ("7z", "7za", "7zz"):
        path = shutil.which(exe)
        if not path:
            continue
        r = subprocess.run(
            [path, "l", "-ba", str(archive)], capture_output=True, text=True, errors="replace"
        )
        if r.returncode == 0:
            names = []
            for line in r.stdout.splitlines():
                cols = line.split(None, 5)
                if len(cols) == 6:
                    names.append(cols[5].strip())
            return names
    return None


def report(name: str, path: Path, optional: bool = False) -> bool:
    if path.exists() and path.stat().st_size > 0:
        got = md5_of(path)
        expect = KNOWN_MD5.get(name)
        flag = ""
        if expect and got != expect:
            flag = f"  ! md5 与实测版本不同（{got[:8]} ≠ {expect[:8]}），可能是别的构建"
        print(f"  [有] {name:<26} {human(path.stat().st_size):>9}{flag}")
        return True
    label = "可选" if optional else "缺失"
    print(f"  [{label}] {name:<24} {'-':>9}")
    return False


def ensure_pikafish(dest_dir: Path, mirror: str, force: bool) -> None:
    landed = (dest_dir / "pikafish", dest_dir / "pikafish.nnue")
    if not force and all(p.exists() and p.stat().st_size > 0 for p in landed):
        print("皮卡鱼：已存在，跳过")
        return

    archive = CACHE_DIR / PIKAFISH_ARCHIVE
    if not (archive.exists() and archive.stat().st_size > 40 << 20):
        print(f"皮卡鱼 {PIKAFISH_VERSION}：下载引擎包（约 54MB）…")
        download(PIKAFISH_URL, archive, mirror)

    names = list_members(archive)
    picked = None
    if names:
        picked = next((m for m in PIKAFISH_ENGINE_MEMBERS if m in names), None)
        if picked is None:
            androidish = [n for n in names if "ndroid" in n or "arm" in n.lower()]
            raise SystemExit(
                "官方包里找不到 Android arm64 引擎 —— 包结构又变了。\n"
                f"  脚本试过：{', '.join(PIKAFISH_ENGINE_MEMBERS)}\n"
                f"  包里与 Android/arm 相关的条目：{', '.join(androidish) or '(一个都没有)'}\n"
                "  处理办法：把实际的文件名加进 PIKAFISH_ENGINE_MEMBERS 的最前面。"
            )
    else:
        # 列不出来（解压后端只支持按名抽取）就按最新的位置试，失败时再报
        picked = PIKAFISH_ENGINE_MEMBERS[0]

    print(f"  解出 arm64 二进制与权重（{picked}）…")
    backend = extract_members(archive, [picked, PIKAFISH_WEIGHTS_MEMBER], dest_dir)
    print(f"  解压后端：{backend}")

    for member, name in ((picked, "pikafish"), (PIKAFISH_WEIGHTS_MEMBER, "pikafish.nnue")):
        src = dest_dir / member
        if not src.exists():
            raise SystemExit(f"包里没找到 {member}，官方包结构可能变了")
        dst = dest_dir / name
        if src != dst:
            shutil.move(str(src), str(dst))
        print(f"  -> {name}  {human(dst.stat().st_size)}")

    # 解压出来的空壳目录留着没意义
    leftover = dest_dir / "Android"
    if leftover.is_dir():
        try:
            leftover.rmdir()
        except OSError:
            pass


def ensure_detector(dest_dir: Path, mirror: str, force: bool) -> None:
    dst = dest_dir / DETECTOR_NAME
    if not force and dst.exists() and dst.stat().st_size > 0:
        print("检测模型：已存在，跳过")
        return
    print(f"检测模型：下载（约 27MB）…")
    download(DETECTOR_URL, dst, mirror)
    print(f"  -> {DETECTOR_NAME}  {human(dst.stat().st_size)}")


def sync_jni(dest_dir: Path) -> None:
    """pikafish 必须再有一份放进 jniLibs。

    Android 10 起不能执行应用可写目录里的文件（W^X），nativeLibraryDir 是唯一
    带可执行权限的地方，所以引擎要以 .so 的形式随包安装。
    """
    src = dest_dir / "pikafish"
    if not src.exists():
        return
    jni = (JNI_DIR if dest_dir == ASSETS else dest_dir) / "libpikafish.so"
    jni.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, jni)
    try:
        shown = jni.relative_to(ROOT)
    except ValueError:
        shown = jni
    print(f"  -> {shown}")


def main() -> int:
    ap = argparse.ArgumentParser(description="补齐 xiangqi-v2 的运行时资产")
    ap.add_argument("--check", action="store_true", help="只检查不下载")
    ap.add_argument("--force", action="store_true", help="已存在也重新下载")
    ap.add_argument("--mirror", default="", help="GitHub 加速前缀，如 https://gh-proxy.com/")
    ap.add_argument("--dest", default="", help="下载到指定目录（默认 app/src/main/assets）")
    args = ap.parse_args()

    dest = Path(args.dest).resolve() if args.dest else ASSETS
    dest.mkdir(parents=True, exist_ok=True)

    print(f"目标目录：{dest}\n")
    print("当前状态：")
    has_fish = report("pikafish", dest / "pikafish")
    has_nnue = report("pikafish.nnue", dest / "pikafish.nnue")
    has_det = report(DETECTOR_NAME, dest / DETECTOR_NAME)
    has_loc = report(LOCATOR_NAME, dest / LOCATOR_NAME, optional=True)
    print()

    if args.check:
        ok = has_fish and has_nnue and has_det
        print("结论：" + ("可以编译运行" if ok else "缺文件，跑一次不带 --check 即可补齐"))
        if not has_loc:
            print("      （board_locator_v1 缺失不影响使用，只是特殊皮肤少一层兜底）")
        return 0 if ok else 1

    ensure_pikafish(dest, args.mirror, args.force)
    ensure_detector(dest, args.mirror, args.force)
    sync_jni(dest)

    print("\n完成。校验：")
    ok = report("pikafish", dest / "pikafish")
    ok = report("pikafish.nnue", dest / "pikafish.nnue") and ok
    ok = report(DETECTOR_NAME, dest / DETECTOR_NAME) and ok
    report(LOCATOR_NAME, dest / LOCATOR_NAME, optional=True)

    if not ok:
        return 1
    print("\n下一步：./gradlew assembleDebug")
    return 0


if __name__ == "__main__":
    sys.exit(main())
