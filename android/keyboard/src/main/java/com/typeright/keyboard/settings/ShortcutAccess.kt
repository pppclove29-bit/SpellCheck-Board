package com.typeright.keyboard.settings

/**
 * Who may add/edit custom shortcuts. Existing custom shortcuts always keep working in the keyboard and can always be
 * deleted (RLS allows owner delete); only adding/editing requires PRO.
 */
enum class ShortcutAccess {
    /** PRO: add/edit allowed. */
    ALLOWED,

    /** Not PRO but has custom shortcuts (PRO expired/cancelled): "PRO가 만료되어 단축어를 추가할 수 없습니다". */
    EXPIRED,

    /** Never had custom shortcuts: the normal PRO paywall. */
    PAYWALL;

    companion object {
        fun forAddOrEdit(isPro: Boolean, hasCustomShortcuts: Boolean): ShortcutAccess = when {
            isPro -> ALLOWED
            hasCustomShortcuts -> EXPIRED
            else -> PAYWALL
        }
    }
}
