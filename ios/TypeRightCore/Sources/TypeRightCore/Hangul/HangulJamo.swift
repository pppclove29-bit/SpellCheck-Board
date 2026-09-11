import Foundation

/// Hangul tables and syllable arithmetic (Unicode "Hangul Syllables" block, U+AC00...).
/// All jamo are handled as *compatibility jamo* (U+3131...) so that a lone consonant or vowel
/// renders as one standalone character (and is exactly 1 UTF-16 unit / 1 grapheme).
public enum HangulJamo {
    public static let choseong: [Character] = [
        "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ", "ㅅ",
        "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
    ]
    public static let jungseong: [Character] = [
        "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ", "ㅙ",
        "ㅚ", "ㅛ", "ㅜ", "ㅝ", "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ", "ㅣ",
    ]
    /// Index 0 = no final consonant.
    public static let jongseong: [Character?] = [
        nil, "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ", "ㄾ",
        "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ",
    ]

    /// 두벌식 vowel combinations (first + second → compound).
    public static let compoundVowels: [String: Character] = [
        "ㅗㅏ": "ㅘ", "ㅗㅐ": "ㅙ", "ㅗㅣ": "ㅚ",
        "ㅜㅓ": "ㅝ", "ㅜㅔ": "ㅞ", "ㅜㅣ": "ㅟ",
        "ㅡㅣ": "ㅢ",
    ]

    /// Compound final consonants (겹받침) and their components.
    public static let compoundFinals: [String: Character] = [
        "ㄱㅅ": "ㄳ", "ㄴㅈ": "ㄵ", "ㄴㅎ": "ㄶ",
        "ㄹㄱ": "ㄺ", "ㄹㅁ": "ㄻ", "ㄹㅂ": "ㄼ", "ㄹㅅ": "ㄽ", "ㄹㅌ": "ㄾ", "ㄹㅍ": "ㄿ", "ㄹㅎ": "ㅀ",
        "ㅂㅅ": "ㅄ",
    ]

    /// Inverse of `compoundFinals`: 겹받침 → (kept final, consonant that moves to the next syllable).
    public static let splitFinals: [Character: (Character, Character)] = {
        var map: [Character: (Character, Character)] = [:]
        for (pair, compound) in compoundFinals {
            let chars = Array(pair)
            map[compound] = (chars[0], chars[1])
        }
        return map
    }()

    private static let choIndex: [Character: Int] = Dictionary(uniqueKeysWithValues: choseong.enumerated().map { ($1, $0) })
    private static let jungIndex: [Character: Int] = Dictionary(uniqueKeysWithValues: jungseong.enumerated().map { ($1, $0) })
    private static let jongIndex: [Character: Int] = {
        var map: [Character: Int] = [:]
        for (i, c) in jongseong.enumerated() { if let c { map[c] = i } }
        return map
    }()

    public static func isConsonant(_ c: Character) -> Bool { choIndex[c] != nil || jongIndex[c] != nil }
    public static func isVowel(_ c: Character) -> Bool { jungIndex[c] != nil }
    /// ㄸ ㅃ ㅉ can never be a final consonant.
    public static func canBeFinal(_ c: Character) -> Bool { jongIndex[c] != nil }
    public static func canBeInitial(_ c: Character) -> Bool { choIndex[c] != nil }

    public static func combineVowels(_ a: Character, _ b: Character) -> Character? { compoundVowels[String([a, b])] }
    public static func combineFinals(_ a: Character, _ b: Character) -> Character? { compoundFinals[String([a, b])] }

    /// Composes a precomposed syllable. Returns nil for invalid combinations.
    public static func compose(cho: Character, jung: Character, jong: Character? = nil) -> Character? {
        guard let c = choIndex[cho], let v = jungIndex[jung] else { return nil }
        var t = 0
        if let jong {
            guard let idx = jongIndex[jong] else { return nil }
            t = idx
        }
        let scalar = 0xAC00 + (c * 21 + v) * 28 + t
        return Unicode.Scalar(scalar).map(Character.init)
    }

