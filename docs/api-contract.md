# TypeRight API Contract (v1 · 기획서 V4 기준)

Backend(Vercel Serverless + Supabase) ↔ 키보드(Android/iOS) 간 단일 계약서. 클라이언트와 서버는 이 문서 기준으로 구현한다.

## 공통

- 호스팅: Vercel Serverless Functions (`backend/api/**`). Base URL은 환경별 설정 (로컬 dev 서버: Android 에뮬레이터 `http://10.0.2.2:8790`, 호스트에서 `http://localhost:8790`).
- Content-Type: `application/json; charset=utf-8`
- **인증**: `Authorization: Bearer <Supabase access token>`
  - 클라이언트는 최초 실행 시 Supabase **익명 로그인**(`signInAnonymously()`)으로 세션을 만들고, access token 만료 시 refresh token으로 갱신한다. 키보드 확장/IME와 호스트 앱은 같은 세션을 공유 저장소(Android 동일 APK 저장소 / iOS App Group Keychain)로 공유한다.
  - 서버는 JWT의 `sub`를 `user_id`로 사용한다. 요청 body의 `user_id`는 V4 기획서 호환용으로 받되 **무시**한다 (위조로 타인 쿼터·PRO 사용 방지).
- 헤더 `X-Client-Platform`: `android` | `ios` (선택, 로깅용)
- **모든 offset/length는 UTF-16 code unit 기준** (JS `String`, Kotlin `String`, Swift `NSString`/`String.utf16`). 한글 음절은 BMP이므로 1음절 = 1 unit.
- 입력 텍스트 최대 1,000자.

## 에러 응답

```json
{ "error": { "code": "INVALID_REQUEST", "message": "string" } }
```
| code | HTTP |
|---|---|
| `INVALID_REQUEST` | 400 |
| `UNAUTHORIZED` | 401 |
| `NOT_FOUND` | 404 |
| `METHOD_NOT_ALLOWED` | 405 |
| `INTERNAL` | 500 |

## 피드백 모드

| mode | 이름 | `wit_feedback` 스타일 | 키보드 UX |
|---|---|---|---|
| `spicy_wit` (기본) | 매운맛 훈수 | 유쾌한 팩트폭격·위트 한 문장 | 상단 말풍선 애니메이션 |
| `police` | 맞춤법 경찰 | 짧고 단호한 경고 | 오류 감지 시 사이렌 햅틱. **PRO**: 교정 칩 탭 또는 '무시' 탭 전까지 스페이스바·엔터·`. ! ?` 입력 차단. **무료**: 햅틱 경고만 |
| `gentle` | 상냥한 선생님 | 정중한 설명 + 문법 원리 팁 | 팁 카드 |

- 경찰 모드 '무시' 버튼은 오탐(false positive) 탈출구로 항상 제공한다 (해당 span을 현재 문장에서 무시 목록에 추가).

## POST /v1/grammar-check

1차 규칙 엔진 → (AI 가능 & 쿼터 남음) 2차 AI 문맥 교정·훈수. PII는 AI 호출 전 마스킹.

Request
```json
{
  "user_id": "usr_99812",
  "text": "오늘 진짜 어의가 없네",
  "mode": "spicy_wit"
}
```
- `mode`: `"spicy_wit"` | `"police"` | `"gentle"` (생략 시 `spicy_wit`)
- `user_id`: 선택, 무시됨 (위 인증 참고)

