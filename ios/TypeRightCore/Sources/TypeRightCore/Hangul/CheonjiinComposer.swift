import Foundation

/// The three 천지인 vowel strokes: 천(ㆍ) 지(ㅡ) 인(ㅣ).
public enum CheonjiinStroke: Character, CaseIterable, Hashable {
    case i = "ㅣ"
    case dot = "ㆍ"
    case eu = "ㅡ"
}

/// 천지인 consonant keys; repeated taps cycle through `letters`.
public enum CheonjiinConsonantGroup: Int, CaseIterable, Hashable {
    case giyeok, nieun, digeut, bieup, siot, jieut, ieung

    public var letters: [Character] {
        switch self {
        case .giyeok: return ["ㄱ", "ㅋ", "ㄲ"]
        case .nieun: return ["ㄴ", "ㄹ"]
        case .digeut: return ["ㄷ", "ㅌ", "ㄸ"]
        case .bieup: return ["ㅂ", "ㅍ", "ㅃ"]
        case .siot: return ["ㅅ", "ㅎ", "ㅆ"]
        case .jieut: return ["ㅈ", "ㅊ", "ㅉ"]
        case .ieung: return ["ㅇ", "ㅁ"]
        }
    }

    /// Key cap label, e.g. "ㄱㅋ".
    public var label: String { String(letters.prefix(2)) }
}

public enum CheonjiinKey: Hashable {
    case stroke(CheonjiinStroke)
    case consonant(CheonjiinConsonantGroup)
}

/// 천지인 composition: vowels are built from ㅣ/ㆍ/ㅡ strokes (ㅏ=ㅣㆍ, ㅓ=ㆍㅣ, ㅗ=ㆍㅡ, ㅜ=ㅡㆍ, ㅑ=ㅣㆍㆍ, ...),
/// consonants cycle on repeated taps (ㄱ→ㅋ→ㄲ→ㄱ). Syllable assembly reuses the 두벌식 automaton.
///
/// An unresolved "ㆍ"/"‥" is shown after the syllable (e.g. 각 + ㆍ → "각ㆍ"; + ㅣ → "가거").
/// If a consonant follows an unresolved dot, the dot stays in the text as a literal character.
public struct CheonjiinComposer: KoreanComposer {
    static let dot: Character = "ㆍ" // U+318D (compatibility jamo: standalone grapheme)
    /// U+2025. Deliberately *not* U+11A2 (conjoining jungseong), which would merge into the preceding syllable's grapheme.
    static let doubleDot: Character = "‥"

    /// Stroke transitions: (current vowel or dot state) + stroke → result.
    static let transitions: [String: Character] = [
        "ㅣㆍ": "ㅏ", "ㅏㆍ": "ㅑ", "ㅏㅣ": "ㅐ", "ㅑㅣ": "ㅒ",
        "ㆍㆍ": "‥", "‥ㆍ": "ㆍ", "ㆍㅣ": "ㅓ", "ㆍㅡ": "ㅗ", "‥ㅣ": "ㅕ", "‥ㅡ": "ㅛ",
        "ㅓㅣ": "ㅔ", "ㅕㅣ": "ㅖ",
        "ㅡㆍ": "ㅜ", "ㅡㅣ": "ㅢ", "ㅜㆍ": "ㅠ", "ㅜㅣ": "ㅟ", "ㅠㅣ": "ㅝ", "ㅝㅣ": "ㅞ",
        "ㅗㅣ": "ㅚ", "ㅚㆍ": "ㅘ", "ㅘㅣ": "ㅙ",
    ]

    struct State: Equatable {
        var syllable = HangulSyllable()
        /// Unresolved "ㆍ" or "‥".
        var pending: Character?

        var text: String { syllable.text + (pending.map { String($0) } ?? "") }
    }

    private struct Cycle {
        let group: CheonjiinConsonantGroup
        var index: Int
        /// Composer history before the first tap of this cycle.
        let snapshot: [State]
        /// Text committed by the latest tap (on screen before the current composing text).
        var committed: String
        var time: TimeInterval?
    }

    private var history: [State] = []
    private var cycle: Cycle?