    /// Decomposes a precomposed syllable into compatibility jamo.
    public static func decompose(_ syllable: Character) -> (cho: Character, jung: Character, jong: Character?)? {
        guard let scalar = syllable.unicodeScalars.first, syllable.unicodeScalars.count == 1 else { return nil }
        let v = Int(scalar.value)
        guard (0xAC00...0xD7A3).contains(v) else { return nil }
        let index = v - 0xAC00
        return (choseong[index / (21 * 28)], jungseong[(index % (21 * 28)) / 28], jongseong[index % 28])
    }
}

/// The state of one in-progress syllable (초성/중성/종성, any of which may be missing).
public struct HangulSyllable: Equatable {
    public var cho: Character?
    public var jung: Character?
    public var jong: Character?

    public init(cho: Character? = nil, jung: Character? = nil, jong: Character? = nil) {
        self.cho = cho
        self.jung = jung
        self.jong = jong
    }

    public var isEmpty: Bool { cho == nil && jung == nil && jong == nil }

    /// On-screen rendering: a precomposed syllable, or the lone compatibility jamo.
    public var text: String {
        if let cho, let jung, let syllable = HangulJamo.compose(cho: cho, jung: jung, jong: jong) {
            return String(syllable)
        }
        // States the automaton never produces together (e.g. vowel + final without initial) fall back to concatenation.
        return [cho, jung, jong].compactMap { $0 }.map(String.init).joined()
    }
}

/// Result of feeding one jamo to a syllable (pure 두벌식 automaton step).
enum SyllableStep: Equatable {
    /// The current syllable was modified in place (push onto the backspace history).
    case update(HangulSyllable)
    /// `committed` is final text; `history` is the backspace chain of the new in-progress syllable.
    case commit(String, history: [HangulSyllable])
}

/// Pure 두벌식 syllable automaton, shared by both composers.
enum SyllableAutomaton {
    static func addConsonant(_ c: Character, to s: HangulSyllable) -> SyllableStep {
        if s.isEmpty { return .update(HangulSyllable(cho: c)) }
        // Initial only, or vowel only (no initial): cannot absorb another consonant.
        guard s.cho != nil, s.jung != nil else {
            return .commit(s.text, history: [HangulSyllable(cho: c)])
        }
        if let jong = s.jong {
            if let compound = HangulJamo.combineFinals(jong, c) {
                var next = s
                next.jong = compound
                return .update(next)
            }
            return .commit(s.text, history: [HangulSyllable(cho: c)])
        }
        if HangulJamo.canBeFinal(c) {
            var next = s
            next.jong = c
            return .update(next)
        }
        return .commit(s.text, history: [HangulSyllable(cho: c)])
    }

    /// `combine` decides vowel compounding (두벌식 table vs 천지인 strokes).
    static func addVowel(_ v: Character, to s: HangulSyllable,
                         combine: (Character, Character) -> Character?) -> SyllableStep {
        if s.isEmpty { return .update(HangulSyllable(jung: v)) }
        if let jong = s.jong, let cho = s.cho, let jung = s.jung {
            // Final consonant moves to the next syllable: 갑+ㅏ → 가바, 값+ㅏ → 갑사.
            let kept: Character?
            let moved: Character
            if let (first, second) = HangulJamo.splitFinals[jong] {
                kept = first
                moved = second
            } else {
                kept = nil
                moved = jong
            }
            let committed = HangulSyllable(cho: cho, jung: jung, jong: kept).text
            return .commit(committed, history: [HangulSyllable(cho: moved), HangulSyllable(cho: moved, jung: v)])
        }
        if let jung = s.jung {
            if let compound = combine(jung, v) {
                var next = s
                next.jung = compound
                return .update(next)
            }
            return .commit(s.text, history: [HangulSyllable(jung: v)])
        }
        // Initial only.
        var next = s
        next.jung = v
        return .update(next)
    }
}
