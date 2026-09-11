import Foundation

/// A sentence located inside `documentContextBeforeInput`.
public struct ExtractedSentence: Equatable {
    public let text: String
    /// UTF-16 offset of `text` within the context string.
    public let offset: Int
    /// UTF-16 length of the whole context string (the cursor sits at this index).
    public let contextLength: Int

    public init(text: String, offset: Int, contextLength: Int) {
        self.text = text
        self.offset = offset
        self.contextLength = contextLength
    }

    public var length: Int { text.utf16.count }
    /// UTF-16 units between the end of the sentence and the cursor (e.g. trailing spaces).
    public var distanceFromCursor: Int { contextLength - offset - length }
}

/// "검사 대상 텍스트: 커서 이전 최대 300자 중 마지막 문장 (`. ! ? \n` 기준)" — docs/api-contract.md.
public enum SentenceExtractor {
    public static let maxLookback = 300

    /// The last sentence before the cursor, looking back at most `maxLookback` UTF-16 units.
    ///
    /// Trailing whitespace is excluded; the trailing `.`/`!`/`?` run is included, so right after typing
    /// "오늘 몇일이야?" the sentence is "오늘 몇일이야?", and after "…? 내일 갈께" it is "내일 갈께".
    public static func currentSentence(in context: String?, maxLookback: Int = maxLookback) -> ExtractedSentence? {
        guard let context, !context.isEmpty else { return nil }
        let units = Array(context.utf16)
        let count = units.count
        var lower = max(0, count - maxLookback)
        // Never start inside a surrogate pair.
        if lower > 0, lower < count, UTF16.isTrailSurrogate(units[lower]) { lower += 1 }

        var end = count
        while end > lower, isWhitespace(units[end - 1]) { end -= 1 }
        var contentEnd = end
        while contentEnd > lower, isSentencePunctuation(units[contentEnd - 1]) { contentEnd -= 1 }
        var start = contentEnd
        while start > lower, !isTerminator(units[start - 1]) { start -= 1 }
        while start < contentEnd, isWhitespace(units[start]) { start += 1 }
        guard start < contentEnd else { return nil }

        let text = String(decoding: units[start..<end], as: UTF16.self)
        return ExtractedSentence(text: text, offset: start, contextLength: count)
    }

    static func isSentencePunctuation(_ unit: UInt16) -> Bool {
        unit == 0x2E || unit == 0x21 || unit == 0x3F // . ! ?
    }

    static func isTerminator(_ unit: UInt16) -> Bool {
        isSentencePunctuation(unit) || unit == 0x0A || unit == 0x0D // \n \r
    }

    static func isWhitespace(_ unit: UInt16) -> Bool {
        switch unit {
        case 0x20, 0x09, 0x0A, 0x0D, 0x0B, 0x0C, 0xA0, 0x3000: return true
        default: return false
        }
    }
}
