#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一键把 xiangqi-v2 从源码编成可安装的 APK。

替掉手工的四步：找 SDK -> 写 local.properties -> 补资产 -> 编译。
每一步失败都会说清楚卡在哪、下一步做什么，不静默跳过。

用法：
    python tools/setup.py            体检 + 补资产 + 编译
    python tools/setup.py --check    只体检，不改任何文件
    python tools/setup.py --clean    编译前先 clean（体积异常时用）
    python tools/setup.py --sdk D:/Android/Sdk   手动指定 SDK
    python tools/setup.py --jdk D:/jdk25         手动指定 JDK
    python tools/setup.py --mirror https://gh-proxy.com/   GitHub 慢时加速

为什么要有这个脚本：手工流程里最容易卡的四件事是
  1. 代码块里的注释被一起复制进命令行（# 在 Windows 原生终端不是注释符）
  2. 不知道 local.properties 要写什么，报 SDK location not found
  3. Gradle 去网上找 JDK，网络受限时卡十几分钟没有任何输出
  4. 备料脚本漏跑，编出来的包缺模型，装上后识别永远没反应
这些都能由机器判断，不该让人对着文档猜。
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
IS_WIN = os.name == "nt"
# AGP 9 要求用 JDK 25 运行 Gradle；比它低的会直接失败
MIN_JDK = 25
# compileSdk，缺这个 platform 时 Gradle 会尝试自己下
TARGET_PLATFORM = "android-34"


# ---------------------------------------------------------------- 输出

def _fix_console() -> None:
    """Windows 控制台默认不是 UTF-8，中文会变成乱码。

    只调自己的流编码不可靠——代码页还是 936。所以连代码页一起改；
    chcp 影响当前控制台，退出后不保留。

    顺带开行缓冲：子进程（Gradle、fetch_assets）的输出会直接落到控制台，
    父进程的 print 若还攒在缓冲区里，就会出现「子进程日志跑到前面」的乱序，
    读起来像是步骤错乱了。
    """
    if IS_WIN:
        try:
            subprocess.run(
                ["chcp", "65001"],
                shell=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False,
            )
        except Exception:
            pass
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
        except Exception:
            try:
                stream.reconfigure(line_buffering=True)
            except Exception:
                pass


def step(index: int, total: int, title: str) -> None:
    print(f"\n[{index}/{total}] {title}")


def ok(msg: str) -> None:
    print(f"      OK   {msg}")


def warn(msg: str) -> None:
    print(f"      !    {msg}")


def info(msg: str) -> None:
    print(f"           {msg}")


def die(msg: str, hint: str = "") -> "None":
    print(f"\n      X    {msg}")
    if hint:
        for line in hint.strip().splitlines():
            print(f"           {line.strip()}")
    print("\n构建中止。\n")
    sys.exit(1)


# ---------------------------------------------------------------- SDK

def _sdk_ok(path: Path) -> bool:
    """够不够用：至少要有 platforms 或 build-tools 之一。

    只查目录存在是不够的——空的 Android/Sdk 目录很常见（装过 Studio 又清过缓存）。
    """
    return path.is_dir() and ((path / "platforms").is_dir() or (path / "build-tools").is_dir())


def _sdk_from_local_properties() -> "Path | None":
    lp = ROOT / "local.properties"
    if not lp.exists():
        return None
    try:
        for line in lp.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if line.startswith("sdk.dir="):
                return Path(line.split("=", 1)[1].strip().replace("\\", "/"))
    except OSError:
        return None
    return None


def sdk_candidates():
    home = Path.home()
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        raw = os.environ.get(env)
        if raw:
            yield Path(raw.replace("\\", "/")), env
    from_lp = _sdk_from_local_properties()
    if from_lp:
        yield from_lp, "local.properties"
    if IS_WIN:
        local = os.environ.get("LOCALAPPDATA")
        if local:
            yield Path(local) / "Android" / "Sdk", "LOCALAPPDATA"
        for drive in ("C", "D", "E"):
            yield Path(f"{drive}:/Android/Sdk"), "常见安装位置"
            yield Path(f"{drive}:/Android/sdk"), "常见安装位置"
    elif sys.platform == "darwin":
        yield home / "Library" / "Android" / "sdk", "默认位置"
    else:
        yield home / "Android" / "Sdk", "默认位置"


