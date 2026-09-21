package com.typeright.keyboard

/**
 * 온디바이스 전용 출시를 위한 기능 스위치 (2026-09-21 결정 — planning-and-dev-log.md 14절).
 *
 * 서버·계정·결제가 필요한 기능을 **코드를 지우지 않고** 한 곳에서 끈다. 모든 판단은
 * [BuildConfig.CLOUD_FEATURES] 하나에서 갈라지고, 그 값은 **product flavor**가 정한다:
 *
 * - `ondevice` (기본, 출시 형태) — `false`. AdMob·Play Billing SDK 와 관련 매니페스트 항목이
 *   APK 에 **아예 들어가지 않는다**(`app/src/cloud/` 소스셋과 `cloudImplementation` 의존성이 빠진다).
 * - `cloud` — `true`. 전부 되살아난다.
 *
 * 되살리기: `./gradlew :app:assembleCloudDebug` (또는 Android Studio 의 Build Variants 에서 cloud 선택).
 *
 * 꺼져 있을 때 앱은 **로그인 없는 온디바이스 경로가 기본**이 된다:
 * 규칙 사전 교정, 3모드 훈수, 짤 카드, 이모지, 롱프레스 숫자, 보안 키패드, 텍스트 선택 검사,
 * 진동·소리는 그대로 동작하고, 서버를 타는 경로는 진입점 자체가 사라진다(비활성 버튼·빈 화면을 남기지 않는다).
 */
object FeatureFlags {
    /** 서버·계정·결제가 필요한 기능 전체의 단일 스위치. */
    val cloud: Boolean = BuildConfig.CLOUD_FEATURES

    /** OpenAI 문맥 교정(`/v1/grammar-check`의 AI 부분)과 쿼터. */
    val ai: Boolean get() = cloud

    /** 구글 로그인 / Supabase 세션 / 계정 삭제. */
    val auth: Boolean get() = cloud

    /** PRO 구독 + Play 영수증 검증. 꺼지면 PRO 탭과 페이월이 사라진다. */
    val billing: Boolean get() = cloud

    /** 보상형 광고와 ⚡충전. */
    val ads: Boolean get() = cloud

    /** 단축어의 Supabase 동기화. 꺼져도 단축어 자체는 기기 안에서 그대로 쓴다. */
    val shortcutSync: Boolean get() = cloud

    /**
     * PRO 페이월을 적용할지. 결제가 없는 빌드에서 PRO 전용 기능을 잠가 두면 **아무도 열 수 없는 막다른 길**이
     * 되므로, 이때는 커스텀 단축어를 기기 안에서 무료로 열어 준다(동기화만 빠진다).
     * 경찰 모드 입력 차단은 원래 PRO 기능이라 이 빌드에 없고, 무료 동작인 진동 경고는 로그인 없이 그대로 된다.
     */
    val proGate: Boolean get() = billing

    /**
     * 커스텀 단축어처럼 "PRO라야 열리는" 잠금을 지금 빌드에서 풀어 줘야 하는지. 결제가 없으면 항상 열어 준다.
     * 경찰 모드 입력 차단은 이 함수를 쓰지 않고 `account.isPro`를 그대로 본다 — 이 빌드에서는 꺼진 채로 둔다.
     */
    fun proUnlocked(isPro: Boolean): Boolean = !proGate || isPro
}
