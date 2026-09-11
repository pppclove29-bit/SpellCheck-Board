import Foundation

public struct AuthConfiguration: Equatable {
    public static let supabaseURLInfoKey = "TYPERIGHT_SUPABASE_URL"
    public static let anonKeyInfoKey = "TYPERIGHT_SUPABASE_ANON_KEY"
    public static let keychainAccessGroupInfoKey = "TYPERIGHT_KEYCHAIN_ACCESS_GROUP"

    public var supabaseURL: URL?
    public var anonKey: String?

    public init(supabaseURL: URL?, anonKey: String?) {
        self.supabaseURL = supabaseURL
        self.anonKey = anonKey
    }

    /// Dev mode (empty Supabase settings): the backend runs with ALLOW_INSECURE_DEV_AUTH and trusts `X-Dev-User-Id`.
    public var isDevMode: Bool { supabaseURL == nil || anonKey == nil }

    public static func fromInfoDictionary(_ info: [String: Any]?) -> AuthConfiguration {
        AuthConfiguration(supabaseURL: InfoPlist.url(info, supabaseURLInfoKey),
                          anonKey: InfoPlist.string(info, anonKeyInfoKey))
    }

    /// Keychain access group from Info.plist (`$(AppIdentifierPrefix)com.typeright.shared`), if configured.
    public static func keychainAccessGroup(_ info: [String: Any]?) -> String? {
        InfoPlist.string(info, keychainAccessGroupInfoKey)
    }
}

public enum AuthError: Error, Equatable {
    /// GoTrue refused the request (4xx), e.g. an invalid/used refresh token.
    case rejected(status: Int, message: String?)
    case invalidResponse
    case transport(String)
}

/// Minimal Supabase GoTrue REST client (no SDK):
/// - anonymous sign-in: `POST {SUPABASE_URL}/auth/v1/signup` with `{}` and header `apikey: <anon key>`
/// - refresh: `POST {SUPABASE_URL}/auth/v1/token?grant_type=refresh_token` with `{"refresh_token": …}`
///
/// Concurrent callers share one in-flight sign-in/refresh (actor + stored task).
public actor AuthSessionManager: AuthHeaderProviding {
    public static let devUserHeader = "X-Dev-User-Id"

    public nonisolated let configuration: AuthConfiguration
    private let store: SessionStore
    private let transport: HTTPTransport
    private let devUserID: String
    private let now: () -> Date
    private var inFlight: Task<AuthSession, Error>?

    public init(configuration: AuthConfiguration, store: SessionStore, devUserID: String,
                transport: HTTPTransport = URLSessionTransport(), now: @escaping () -> Date = Date.init) {
        self.configuration = configuration
        self.store = store
        self.devUserID = devUserID
        self.transport = transport
        self.now = now
    }

    public func authorizationHeaders() async throws -> [String: String] {
        if configuration.isDevMode { return [Self.devUserHeader: devUserID] }
        let session = try await validSession()
        return ["Authorization": "Bearer \(session.accessToken)"]
    }

    public func invalidateAccessToken() async {
        guard var session = store.load() else { return }
        session.expiresAt = .distantPast
        store.save(session)
    }

    /// Supabase user id (AdMob SSV `userId`), or the dev id in dev mode.
    public func userID() async throws -> String {
        if configuration.isDevMode { return devUserID }
        return try await validSession().userID
    }

    public func validSession() async throws -> AuthSession {
        if let inFlight { return try await inFlight.value }
        let existing = store.load()
        if let existing, !existing.isExpired(at: now()) { return existing }
        let task = Task { try await self.obtainSession(replacing: existing) }
        inFlight = task
        defer { inFlight = nil }
        return try await task.value
    }

    private func obtainSession(replacing existing: AuthSession?) async throws -> AuthSession {
        if let existing {
            do {
                let refreshed = try await refresh(refreshToken: existing.refreshToken)
                store.save(refreshed)
                return refreshed
            } catch AuthError.rejected {
                // Refresh token invalid/revoked: fall back to a fresh anonymous user.
                // (Anonymous identities cannot be recovered; PRO is re-linked via StoreKit restore later.)
            }
        }
        let session = try await signInAnonymously()
        store.save(session)
        return session
    }

    func signInAnonymously() async throws -> AuthSession {
        try await post(path: "auth/v1/signup", query: nil, body: Data("{}".utf8))
    }

    func refresh(refreshToken: String) async throws -> AuthSession {
        let body = try JSONEncoder().encode(["refresh_token": refreshToken])
        return try await post(path: "auth/v1/token", query: [URLQueryItem(name: "grant_type", value: "refresh_token")], body: body)
    }

    private func post(path: String, query: [URLQueryItem]?, body: Data) async throws -> AuthSession {
        guard let base = configuration.supabaseURL, let anonKey = configuration.anonKey else {
            throw AuthError.invalidResponse
        }
        var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false)
        components?.queryItems = query
        guard let url = components?.url else { throw AuthError.invalidResponse }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(anonKey, forHTTPHeaderField: "apikey")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await transport.send(request)
        } catch {
            throw AuthError.transport(String(describing: error))
        }
        guard let http = response as? HTTPURLResponse else { throw AuthError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let message = String(data: data, encoding: .utf8)
            if (400..<500).contains(http.statusCode), http.statusCode != 429 {
                throw AuthError.rejected(status: http.statusCode, message: message)
            }
            throw AuthError.transport("HTTP \(http.statusCode)")
        }
        guard let payload = try? JSONDecoder().decode(GoTrueSessionPayload.self, from: data),
              let userID = payload.user?.id else { throw AuthError.invalidResponse }
        let expiresAt: Date
        if let absolute = payload.expiresAt {
            expiresAt = Date(timeIntervalSince1970: absolute)
        } else {
            expiresAt = now().addingTimeInterval(payload.expiresIn ?? 3600)
        }
        return AuthSession(accessToken: payload.accessToken, refreshToken: payload.refreshToken,
                           expiresAt: expiresAt, userID: userID)
    }
}

/// GoTrue session JSON (`/signup` for anonymous users and `/token?grant_type=refresh_token`).
struct GoTrueSessionPayload: Decodable {
    struct User: Decodable { let id: String }

    let accessToken: String
    let refreshToken: String
    let expiresIn: Double?
    let expiresAt: Double?
    let user: User?

    enum CodingKeys: String, CodingKey {
        case user
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case expiresIn = "expires_in"
        case expiresAt = "expires_at"
    }
}