def find_sdk(explicit: str) -> "tuple[Path, str]":
    if explicit:
        p = Path(explicit.replace("\\", "/"))
        if not _sdk_ok(p):
            die(f"指定的 SDK 目录不可用：{p}",
                "该目录下应该有 platforms/ 或 build-tools/ 子目录。")
        return p, "--sdk 指定"

    tried = []
    for path, why in sdk_candidates():
        tried.append(f"{path}  ({why})")
        if _sdk_ok(path):
            return path, why

    die("找不到 Android SDK。",
        "装法二选一：\n"
        "  a) 装 Android Studio，启动一次让它自动下载 SDK\n"
        "  b) 只装命令行工具：https://developer.android.com/studio#command-line-tools-only\n"
        "然后重跑，或直接指定：python tools/setup.py --sdk 你的SDK路径\n\n"
        "已查找过：\n  " + "\n  ".join(tried[:6]))


def write_local_properties(sdk: Path) -> bool:
    """返回是否真的改了文件。

    保留原有内容：这个文件里可能还有别的键，覆盖掉等于替用户做决定。
    """
    lp = ROOT / "local.properties"
    value = f"sdk.dir={sdk.as_posix()}"
    if lp.exists():
        lines = lp.read_text(encoding="utf-8", errors="replace").splitlines()
        if any(line.strip().startswith("sdk.dir=") and line.split("=", 1)[1].strip().replace("\\", "/") == sdk.as_posix()
               for line in lines if "=" in line):
            return False
        kept = [line for line in lines if not line.strip().startswith("sdk.dir=")]
        new = value + "\n" + "".join(
            (line if line.endswith("\n") else line + "\n") for line in kept
        )
    else:
        new = "# 由 tools/setup.py 生成；此文件不入仓库\n" + value + "\n"
    lp.write_text(new, encoding="utf-8", newline="\n")
    return True


# ---------------------------------------------------------------- JDK

_VER_RE = re.compile(r'JAVA_VERSION="([^"]+)"')


def jdk_major(path: Path) -> int:
    """读 release 文件拿主版本，不启动 java。

    启动 java 一是慢，二是某些机器上 JAVA_HOME 指向的是 JRE，跑起来才发现不行。
    8 之前是 "1.8.0"，之后是 "25.0.1"。
    """
    release = path / "release"
    if not release.is_file():
        return 0
    try:
        m = _VER_RE.search(release.read_text(encoding="utf-8", errors="replace"))
    except OSError:
        return 0
    if not m:
        return 0
    raw = m.group(1)
    if raw.startswith("1."):
        raw = raw[2:]
    try:
        return int(raw.split(".")[0].split("-")[0].split("+")[0])
    except ValueError:
        return 0


def _jdk_from_gradle_properties():
    """从用户级 gradle.properties 里捡 JDK 路径。

    用户为了绕开 AGP 的 toolchain 联网下载，多半已经在这里写过
    org.gradle.java.installations.paths，机器上现成的 25 就在其中。
    再从零猜安装位置，等于把用户已经告诉过的事情重新问一遍。
    """
    gp = Path.home() / ".gradle" / "gradle.properties"
    if not gp.is_file():
        return
    try:
        lines = gp.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return
    for line in lines:
        line = line.strip()
        if line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() != "org.gradle.java.installations.paths":
            continue
        for item in value.split(","):
            item = item.strip().replace("\\", "/")
            if item:
                yield Path(item)


