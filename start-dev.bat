@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 437 >nul
title FZSU Five-Year Plan Dev Launcher
mode con: cols=94 lines=40 >nul 2>&1
cd /d "%~dp0"

set "BACKEND_LOG=%TEMP%\fzsu-backend-dev.log"
set "PC_PORT=5173"
set "H5_PORT=5174"

if /i "%~1"=="--backend" goto backendService

for /f %%a in ('echo prompt $E ^| cmd') do set "ESC=%%a"
set "RESET=%ESC%[0m"
set "BOLD=%ESC%[1m"
set "WHITE=%ESC%[97m"
set "CYAN=%ESC%[96m"
set "BLUE=%ESC%[94m"
set "GREEN=%ESC%[92m"
set "YELLOW=%ESC%[93m"
set "RED=%ESC%[91m"
set "MAGENTA=%ESC%[95m"
set "DIM=%ESC%[90m"

call :banner

echo   %CYAN%[1/6]%RESET%  %BOLD%%WHITE%CONFIG%RESET%    Loading local environment
if not exist "backend\mvnw.cmd" set "ERROR=backend\mvnw.cmd not found. Put this script in the project root." && goto fail
if not exist "frontend\package.json" set "ERROR=frontend\package.json not found." && goto fail
if not exist "h5\package.json" set "ERROR=h5\package.json not found." && goto fail
if not exist ".env" call :initEnv
if errorlevel 1 goto fail
call :loadEnv
if not defined SERVER_PORT set "SERVER_PORT=8081"
if not defined DB_PORT set "DB_PORT=3306"
if not defined DB_HOST set "DB_HOST=localhost"
echo            %GREEN%[ OK ]%RESET%  .env loaded  -  secrets stay local

echo.
echo   %CYAN%[2/6]%RESET%  %BOLD%%WHITE%RUNTIME%RESET%   Checking Java, Node.js and dependencies
where java >nul 2>&1 || (set "ERROR=Java not found. Install JDK 17 and configure PATH." && goto fail)
where node >nul 2>&1 || (set "ERROR=Node.js not found. Install Node.js 20+ and configure PATH." && goto fail)
where npm >nul 2>&1 || (set "ERROR=npm not found. Check your Node.js installation." && goto fail)
for /f "tokens=1" %%v in ('node -v') do set "NODE_VER=%%v"
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~v"
echo            %GREEN%[ OK ]%RESET%  Node.js %NODE_VER%  ^|  Java %JAVA_VER%
call :ensureDeps "frontend" "desktop web"
if errorlevel 1 (set "ERROR=Desktop dependency installation failed. Check npm network settings." && goto fail)
call :ensureDeps "h5" "mobile H5"
if errorlevel 1 (set "ERROR=H5 dependency installation failed. Check npm network settings." && goto fail)
echo            %GREEN%[ OK ]%RESET%  Dependencies ready

echo.
echo   %CYAN%[3/6]%RESET%  %BOLD%%WHITE%DATABASE%RESET%  Checking MySQL on %DB_HOST%:%DB_PORT%
call :portOpen %DB_PORT%
if errorlevel 1 (
    echo            %YELLOW%[WAIT]%RESET%  MySQL is not listening. Trying Docker...
    where docker >nul 2>&1
    if errorlevel 1 (
        set "ERROR=MySQL is stopped and Docker was not found. Start MySQL and retry."
        goto fail
    )
    docker compose up -d mysql
    if errorlevel 1 (
        set "ERROR=Docker MySQL startup failed. Check whether Docker Desktop is running."
        goto fail
    )
    call :waitPort %DB_PORT% 45
    if errorlevel 1 (
        set "ERROR=Timed out waiting for MySQL. Check the database logs."
        goto fail
    )
    echo            %GREEN%[ OK ]%RESET%  MySQL started by Docker
) else (
    echo            %GREEN%[ OK ]%RESET%  MySQL is ready
)

echo.
echo   %CYAN%[4/6]%RESET%  %BOLD%%WHITE%BACKEND%RESET%   Starting Spring Boot on port %SERVER_PORT%
call :portOpen %SERVER_PORT%
if errorlevel 1 (
    if exist "%BACKEND_LOG%" del "%BACKEND_LOG%" >nul 2>&1
    start "FZSU Backend" /D "%~dp0backend" cmd /k ""%~f0" --backend"
    call :waitPort %SERVER_PORT% 90
    if errorlevel 1 (
        set "ERROR=Backend startup timed out. Check the FZSU Backend window."
        goto fail
    )
    echo            %GREEN%[ OK ]%RESET%  Backend ready at http://localhost:%SERVER_PORT%
) else (
    echo            %YELLOW%[SKIP]%RESET%  Backend already running on port %SERVER_PORT%
)

