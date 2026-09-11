import Foundation

/// What the keyboard must do to the document to reflect a composer step.
///
/// Custom keyboards cannot use marked text, so the in-progress syllable is real text:
/// `deleteBackward()` × `deleteCount`, then `insertText(insertText)`.
/// `deleteCount` is in *characters* (what one `deleteBackward()` removes). Every character a
/// composer emits (precomposed syllables, compatibility jamo, "ㆍ", "‥") is 1 grapheme = 1 UTF-16 unit.
public struct CompositionEdit: Equatable {
    public var deleteCount: Int
    public var insertText: String

    public init(deleteCount: Int, insertText: String) {
        self.deleteCount = deleteCount
        self.insertText = insertText
    }

    public static let none = CompositionEdit(deleteCount: 0, insertText: "")

    public var isEmpty: Bool { deleteCount == 0 && insertText.isEmpty }

    /// Minimal edit turning the on-screen tail `old` into `new` (keeps the common prefix to avoid flicker).
    public static func diff(from old: String, to new: String) -> CompositionEdit {
        let o = Array(old)
        let n = Array(new)
        var common = 0
        while common < o.count, common < n.count, o[common] == n[common] { common += 1 }
        return CompositionEdit(deleteCount: o.count - common, insertText: String(n[common...]))
    }

    /// Applies the edit to a plain string (tests, and tracking the expected document context).
    public func applied(to text: String) -> String {
        var result = text
        result.removeLast(min(deleteCount, result.count))
        result += insertText
        return result
    }
}

/// Common surface of the 두벌식 and 천지인 composers.
public protocol KoreanComposer {
    /// Text of the in-progress syllable currently on screen ("" when idle).
    var composingText: String { get }
    /// Jamo-level backspace. `nil` when nothing is composing: the caller should delete normally.
    mutating func backspace() -> CompositionEdit?
    /// Drops composition state without touching the document (the syllable on screen simply becomes final).
    /// Call before inserting non-jamo text, and when the document changed externally.
    mutating func reset()
}

extension KoreanComposer {
    public var isComposing: Bool { !composingText.isEmpty }
}
