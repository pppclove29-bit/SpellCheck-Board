# TypeRight

AI 기반 실시간 맞춤법 & 위트 훈수 키보드. 온디바이스 규칙 교정(무료·무제한)에 AI 문맥 교정 + 피드백 3모드(매운맛 훈수 / 맞춤법 경찰 / 상냥한 선생님)를 얹은 커스텀 키보드(IME).

> **현재 출시 모드: 온디바이스 전용** (2026-09-21 기획자 결정 — [planning-and-dev-log.md](docs/planning-and-dev-log.md) 14절).
> AI 문맥 교정·쿼터·PRO 구독·광고·구글 로그인이 **꺼져 있다.** product flavor 로 갈린다:
>
> | flavor | 빌드 | 내용 |
> |---|---|---|
> | `ondevice` (기본) | `./gradlew :app:assembleOndeviceDebug` | 출시 형태. **AdMob·Play Billing SDK 가 APK 에 없다** |
> | `cloud` | `./gradlew :app:assembleCloudDebug` | AI·로그인·결제·광고 전부 복구 |
>
> 코드·서버·테스트는 전부 그대로 있다. 아래 문서의 AI·결제·계정 관련 절은 **되살릴 때** 필요한 내용이다.

**MVP 범위: Android 단독 출시.** 스택: Android(Kotlin, InputMethodService, Compose) · Python FastAPI · Supabase(Auth + Postgres) · OpenAI GPT-4o-mini · Vercel(서울 `icn1`).

## 구조

```
TypeRight/
├── android/            # app(호스트 앱) + keyboard(IME 라이브러리 모듈)
├── backend/            # FastAPI (Vercel Python 함수)
│   ├── app/api/        # 라우트, 의존성 컨테이너
│   ├── app/services/   # 교정 오케스트레이션, OpenAI, PII 익명화, 쿼터, 인증, AdMob SSV
│   ├── app/utils/      # 한국어 규칙 엔진, 피드백 생성, UTF-16 오프셋 변환
│   ├── app/data/       # shared/korean-rules.json 동기화본 (커밋됨, 테스트로 드리프트 감시)
│   ├── api/index.py    # Vercel 엔트리
│   ├── supabase/migrations/
│   └── tests/
├── shared/
│   ├── korean-rules.json        # 온디바이스 규칙 — 서버·Android 공통 단일 원본
│   └── rule-golden-cases.json   # 크로스플랫폼 골든 테스트
├── docs/api-contract.md         # API·알고리즘·클라이언트 트리거 계약서 (단일 기준)
└── ios/                         # 보류 — ios/README.md 참고
```

## 백엔드 로컬 실행

```bash
cd backend
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
cp .env.example .env            # 로컬: ALLOW_INSECURE_DEV_AUTH=true 로 바꾸면 Supabase 없이 동작
.venv/bin/uvicorn app.main:app --reload --port 8790 --env-file .env
```

```bash
curl -s localhost:8790/v1/grammar-check -H 'content-type: application/json' -H 'x-dev-user-id: me' \
  -d '{"text":"오늘 진짜 어의가 없네","mode":"spicy_wit"}'
```

- `OPENAI_API_KEY`가 없으면 AI 없이 규칙 엔진만 동작 (`ai_status: "unavailable"`).
- Android 에뮬레이터에서는 `http://10.0.2.2:8790`.
- 테스트: `.venv/bin/pytest`

## 규칙 추가/수정

1. `shared/korean-rules.json` 편집 (정규식은 JS/Java/Python 공통 문법만, `\b` 금지)
2. `shared/rule-golden-cases.json`에 케이스 추가 (offset은 UTF-16 기준). 오탐 위험이 있는 규칙이면
   `clean_cases`에 "이 문장은 건드리면 안 된다"는 반례도 함께 추가
3. `cd backend && .venv/bin/python scripts/sync_rules.py && .venv/bin/pytest`
4. Android: `cd android && ./gradlew :keyboard:testDebugUnitTest` (같은 골든 파일 사용)

