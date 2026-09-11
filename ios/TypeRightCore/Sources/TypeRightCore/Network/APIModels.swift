import Foundation

// Codable models for docs/api-contract.md (v1 · 기획서 V4). All offsets/lengths are UTF-16 code units.
// Enums decode leniently (unknown raw values → `.unknown`) so a newer server never breaks the keyboard.

public enum CorrectionSource: String, Codable, Equatable, Hashable {
    case rule
    case ai
    case unknown

    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = CorrectionSource(rawValue: raw) ?? .unknown
    }
}

public enum SuggestionType: String, Codable, Equatable, Hashable {
    case spelling
    case spacing
    case grammar
    case wordChoice = "word_choice"
    case unknown

    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = SuggestionType(rawValue: raw) ?? .unknown
    }

    public var displayName: String {
        switch self {
        case .spelling: return "맞춤법"
        case .spacing: return "띄어쓰기"
        case .grammar: return "문법"
        case .wordChoice: return "어휘"
        case .unknown: return "교정"
        }
    }
}

/// One suggestion (`suggestions[]` in the API; also produced on-device by `RuleEngine`).
public struct Correction: Codable, Equatable, Hashable {
    public var offset: Int
    public var length: Int
    public var originalWord: String
    public var suggestedWord: String
    public var type: SuggestionType
    public var reason: String
    public var source: CorrectionSource
    /// Rule-specific 매운맛 line (on-device only; never part of the API payload).
    public var wit: String?

    public init(offset: Int, length: Int, originalWord: String, suggestedWord: String,
                type: SuggestionType? = nil, reason: String = "", source: CorrectionSource = .rule, wit: String? = nil) {
        self.offset = offset
        self.length = length
        self.originalWord = originalWord
        self.suggestedWord = suggestedWord
        self.type = type ?? FeedbackComposer.ruleSuggestionType(original: originalWord, suggested: suggestedWord)
        self.reason = reason
        self.source = source
        self.wit = wit
    }

    enum CodingKeys: String, CodingKey {
        case offset, length, type, reason, source
        case originalWord = "original_word"
        case suggestedWord = "suggested_word"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        offset = try c.decode(Int.self, forKey: .offset)
        length = try c.decode(Int.self, forKey: .length)
        originalWord = try c.decode(String.self, forKey: .originalWord)
        suggestedWord = try c.decode(String.self, forKey: .suggestedWord)
        type = try c.decodeIfPresent(SuggestionType.self, forKey: .type)
            ?? FeedbackComposer.ruleSuggestionType(original: originalWord, suggested: suggestedWord)
        reason = try c.decodeIfPresent(String.self, forKey: .reason) ?? ""
        source = try c.decodeIfPresent(CorrectionSource.self, forKey: .source) ?? .rule
        wit = nil
    }

    /// True if `text` really contains `originalWord` at `offset` (defensive check before showing/applying).
    public func matches(in text: String) -> Bool {
        let ns = text as NSString
        guard offset >= 0, length > 0, offset + length <= ns.length,
              originalWord.utf16.count == length else { return false }
        return ns.substring(with: NSRange(location: offset, length: length)).utf16Equals(originalWord)
    }
}

public struct GrammarCheckRequest: Codable, Equatable {
    public let text: String
    public let mode: FeedbackMode
}

public enum CheckEngine: String, Codable, Equatable {
    case rule
    case hybrid
    case unknown

    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = CheckEngine(rawValue: raw) ?? .unknown
    }
}

public enum AIStatus: String, Codable, Equatable {
    case used
    case quotaExceeded = "quota_exceeded"
    case unavailable
    case unknown

    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = AIStatus(rawValue: raw) ?? .unknown
    }
}

public struct QuotaStatus: Codable, Equatable {
    public var isPro: Bool
    public var limit: Int
    public var used: Int
    public var bonus: Int
    public var remaining: Int

    public init(isPro: Bool, limit: Int, used: Int, bonus: Int, remaining: Int) {
        self.isPro = isPro
        self.limit = limit
        self.used = used
        self.bonus = bonus
        self.remaining = remaining
    }

    enum CodingKeys: String, CodingKey {
        case limit, used, bonus, remaining
        case isPro = "is_pro"
    }
}

public struct GrammarCheckResponse: Codable, Equatable {
    public let originalText: String
    public let hasError: Bool
    public let correctedText: String
    /// Mode-styled one-liner; `null` when there is no error.
    public let witFeedback: String?
    public let suggestions: [Correction]
    public let engine: CheckEngine
    public let aiStatus: AIStatus
    public let quota: QuotaStatus?

    enum CodingKeys: String, CodingKey {
        case suggestions, engine, quota
        case originalText = "original_text"
        case hasError = "has_error"
        case correctedText = "corrected_text"
        case witFeedback = "wit_feedback"
        case aiStatus = "ai_status"
    }
}

/// `GET /v1/me`
public struct MeResponse: Codable, Equatable {
    public let userID: String
    public let isPro: Bool
    public let quota: QuotaStatus

    enum CodingKeys: String, CodingKey {
        case quota
        case userID = "user_id"
        case isPro = "is_pro"
    }
}

public struct HealthResponse: Codable, Equatable {
    public let status: String
    public let ai: Bool
}

/// `{ "error": { "code": "...", "message": "..." } }`
public struct APIErrorEnvelope: Codable, Equatable {
    public struct Body: Codable, Equatable {
        public let code: String
        public let message: String?
    }

    public let error: Body
}
