#!/usr/bin/env sh
# 一键构建 xiangqi-v2。用法：./setup.sh [--check] [--clean] [--mirror URL] ...
set -e
cd "$(dirname "$0")"

if command -v python3 >/dev/null 2>&1; then
    PY=python3
elif command -v python >/dev/null 2>&1; then
    PY=python
else
    echo "[X] 找不到 python3。装好后再跑一次：https://www.python.org/downloads/"
    exit 1
fi

exec "$PY" tools/setup.py "$@"
