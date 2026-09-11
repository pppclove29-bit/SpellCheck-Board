import Foundation

/// `shared/korean-rules.json`.
public struct RulesFile: Decodable, Equatable {
    public struct DictionaryRule: Decodable, Equatable {
        public let from: String
        public let to: String
        public let reason: String
    }

    public struct PatternRule: Decodable, Equatable {
        public let id: String
        public let pattern: String
        public let replacement: String
        public let reason: String
    }

    public let version: Int
    public let dictionary: [DictionaryRule]
    public let patterns: [PatternRule]
}

public enum RuleEngineError: Error, Equatable {
    case invalidPattern(id: String, pattern: String)
}

/// On-device rule checker. Implements the shared algorithm in docs/api-contract.md exactly
/// (mirrors backend/src/utils/ruleEngine.ts so golden cases match on every platform):
///
/// 1. every literal occurrence of each dictionary `from` (search resumes at index + 1);
/// 2. every non-empty regex match on the full text, `$0`...`$9` expanded manually from capture groups
///    (the regex is never re-run on the isolated match: lookarounds need the surrounding text);
/// 3. candidates whose suggestion equals the original are dropped (before selection, as the backend does);
/// 4. stable sort by (offset asc, length desc), ties in generation order, then greedy non-overlapping pick.
///
/// Offsets/lengths are UTF-16 code units (NSString / NSRange semantics).
public final class RuleEngine {
    private struct CompiledPattern {
        let rule: RulesFile.PatternRule
        let regex: NSRegularExpression
    }

    private let dictionary: [RulesFile.DictionaryRule]
    private let patterns: [CompiledPattern]
    public let version: Int

    public init(rules: RulesFile) throws {
        dictionary = rules.dictionary.filter { !$0.from.isEmpty }
        patterns = try rules.patterns.map { rule in
            do {
                return CompiledPattern(rule: rule, regex: try NSRegularExpression(pattern: rule.pattern, options: []))
            } catch {
                throw RuleEngineError.invalidPattern(id: rule.id, pattern: rule.pattern)
            }
        }
        version = rules.version
    }

    public convenience init(rulesData: Data) throws {
        try self.init(rules: JSONDecoder().decode(RulesFile.self, from: rulesData))
    }

    /// Loads `korean-rules.json` from a bundle (the app/extension bundles it from ../shared via project.yml).
    public convenience init(bundle: Bundle, resource: String = "korean-rules") throws {
        guard let url = bundle.url(forResource: resource, withExtension: "json") else {
            throw CocoaError(.fileNoSuchFile)
        }
        try self.init(rulesData: Data(contentsOf: url))
    }

    public func check(_ text: String) -> [Correction] {
        let ns = text as NSString
        let fullRange = NSRange(location: 0, length: ns.length)
        var candidates: [Correction] = []

        for rule in dictionary {
            let needleLength = (rule.from as NSString).length
            var searchStart = 0
            while searchStart + needleLength <= ns.length {
                let found = ns.range(of: rule.from, options: .literal,
                                     range: NSRange(location: searchStart, length: ns.length - searchStart))
                if found.location == NSNotFound { break }
                candidates.append(Correction(offset: found.location, length: found.length,
                                             originalWord: rule.from, suggestedWord: rule.to,
                                             reason: rule.reason, source: .rule))
                searchStart = found.location + 1
            }
        }

        for pattern in patterns {
            for match in pattern.regex.matches(in: text, options: [], range: fullRange) where match.range.length > 0 {
                candidates.append(Correction(offset: match.range.location, length: match.range.length,
                                             originalWord: ns.substring(with: match.range),
                                             suggestedWord: Self.expand(pattern.rule.replacement, match: match, in: ns),
                                             reason: pattern.rule.reason, source: .rule))
            }
        }

        return Self.selectNonOverlapping(candidates.filter { !$0.suggestedWord.utf16Equals($0.originalWord) })
    }

    /// Same as JS `replacement.replace(/\$(\d)/g, (_, n) => match[n] ?? "")`.
    static func expand(_ replacement: String, match: NSTextCheckingResult, in text: NSString) -> String {
        var output = ""
        var scalars = replacement.unicodeScalars.makeIterator()
        var pendingDollar = false
        while let scalar = scalars.next() {
            if pendingDollar {
                pendingDollar = false
                if let digit = Int(String(scalar)), ("0"..."9").contains(scalar) {
                    if digit < match.numberOfRanges {
                        let range = match.range(at: digit)
                        if range.location != NSNotFound { output += text.substring(with: range) }
                    }
                    continue
                }
                output += "$"
            }
            if scalar == "$" {
                pendingDollar = true
            } else {
                output.unicodeScalars.append(scalar)
            }
        }
        if pendingDollar { output += "$" }
        return output
    }

    /// Stable sort (offset asc, length desc; ties keep input order), then greedy non-overlapping selection.
    public static func selectNonOverlapping(_ candidates: [Correction]) -> [Correction] {
        let sorted = candidates.enumerated().sorted { a, b in
            if a.element.offset != b.element.offset { return a.element.offset < b.element.offset }
            if a.element.length != b.element.length { return a.element.length > b.element.length }
            return a.offset < b.offset
        }
        var selected: [Correction] = []
        var end = 0
        for (_, candidate) in sorted where candidate.offset >= end {
            selected.append(candidate)
            end = candidate.offset + candidate.length
        }
        return selected
    }
}

extension String {
    /// Code-unit equality. Swift `==` uses canonical equivalence (e.g. "가" == "가" in conjoining jamo),
    /// which would silently break UTF-16 offset arithmetic.
    func utf16Equals(_ other: String) -> Bool { utf16.elementsEqual(other.utf16) }

    func utf16HasPrefix(_ prefix: String) -> Bool { utf16.starts(with: prefix.utf16) }
}
