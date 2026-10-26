@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem 找 Python。先真跑一次 import，避免 Windows 商店那个占位 exe 混进来
set "PY="
python -c "import sys" >nul 2>nul
if not errorlevel 1 set "PY=python"
if not defined PY (
  py -3 -c "import sys" >nul 2>nul
  if not errorlevel 1 set "PY=py -3"
)
if not defined PY (
  echo.
  echo [X] 找不到可用的 Python 3。
  echo     装的时候记得勾选 "Add python.exe to PATH"，装完重开一个窗口再跑。
  echo     下载：https://www.python.org/downloads/
  echo.
  pause
  exit /b 1
)

%PY% tools\setup.py %*
set "CODE=%errorlevel%"
if not "%CODE%"=="0" (
  echo.
  pause
)
exit /b %CODE%