Response `200`
```json
{
  "original_text": "오늘 진짜 어의가 없네",
  "has_error": true,
  "corrected_text": "오늘 진짜 어이가 없네",
  "wit_feedback": "어의는 조선시대 궁궐 의사입니다. '어처구니'가 없으신 거죠? 🩺",
  "suggestions": [
    {
      "offset": 6,
      "length": 2,
      "original_word": "어의",
      "suggested_word": "어이",
      "type": "spelling",
      "reason": "'어이없다'가 올바른 표기입니다.",
      "source": "rule"
    }
  ],
  "engine": "hybrid",
  "ai_status": "used",
  "quota": { "is_pro": false, "limit": 5, "used": 1, "bonus": 0, "remaining": 4 }
}
```
- `suggestions`: offset 오름차순, 서로 겹치지 않음. `original_text.substring(offset, offset+length) == original_word` 보장.
- `type`: `"spelling"` | `"spacing"` | `"grammar"` | `"word_choice"`. 규칙 결과는 공백 제거 후 두 문자열이 같으면 `spacing`, 아니면 `spelling`.
- `source`: `"rule"` | `"ai"`. 겹치면 규칙 결과 우선.
- `corrected_text`: 원문에 `suggestions` 전체를 적용한 결과 (칩과 항상 일치).
- `wit_feedback`: 모드 스타일 한 문장. 오류 없으면 `null`. AI 미참여 시 온디바이스 피드백 알고리즘(아래)과 동일.
- `engine`: `"rule"` | `"hybrid"` (AI 결과가 반영됐는지)
- `ai_status`:
  - `"used"` — AI 참여
  - `"quota_exceeded"` — 무료 횟수 소진 → 규칙 결과만. 키보드는 "광고 보고 AI 훈수 3회 충전" 칩 표시
  - `"unavailable"` — AI 미설정/장애/타임아웃 → 규칙 결과만
- `quota`: 요청 처리 후 상태.

### 쿼터 정책

- 무료: AI 훈수 **하루 5회** (KST 자정 리셋). **AI가 오류를 찾아 훈수를 전달한 경우(`ai_status=used` && `has_error`)에만 1회 차감** — 오류 없는 문장은 차감하지 않는다.
- AdMob 보상형 광고 1회 시청 = 당일 +3회 (하루 최대 5회 시청).
- PRO(월 2,900원 / 연 19,900원): 무제한 (서버 fair-use 상한 300회/일), 경찰 모드 풀버전, 커스텀 단축어 저장.
- 온디바이스 규칙 교정은 항상 무료·무제한.

## GET /v1/me

Response `200`
```json
{ "user_id": "uuid", "is_pro": false, "quota": { "is_pro": false, "limit": 5, "used": 1, "bonus": 3, "remaining": 7 } }
```
키보드/호스트 앱은 시작 시·광고 시청 후·결제 후 호출해 PRO 여부와 잔여 횟수를 캐시한다.

## GET /v1/ads/admob-ssv

AdMob 보상형 광고 **서버 측 확인(SSV) 콜백** 전용 (클라이언트가 직접 호출하지 않음).
- 광고 요청 시 `ServerSideVerificationOptions.userId = Supabase user id` 설정.
- 서버는 Google 공개키(`verifier-keys.json`)로 ECDSA 서명을 검증하고, `transaction_id` 기준 멱등 처리 후 당일 bonus +3.
- 서명 유효 → `200` (중복 콜백 포함), 무효 → `400`.
- 광고 SDK는 키보드 확장/IME에서 쓰지 않는다 (iOS 앱 확장 AdMob 미지원). 키보드의 충전 칩은 호스트 앱 광고 화면으로 딥링크한다 (`typeright://reward`).

## GET /health

`200 { "status": "ok", "ai": true|false }`

## 온디바이스 규칙 (shared/korean-rules.json, version 2)

서버 `backend/src/utils`와 Android/iOS 키보드가 **동일한 규칙 파일**을 사용한다.

```json
{
  "version": 2,
  "feedback_templates": { "spicy_wit": "...{original}...{suggested}...", "police": "...", "gentle": "...{reason}" },
  "dictionary": [ { "from": "몇일", "to": "며칠", "reason": "...", "wit": "(선택) 매운맛 전용 멘트" } ],
  "patterns":   [ { "id": "ㄹ게", "pattern": "(할|갈)께", "replacement": "$1게", "reason": "...", "wit": "(선택)" } ]
}
```

