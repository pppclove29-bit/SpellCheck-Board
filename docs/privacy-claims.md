# 개인정보처리방침 ↔ 코드 대조표

> **이 파일을 먼저 읽어라.** [site/privacy/index.html](../site/privacy/index.html)은 **공개 웹사이트**이고
> Play 심사 제출물이다. 거기 쓰인 문장은 전부 코드로 증명되는 사실이어야 한다.
> 아래 "근거" 칸의 코드를 건드릴 때는 **방침 페이지도 같이 고친다.** 둘이 어긋나면 허위 고지다.

대조 시점: 2026-09-21 (`ondevice` 플레이버 기준). 배경은
[planning-and-dev-log.md](planning-and-dev-log.md) 14.8절.

## 1. 입력한 내용

| 방침 문장 | 근거 (코드) | 깨지는 변경 |
|---|---|---|
| 맞춤법 검사는 기기 안에서만 하고, 입력 문장을 서버로 보내지 않는다 | `FeatureFlags.ai == false` → `TypeRightIME.maybeRequestAi()`가 즉시 반환, `ProcessTextActivity`도 규칙 결과에서 끝낸다. 규칙은 assets의 `korean-rules.json` | **AI를 되살리면(cloud 플레이버) 이 문장이 거짓이 된다.** 그때는 방침에 전송 조항을 되살려야 한다 |
| 입력한 문장을 저장하지 않는다 | 검사 결과는 `TypeRightIME`의 메모리 상태(`snapshot`, `corrections`)로만 존재. 영속 저장 경로 없음 | 입력 이력·예측 학습 같은 기능을 추가하면 깨진다 |
| 비밀번호 입력란과 개인화 학습 거부 입력란에서는 **검사 자체를 하지 않는다** | `TypeRightIME.runLocalCheck()` 첫 줄 `if (ui.secure) return` — 규칙 엔진이 텍스트를 읽지도 않는다. 판정은 `SecureFieldDetector` | `runLocalCheck`의 가드를 빼거나 `SecureFieldDetector`의 범위를 좁히면 깨진다 |
| 클립보드를 읽지 않는다 (쓰기만) | 저장소 전체에 `setPrimaryClip`만 있고 `getPrimaryClip`은 없다 | **클립보드 패널**(12.6에서 보류 중)을 만들면 바로 깨진다 |

## 2. 계정

| 방침 문장 | 근거 | 깨지는 변경 |
|---|---|---|
| 계정·로그인이 없고 이름·이메일·전화번호를 받지 않는다 | `FeatureFlags.auth == false` → 로그인 진입점이 전부 빠짐 | 구글 로그인을 되살리면 깨진다 |

## 3. 기기에 저장되는 것

| 방침 문장 | 근거 | 깨지는 변경 |
|---|---|---|
| 설정·단축어·최근 이모지는 기기 안에만 저장된다 | `SettingsRepository`(DataStore). `FeatureFlags.shortcutSync == false`라 Supabase 동기화 안 함 | 단축어 동기화를 켜면 깨진다 |
| 다른 기기와 동기화되지 않고, 앱을 삭제하면 함께 지워진다 | `AndroidManifest.xml`의 `android:allowBackup="false"` | `allowBackup`을 켜면 깨진다 (구글 백업으로 넘어감) |

## 4. 사용 통계 (Firebase Analytics)

| 방침 문장 | 근거 | 깨지는 변경 |
|---|---|---|
| 수집 항목은 숫자이거나 미리 정해진 항목 이름 | `analytics/Analytics.kt`의 `Events` 카탈로그 — 파라미터가 전부 Int/Boolean/enum | 자유 문자열 파라미터를 넣으면 `AnalyticsEventTest`가 먼저 실패한다 |
| 입력한 글자·교정된 단어를 **어떤 형태로도 전송하지 않는다** | `AnalyticsEventTest`의 "no event carries free text"가 허용 문자열 화이트리스트로 강제 | 같은 위 |
| 타이핑 중인 앱 이름 대신 5개 분류만 기록 | `AppCategories.of()` → messenger/social/work/browser/other | 패키지명을 그대로 보내면 깨진다 |
| Firebase가 앱 버전·기기 모델·OS 버전·대략적 지역·익명 설치 식별자를 자동 수집한다 | Firebase Analytics SDK의 기본 동작 (우리 코드가 아니라 SDK가 수집) | — (초안에서 이 항목을 빠뜨렸다가 바로잡았다. **"횟수만 수집"은 사실이 아니다**) |
| 광고 ID를 수집하지 않는다 | `AndroidManifest.xml`의 `google_analytics_adid_collection_enabled=false` | 이 메타데이터를 빼면 깨진다. Play '데이터 보안' 신고 항목도 늘어난다 |
| 광고를 표시하지 않는다 | `ondevice` 플레이버에 AdMob SDK·매니페스트 항목이 없다 (14.7 실측: `billingclient` 0건, 매니페스트 AdMob 0건) | 광고를 되살리면 깨진다 |

