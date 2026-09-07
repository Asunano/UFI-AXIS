@echo off
chcp 65001 >nul
REM Build and package the web frontend into web-<version>.zip
REM version comes from web.version in the repo-root version.json
setlocal
set "SCRIPT_DIR=%~dp0"
set "WEB_DIR=%SCRIPT_DIR%web"

echo ==^> 进入 %WEB_DIR%
pushd "%WEB_DIR%" || (echo 找不到 web 目录 & exit /b 1)

echo ==^> 构建前端 (npm run build)
call npm run build
if errorlevel 1 (
  echo [错误] npm run build 失败
  popd
  exit /b 1
)

for /f "delims=" %%v in ('node -e "console.log(require('../version.json').web.version)"') do set "VERSION=%%v"
if not defined VERSION (
  echo [错误] 无法读取 version.json 的 web.version
  popd
  exit /b 1
)

set "OUT=%SCRIPT_DIR%web-%VERSION%.zip"
set "DIST=%WEB_DIR%\dist"

REM Do NOT use Compress-Archive here. Windows PowerShell 5.1 writes zip entry
REM names with backslashes, which violates ZIP APPNOTE 4.4.17.1. On the device
REM ZipInputStream then treats them as flat filenames containing a backslash,
REM so no assets/ directory is created, requests for /assets/*.js fall through
REM to the SPA fallback and receive index.html -> blank page.
REM Instead: add entries one by one and force forward slashes.
echo ==^> 打包 dist -^> %OUT%
powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; $src='%DIST%'; $out='%OUT%'; if (Test-Path $out) { Remove-Item $out -Force }; $zip=[System.IO.Compression.ZipFile]::Open($out,'Create'); try { $files=[System.IO.Directory]::GetFiles($src,'*','AllDirectories'); foreach ($f in $files) { $rel=$f.Substring($src.Length+1).Replace('\','/'); [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$f,$rel) } } finally { $zip.Dispose() }"

if not exist "%OUT%" (
  echo [错误] 打包失败
  popd
  exit /b 1
)

REM Self-check: entry names must not contain backslashes, index.html must be at root
echo ==^> 校验 zip 条目名
powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; $zip=[System.IO.Compression.ZipFile]::OpenRead('%OUT%'); $bad=@(); $hasIndex=$false; $count=0; try { foreach ($e in $zip.Entries) { $count++; if ($e.FullName.Contains([char]92)) { $bad+=$e.FullName }; if ($e.FullName -eq 'index.html') { $hasIndex=$true } } } finally { $zip.Dispose() }; if ($bad.Count -gt 0) { Write-Host ('[FAIL] backslash in entry names: ' + ($bad -join ', ')); exit 1 }; if (-not $hasIndex) { Write-Host '[FAIL] index.html missing at zip root'; exit 1 }; Write-Host ('[OK] ' + $count + ' entries, separators are fine')"
if errorlevel 1 (
  echo [错误] zip 结构校验未通过
  popd
  exit /b 1
)

echo [完成] 已生成 %OUT%

popd
endlocal
exit /b 0