교정 알고리즘 (3 플랫폼 공통, 결과 동일해야 함):
1. `dictionary`의 각 `from`을 텍스트에서 모든 등장 위치 검색 (리터럴 부분 문자열).
2. `patterns`의 각 `pattern`을 원문 전체에 정규식 매치. `suggested_word` = `replacement` 문자열의 `$0`(전체 매치)과 `$1`..`$9`(캡처 그룹)를 직접 치환(미매치·없는 그룹은 빈 문자열). **매치 문자열에 정규식을 다시 적용하지 말 것** — lookahead/lookbehind 문맥이 사라져 결과가 달라진다.
3. `suggested_word == original_word`인 후보를 **선택 전에** 제거 (선택 후에 제거하면 no-op 후보가 자리를 차지해 유효 후보가 밀려난다).
4. 남은 후보를 (offset 오름차순, length 내림차순) **안정 정렬**(동률은 생성 순서 유지: dictionary 파일 순서 → patterns 파일 순서) 후 앞에서부터 겹치지 않는 것만 채택.

피드백 알고리즘 (온디바이스 / AI 미참여 시 서버):
1. 채택된 교정 중 **첫 번째**(가장 앞 offset)를 사용. 없으면 `null`.
2. `spicy_wit`: 해당 규칙의 `wit`가 있으면 그대로, 없으면 `feedback_templates.spicy_wit`.
3. `police` / `gentle`: 각각 `feedback_templates.police` / `.gentle`.
4. 템플릿의 `{original}` `{suggested}` `{reason}`을 교정 값으로 치환.

정규식은 Java / Python / JS / ICU 공통 문법만 사용: 그룹, 비캡처 그룹, 명시적 문자 클래스, `|`, `?`, lookahead/lookbehind(고정 길이). **`\b` `\s` `\d` `\w` 등 축약 클래스 금지** — 엔진마다 유니코드 범위가 달라(NBSP, U+3000 등) 결과가 갈린다. 공백은 `[ \t\n]`처럼 명시적으로 쓴다 (백엔드 테스트가 강제).

## 클라이언트 트리거 정책

| 이벤트 | 동작 |
|---|---|
| 키 입력 후 300ms 무입력 (debounce) | 온디바이스 규칙 검사 → 교정 칩 + 모드별 피드백 |
| 스페이스바 | debounce 즉시 flush → 온디바이스 검사. 경찰 모드(PRO)에서 미교정 오류가 남아 있으면 햅틱 + 입력 차단 |
| `.` `!` `?` 줄바꿈 | 온디바이스 검사(경찰 차단 동일 적용) + 네트워크 허용 & (`is_pro` 또는 `remaining > 0`) & 직전 AI 요청과 텍스트가 다를 때 `/v1/grammar-check` (현재 문장) |
| `ai_status=quota_exceeded` | "광고 보고 3회 충전" 칩 → 호스트 앱 딥링크 |
| 보안 필드 | 캡처·검사·네트워크·경찰 차단 전면 중단 (일반 키보드처럼 동작) |

- 검사 대상 텍스트: 커서 이전 최대 300자 중 마지막 문장 (`. ! ? \n` 기준).
- 응답 유효성(stale guard): 요청 시 검사한 문장 텍스트(뒤쪽 공백 제외)와 문서 내 절대 시작 위치를 스냅샷으로 저장. 응답 도착 시 현재 문서의 같은 위치에 같은 문장 텍스트가 그대로 있으면 **유효** — 그 뒤에 이어서 입력한 내용(스페이스, 다음 문장)은 무관. 문장 내부가 수정됐거나 위치가 밀렸으면 폐기.
- 경찰 모드 차단 판단(PRO): 보안 필드 아님 && 현재 문장에 무시되지 않은 미교정 교정이 1개 이상 → 스페이스/엔터/`. ! ?` 입력을 삼키고 햅틱 재생. 교정 칩 적용 또는 '무시' 탭 시 해당 교정 해제. 무시 목록 키 = (문서 내 절대 위치, original_word); 문장 시작 위치가 바뀌면 초기화.
- iOS: 네트워크와 햅틱(`UINotificationFeedbackGenerator`) 모두 **전체 접근 허용**이 필요. 미허용 시 온디바이스 검사 + 시각적 경고만.
