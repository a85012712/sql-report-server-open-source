@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo.
echo ========================================
echo   HIS报表查询系统 - 清理历史备份与旧日志
echo ========================================
echo.

REM ========== 1. 程序包版本留存：保留最近 KEEP 个版本，删除更老的 ==========
REM 各版本以 sql-report-server-<版本>.jar 并存（不再覆盖同名包），按修改时间倒序保留最新 KEEP 个
REM 保留的旧版本包个数 = 回滚深度。发版是手工拷 jar、无 CI，唯一回滚手段就是上一版 jar。
REM 早先 KEEP=3 意味着发到第 4 版时，唯一能回滚的那份会被本脚本自己删掉。
set "KEEP=5"
set "JARN=0"
set "DELN=0"
for /f "delims=" %%f in ('dir /b /a-d /o-d "sql-report-server-*.jar" 2^>nul') do (
    set /a JARN+=1
    if !JARN! GTR %KEEP% (
        del /q "%%f" >nul 2>&1
        if exist "%%f" (
            echo [FAIL] %%f  (in use? service still running?)
        ) else (
            echo [DEL] %%f
            set /a DELN+=1
        )
    )
)
echo [OK] 程序包共 !JARN! 个，保留最近 %KEEP% 个，已清理 !DELN! 个旧版本

REM ========== 2. 清理历史 .bak 备份（旧流程遗留；现已改为按版本号留存） ==========
set "JARBAK=0"
for %%f in (sql-report-server-*.jar.bak*) do (
    del /q "%%f" >nul 2>&1
    if exist "%%f" (
        echo [FAIL] %%f  (in use? service still running?)
    ) else (
        echo [DEL] %%f
        set /a JARBAK+=1
    )
)
echo [OK] 已清理 %JARBAK% 个 jar 备份

REM 3. 删除超过 30 天的压缩历史日志（.gz），保留当前 sql-report-server.log
powershell -NoProfile -Command "$cut=(Get-Date).AddDays(-30); Get-ChildItem -Path '.\logs' -Filter '*.gz' -File | Where-Object { $_.LastWriteTime -lt $cut } | ForEach-Object { Write-Host ('[DEL] ' + $_.Name); Remove-Item $_.FullName -Force }" 2>nul

REM 4. 删除 GC 滚动日志（gc.log.0~4），保留当前 gc.log
set "GC=0"
for %%f in (logs\gc.log.*) do (
    if /i not "%%~nxf"=="gc.log" (
        echo [DEL] %%f
        del /q "%%f" >nul 2>&1
        set /a GC+=1
    )
)
echo [OK] 已清理 %GC% 个 GC 滚动日志

echo.
echo 清理完成。
echo.
pause