문맥에 따라 맞고 틀림이 갈리는 쌍(너머/넘어, 띠다/띄다, 반드시/반듯이, 부치다/붙이다 등)은 오탐 위험 때문에 확실한 연어만 규칙화하고, 나머지는 AI 프롬프트의 집중 점검 목록으로 처리한다.

## 계정·키가 필요한 작업

계정 결제, OAuth 클라이언트 생성, 개인정보처리방침, 실기기 확인처럼 코드로 끝낼 수 없는 항목은
[docs/human-todo.md](docs/human-todo.md)에 모아 두었다.

## Supabase 설정

1. 프로젝트 생성 → Authentication → Providers → **Google 활성화** (Google Cloud의 Web client ID/secret 입력, Android는 같은 Web client ID를 `GOOGLE_WEB_CLIENT_ID`로 사용). Anonymous sign-ins는 끈다 (서버도 익명 토큰을 거부)
2. `backend/supabase/migrations/*.sql` 적용 (`supabase db push` 또는 SQL Editor에서 순서대로 실행)
3. 백엔드 env: `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY` (서버 전용 — 클라이언트에 절대 포함 금지). 레거시 HS256 프로젝트만 `SUPABASE_JWT_SECRET`
4. Android BuildConfig: `SUPABASE_URL`, `SUPABASE_ANON_KEY`

## Android 빌드 속성 (gradle.properties 또는 `-P`)

| 속성 | 기본값 | 없으면 |
|---|---|---|
| `typeright.googleWebClientId` | 빈 값 | 구글 로그인 불가 |
| `typeright.admobAppId` | Google **테스트** 앱 ID | 테스트 광고만 표시 (cloud flavor 전용) |
| `typeright.admobRewardedUnitId` | Google **테스트** 광고 단위 | 테스트 광고만 표시 (SSV 콜백 없음 → 충전 안 됨, cloud flavor 전용) |
| `typeright.apiBaseUrl.debug` | `http://10.0.2.2:8790` | — |

AdMob 기본값이 테스트 ID라서 계정 없이도 앱이 뜨고 광고 화면까지 확인할 수 있다. 다만 테스트 광고는 SSV 콜백을
보내지 않으므로 **실제 훈수 충전은 실제 AdMob ID를 넣어야 동작**한다. 이 값들은 `cloud` flavor 에만 쓰인다 —
출시 형태인 `ondevice` 에는 AdMob SDK 자체가 들어가지 않는다.

## 릴리스 서명

`android/keystore.properties`(gitignore 됨)가 있으면 릴리스 빌드에 서명한다. 없으면 **서명 없이** 빌드된다
(디버그 키로 서명하지 않는다 — 그런 AAB를 Play에 올리면 업로드 키가 디버그 키로 굳는다).
설정 방법은 [keystore.properties.example](android/keystore.properties.example) 참고.
**`.jks`·비밀번호는 저장소에 넣지 않는다.**

쿼터 함수는 service role에만 실행 권한이 있고, 테이블은 RLS로 클라이언트 접근이 막혀 있다. 단축어(`shortcuts`)만 클라이언트가 PostgREST로 직접 읽고 쓰며, 쓰기는 RLS에서 PRO 여부를 검사한다.

## Vercel 배포

- Root Directory: `backend` / 리전: `icn1` (vercel.json)
- 환경 변수: `.env.example` 참고 (`ALLOW_INSECURE_DEV_AUTH`는 production에서 켜면 부팅 실패하도록 막혀 있음)
- AdMob 콘솔의 SSV 콜백 URL: `https://<도메인>/v1/ads/admob-ssv`
- Play 구독 검증: `PLAY_PACKAGE_NAME` + `GOOGLE_SERVICE_ACCOUNT_JSON` (Play Developer API 권한이 있는 서비스 계정 키
  JSON 전체, 줄바꿈은 `\n`). 둘 중 하나라도 없으면 구매 검증이 항상 `unavailable`이라 PRO가 부여되지 않는다
- Render 무료 플랜은 유휴 후 슬립(콜드스타트 수십 초)이라 실시간 키보드에 부적합

## 제품 정책 결정 사항 (기획서에 명시되지 않아 정한 것)

