import Foundation

/// Plain-value snapshot of the host field's `UITextInputTraits` (filled in by the keyboard extension).
public struct InputTraitsSnapshot: Equatable {
    public var isSecureTextEntry: Bool
    /// `keyboardType` is `.phonePad` or `.namePhonePad`.
    public var isPhonePad: Bool
    /// `textContentType` is `.password`, `.newPassword` or `.oneTimeCode`.
    public var isCredentialContentType: Bool

    public init(isSecureTextEntry: Bool = false, isPhonePad: Bool = false, isCredentialContentType: Bool = false) {
        self.isSecureTextEntry = isSecureTextEntry
        self.isPhonePad = isPhonePad
        self.isCredentialContentType = isCredentialContentType
    }
}

/// Small hint rendered in the suggestion bar.
public enum StatusHint: Equatable {
    case secureField
    case noFullAccess
    case aiDisabled

    public var message: String {
        switch self {
        case .secureField: return "보안 입력란 · 검사 꺼짐"
        case .noFullAccess: return "전체 접근 꺼짐 · 기기 내 검사만"
        case .aiDisabled: return "AI 꺼짐 · 기기 내 검사만"
        }
    }
}

public struct KeyboardCapabilities: Equatable {
    /// Reading the document context for checks/suggestions.
    public var captureEnabled: Bool
    public var onDeviceChecks: Bool
    /// `/v1/grammar-check` and `/v1/me`.
    public var network: Bool
    /// `UINotificationFeedbackGenerator` in a keyboard extension needs Full Access.
    public var haptics: Bool
    public var status: StatusHint?

    public init(captureEnabled: Bool, onDeviceChecks: Bool, network: Bool, haptics: Bool, status: StatusHint?) {
        self.captureEnabled = captureEnabled
        self.onDeviceChecks = onDeviceChecks
        self.network = network
        self.haptics = haptics
        self.status = status
    }

    /// Nothing enabled (used before the first evaluation, and for secure fields).
    public static let disabled = KeyboardCapabilities(captureEnabled: false, onDeviceChecks: false,
                                                      network: false, haptics: false, status: nil)
}

/// iOS already swaps in the system keyboard for secure text entry and phone pads; this is the
/// defensive second line: sensitive fields get no capture, no checks, no network, no police blocking.
public enum SecureFieldPolicy {
    public static func isSensitive(_ traits: InputTraitsSnapshot) -> Bool {
        traits.isSecureTextEntry || traits.isPhonePad || traits.isCredentialContentType
    }

    /// Network requires Full Access *and* the AI toggle; otherwise on-device rule checks only.
    public static func capabilities(traits: InputTraitsSnapshot, hasFullAccess: Bool, aiEnabled: Bool) -> KeyboardCapabilities {
        if isSensitive(traits) {
            return KeyboardCapabilities(captureEnabled: false, onDeviceChecks: false, network: false,
                                        haptics: false, status: .secureField)
        }
        if !hasFullAccess {
            return KeyboardCapabilities(captureEnabled: true, onDeviceChecks: true, network: false,
                                        haptics: false, status: .noFullAccess)
        }
        if !aiEnabled {
            return KeyboardCapabilities(captureEnabled: true, onDeviceChecks: true, network: false,
                                        haptics: true, status: .aiDisabled)
        }
        return KeyboardCapabilities(captureEnabled: true, onDeviceChecks: true, network: true,
                                    haptics: true, status: nil)
    }
}
