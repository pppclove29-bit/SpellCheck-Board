# iOS — 보류 (MVP 범위 제외)

MVP는 Android 단독 출시로 결정되어 iOS 작업을 중단했다. 이 디렉터리는 **미완성·미검증** 상태다.

## 현재 상태

- `TypeRightCore/` (Foundation 전용 Swift Package) 일부만 존재: 한글 조합기(두벌식/천지인), RuleEngine, SentenceExtractor, CorrectionApplier, ShortcutExpander, Debouncer, StaleGuard/TriggerPolicy/SecureFieldPolicy/PoliceGate, V4 API 모델·클라이언트, Supabase 익명 세션 관리, FeedbackComposer.
- **빌드되지 않음**: `SharedSettings.swift`가 없어 `KeyboardLayoutKind`, `Shortcut` 타입이 정의되지 않음.
- `RuleEngine`은 규칙 v1 기준 — `feedback_templates`, `wit`, 제안 `type` 미지원.
- 테스트 타깃, `TypeRightHostApp/`, `KeyboardExtension/`, XcodeGen `project.yml` 없음.
- 한 번도 컴파일/테스트하지 않았다 (이 개발 머신에 Xcode 없음, Command Line Tools만 있음. swift-testing은 동작 확인).

## 재개 시

1. `docs/api-contract.md`와 `shared/rule-golden-cases.json`(cases / feedback_cases / type_cases)을 기준으로 Core를 완성하고 `swift test`로 골든 케이스 통과시키기.
2. Android 구현(`android/keyboard`)을 레퍼런스로 키보드 확장·호스트 앱 작성.
3. iOS 전용 제약: 키보드 확장 메모리 ~50MB 내외, 네트워크·햅틱은 '전체 접근 허용' 필요, 앱 확장에서 AdMob 사용 불가.
