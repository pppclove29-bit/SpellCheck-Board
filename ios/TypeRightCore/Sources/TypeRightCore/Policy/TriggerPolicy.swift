import Foundation

/// Client trigger policy (docs/api-contract.md "클라이언트 트리거 정책").
public enum TriggerAction: Equatable {
    /// Any other key: on-device check after 300 ms without input.
    case debounce
    /// Space: flush the debounce → on-device check now.
    case flush
    /// `.` `!` `?` newline: on-device check now + `/v1/grammar-check` on the current sentence (if network allowed).
    case flushAndRemote
}

public enum TriggerPolicy {
    public static let remoteTriggers: Set<Character> = [".", "!", "?", "\n", "\r", "\r\n"]

    public static func action(forInserted text: String) -> TriggerAction {
        guard let last = text.last else { return .debounce }
        if remoteTriggers.contains(last) { return .flushAndRemote }
        if last == " " { return .flush }
        return .debounce
    }
}
