# 쿠만 미니 (KUMAN mini)

갤럭시 A50(4GB) 등 저사양 안드로이드에서 100% 오프라인으로 동작하는 온디바이스 AI 로봇 앱.
llama.cpp를 네이티브로 내장하며 외부 네트워크 통신을 하지 않는다.

## 구조

- `app/` — Kotlin + View 기반 UI. `FaceView`(표정), 채팅 RecyclerView, `MainActivity`(모델 로딩/대화)
- `lib/` — `com.arm.aichat` llama.cpp JNI 래퍼 (`ai_chat.cpp`, `InferenceEngine`)
- `third_party/llama.cpp` — llama.cpp 서브모듈(현재 shallow clone). `lib/src/main/cpp/CMakeLists.txt`가 `add_subdirectory`로 빌드
- `app/src/main/assets/models/kuman-mini.gguf` — 번들 모델(git 제외, `scripts/fetch-model.*`로 다운로드)

## 빌드

```bash
./gradlew :app:assembleDebug        # Windows: gradlew.bat assembleDebug
```

- JDK 17, Android SDK(`ANDROID_HOME`), NDK 29.0.13113456, CMake 3.31.6 필요
- compileSdk/targetSdk = 35 (android-35 플랫폼). androidx는 35 호환 버전으로 고정됨 — 최신 버전 업그레이드 시 AAR 메타데이터 충돌 주의
- minSdk = 30 (A50 = Android 11/API 30)
- 네이티브 빌드가 김(GGML_CPU_ALL_VARIANTS=ON, arm64+x86_64 전체 variant). `.cxx/` 캐시로 증분 빌드 가능

## 모델

```powershell
powershell -File scripts/fetch-model.ps1   # Qwen3-0.6B Q4_K_M → assets/models/kuman-mini.gguf
```

- Low 티어 기본: Qwen3-0.6B/0.8B Q4. Mid/High 티어는 더 큰 GGUF로 교체 (UI/로직 동일)
- 모델이 assets에 없으면 FAB 탭 → 파일 선택으로 임의 GGUF 로드 가능(개발용)

## 런타임 핵심 파라미터 (`lib/src/main/cpp/ai_chat.cpp`)

- `DEFAULT_CONTEXT_SIZE = 4096` (4GB 메모리 대응; 필요시 조정)
- KV 캐시 `type_k/type_v = GGML_TYPE_Q8_0`
- 스레드 `min(4, nproc-2)` — A50에서 빅코어 위주 사용 목적
- `BATCH_SIZE = 512`, 샘플러 temp 0.3

## 주의사항

- 시스템 프롬프트는 모델 로드 직후 `setSystemPrompt`로 반드시 1회 설정 (`_readyForSystemPrompt` 플래그)
- One UI 백그라운드 강등 대책: `activity_main.xml` 루트에 `keepScreenOn="true"` 적용됨
- APK 용량: CPU variant .so 전체 포함 시 ~120MB+. 릴리스 시 `abiFilters`를 `arm64-v8a`로 좁히는 것 검토
