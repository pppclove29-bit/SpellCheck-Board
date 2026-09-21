package com.typeright.keyboard.secure

/**
 * 입력한 텍스트를 읽어도 되는지 판단하는 **단 하나의 관문**.
 *
 * 공개된 개인정보처리방침은 "비밀번호 입력란과 시크릿 모드에서는 맞춤법 검사 자체를 하지 않는다"고
 * 약속한다(`docs/privacy-claims.md`). 그 약속은 호출부마다 `if (secure) return` 을 잊지 않는 것에
 * 기대면 언젠가 깨진다 — 텍스트를 읽는 새 코드 경로가 하나만 생겨도 그렇다.
 *
 * 그래서 IME 의 `readWindow()` 가 이 판단을 거치게 하고, 텍스트 읽기는 전부 그 함수로만 지나가게 했다.
 * `SecureFieldDetector` 가 "이 입력란이 보안인가"를 정하고, 여기서 "그러면 읽어도 되는가"를 정한다.
 */
object TextAccessPolicy {

    /** 보안 입력란(비밀번호·개인화 학습 거부)에서는 입력 텍스트를 읽지 않는다. */
    fun mayReadText(secure: Boolean): Boolean = !secure
}
