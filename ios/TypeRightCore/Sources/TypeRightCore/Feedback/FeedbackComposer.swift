import Foundation

/// 피드백 모드 (docs/api-contract.md). Raw values are the API's `mode` strings.
public enum FeedbackMode: String, Codable, CaseIterable, Hashable, Identifiable {
    case spicyWit = "spicy_wit"
    case police
    case gentle

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .spicyWit: return "매운맛 훈수"
        case .police: return "맞춤법 경찰"
        case .gentle: return "상냥한 선생님"
        }
    }

    public var summary: String {
        switch self {
        case .spicyWit: return "유쾌한 팩트폭격 한 문장을 말풍선으로 보여줘요."
        case .police: return "오류를 찾으면 사이렌 햅틱! PRO는 교정 전까지 스페이스·엔터·문장부호 입력을 막아요."
        case .gentle: return "정중한 설명과 문법 원리 팁 카드를 보여줘요."
        }
    }
}

/// `feedback_templates` in korean-rules.json (v2).
public struct FeedbackTemplates: Decodable, Equatable {
    public let spicyWit: String
    public let police: String
    public let gentle: String

    public init(spicyWit: String, police: String, gentle: String) {
        self.spicyWit = spicyWit
        self.police = police
        self.gentle = gentle
    }

    enum CodingKeys: String, CodingKey {
        case police, gentle
        case spicyWit = "spicy_wit"
    }

    public func template(for mode: FeedbackMode) -> String {
        switch mode {
        case .spicyWit: return spicyWit
        case .police: return police
        case .gentle: return gentle
        }
    }

    /// Used only if a rules file predates v2.
    public static let fallback = FeedbackTemplates(
        spicyWit: "'{original}'? 정답은 '{suggested}' 😏",
        police: "🚨 '{original}' → '{suggested}' 교정 전까지 통과 불가!",
        gentle: "✏️ '{original}' → '{suggested}' : {reason}"
    )
}

/// On-device feedback algorithm (contract "피드백 알고리즘"; mirrors backend/src/utils/feedback.ts):
/// first correction → spicy_wit uses the rule's `wit` if present, else the mode template;
/// `{original}` `{suggested}` `{reason}` are substituted (in that order).
public enum FeedbackComposer {
    public static func compose(_ corrections: [Correction], mode: FeedbackMode, templates: FeedbackTemplates) -> String? {
        guard let first = corrections.first else { return nil }
        if mode == .spicyWit, let wit = first.wit, !wit.isEmpty { return wit }
        return templates.template(for: mode)
            .replacingOccurrences(of: "{original}", with: first.originalWord)
            .replacingOccurrences(of: "{suggested}", with: first.suggestedWord)
            .replacingOccurrences(of: "{reason}", with: first.reason)
    }

    /// Rule suggestion type: equal once whitespace is removed → `spacing`, else `spelling`.
    public static func ruleSuggestionType(original: String, suggested: String) -> SuggestionType {
        stripWhitespace(original).utf16Equals(stripWhitespace(suggested)) ? .spacing : .spelling
    }

    /// JS `\s` set (what the backend's `/\s+/g` strips).
    private static let jsWhitespace: Set<UInt32> = {
        var set: Set<UInt32> = [0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF]
        for scalar in UInt32(0x2000)...UInt32(0x200A) { set.insert(scalar) }
        return set
    }()

    static func stripWhitespace(_ text: String) -> String {
        var scalars = String.UnicodeScalarView()
        scalars.append(contentsOf: text.unicodeScalars.filter { !jsWhitespace.contains($0.value) })
        return String(scalars)
    }
}
