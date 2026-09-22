@echo off
setlocal enabledelayedexpansion
chcp 936 >nul
title UFI-AXIS-Core 一键安装

rem ======== 基础路径 ========
set "BASE=%~dp0"
set "ADB=%BASE%adb.exe"
set "CORE=%BASE%core"
set "LOGDIR=%BASE%log"
set "DEF_IP=192.168.0.1"
set "DEF_PORT=5555"
set "HEALTH_PORT=8088"
set "KNOWN_PKG=com.ufi_axis_core"
set "TMPOUT=%TEMP%\ufiaxis_out.txt"
set "TMPBEFORE=%TEMP%\ufiaxis_pkgs_before.txt"
set "TMPAFTER=%TEMP%\ufiaxis_pkgs_after.txt"

rem ======== 日志：直接写文件，不再用 PowerShell 管道包装（管道会吞掉交互提示）========
if not exist "%LOGDIR%" mkdir "%LOGDIR%" >nul 2>&1
set "TS="
for /f "delims=" %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss" 2^>nul') do set "TS=%%i"
if not defined TS set "TS=%RANDOM%%RANDOM%"
set "LOGFILE=%LOGDIR%\install_%TS%.log"
>"%LOGFILE%" echo ==== UFI-AXIS-Core 安装日志 %TS% ====

:start
cls
call :say "============================================"
call :say "        UFI-AXIS-Core 一键安装工具"
call :say "============================================"
call :say "日志文件: %LOGFILE%"
call :say ""

if not exist "%ADB%" (
    call :say "[错误] 未找到 adb.exe: %ADB%"
    call :say "       请确认脚本与 adb.exe 在同一目录"
    goto :fail
)
if not exist "%CORE%\" (
    call :say "[错误] 未找到 core 目录: %CORE%"
    call :say "       请在脚本同目录下放置 core 文件夹"
    goto :fail
)
set "APKCOUNT=0"
for %%f in ("%CORE%\*.apk") do set /a APKCOUNT+=1
if "!APKCOUNT!"=="0" (
    call :say "[错误] core 目录中未找到 APK 文件"
    goto :fail
)
call :say "[1/6] 请输入设备远程 ADB 地址"
set "ADDR="
set /p "ADDR=> 地址 [回车使用默认 %DEF_IP%]: "
if defined ADDR set "ADDR=!ADDR:"=!"
if not defined ADDR set "ADDR=%DEF_IP%"