echo.
echo   %CYAN%[5/6]%RESET%  %BOLD%%WHITE%WEB-PC%RESET%    Starting Vue 3 desktop client on port %PC_PORT%
call :portOpen %PC_PORT%
if errorlevel 1 (
    start "FZSU PC Frontend" cmd /k "color 0B && title FZSU PC Frontend && cd /d "%~dp0frontend" && npm run dev -- --host 0.0.0.0"
    call :waitPort %PC_PORT% 30
    if errorlevel 1 (
        set "ERROR=Desktop frontend startup timed out. Check the FZSU PC Frontend window."
        goto fail
    )
    echo            %GREEN%[ OK ]%RESET%  Desktop client ready at http://localhost:%PC_PORT%
) else (
    echo            %YELLOW%[SKIP]%RESET%  Desktop client already running on port %PC_PORT%
)

echo.
echo   %CYAN%[6/6]%RESET%  %BOLD%%WHITE%WEB-H5%RESET%    Starting Vue 3 + Vant mobile client on port %H5_PORT%
call :portOpen %H5_PORT%
if errorlevel 1 (
    start "FZSU H5 Mobile" cmd /k "color 0E && title FZSU H5 Mobile && cd /d "%~dp0h5" && npm run dev -- --host 0.0.0.0 --port %H5_PORT%"
    call :waitPort %H5_PORT% 30
    if errorlevel 1 (
        set "ERROR=H5 frontend startup timed out. Check the FZSU H5 Mobile window."
        goto fail
    )
    echo            %GREEN%[ OK ]%RESET%  Mobile H5 ready at http://localhost:%H5_PORT%
) else (
    echo            %YELLOW%[SKIP]%RESET%  Mobile H5 already running on port %H5_PORT%
)

set "LAN_IP="
set "IP_FILE=%TEMP%\fzsu-lan-ip.txt"
powershell -NoProfile -Command "Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } | Sort-Object InterfaceMetric | Select-Object -First 1 -ExpandProperty IPAddress" > "%IP_FILE%" 2>nul
if exist "%IP_FILE%" set /p LAN_IP=<"%IP_FILE%"
if exist "%IP_FILE%" del "%IP_FILE%" >nul 2>&1

title FZSU Dev Launcher - READY
echo.
echo   %GREEN%+========================================================================+%RESET%
echo   %GREEN%^|%RESET%                         %BOLD%%WHITE%ALL SYSTEMS ARE READY%RESET%                          %GREEN%^|%RESET%
echo   %GREEN%+========================================================================+%RESET%
echo     %BLUE%WEB  (PC)%RESET%     http://localhost:%PC_PORT%
echo     %MAGENTA%MOBILE H5%RESET%     http://localhost:%H5_PORT%
echo     %BLUE%API%RESET%           http://localhost:%SERVER_PORT%
echo     %BLUE%SWAGGER%RESET%       http://localhost:%SERVER_PORT%/swagger-ui.html
if defined LAN_IP echo     %MAGENTA%PHONE (LAN)%RESET%   http://%LAN_IP%:%H5_PORT%
echo   %DIM%------------------------------------------------------------------------%RESET%
echo     %DIM%DEMO LOGIN%RESET%    admin / Test@123456
echo   %GREEN%+========================================================================+%RESET%
echo.
start "" "http://localhost:%PC_PORT%"
start "" "http://localhost:%H5_PORT%"
echo   %DIM%Press any key to close this launcher. All services keep running.%RESET%
pause >nul
exit /b 0

:backendService
cd /d "%~dp0backend"
color 0A
title FZSU Backend
echo FZSU Spring Boot backend - log: %BACKEND_LOG%
echo.
call mvnw.cmd spring-boot:run 2>&1 | powershell -NoProfile -Command "$input | Tee-Object -FilePath $env:BACKEND_LOG"
echo.
echo Backend stopped. See %BACKEND_LOG%
pause
exit /b 1