    /// Taps of the same consonant key further apart than this start a new consonant instead of cycling.
    /// `nil` = always cycle (callers without a clock).
    public var cycleTimeout: TimeInterval?

    public init(cycleTimeout: TimeInterval? = nil) {
        self.cycleTimeout = cycleTimeout
    }

    public var composingText: String { history.last?.text ?? "" }

    /// - Parameter time: monotonic-ish timestamp of the tap (used only for `cycleTimeout`).
    public mutating func input(_ key: CheonjiinKey, at time: TimeInterval? = nil) -> CompositionEdit {
        switch key {
        case .stroke(let stroke):
            cycle = nil
            let old = composingText
            let committed = applyStroke(stroke.rawValue)
            return .diff(from: old, to: committed + composingText)

        case .consonant(let group):
            if var active = cycle, active.group == group, !isExpired(active, now: time) {
                // Repeated tap: undo the previous letter and apply the next one from the same snapshot.
                let onScreen = active.committed + composingText
                history = active.snapshot
                active.index = (active.index + 1) % group.letters.count
                active.committed = applyConsonant(group.letters[active.index])
                active.time = time
                cycle = active
                return .diff(from: onScreen, to: active.committed + composingText)
            }
            let old = composingText
            let snapshot = history
            let committed = applyConsonant(group.letters[0])
            cycle = Cycle(group: group, index: 0, snapshot: snapshot, committed: committed, time: time)
            return .diff(from: old, to: committed + composingText)
        }
    }

    /// Stops consonant cycling so the next tap of the same key inserts a new consonant (e.g. 각 + ㄱ → "각ㄱ").
    public mutating func breakCycle() {
        cycle = nil
    }

    public mutating func backspace() -> CompositionEdit? {
        cycle = nil
        guard !history.isEmpty else { return nil }
        let old = composingText
        history.removeLast()
        return .diff(from: old, to: composingText)
    }

    public mutating func reset() {
        history = []
        cycle = nil
    }

    // MARK: - Private

    private func isExpired(_ cycle: Cycle, now: TimeInterval?) -> Bool {
        guard let timeout = cycleTimeout, let now, let last = cycle.time else { return false }
        return now - last > timeout
    }

    /// Returns text committed by this step.
    private mutating func applyStroke(_ stroke: Character) -> String {
        let current = history.last ?? State()
        if let pending = current.pending {
            let resolved = Self.transitions[String([pending, stroke])] ?? stroke
            if resolved == Self.dot || resolved == Self.doubleDot {
                history.append(State(syllable: current.syllable, pending: resolved))
                return ""
            }
            return feedVowel(resolved, into: current.syllable)
        }
        if let jung = current.syllable.jung, current.syllable.jong == nil,
           let combined = Self.transitions[String([jung, stroke])] {
            var syllable = current.syllable
            syllable.jung = combined
            history.append(State(syllable: syllable))
            return ""
        }
        if stroke == Self.dot {
            history.append(State(syllable: current.syllable, pending: Self.dot))
            return ""
        }
        return feedVowel(stroke, into: current.syllable)
    }

    /// Vowel compounding already happened via stroke transitions, so the automaton must not combine again.
    private mutating func feedVowel(_ vowel: Character, into syllable: HangulSyllable) -> String {
        switch SyllableAutomaton.addVowel(vowel, to: syllable, combine: { _, _ in nil }) {
        case .update(let next):
            history.append(State(syllable: next))
            return ""
        case .commit(let committed, let newHistory):
            history = newHistory.map { State(syllable: $0) }
            return committed
        }
    }

    private mutating func applyConsonant(_ consonant: Character) -> String {
        let current = history.last ?? State()
        if current.pending != nil {
            history = [State(syllable: HangulSyllable(cho: consonant))]
            return current.text
        }
        switch SyllableAutomaton.addConsonant(consonant, to: current.syllable) {
        case .update(let next):
            history.append(State(syllable: next))
            return ""
        case .commit(let committed, let newHistory):
            history = newHistory.map { State(syllable: $0) }
            return committed
        }
    }
}
