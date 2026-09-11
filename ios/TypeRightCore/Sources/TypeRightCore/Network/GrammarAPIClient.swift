import Foundation

/// Injectable HTTP layer (tests stub this; production wraps URLSession).
public protocol HTTPTransport {
    func send(_ request: URLRequest) async throws -> (Data, URLResponse)
}

public struct URLSessionTransport: HTTPTransport {
    public let session: URLSession

    /// Ephemeral by default: user text must never land in an on-disk URL cache.
    public init(session: URLSession = URLSession(configuration: .ephemeral)) {
        self.session = session
    }

    public func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        try await session.data(for: request)
    }
}

/// Supplies per-request auth headers (`Authorization: Bearer …`, or `X-Dev-User-Id` in dev mode).
public protocol AuthHeaderProviding: AnyObject {
    func authorizationHeaders() async throws -> [String: String]
    /// Called after a 401 so the next `authorizationHeaders()` refreshes the token.
    func invalidateAccessToken() async
}

public struct APIConfiguration: Equatable {
    public static let baseURLInfoKey = "TYPERIGHT_API_BASE_URL"

    public var baseURL: URL
    public var platform: String
    /// The server's AI call itself can take several seconds; leave headroom for the network.
    public var grammarTimeout: TimeInterval

    public init(baseURL: URL, platform: String = "ios", grammarTimeout: TimeInterval = 10) {
        self.baseURL = baseURL
        self.platform = platform
        self.grammarTimeout = grammarTimeout
    }

    /// Reads `TYPERIGHT_API_BASE_URL` from an Info.plist dictionary. Empty / unexpanded "$(...)" → nil.
    public static func fromInfoDictionary(_ info: [String: Any]?) -> APIConfiguration? {
        guard let url = InfoPlist.url(info, baseURLInfoKey) else { return nil }
        return APIConfiguration(baseURL: url)
    }
}

enum InfoPlist {
    static func string(_ info: [String: Any]?, _ key: String) -> String? {
        guard let raw = (info?[key] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty, !raw.hasPrefix("$(") else { return nil }
        return raw
    }

    static func url(_ info: [String: Any]?, _ key: String) -> URL? {
        guard let raw = string(info, key), let url = URL(string: raw),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
              url.host != nil else { return nil }
        return url
    }
}

public enum APIClientError: Error, Equatable {
    case emptyText
    /// Over the contract's 1,000 UTF-16 unit limit (the server would answer 400).
    case textTooLong
    case invalidResponse
    case unauthorized
    case http(status: Int, code: String?, message: String?)
    case decoding(String)
    case transport(String)
    case auth(String)
}

/// Client for `POST /v1/grammar-check`, `GET /v1/me`, `GET /health`.
public final class GrammarAPIClient {
    public static let maxTextLength = 1000

    public let configuration: APIConfiguration
    private let auth: AuthHeaderProviding?
    private let transport: HTTPTransport
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(configuration: APIConfiguration, auth: AuthHeaderProviding?, transport: HTTPTransport = URLSessionTransport()) {
        self.configuration = configuration
        self.auth = auth
        self.transport = transport
    }

    public func grammarCheck(text: String, mode: FeedbackMode) async throws -> GrammarCheckResponse {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { throw APIClientError.emptyText }
        if text.utf16.count > Self.maxTextLength { throw APIClientError.textTooLong }
        let body = try encoder.encode(GrammarCheckRequest(text: text, mode: mode))
        return try await send(path: "v1/grammar-check", method: "POST", body: body,
                              timeout: configuration.grammarTimeout, authenticated: true)
    }

    public func me() async throws -> MeResponse {
        try await send(path: "v1/me", method: "GET", body: nil, timeout: 8, authenticated: true)
    }

    public func health() async throws -> HealthResponse {
        try await send(path: "health", method: "GET", body: nil, timeout: 5, authenticated: false)
    }

    func makeRequest(path: String, method: String, body: Data?, timeout: TimeInterval, headers: [String: String]) -> URLRequest {
        var request = URLRequest(url: configuration.baseURL.appendingPathComponent(path),
                                 cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpMethod = method
        request.httpBody = body
        if body != nil {
            request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        }
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(configuration.platform, forHTTPHeaderField: "X-Client-Platform")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        return request
    }

    private func send<T: Decodable>(path: String, method: String, body: Data?, timeout: TimeInterval,
                                    authenticated: Bool) async throws -> T {
        var (data, http) = try await attempt(path: path, method: method, body: body, timeout: timeout, authenticated: authenticated)
        if http.statusCode == 401, authenticated, let auth {
            // Access token expired or revoked: refresh once and retry.
            await auth.invalidateAccessToken()
            (data, http) = try await attempt(path: path, method: method, body: body, timeout: timeout, authenticated: authenticated)
        }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 { throw APIClientError.unauthorized }
            let envelope = try? decoder.decode(APIErrorEnvelope.self, from: data)
            throw APIClientError.http(status: http.statusCode, code: envelope?.error.code, message: envelope?.error.message)
        }
        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw APIClientError.decoding(String(describing: error))
        }
    }

    private func attempt(path: String, method: String, body: Data?, timeout: TimeInterval,
                         authenticated: Bool) async throws -> (Data, HTTPURLResponse) {
        var headers: [String: String] = [:]
        if authenticated, let auth {
            do {
                headers = try await auth.authorizationHeaders()
            } catch let error as CancellationError {
                throw error
            } catch {
                throw APIClientError.auth(String(describing: error))
            }
        }
        let request = makeRequest(path: path, method: method, body: body, timeout: timeout, headers: headers)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await transport.send(request)
        } catch let error as CancellationError {
            throw error
        } catch {
            throw APIClientError.transport(String(describing: error))
        }
        guard let http = response as? HTTPURLResponse else { throw APIClientError.invalidResponse }
        return (data, http)
    }
}

/// Callback-style façade used by the keyboard (completions always delivered on the main queue).
public protocol GrammarChecking: AnyObject {
    func checkGrammar(_ text: String, mode: FeedbackMode, completion: @escaping (Result<GrammarCheckResponse, Error>) -> Void)
    func fetchAccount(completion: @escaping (Result<MeResponse, Error>) -> Void)
}

/// Bridges `GrammarAPIClient` (async/await) to `GrammarChecking`. A new check cancels the previous one.
public final class RemoteGrammarService: GrammarChecking {
    private let client: GrammarAPIClient
    private var grammarTask: Task<Void, Never>?
    private var accountTask: Task<Void, Never>?

    public init(client: GrammarAPIClient) {
        self.client = client
    }

    deinit {
        grammarTask?.cancel()
        accountTask?.cancel()
    }

    public func checkGrammar(_ text: String, mode: FeedbackMode,
                             completion: @escaping (Result<GrammarCheckResponse, Error>) -> Void) {
        grammarTask?.cancel()
        let client = self.client
        grammarTask = Task {
            let result: Result<GrammarCheckResponse, Error>
            do {
                result = .success(try await client.grammarCheck(text: text, mode: mode))
            } catch {
                result = .failure(error)
            }
            if Task.isCancelled { return }
            DispatchQueue.main.async { completion(result) }
        }
    }

    public func fetchAccount(completion: @escaping (Result<MeResponse, Error>) -> Void) {
        accountTask?.cancel()
        let client = self.client
        accountTask = Task {
            let result: Result<MeResponse, Error>
            do {
                result = .success(try await client.me())
            } catch {
                result = .failure(error)
            }
            if Task.isCancelled { return }
            DispatchQueue.main.async { completion(result) }
        }
    }
}
