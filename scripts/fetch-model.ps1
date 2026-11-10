# 쿠만 미니(Low 티어) 기본 모델 다운로드 스크립트
# 사용법: powershell -File scripts\fetch-model.ps1 [-ModelFile <파일명>]
# 대상: EXAONE-3.5-2.4B Q4_K_M (~1.6GB). 다른 GGUF를 쓰려면 -ModelFile로 파일명 지정.
param(
    [string]$Repo = "LGAI-EXAONE/EXAONE-3.5-2.4B-Instruct-GGUF",
    [string]$ModelFile = "EXAONE-3.5-2.4B-Instruct-Q4_K_M.gguf"
)

$ErrorActionPreference = "Stop"
$outDir = Join-Path $PSScriptRoot "..\app\src\main\assets\models"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out = Join-Path $outDir "kuman-mini.gguf"
$url = "https://huggingface.co/$Repo/resolve/main/$ModelFile"

Write-Host "Downloading $url"
Write-Host " -> $out"
curl.exe -L --fail -o "$out.part" "$url"
if ($LASTEXITCODE -ne 0) { Remove-Item "$out.part" -ErrorAction SilentlyContinue; throw "download failed" }
Move-Item -Force "$out.part" $out
Write-Host "Done. APK에 번들됩니다."
# 참고: 모델을 교체하면 MainActivity.kt의 BUNDLED_MODEL_BYTES도 새 파일 크기로 갱신하세요.
