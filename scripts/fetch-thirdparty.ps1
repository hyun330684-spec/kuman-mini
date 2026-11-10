# Fetch pinned upstream sources into third_party/ (excluded from git).
# llama.cpp @ 7592b61, sd.cpp @ 32d516c (+ submodules incl. patched ggml)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$tp = Join-Path $root "third_party"
New-Item -ItemType Directory -Force -Path $tp | Out-Null

if (-not (Test-Path "$tp\llama.cpp\.git")) {
    git clone https://github.com/ggml-org/llama.cpp "$tp\llama.cpp"
}
git -C "$tp\llama.cpp" fetch --depth 1 origin 7592b61
git -C "$tp\llama.cpp" checkout 7592b61

if (-not (Test-Path "$tp\sd.cpp\.git")) {
    git clone --recursive https://github.com/leejet/stable-diffusion.cpp "$tp\sd.cpp"
}
git -C "$tp\sd.cpp" fetch --depth 1 origin 32d516c
git -C "$tp\sd.cpp" checkout 32d516c
git -C "$tp\sd.cpp" submodule update --init --recursive

Write-Host "third_party ready."
