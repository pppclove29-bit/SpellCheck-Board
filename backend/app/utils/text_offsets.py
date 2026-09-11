"""Python indexes str by code point; the API contract (and Kotlin/Swift/JS) uses UTF-16 code units.

They differ only for astral characters (emoji etc.), which take 2 UTF-16 units but 1 code point.
Everything inside the backend works in code points; convert only at the boundary.
"""


def utf16_len(s: str) -> int:
    return len(s.encode("utf-16-le")) // 2


def cp_to_utf16(text: str, cp_index: int) -> int:
    return utf16_len(text[:cp_index])


def utf16_to_cp(text: str, u16_index: int) -> int:
    units = 0
    for i, ch in enumerate(text):
        if units >= u16_index:
            return i
        units += 2 if ord(ch) > 0xFFFF else 1
    return len(text)
