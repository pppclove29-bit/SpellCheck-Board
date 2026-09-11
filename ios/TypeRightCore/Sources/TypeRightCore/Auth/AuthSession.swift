import Foundation
#if canImport(Security)
import Security
#endif

/// A Supabase (GoTrue) session. Shared between the host app and the keyboard extension.
public struct AuthSession: Codable, Equatable {
    public var accessToken: String
    public var refreshToken: String
    public var expiresAt: Date
    public var userID: String

    public init(accessToken: String, refreshToken: String, expiresAt: Date, userID: String) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.expiresAt = expiresAt
        self.userID = userID
    }

    /// Treat the token as expired slightly early so it never expires in flight.
    public func isExpired(at now: Date, leeway: TimeInterval = 60) -> Bool {
        expiresAt.timeIntervalSince(now) <= leeway
    }
}

/// Persistence for the shared session.
public protocol SessionStore: AnyObject {
    func load() -> AuthSession?
    /// `nil` deletes the stored session.
    func save(_ session: AuthSession?)
}

public final class InMemorySessionStore: SessionStore {
    private var session: AuthSession?

    public init(session: AuthSession? = nil) {
        self.session = session
    }

    public func load() -> AuthSession? { session }
    public func save(_ session: AuthSession?) { self.session = session }
}

/// App Group `UserDefaults` fallback (used when no keychain access group is configured).
public final class UserDefaultsSessionStore: SessionStore {
    private let defaults: UserDefaults
    private let key: String

    public init(defaults: UserDefaults, key: String = "auth.session.v1") {
        self.defaults = defaults
        self.key = key
    }

    public func load() -> AuthSession? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(AuthSession.self, from: data)
    }

    public func save(_ session: AuthSession?) {
        guard let session, let data = try? JSONEncoder().encode(session) else {
            defaults.removeObject(forKey: key)
            return
        }
        defaults.set(data, forKey: key)
    }
}

#if canImport(Security)
/// Keychain store in a shared access group (preferred): `keychain-access-groups` entitlement
/// `$(AppIdentifierPrefix)com.typeright.shared` on both targets (see ios/project.yml).
/// The extension can only reach the shared keychain with Full Access, which is also when it needs a token.
public final class KeychainSessionStore: SessionStore {
    private let service: String
    private let account: String
    private let accessGroup: String?

    public init(service: String = "com.typeright.auth", account: String = "supabase-session", accessGroup: String?) {
        self.service = service
        self.account = account
        self.accessGroup = accessGroup
    }

    private var baseQuery: [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        return query
    }

    public func load() -> AuthSession? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return try? JSONDecoder().decode(AuthSession.self, from: data)
    }

    public func save(_ session: AuthSession?) {
        SecItemDelete(baseQuery as CFDictionary)
        guard let session, let data = try? JSONEncoder().encode(session) else { return }
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        // The keyboard may run before the user unlocks again after a reboot? No: after first unlock is enough.
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(attributes as CFDictionary, nil)
    }
}
#endif
