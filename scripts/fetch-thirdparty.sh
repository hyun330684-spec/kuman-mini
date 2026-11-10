#!/usr/bin/env bash
# Fetch pinned upstream sources into third_party/ (excluded from git).
# llama.cpp  @ 7592b61 (ggml-org/llama.cpp)
# sd.cpp     @ 32d516c (leejet/stable-diffusion.cpp) + submodules (incl. patched ggml)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP"

if [ ! -d "$TP/llama.cpp/.git" ]; then
  git clone https://github.com/ggml-org/llama.cpp "$TP/llama.cpp"
fi
git -C "$TP/llama.cpp" fetch --depth 1 origin 7592b61 || true
git -C "$TP/llama.cpp" checkout 7592b61

if [ ! -d "$TP/sd.cpp/.git" ]; then
  git clone --recursive https://github.com/leejet/stable-diffusion.cpp "$TP/sd.cpp"
fi
git -C "$TP/sd.cpp" fetch --depth 1 origin 32d516c || true
git -C "$TP/sd.cpp" checkout 32d516c
git -C "$TP/sd.cpp" submodule update --init --recursive

echo "third_party ready."
