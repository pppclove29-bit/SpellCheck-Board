import Foundation

/// 두벌식 (standard Korean QWERTY) composition automaton.
///
/// Feed compatibility jamo (ㄱ, ㅏ, ㅃ, ...) one key at a time; apply the returned `CompositionEdit`
/// to the document. Handles compound vowels (ㅘㅙㅚㅝㅞㅟㅢ), compound finals (ㄳㄵㄶㄺㄻㄼㄽㄾㄿㅀㅄ),
/// the final consonant moving to the next syllable (갑+ㅏ → 가바, 값+ㅏ → 갑사), and jamo-level backspace.
public struct HangulComposer: KoreanComposer {
    /// Backspace chain of the current syllable; `last` is what is on screen.
    private var history: [HangulSyllable] = []

    public init() {}

    public var composingText: String { history.last?.text ?? "" }

    /// Feeds one key. Non-jamo characters end the composition and are inserted as-is.
    public mutating func input(_ key: Character) -> CompositionEdit {
        let old = composingText
        let current = history.last ?? HangulSyllable()
        let step: SyllableStep
        if HangulJamo.isVowel(key) {
            step = SyllableAutomaton.addVowel(key, to: current, combine: HangulJamo.combineVowels)
        } else if HangulJamo.canBeInitial(key) {
            step = SyllableAutomaton.addConsonant(key, to: current)
        } else {
            history = []
            return .diff(from: old, to: old + String(key))
        }
        switch step {
        case .update(let syllable):
            history.append(syllable)
            return .diff(from: old, to: syllable.text)
        case .commit(let committed, let newHistory):
            history = newHistory
            return .diff(from: old, to: committed + composingText)
        }
    }

    public mutating func backspace() -> CompositionEdit? {
        guard !history.isEmpty else { return nil }
        let old = composingText
        history.removeLast()
        return .diff(from: old, to: composingText)
    }

    public mutating func reset() {
        history = []
    }
}
