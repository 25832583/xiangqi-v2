@echo off
REM 训练 YOLOv8 棋子检测模型（15 类）。双击运行，或在本目录执行 train.bat
REM 环境：D:\envs\xiangqi-cv（torch 2.9.1+cu128 / ultralytics 8.4）
chcp 65001 >nul
cd /d "%~dp0"

set PY=D:\envs\xiangqi-cv\Scripts\python.exe
set DATA=D:\datasets\xiangqi-yolo\xiangqi.yaml
REM 本地代理做了证书替换，Python 默认不认，必须指向合并后的证书包
set REQUESTS_CA_BUNDLE=D:\envs\certs\bundle.pem
set PIP_CACHE_DIR=D:\envs\.pip-cache

echo === 环境自检 ===
"%PY%" -c "import torch,ultralytics;print('torch',torch.__version__,'cuda',torch.cuda.is_available(),'ultralytics',ultralytics.__version__)"
if errorlevel 1 goto :fail

echo === 开始训练（80 轮，早停 25）===
"%PY%" tools\train_yolo.py --data "%DATA%" --epochs 80 --batch 12 --project D:/runs --name xiangqi
if errorlevel 1 goto :fail

echo.
echo === 完成。权重在 D:\runs\xiangqi\weights\best.pt ===
pause
exit /b 0

:fail
echo.
echo [失败] 看上面的报错。若提示证书问题，确认 D:\envs\certs\bundle.pem 存在。
pause
exit /b 1
