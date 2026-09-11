import Foundation

public struct ShortcutMatch: Equatable {
    public let trigger: String
    public let expansion: String

    public init(trigger: String, expansion: String) {
        self.trigger = trigger
        self.expansion = expansion
    }
}

/// Offers a phrase when the token right before the cursor equals a shortcut trigger (e.g. "ㅈㅅ" → "죄송합니다").
/// Triggers typed with either keyboard end up as compatibility jamo, which is what we store.
public struct ShortcutExpander {
    private let expansions: [String: String]

    public init(shortcuts: [Shortcut]) {
        var map: [String: String] = [:]
        for shortcut in shortcuts where !shortcut.trigger.isEmpty && map[shortcut.trigger] == nil {
            map[shortcut.trigger] = shortcut.expansion
        }
        expansions = map
    }

    public func match(contextBefore: String?) -> ShortcutMatch? {
        guard let context = contextBefore, let last = context.last, !last.isWhitespace else { return nil }
        let tokenStart = context.lastIndex(where: { $0.isWhitespace }).map { context.index(after: $0) } ?? context.startIndex
        let token = String(context[tokenStart...])
        guard let expansion = expansions[token] else { return nil }
        return ShortcutMatch(trigger: token, expansion: expansion)
    }
}
