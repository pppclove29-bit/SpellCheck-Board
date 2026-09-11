import Foundation

/// 맞춤법 경찰 decisions. Pure logic; the keyboard performs the haptics / visual flash.
///
/// - Detection (any user): new unresolved error in police mode → siren (haptic with Full Access, else visual).
/// - Gated keys (space, return, `.` `!` `?`) with unresolved errors in police mode:
///   PRO → block the key; free → let it through with a warning.
/// - Sensitive fields (`captureEnabled == false`): everything off, never blocks.
public enum PoliceGate {
    public enum Alert: Equatable {
        case haptic
        case visualOnly
    }

    public enum Decision: Equatable {
        case allow
        case warn(Alert)
        case block(Alert)

        public var blocksInput: Bool {
            if case .block = self { return true }
            return false
        }
    }

    /// Keys that end a word/sentence: 스페이스바·엔터·`. ! ?`.
    public static func isGatedInsertion(_ text: String) -> Bool {
        switch text {
        case " ", "\n", "\r", "\r\n", ".", "!", "?": return true
        default: return false
        }
    }

    public static func decide(inserting text: String, mode: FeedbackMode, isPro: Bool, captureEnabled: Bool,
                              unresolvedErrors: Int, hapticsAvailable: Bool) -> Decision {
        guard captureEnabled, mode == .police, unresolvedErrors > 0, isGatedInsertion(text) else { return .allow }
        let alert: Alert = hapticsAvailable ? .haptic : .visualOnly
        return isPro ? .block(alert) : .warn(alert)
    }

    /// Siren when errors newly appear (not on every re-check of the same span).
    public static func detectionAlert(mode: FeedbackMode, captureEnabled: Bool, newErrors: Int,
                                      hapticsAvailable: Bool) -> Alert? {
        guard captureEnabled, mode == .police, newErrors > 0 else { return nil }
        return hapticsAvailable ? .haptic : .visualOnly
    }
}

/// A span the user dismissed with "무시" — valid only while the current sentence still starts
/// with the same text up to the end of the span (i.e. within the current sentence).
public struct IgnoredSpan: Hashable {
    public let offsetInSentence: Int
    public let originalWord: String
    /// Sentence text from its start to the end of the span.
    public let sentencePrefix: String

    public init(offsetInSentence: Int, originalWord: String, sentencePrefix: String) {
        self.offsetInSentence = offsetInSentence
        self.originalWord = originalWord
        self.sentencePrefix = sentencePrefix
    }
}

public struct IgnoreList: Equatable {
    public private(set) var spans: Set<IgnoredSpan> = []

    public init() {}

    public mutating func add(_ span: IgnoredSpan) {
        spans.insert(span)
    }

    public func isIgnored(_ correction: Correction, inSentence sentence: String) -> Bool {
        spans.contains { span in
            span.offsetInSentence == correction.offset
                && span.originalWord.utf16Equals(correction.originalWord)
                && sentence.utf16HasPrefix(span.sentencePrefix)
        }
    }

    /// Drops spans that no longer belong to the current sentence.
    public mutating func prune(currentSentence: String?) {
        guard let currentSentence else {
            spans.removeAll()
            return
        }
        spans = spans.filter { currentSentence.utf16HasPrefix($0.sentencePrefix) }
    }

    public mutating func removeAll() {
        spans.removeAll()
    }
}

/// When to spend an AI call ("네트워크 허용 & (is_pro 또는 remaining > 0) & 직전 AI 요청과 텍스트가 다를 때").
public enum AIGate {
    public enum Decision: Equatable {
        case request
        case skip
        /// Free quota used up: show the "광고 보고 AI 훈수 3회 충전" chip instead of calling.
        case quotaExhausted
    }

    public static func decide(networkAllowed: Bool, quota: QuotaStatus?, sentence: String,
                              lastRequestedSentence: String?) -> Decision {
        guard networkAllowed,
              !sentence.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              sentence.utf16.count <= GrammarAPIClient.maxTextLength else { return .skip }
        if let last = lastRequestedSentence, last.utf16Equals(sentence) { return .skip }
        // Quota unknown (no /v1/me yet): let the server decide.
        guard let quota else { return .request }
        return quota.isPro || quota.remaining > 0 ? .request : .quotaExhausted
    }
}
