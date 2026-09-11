import Foundation

/// Drops async results whose request snapshot no longer matches the document
/// ("응답 도착 시 텍스트가 이미 바뀌었으면 결과 폐기"), and results superseded by a newer request.
public struct StaleGuard {
    public struct Ticket: Equatable {
        public let generation: Int
        public let snapshot: String
    }

    public private(set) var generation = 0

    public init() {}

    /// Starts a request against `snapshot` (the context before the cursor at request time).
    public mutating func issue(snapshot: String?) -> Ticket {
        generation += 1
        return Ticket(generation: generation, snapshot: snapshot ?? "")
    }

    /// Invalidates every outstanding ticket (e.g. the host app changed the text or moved the cursor).
    public mutating func invalidate() {
        generation += 1
    }

    /// True if `ticket` is the latest request and the context is unchanged (UTF-16 exact).
    public func isCurrent(_ ticket: Ticket, currentSnapshot: String?) -> Bool {
        ticket.generation == generation && ticket.snapshot.utf16Equals(currentSnapshot ?? "")
    }

    /// True if `ticket` is the latest request, whether or not the text changed.
    public func isLatest(_ ticket: Ticket) -> Bool {
        ticket.generation == generation
    }
}