## 5. 권한

| 방침 문장 | 근거 | 깨지는 변경 |
|---|---|---|
| 저장공간 쓰기는 Android 9 이하에서 짤 카드 저장에만 쓴다 | `AndroidManifest.xml`: `WRITE_EXTERNAL_STORAGE` + `maxSdkVersion="28"` | 권한을 추가하면 방침에 적어야 한다 |
| 연락처·위치·카메라·마이크에 접근하지 않는다 | 매니페스트에 해당 권한 없음 | 같은 위 |

## 공개 상태

1. **B1-1 ✅** — 문의 이메일을 `musikga1116@gmail.com`으로 교체 완료 (2026-09-21, 세 앱 공용).
   Play 스토어 등록정보의 개발자 연락처도 **같은 주소**를 쓴다 — 방침과 다르면 심사에서 지적된다.
2. **B1-2 ✅** — GitHub Pages 공개 완료 (2026-09-21).

**방침은 현재 공개돼 있다: `https://pppclove29-bit.github.io/SpellCheck-Board/privacy/` (200 응답 확인).**
이 주소를 Play '앱 콘텐츠'의 개인정보처리방침 URL로 넣는다.
`site/**` 를 push 하면 자동 배포되므로, **여기 적힌 근거를 깨는 코드 변경은 공개 문서를 즉시 거짓으로 만든다.**

## 기계로 검사되는 항목

아래 표의 상당수는 `android/app/src/test/java/com/typeright/app/PrivacyClaimsTest.kt` 가 **테스트로 고정**한다
(두 플레이버 모두에서 실행). 테스트가 깨지면 코드를 되돌리거나 방침과 이 문서를 같이 고쳐야 한다.

| 방침 문장 | 테스트 |
|---|---|
| 광고를 표시하지 않는다 | AdMob SDK 가 클래스패스에 있는지 ↔ `FeatureFlags.ads` 일치 |
| (결제 없음) | Play Billing SDK 존재 ↔ `FeatureFlags.billing` 일치 |
| 광고 ID 를 수집하지 않는다 | 매니페스트의 `google_analytics_adid_collection_enabled=false` |
| — | AdMob `APPLICATION_ID` 가 메인 매니페스트에 없을 것 (cloud 전용) |
| 앱을 삭제하면 함께 지워진다 | `android:allowBackup="false"` |
| 클립보드를 읽지 않는다 | 프로덕션 소스에 `getPrimaryClip` 계열 호출 없음 |
| 연락처·위치·카메라·마이크에 접근하지 않는다 | 매니페스트에 해당 권한 없음 |
| 저장공간은 Android 9 이하에서만 | `WRITE_EXTERNAL_STORAGE` 에 `maxSdkVersion="28"` |
| 계정·로그인이 없고 문장을 서버로 안 보낸다 | `ai`/`auth`/`shortcutSync` 가 `cloud` 와 일치 |
| (방침 페이지 자체) | 자리표시·TODO 없음, 문의 이메일 존재, 앱의 URL 상수가 `/privacy/` 로 끝남 |

**테스트로 덮지 못한 것** (사람이 봐야 한다):
- "보안 입력란에서는 검사 자체를 하지 않는다" — `TypeRightIME.runLocalCheck()` 의 조기 반환은
  IME 서비스 동작이라 JVM 단위 테스트로 재현하기 어렵다. `SecureFieldDetector` 의 판정 로직만 테스트돼 있다.
- "입력한 문장을 저장하지 않는다" — 저장 경로가 *없음*을 증명하는 것이라 기계 검사가 어렵다.
- Firebase 가 자동 수집하는 항목 — 우리 코드가 아니라 SDK 동작이라 우리 테스트로 고정할 수 없다.