set "IPONLY=!ADDR!"
echo(!ADDR!| findstr /C:":" >nul
if errorlevel 1 (
    set "ADDR=!ADDR!:%DEF_PORT%"
) else (
    for /f "tokens=1 delims=:" %%i in ("!ADDR!") do set "IPONLY=%%i"
)
call :say "使用地址: !ADDR!"
call :say ""

:connect
call :say "[2/6] 正在连接 !ADDR! ..."
"%ADB%" connect !ADDR! >"%TMPOUT%" 2>&1
call :dump "%TMPOUT%"
"%ADB%" -s !ADDR! get-state >"%TMPOUT%" 2>&1
set "STATE="
for /f "usebackq delims=" %%s in ("%TMPOUT%") do if not defined STATE set "STATE=%%s"
if /i "!STATE!"=="device" goto connected
call :say "       当前状态: !STATE!"
call :say "[失败] 无法连接 !ADDR!，请检查："
call :say "       - 设备已开启无线调试"
call :say "       - 电脑与设备在同一网络"
call :say "       - 设备上如有授权弹窗请先点允许"
call :say ""
set "RETRYASK=0"
:ask_retry
set /a RETRYASK+=1
if !RETRYASK! GTR 5 goto :fail
set "YN="
set /p "YN=输入 Y 重新连接，输入 N 返回修改地址，然后按回车: "
set "YN=!YN: =!"
if /i "!YN!"=="Y" goto connect
if /i "!YN!"=="N" goto start
call :say "[提示] 只能输入 Y 或 N"
goto ask_retry

:connected
call :say "[成功] 设备已连接: !ADDR!"
call :say ""

call :say "[3/6] 即将安装 core 目录中的 APK:"
for %%f in ("%CORE%\*.apk") do call :say "       - %%~nxf"
call :say ""
set "CONFIRMASK=0"
:ask_install
set /a CONFIRMASK+=1
if !CONFIRMASK! GTR 5 goto :fail
set "YN="
set /p "YN=输入 Y 确认安装，输入 N 取消，然后按回车: "
set "YN=!YN: =!"
if /i "!YN!"=="Y" goto do_install
if /i "!YN!"=="N" goto cancel_install
call :say "[提示] 只能输入 Y 或 N"
goto ask_install

:cancel_install
call :say "已取消安装。"
goto :done_cancel

:do_install
call :say ""
call :say "[4/6] 正在安装 APK ..."
"%ADB%" -s !ADDR! shell pm list packages -3 >"%TMPBEFORE%" 2>nul
if not exist "%TMPBEFORE%" >"%TMPBEFORE%" echo(
for %%f in ("%CORE%\*.apk") do (
    call :say "   安装 %%~nxf ..."
    "%ADB%" -s !ADDR! install -r -d "%%f" >"%TMPOUT%" 2>&1
    call :dump "%TMPOUT%"
    findstr /I /C:"Success" "%TMPOUT%" >nul 2>&1
    if errorlevel 1 (
        call :say "[失败] %%~nxf 安装失败，请检查 APK 文件与设备兼容性。"
        goto :fail
    )
)
call :say "[成功] APK 安装完成。"

"%ADB%" -s !ADDR! shell pm list packages -3 >"%TMPAFTER%" 2>nul
set "NEWPKG="
for %%c in (%KNOWN_PKG%) do (
    if not defined NEWPKG (
        "%ADB%" -s !ADDR! shell pm path %%c >"%TMPOUT%" 2>&1
        findstr /I /C:"package:" "%TMPOUT%" >nul 2>&1
        if not errorlevel 1 set "NEWPKG=%%c"
    )
)
if defined NEWPKG goto pkg_done
for /f "usebackq delims=" %%a in (`powershell -NoProfile -Command "$b=@(); if (Test-Path -LiteralPath '%TMPBEFORE%') { $b=@(Get-Content -LiteralPath '%TMPBEFORE%' | ForEach-Object { $_.Trim() } | Where-Object { $_ -like 'package:*' }) }; if ($b.Count -gt 0 -and (Test-Path -LiteralPath '%TMPAFTER%')) { Get-Content -LiteralPath '%TMPAFTER%' | ForEach-Object { $_.Trim() } | Where-Object { $_ -and ($b -notcontains $_) } | ForEach-Object { $_ -replace '^package:','' } | Select-Object -First 1 }" 2^>nul`) do set "NEWPKG=%%a"
:pkg_done
if defined NEWPKG goto pkg_ok
set /p "NEWPKG=未能自动识别包名，请手动输入（直接回车跳过）: "
if defined NEWPKG set "NEWPKG=!NEWPKG:"=!"
:pkg_ok
if defined NEWPKG call :say "识别到包名: !NEWPKG!"
call :say ""

if not defined NEWPKG goto skip_pkg_ops

rem 先授权再启动：首启缺权限时应用可能弹系统授权框，导致启动流程卡住
call :say "[5/6] 正在授予权限 ..."
for %%p in (
    READ_EXTERNAL_STORAGE
    WRITE_EXTERNAL_STORAGE
    MANAGE_EXTERNAL_STORAGE
    ACCESS_FINE_LOCATION
    ACCESS_COARSE_LOCATION
    READ_PHONE_STATE
    READ_SMS
    RECEIVE_SMS
    RECEIVE_MMS
    READ_CELL_BROADCASTS
    POST_NOTIFICATIONS
    REQUEST_INSTALL_PACKAGES
) do (
    set "ST=FAIL"
    "%ADB%" -s !ADDR! shell pm grant !NEWPKG! android.permission.%%p >nul 2>&1
    if not errorlevel 1 (
        set "ST=OK"
    ) else (
        "%ADB%" -s !ADDR! shell appops set !NEWPKG! %%p allow >nul 2>&1
        if not errorlevel 1 set "ST=OK"
    )
    if "!ST!"=="OK" (call :say "    [OK] %%p") else (call :say "    [--] %%p 未自动授权")
)
call :say "[完成] 权限授予完成。"
call :say "提示: 如系统设置中仍有权限显示未开启，可手动允许。"
call :say ""

call :say "正在启动应用 !NEWPKG! ..."
set "LAUNCH_COMP="
"%ADB%" -s !ADDR! shell cmd package resolve-activity --brief !NEWPKG! >"%TMPOUT%" 2>&1
for /f "usebackq delims=" %%a in (`powershell -NoProfile -Command "if (Test-Path -LiteralPath '%TMPOUT%') { Get-Content -LiteralPath '%TMPOUT%' | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[A-Za-z0-9_.]+/' } | Select-Object -First 1 }" 2^>nul`) do set "LAUNCH_COMP=%%a"
if not defined LAUNCH_COMP goto launch_monkey
"%ADB%" -s !ADDR! shell am start -n !LAUNCH_COMP! >"%TMPOUT%" 2>&1
call :dump "%TMPOUT%"
goto launch_done
:launch_monkey
call :say "[提示] 未解析到启动入口，改用 monkey 启动"
"%ADB%" -s !ADDR! shell monkey -p !NEWPKG! -c android.intent.category.LAUNCHER 1 >"%TMPOUT%" 2>&1
call :dump "%TMPOUT%"
:launch_done
call :say "[完成] 启动指令已发送。"
call :say ""
goto pkg_ops_done

:skip_pkg_ops
call :say "[跳过] 未提供包名，跳过启动与授权。"
:pkg_ops_done
call :say "[6/6] 等待 5 秒让应用服务启动..."
timeout /t 5 >nul 2>&1
where curl >nul 2>&1
if errorlevel 1 (
    call :say "[错误] 系统未找到 curl，无法检查服务状态。"
    goto :fail
)
set "TRY=0"
set "HEALTH_URL=http://!IPONLY!:%HEALTH_PORT%/health"

:health_check
set /a TRY+=1
call :say "正在请求 !HEALTH_URL! ..."
del "%TMPOUT%" >nul 2>&1
curl -s --max-time 8 "!HEALTH_URL!" >"%TMPOUT%" 2>nul
findstr /I /C:"status" "%TMPOUT%" >nul 2>&1
if not errorlevel 1 (
    findstr /I /C:"ok" "%TMPOUT%" >nul 2>&1
    if not errorlevel 1 goto health_ok
)
if !TRY! LSS 6 (
    call :say "服务尚未就绪，5 秒后自动重试 第 !TRY! 次 ..."
    timeout /t 5 >nul 2>&1
    goto health_check
)
call :say "[失败] 服务状态异常或无法访问，响应内容:"
call :dump "%TMPOUT%"
call :say ""
set "HEALTHASK=0"
:ask_health
set /a HEALTHASK+=1
if !HEALTHASK! GTR 5 goto :fail
set "YN="
set /p "YN=输入 Y 重新检查，输入 N 退出，然后按回车: "
set "YN=!YN: =!"
if /i "!YN!"=="N" goto :fail
if /i "!YN!"=="Y" goto health_retry
call :say "[提示] 只能输入 Y 或 N"
goto ask_health

:health_retry
set "TRY=0"
goto health_check

:health_ok
call :say "[成功] 服务状态正常。"
call :logonly "%TMPOUT%"
call :say ""
call :say "============================================"
call :say "     安装成功！UFI-AXIS-Core 已就绪"
call :say "     服务地址: !HEALTH_URL!"
call :say "     本次日志: %LOGFILE%"
call :say "============================================"
call :cleanup
echo 窗口 5 秒后自动关闭...
timeout /t 5 /nobreak >nul 2>&1
exit /b 0

:done_cancel
call :cleanup
call :say "本次日志: %LOGFILE%"
pause
exit /b 0

:fail
call :cleanup
call :say ""
call :say "============================================"
call :say "安装未完成，详细日志: %LOGFILE%"
call :say "============================================"
pause
exit /b 1

rem ======== 子过程 ========
:say
setlocal disabledelayedexpansion
echo(%~1
>>"%LOGFILE%" echo(%~1
endlocal
goto :eof

:dump
if not exist "%~1" goto :eof
type "%~1"
type "%~1" >>"%LOGFILE%"
goto :eof

rem 只写日志、不回显（用于健康检查响应体）
:logonly
if not exist "%~1" goto :eof
type "%~1" >>"%LOGFILE%"
goto :eof

:cleanup
del "%TMPOUT%" "%TMPBEFORE%" "%TMPAFTER%" >nul 2>&1
goto :eof