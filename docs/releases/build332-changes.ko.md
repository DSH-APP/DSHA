# DSHA 0.2.1-rc.5-20261106.0700-dsh0.2.2-rc.1 / build332

- 문서만 수정하는 릴리스입니다. T58 아카이브 문서의 사실 관계를 정정합니다. 최초 연결 표시기 패치는 build324(`a54f9854`)부터 병합되어 함께 배포되었으며 build324–build332 모두 포함합니다. 아카이브 tag `archive/feat/first-connect-indicator-20261106`(`5918af63`)은 같은 패치의 별도 브랜치 스냅샷일 뿐이며 별도로 병합되지 않고, 패치가 패키지에 없다는 의미도 아닙니다.
- 새 기능, 동작 변경, 실행 환경 변경이 없습니다. fixed-inputs는 다시 만들지 않으며 managed-runtime-inputs와 runtime-descriptor도 바꾸지 않습니다(runtimeId는 `35e3f499` 유지). 관리 런타임이나 플러그인도 추가하지 않습니다.
- 검증 범위: 호스트 contracts 전체 그룹, 전체 트리 포맷 검사(JDK 17), host-tests manifest, 대상 지정 단위 테스트(업데이트 페이지). 이 기기에서 실제 기기 검증은 하지 않았습니다. 버전 코드를 332로 올립니다(build331 기준). DSHA 개정 번호는 0.2.1-rc.5, 내장 DSH는 0.2.2-rc.1입니다.
