import Foundation

/// Handle to scheduled work.
public protocol CancellableWork: AnyObject {
    func cancel()
}

/// Abstract timer so the debouncer can be driven by a manual clock in tests.
public protocol WorkScheduler: AnyObject {
    func schedule(after delay: TimeInterval, _ work: @escaping () -> Void) -> CancellableWork
}

/// Production scheduler: main queue (keyboard UI and textDocumentProxy must be touched on main).
public final class MainQueueScheduler: WorkScheduler {
    private final class Item: CancellableWork {
        let workItem: DispatchWorkItem
        init(_ workItem: DispatchWorkItem) { self.workItem = workItem }
        func cancel() { workItem.cancel() }
    }

    public init() {}

    public func schedule(after delay: TimeInterval, _ work: @escaping () -> Void) -> CancellableWork {
        let item = DispatchWorkItem(block: work)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
        return Item(item)
    }
}

/// Trailing-edge debouncer (default 300 ms, per the client trigger policy).
/// `call` replaces any pending action; `flush` runs the pending action immediately (space bar).
public final class Debouncer {
    private final class Pending {
        let action: () -> Void
        var work: CancellableWork?
        init(action: @escaping () -> Void) { self.action = action }
    }

    public static let defaultDelay: TimeInterval = 0.3

    public let delay: TimeInterval
    private let scheduler: WorkScheduler
    private var pending: Pending?

    public init(delay: TimeInterval = Debouncer.defaultDelay, scheduler: WorkScheduler = MainQueueScheduler()) {
        self.delay = delay
        self.scheduler = scheduler
    }

    public var hasPending: Bool { pending != nil }

    public func call(_ action: @escaping () -> Void) {
        pending?.work?.cancel()
        let entry = Pending(action: action)
        pending = entry
        entry.work = scheduler.schedule(after: delay) { [weak self, weak entry] in
            guard let self, let entry, self.pending === entry else { return }
            self.pending = nil
            entry.action()
        }
    }

    /// Runs the pending action now. Returns false when nothing was pending.
    @discardableResult
    public func flush() -> Bool {
        guard let entry = pending else { return false }
        entry.work?.cancel()
        pending = nil
        entry.action()
        return true
    }

    public func cancel() {
        pending?.work?.cancel()
        pending = nil
    }
}
