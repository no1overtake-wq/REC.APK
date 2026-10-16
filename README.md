# REC.APK — 안드로이드 화면 녹화

무음 · 60FPS 목표 · 알림에서 종료하는 Android 화면 녹화 앱입니다.

**APK 다운로드:** [Releases](https://github.com/no1overtake-wq/REC.APK/releases/latest)

GitHub Actions에서 Android SDK를 사용해 빌드하고, 완료되면 APK를 GitHub Release에 직접 게시합니다. 녹화는 MediaProjection 동의를 거쳐 시작하며 `Movies/ScreenRecordings`에 MP4로 저장합니다. 특정 DRM·보호 화면은 Android 정책상 캡처되지 않을 수 있습니다.

소스와 빌드 스크립트는 자동 빌드 과정에서 저장소에 공개됩니다. `app/`, `tests/`, `tools/build_linux.py` 경로를 참고하세요.

> 최초 배포본은 GitHub에서 새 서명키로 빌드됩니다. 기존 PC에서 생성한 APK와 서명이 다를 수 있어 기존 앱 위에 업데이트가 불가능할 수 있습니다.