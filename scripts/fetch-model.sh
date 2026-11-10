#!/usr/bin/env bash
# 쿠만 미니(Low 티어) 기본 모델 다운로드 스크립트
# 사용법: ./scripts/fetch-model.sh [모델파일명]
set -euo pipefail

REPO="LGAI-EXAONE/EXAONE-3.5-2.4B-Instruct-GGUF"
MODEL_FILE="${1:-EXAONE-3.5-2.4B-Instruct-Q4_K_M.gguf}"
OUT_DIR="$(cd "$(dirname "$0")/../app/src/main/assets/models" && pwd)"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/kuman-mini.gguf"
URL="https://huggingface.co/$REPO/resolve/main/$MODEL_FILE"

echo "Downloading $URL"
echo " -> $OUT"
curl -fL --progress-bar -o "$OUT.part" "$URL"
mv "$OUT.part" "$OUT"
echo "Done. APK에 번들됩니다."
# 참고: 모델 교체 시 MainActivity.kt의 BUNDLED_MODEL_BYTES도 새 파일 크기로 갱신하세요.
