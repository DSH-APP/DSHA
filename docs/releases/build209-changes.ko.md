# DSHA 0.2.1-rc.3-20261029.1845-dsh0.2.1-rc.3 / build209

- 앱 이름이 DSH-A-Su(Sunset Mod)로, applicationId가 io.github.sunsetrne.dsha로 변경되었습니다. 원본 dsh.android.client와 나란히 설치할 수 있고 데이터는 완전히 분리되며 기존 데이터를 덮어쓰지 않습니다.
- 릴리스 서명을 새로 만들었습니다. 기존 E7E3 인증서 대신 Mod 자체 릴리스 인증서를 사용하며, verifyPublishCertificate가 새 인증서와 새 패키지 이름을 검증합니다. 예상 해시는 ci/release-identity.json에 기록됩니다.
- 아일랜드(승격 알림) 변형을 정식 패키지에 통합했습니다. 별도 빌드나 별도 패키지 이름이 더 이상 없습니다.
- Android 10 이하 지원을 종료했습니다. low / GeckoView 호환 라인을 삭제했으며 최소 Android 11(API 30), arm64-v8a만 지원합니다.
- 접근성 선언을 모든 변형이 공유합니다. main manifest에 선언하며 packageNames를 제한하지 않고 flavor 재정의도 없습니다. 레이블과 설명은 "DSH-A-Su 화면 제어"로 갱신하고 간결하게 다듬었습니다.
- zh, zh-Hant, en, ru, ja, ko, ar, pt, la, fr 10개 언어를 유지합니다.
- fr, pt, ru, ar, la의 짧은 UI 레이블을 한층 더 줄여 화면 점유를 낮췄습니다.
- 라틴어(la)의 "다음" 문구가 깨져 있던 문제를 수정했습니다.
- 고정 빌드 입력을 공개 저장소의 프리릴리스 Release 자산으로 전환하고 SHA-256으로 고정했습니다. 공개 저장소에서 전체 빌드 입력을 그대로 복원할 수 있습니다([고정 빌드 입력 문서](../../docs/fixed-build-inputs.md)).
- 패키지 이름과 인증서 관련 릴리스 도구 및 CI 단언을 함께 갱신했습니다. 빌드와 게이트 결과는 저장소 영수증을 기준으로 합니다.
