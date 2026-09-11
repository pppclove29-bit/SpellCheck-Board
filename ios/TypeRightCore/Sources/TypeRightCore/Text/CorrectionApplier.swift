import Foundation

/// The `textDocumentProxy` operations that replace a span inside `documentContextBeforeInput`.
///
/// Execute in order:
/// 1. `adjustTextPosition(byCharacterOffset: cursorOffsetToSpanEnd)` (skip if 0)
/// 2. `deleteBackward()` × `deleteCount`
/// 3. `insertText(insertText)`
/// 4. `adjustTextPosition(byCharacterOffset: cursorOffsetAfterInsert)` (skip if 0)
///
/// Units caveat: cursor offsets are UTF-16 code units (what UIKit hosts use for
/// `adjustTextPosition` in practice), while `deleteCount` counts *characters* (grapheme clusters),
/// because one `deleteBackward()` removes a whole composed character (an emoji is 2+ units but 1 delete).
/// For Hangul syllables both are 1 per syllable, so the two only diverge around emoji/combining marks.
/// Moving back after the insert is simply `+distance`: the text between the span and the cursor is
/// unchanged, so the length delta of the replacement does not affect the relative move.
public struct CorrectionPlan: Equatable {
    public let cursorOffsetToSpanEnd: Int
    public let deleteCount: Int
    public let insertText: String
    public let cursorOffsetAfterInsert: Int
    /// What `documentContextBeforeInput` should read afterwards.
    public let resultingContext: String
    /// UTF-16 length change (replacement - original). The cursor's absolute index shifts by this.
    public let lengthDelta: Int
}

public enum CorrectionApplier {
    /// - Parameters:
    ///   - context: current `documentContextBeforeInput` (cursor at its end).
    ///   - offset/length: UTF-16 span inside `context`.
    ///   - expectedOriginal: when given, the span must contain exactly this text (else nil: stale correction).
    public static func plan(context: String, offset: Int, length: Int,
                            expectedOriginal: String?, replacement: String) -> CorrectionPlan? {
        let ns = context as NSString
        guard offset >= 0, length > 0, offset + length <= ns.length else { return nil }
        let range = NSRange(location: offset, length: length)
        let original = ns.substring(with: range)
        if let expectedOriginal, !original.utf16Equals(expectedOriginal) { return nil }
        // Both ends must sit on character boundaries, otherwise deleteBackward() counts are ambiguous.
        let boundaries = characterBoundaries(of: context)
        guard boundaries.contains(offset), boundaries.contains(offset + length) else { return nil }

        let distance = ns.length - (offset + length)
        return CorrectionPlan(
            cursorOffsetToSpanEnd: -distance,
            deleteCount: original.count,
            insertText: replacement,
            cursorOffsetAfterInsert: distance,
            resultingContext: ns.replacingCharacters(in: range, with: replacement),
            lengthDelta: replacement.utf16.count - length
        )
    }

    /// Locates the span by its distance from the cursor (robust to the proxy returning a
    /// differently-truncated context start than when the correction was computed).
    public static func plan(context: String, distanceFromCursor: Int,
                            original: String, replacement: String) -> CorrectionPlan? {
        let offset = context.utf16.count - distanceFromCursor - original.utf16.count
        return plan(context: context, offset: offset, length: original.utf16.count,
                    expectedOriginal: original, replacement: replacement)
    }

    static func characterBoundaries(of text: String) -> Set<Int> {
        var boundaries: Set<Int> = [0]
        var position = 0
        for character in text {
            position += character.utf16.count
            boundaries.insert(position)
        }
        return boundaries
    }
}