:initEnv
echo            %YELLOW%[WAIT]%RESET%  First run: creating local .env. It is ignored by Git.
set "DB_PASSWORD="
set /p "DB_PASSWORD=Enter MySQL root password [Metrics@2026]: "
if not defined DB_PASSWORD set "DB_PASSWORD=Metrics@2026"
for /f %%i in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')"') do set "JWT_SECRET=%%i"
for /f %%i in ('powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')"') do set "FILE_SIGN_SECRET=%%i"
(
    echo DB_HOST=localhost
    echo DB_PORT=3306
    echo DB_NAME=metrics_db
    echo DB_USER=root
    echo DB_PASSWORD=!DB_PASSWORD!
    echo JWT_SECRET=!JWT_SECRET!
    echo FILE_SIGN_SECRET=!FILE_SIGN_SECRET!
    echo FILE_STORAGE_TYPE=LOCAL
    echo FILE_STORAGE_DIR=./data/files
    echo SERVER_PORT=8081
    echo SWAGGER_ENABLED=true
) > ".env"
if errorlevel 1 (
    set "ERROR=Cannot write .env. Check project directory permissions."
    exit /b 1
)
echo            %GREEN%[ OK ]%RESET%  .env created
exit /b 0

:loadEnv
for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
    if not "%%A"=="" set "%%A=%%B"
)
exit /b 0

:ensureDeps
if exist "%~1\node_modules" exit /b 0
echo            %YELLOW%[WAIT]%RESET%  First run: installing %~2 dependencies...
pushd "%~1"
if exist "package-lock.json" (call npm ci) else (call npm install)
if errorlevel 1 (
    popd
    exit /b 1
)
popd
exit /b 0

:portOpen
powershell -NoProfile -Command "try { $c = New-Object System.Net.Sockets.TcpClient; $c.Connect('127.0.0.1', %1); $c.Close(); exit 0 } catch { exit 1 }" >nul 2>&1
exit /b %errorlevel%

:waitPort
powershell -NoProfile -Command "$port=%1; $limit=%2; $esc=[char]27; $frames=@('|','/','-','\'); $sw=[Diagnostics.Stopwatch]::StartNew(); $i=0; while($sw.Elapsed.TotalSeconds -lt $limit){ try { $client=New-Object Net.Sockets.TcpClient; $client.Connect('127.0.0.1',$port); $client.Close(); [Console]::Write([char]13+(' '*78)+[char]13); exit 0 } catch { $frame=$frames[$i]; [Console]::Write([char]13+'            '+$esc+'[93m'+$frame+$esc+'[0m  Waiting for port '+$port+'  '+[int]$sw.Elapsed.TotalSeconds+'s / '+$limit+'s   '); Start-Sleep -Milliseconds 160; $i=($i+1) -band 3 } }; [Console]::Write([char]13+(' '*78)+[char]13); exit 1"
exit /b %errorlevel%

:banner
cls
powershell -NoProfile -Command "$esc=[char]27; $c=$esc+'[96m'; $b=$esc+'[94m'; $w=$esc+'[97m'; $d=$esc+'[90m'; $r=$esc+'[0m'; $lines=@('','  +========================================================================+','  |  FZSU  //  FIVE-YEAR PLAN  //  LOCAL DEV LAUNCHER                v2.1  |','  +------------------------------------------------------------------------+','  |  BACKEND   Spring Boot 3 + MySQL 8      WEB       Vue 3 + Element Plus |','  |  MOBILE    Vue 3 + Vant 4  (H5)         API       REST  /api/v1        |','  +========================================================================+',''); $n=0; foreach($line in $lines){ $color=$c; if($n -eq 2){$color=$w}; if($n -eq 4 -or $n -eq 5){$color=$b}; [Console]::WriteLine($color+$line+$r); Start-Sleep -Milliseconds 55; $n++ }; for($i=0;$i -le 28;$i++){ $done='='*$i; $todo=' '*(28-$i); [Console]::Write([char]13+'  '+$c+'['+$w+$done+$d+$todo+$c+']'+$r+'  BOOT SEQUENCE'); Start-Sleep -Milliseconds 20 }; [Console]::Write([char]13+(' '*78)+[char]13); [Console]::WriteLine()"
exit /b 0

:fail
echo.
echo   %RED%+========================================================================+%RESET%
echo   %RED%^|%RESET%                             %BOLD%%WHITE%STARTUP FAILED%RESET%                             %RED%^|%RESET%
echo   %RED%+========================================================================+%RESET%
echo.
echo   %RED%%ERROR%%RESET%
echo.
pause
exit /b 1