def jdk_candidates():
    home = Path.home()
    yield from _jdk_from_gradle_properties()
    if IS_WIN:
        for base in (os.environ.get("ProgramFiles", "C:/Program Files"),
                     os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)"),
                     os.environ.get("LOCALAPPDATA", "")):
            if base:
                yield Path(base) / "Android" / "Android Studio" / "jbr"
                yield Path(base) / "Android" / "Android Studio Preview" / "jbr"
        java_root = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Java"
        if java_root.is_dir():
            try:
                for child in sorted(java_root.iterdir()):
                    yield child
            except OSError:
                pass
        adoptium = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Eclipse Adoptium"
        if adoptium.is_dir():
            try:
                for child in sorted(adoptium.iterdir()):
                    yield child
            except OSError:
                pass
    elif sys.platform == "darwin":
        yield Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    else:
        yield Path("/opt/android-studio/jbr")
        yield home / "android-studio" / "jbr"
        yield Path("/usr/lib/jvm/default-java")


def find_jdk(explicit: str) -> "tuple[Path, int] | None":
    seen = set()

    def check(path: Path) -> "tuple[Path, int] | None":
        try:
            key = str(path.resolve())
        except OSError:
            return None
        if key in seen:
            return None
        seen.add(key)
        major = jdk_major(path)
        if major >= MIN_JDK:
            return path, major
        return None

    if explicit:
        p = Path(explicit.replace("\\", "/"))
        found = check(p)
        if not found:
            die(f"指定的 JDK 版本不够：{p}（读到的版本 {jdk_major(p) or '未知'}，需要 {MIN_JDK}+）",
                "AGP 9 要求 JDK 25 运行 Gradle。Android Studio 自带的 jbr 通常就是。")
        return found

    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        found = check(Path(java_home.replace("\\", "/")))
        if found:
            return found

    for cand in jdk_candidates():
        found = check(cand)
        if found:
            return found
    return None


# ---------------------------------------------------------------- 构建

def run(cmd: list, desc: str) -> None:
    info(desc)
    result = subprocess.run(cmd, cwd=str(ROOT))
    if result.returncode != 0:
        die(f"失败：{desc}（退出码 {result.returncode}）", _build_hint(result.returncode))


def _build_hint(code: int) -> str:
    return (
        "常见原因，按可能性排：\n"
        "  - 卡住十几分钟没输出后失败：Gradle 在联网找 JDK。用 --jdk 指定本机 JDK 25\n"
        "  - SDK location not found：用 --sdk 指定 SDK，或检查 local.properties\n"
        "  - 缺 platform android-34：在 Android Studio 的 SDK Manager 里装 Android 14 (API 34)\n"
        "  - 体积异常（明显大于 95MB）：加 --clean 重来\n"
        f"完整日志在上面的输出里（退出码 {code}）。"
    )


def assets_ready() -> bool:
    result = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "fetch_assets.py"), "--check"],
        cwd=str(ROOT), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return result.returncode == 0


def apk_path() -> Path:
    return ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


def human(n: int) -> str:
    if n >= 1_000_000_000:
        return f"{n / 1_000_000_000:.1f}GB"
    if n >= 1_000_000:
        return f"{n / 1_000_000:.1f}MB"
    if n >= 1_000:
        return f"{n / 1_000:.1f}KB"
    return f"{n}B"


# ---------------------------------------------------------------- 主流程

