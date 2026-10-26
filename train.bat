@echo off
REM 训练 YOLOv8 棋子检测模型（15 类）。
REM
REM 用法：
REM   train.bat                     用环境变量里的配置
REM   train.bat <数据集yaml> [轮数]
REM
REM 需要先准备：
REM   1. Python 环境，已装 torch + ultralytics
REM      → 用环境变量 PYTHON 指定解释器路径，不设则用 PATH 里的 python
REM   2. 数据集 yaml（含 train/val 路径与 15 个类名）
REM      → 可用 tools/build_dataset.py 生成
REM
REM 示例（Windows CMD）：
REM   set PYTHON=C:\venvs\xiangqi-cv\Scripts\python.exe
REM   set DATA=D:\datasets\xiangqi-yolo\xiangqi.yaml
REM   train.bat
chcp 65001 >nul
cd /d "%~dp0"

if "%~1"=="" (
    if not defined DATA (
        echo [错误] 没有指定数据集 yaml。
        echo        用法: train.bat ^<数据集yaml^> [轮数]
        echo        或先 set DATA=...
        pause
        exit /b 1
    )
) else (
    set DATA=%~1
)

if "%~2"=="" (
    if not defined EPOCHS set EPOCHS=80
) else (
    set EPOCHS=%~2
)

if not defined PYTHON set PYTHON=python
if not defined PROJECT set PROJECT=runs

echo === 环境自检 ===
"%PYTHON%" -c "import torch,ultralytics;print('torch',torch.__version__,'cuda',torch.cuda.is_available(),'ultralytics',ultralytics.__version__)"
if errorlevel 1 goto :fail

echo === 开始训练（%EPOCHS% 轮）===
"%PYTHON%" tools\train_yolo.py --data "%DATA%" --epochs %EPOCHS% --project "%PROJECT%" --name xiangqi
if errorlevel 1 goto :fail

echo.
echo === 完成。权重在 %PROJECT%\xiangqi\weights\best.pt ===
pause
exit /b 0

:fail
echo.
echo [失败] 看上面的报错。
pause
exit /b 1