| 항목 | 결정 | 이유 |
|---|---|---|
| AI 쿼터 차감 | AI가 오류를 찾아 훈수를 전달했을 때만 1회 차감 | 하루 5회가 오류 없는 문장에 소진되지 않도록 |
| 경찰 모드 무료/PRO | 무료 = 햅틱 경고만, PRO = 스페이스바·엔터·문장부호 차단 | 기획서의 "경찰 모드 풀버전 = PRO" |
| 경찰 모드 탈출구 | '무시' 칩 항상 제공 | 규칙 오탐 시 입력 불능 방지 |
| 요청 body `user_id` | 받되 무시, Supabase JWT의 `sub` 사용 | 위조로 타인 쿼터·PRO 도용 방지 |
| PRO "무제한" | 서버 fair-use 상한 300회/일 | 비용 폭주·남용 방지 |
| 광고 보상 | 1회 +3, 하루 최대 5회, AdMob SSV 서명 검증 + transaction_id 멱등 | 클라이언트 보상 위조 방지 |
| 규칙 vs AI 충돌 | 같은 구간은 규칙 결과 우선, AI 오프셋은 원문에서 재탐색해 검증 | LLM 오프셋 부정확, 환각 구간 제거 |
| 개인정보 | AI 호출 전 주민번호·카드·전화·이메일·계좌 마스킹, 서버 로그에 입력 텍스트 미기록 | |
| 톤 변환(V2) | 제거 — V4 피드백 모드로 대체 | |

## 진행 상태

- [x] API 계약서, 공통 규칙 파일(사전 193개 + 패턴 11개, 매운맛 전용 멘트 55개), 골든 테스트(교정 41 · 오탐 방지 15 · 피드백 6 · 유형 4)
- [x] 백엔드: 규칙 엔진, PII 마스킹, OpenAI 구조화 출력 연동, 쿼터/광고 보상/PRO, Supabase JWT 인증, AdMob SSV, **Play 구독 영수증 검증** — pytest 182개 통과 (OpenAI·Play API는 HTTP 모킹으로 검증)
- [x] Supabase 마이그레이션: Postgres 18(PGlite)에서 적용 + 쿼터 상한·광고 멱등/일일 상한·PRO 만료·클라이언트 RPC 차단·단축어 RLS 검증 (실제 Supabase 프로젝트 적용은 미확인)
- [ ] 백엔드: 실제 OpenAI 키로 응답 품질 확인, Vercel 실배포 확인
- [x] 인앱 결제: Play Billing 연동 + 서버 영수증 검증(`POST /v1/billing/play/verify`) → `entitlements` 갱신, 갱신·해지는 `/v1/me`에서 지연 재검증
- [x] AdMob SDK 연동 (호스트 앱 보상형 광고, SSV userId = Supabase user id). 기본값은 Google 테스트 광고 ID — **테스트 광고는 SSV 콜백을 보내지 않으므로 실제 충전은 실제 AdMob ID 필요**
- [x] Android 키보드·호스트 앱 (구글 로그인, 충전 팝업, AI 동의, 계정 삭제, 예산 중단·AI 대상 사전 판정 포함) — 단위 테스트 166개 통과, 디버그 APK 빌드
- [x] 키보드 기본기: 이모지 패널(8개 카테고리 + 최근 사용), 키 진동·소리 토글, 상단 행 롱프레스 숫자
- [x] 계측: Firebase Analytics (이벤트 16종 + 사용자 속성 3종). **입력 텍스트·교정 단어·앱 패키지명은 전송하지 않음** — 테스트로 강제. `app/google-services.json`이 없으면 no-op이라 빌드·동작에 영향 없음
- [x] 성장 기능: 텍스트 선택 메뉴 [맛춤뻡 검사](키보드 교체 없이 검사), 📸 짤 생성 공유 카드, 앱별 자동 모드(카톡=매운맛 / 슬랙·메일=선생님) (`cd android && ./gradlew :keyboard:testDebugUnitTest :app:assembleDebug`)
- [ ] Android 실기기/에뮬레이터 동작 확인 → 사람이 해야 할 일은 [docs/human-todo.md](docs/human-todo.md)에 정리
- [ ] iOS — 보류