def main() -> int:
    _fix_console()
    ap = argparse.ArgumentParser(description="一键构建 xiangqi-v2")
    ap.add_argument("--check", action="store_true", help="只体检，不下载不编译")
    ap.add_argument("--clean", action="store_true", help="编译前先 clean")
    ap.add_argument("--sdk", default="", help="Android SDK 路径")
    ap.add_argument("--jdk", default="", help="JDK 25 路径")
    ap.add_argument("--mirror", default="", help="GitHub 加速前缀，如 https://gh-proxy.com/")
    args = ap.parse_args()

    total = 5 if args.check else 4
    print("xiangqi-v2 一键构建")
    print("=" * 58)
    info(f"工程目录：{ROOT}")
    info(f"平台：{'Windows' if IS_WIN else sys.platform}   Python {sys.version.split()[0]}")

    # 1. Python 版本
    step(1, total, "检查 Python")
    if sys.version_info < (3, 8):
        die(f"Python {sys.version.split()[0]} 太旧，需要 3.8+")
    ok(f"Python {sys.version.split()[0]}")

    # 2. SDK
    step(2, total, "定位 Android SDK")
    sdk, why = find_sdk(args.sdk)
    ok(f"{sdk.as_posix()}  ({why})")
    if args.check:
        if (sdk / "platforms" / TARGET_PLATFORM).is_dir():
            ok(f"platforms/{TARGET_PLATFORM} 已装")
        else:
            warn(f"缺 platforms/{TARGET_PLATFORM}，编译时 Gradle 可能会自己下载")
    else:
        changed = write_local_properties(sdk)
        ok("已写入 local.properties" if changed else "local.properties 已正确，未改动")

    # 3. JDK
    step(3, total, "定位 JDK")
    jdk = find_jdk(args.jdk)
    if jdk:
        path, major = jdk
        ok(f"JDK {major}  {path.as_posix()}")
    else:
        warn(f"没找到 JDK {MIN_JDK}，交给 Gradle 自己找")
        info("若随后报 JDK 版本不对或卡十几分钟，用 --jdk 指定（Android Studio 的 jbr 通常够）")

    # 4. 资产
    step(4, total, "补齐模型和引擎")
    if args.check:
        subprocess.run([sys.executable, str(ROOT / "tools" / "fetch_assets.py"), "--check"], cwd=str(ROOT))
        print("\n" + "=" * 58)
        info("体检结束。不带 --check 再跑一次就会补齐并编译。")
        return 0

    if assets_ready():
        ok("已齐，跳过下载")
    else:
        cmd = [sys.executable, str(ROOT / "tools" / "fetch_assets.py")]
        if args.mirror:
            cmd += ["--mirror", args.mirror]
        run(cmd, "调用 fetch_assets.py（约 80MB）")

    # 5. 编译
    step(total, total, "编译 APK")
    if IS_WIN:
        cmd = ["cmd", "/c", str(ROOT / "gradlew.bat")]
    else:
        cmd = [str(ROOT / "gradlew")]
    if jdk:
        cmd.append(f"-Dorg.gradle.java.home={jdk[0].as_posix()}")
    if args.clean:
        cmd.append("clean")
    cmd.append("assembleDebug")
    info("首次编译 1~3 分钟，之后有缓存会快很多")
    # Gradle 自己的输出原样透传：卡住时那几十行才是线索，藏起来等于让人瞎猜
    result = subprocess.run(cmd, cwd=str(ROOT))
    if result.returncode != 0:
        die(f"编译失败（退出码 {result.returncode}）", _build_hint(result.returncode))

    # 收尾
    apk = apk_path()
    if not apk.is_file():
        die("编译报成功，但没找到 app-debug.apk。",
            "跑一次 --clean 重来；仍不行请把上面的输出贴到 issues。")

    size = apk.stat().st_size
    md5 = hashlib.md5(apk.read_bytes()).hexdigest()
    print("\n" + "=" * 58)
    print(f"完成：{apk}")
    info(f"体积 {human(size)}   md5 {md5}")

    # 两种"比预期小"的原因完全不同，别混成一句话
    locator = ROOT / "app" / "src" / "main" / "assets" / "board_locator_v1.onnx"
    if size < 60_000_000:
        warn("这个包偏小，多半缺 NNUE 权重（完整包约 95MB）。引擎会退到无权重模式，")
        warn("识别不受影响，但棋力明显下降。跑一次不带 --check 的 fetch_assets.py 补上。")
    elif not locator.is_file():
        info("比 95MB 略小是正常的：board_locator_v1.onnx 上游没有许可文件、不能随仓库分发，")
        info("所以自动备料会跳过它。缺它只少一层棋盘框兜底，识别照常。")

    print("\n装到手机：")
    info(f'adb install -r "{apk}"')
    info("或把 APK 传到手机点安装（需允许「未知来源」）")
    print("\n装好后：授予 无障碍 + 悬浮窗 + 通知 -> 打开棋类 App -> 点悬浮球")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
