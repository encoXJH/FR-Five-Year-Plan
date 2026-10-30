@echo off
chcp 65001 >nul
setlocal
set "ROOT=%~dp0"

where python >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 Python，请先安装并加入 PATH。
    pause
    exit /b 1
)

where npm >nul 2>nul
if errorlevel 1 (
    echo [错误] 未找到 npm，请先安装 Node.js 并加入 PATH。
    pause
    exit /b 1
)

echo 正在启动后端服务：http://localhost:8000
start "Five-year Backend" /D "%ROOT%backend" cmd /k "python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000"

echo 正在启动前端服务：http://localhost:5173
start "Five-year Frontend" /D "%ROOT%frontend" cmd /k "npm run dev -- --host 0.0.0.0 --port 5173"

timeout /t 3 /nobreak >nul
start "" "http://localhost:5173"

echo 前后端启动命令已执行，可在新打开的窗口中查看运行日志。
endlocal
