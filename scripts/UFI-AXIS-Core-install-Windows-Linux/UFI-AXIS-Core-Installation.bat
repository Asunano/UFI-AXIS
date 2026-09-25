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
rem 给 PowerShell 用的同名环境变量：路径不能直接插进 PS 的单引号字符串，
rem 用户名含撇号（如 C:\Users\O'Brien\...）会让字符串提前闭合、解析失败，
rem 报错还被 2^>nul 吞掉，表现成「未能自动识别包名」
set "UFI_TMPOUT=%TMPOUT%"
set "UFI_TMPBEFORE=%TMPBEFORE%"
set "UFI_TMPAFTER=%TMPAFTER%"

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
for /f "usebackq delims=" %%a in (`powershell -NoProfile -Command "$b=@(); if (Test-Path -LiteralPath $env:UFI_TMPBEFORE) { $b=@(Get-Content -LiteralPath $env:UFI_TMPBEFORE | ForEach-Object { $_.Trim() } | Where-Object { $_ -like 'package:*' }) }; if ($b.Count -gt 0 -and (Test-Path -LiteralPath $env:UFI_TMPAFTER)) { Get-Content -LiteralPath $env:UFI_TMPAFTER | ForEach-Object { $_.Trim() } | Where-Object { $_ -and ($b -notcontains $_) } | ForEach-Object { $_ -replace '^package:','' } | Select-Object -First 1 }" 2^>nul`) do set "NEWPKG=%%a"
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
rem 读设备 API level，用于跳过本机不适用的权限；读不到就全部逐项尝试。
rem exec-out 不分配 pty，输出不会被转成 CRLF，省去清洗 \r。
set "SDK="
"%ADB%" -s !ADDR! exec-out getprop ro.build.version.sdk >"%TMPOUT%" 2>nul
for /f "usebackq tokens=1 delims= " %%s in ("%TMPOUT%") do if not defined SDK set "SDK=%%s"
echo(!SDK!| findstr /R "^[0-9][0-9]*$" >nul 2>&1
if errorlevel 1 set "SDK="
if defined SDK (
    call :say "       设备 API level: !SDK!"
) else (
    call :say "       [提示] 读取设备 API level 失败，将逐项尝试全部权限"
)
rem 清单与 core 的 AndroidManifest 对齐（core 加了新权限必须同步这里，
rem 否则装完是「装上了但功能用不了」）。给 core 没声明的权限做 grant 是无效动作。
rem 每项格式：权限名:minSdk:maxSdk:方式[:appops 名]
for %%e in (
    "READ_EXTERNAL_STORAGE:0:32:grant"
    "WRITE_EXTERNAL_STORAGE:0:29:grant"
    "MANAGE_EXTERNAL_STORAGE:30:999:appop"
    "READ_MEDIA_IMAGES:33:999:grant"
    "READ_MEDIA_VIDEO:33:999:grant"
    "READ_MEDIA_AUDIO:33:999:grant"
    "ACCESS_FINE_LOCATION:0:999:grant"
    "ACCESS_COARSE_LOCATION:0:999:grant"
    "READ_PHONE_STATE:0:999:grant"
    "READ_PHONE_NUMBERS:0:999:grant"
    "SEND_SMS:0:999:grant"
    "READ_SMS:0:999:grant"
    "POST_NOTIFICATIONS:33:999:grant"
    "REQUEST_INSTALL_PACKAGES:0:999:appop"
    "PACKAGE_USAGE_STATS:0:999:appop:GET_USAGE_STATS"
    "SCHEDULE_EXACT_ALARM:31:999:appop"
) do call :grant_one %%e
call :say "[完成] 权限授予完成。"
call :say "提示: 标 [跳过] 的是本机 API 不适用（正常）；标 [--] 的才是没授上，可在系统设置里手动允许。"
call :say ""


call :say "正在启动应用 !NEWPKG! ..."
set "LAUNCH_COMP="
"%ADB%" -s !ADDR! shell cmd package resolve-activity --brief !NEWPKG! >"%TMPOUT%" 2>&1
for /f "usebackq delims=" %%a in (`powershell -NoProfile -Command "if (Test-Path -LiteralPath $env:UFI_TMPOUT) { Get-Content -LiteralPath $env:UFI_TMPOUT | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[A-Za-z0-9_.]+/' } | Select-Object -First 1 }" 2^>nul`) do set "LAUNCH_COMP=%%a"
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
rem 授予单条权限。参数格式：权限名:minSdk:maxSdk:方式[:appops 名]
rem   grant = 先 pm grant，失败再回退 appops
rem   appop = 只走 appops（MANAGE_EXTERNAL_STORAGE / REQUEST_INSTALL_PACKAGES /
rem           PACKAGE_USAGE_STATS / SCHEDULE_EXACT_ALARM 这类 appop 控制的权限，
rem           pm grant 对它们必然失败，直接走 appops 少一次无效调用和一条误导日志）
rem   appops 名默认与权限名相同，例外：PACKAGE_USAGE_STATS 的 appop 叫 GET_USAGE_STATS
:grant_one
set "GP_P="
set "GP_MIN="
set "GP_MAX="
set "GP_MODE="
set "GP_OP="
for /f "tokens=1,2,3,4,5 delims=:" %%a in ("%~1") do (
    set "GP_P=%%a"
    set "GP_MIN=%%b"
    set "GP_MAX=%%c"
    set "GP_MODE=%%d"
    set "GP_OP=%%e"
)
if not defined GP_OP set "GP_OP=!GP_P!"
if defined SDK (
    if !SDK! LSS !GP_MIN! (
        call :say "    [跳过] !GP_P!（设备 API !SDK! 低于 !GP_MIN!，本机无此权限）"
        goto :eof
    )
    if !SDK! GTR !GP_MAX! (
        call :say "    [跳过] !GP_P!（设备 API !SDK! 高于 !GP_MAX!，该权限已被取代）"
        goto :eof
    )
)
set "GP_ST=FAIL"
if /i "!GP_MODE!"=="grant" (
    "%ADB%" -s !ADDR! shell pm grant !NEWPKG! android.permission.!GP_P! >"%TMPOUT%" 2>&1
    call :cmd_ok "%TMPOUT%"
    if not errorlevel 1 set "GP_ST=OK"
)
if not "!GP_ST!"=="OK" (
    "%ADB%" -s !ADDR! shell appops set !NEWPKG! !GP_OP! allow >"%TMPOUT%" 2>&1
    call :cmd_ok "%TMPOUT%"
    if not errorlevel 1 set "GP_ST=OK"
)
if "!GP_ST!"=="OK" (
    call :say "    [OK] !GP_P!"
) else (
    call :say "    [--] !GP_P! 未自动授权"
    call :logonly "%TMPOUT%"
)
goto :eof

rem 判定设备侧命令是否成功：返回 0 成功、1 失败。
rem 不能看 adb 的退出码 —— 那是 adb 客户端的，设备侧 pm/appops 失败时它照样是 0。
rem pm grant / appops set 成功时没有输出，失败才打印异常文本，所以按输出判定。
:cmd_ok
findstr /I /C:"Exception" /C:"Error" /C:"Failure" /C:"not allowed" /C:"Unknown" /C:"denied" "%~1" >nul 2>&1
if errorlevel 1 exit /b 0
exit /b 1

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