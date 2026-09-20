import Foundation

// MARK: - 轻量 HTTP 栈（URLSession async/await，对齐 Android HttpStack 语义）

enum Http {
    /// 标准会话：连接/请求 8s（对齐 TIMEOUT_CONNECT_MS）
    static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 8
        config.timeoutIntervalForResource = 20
        config.httpAdditionalHeaders = [
            "User-Agent": "Mozilla/5.0 (Linux; Android 13; SM-S908B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        ]
        return URLSession(configuration: config)
    }()

    /// 快失败会话：3s，供 LX 代理 / GD 等兜底策略（对齐 TIMEOUT_FALLBACK_MS）
    static let fastSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 3
        config.timeoutIntervalForResource = 6
        config.httpAdditionalHeaders = session.configuration.httpAdditionalHeaders
        return URLSession(configuration: config)
    }()

    static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        return d
    }()

    enum HttpError: Error {
        case badURL
        case badStatus(Int)
        case decode(String)
    }

    /// 请求并解码 JSON（URL 查询参数已由调用方拼好）
    static func getJSON<T: Decodable>(
        _ type: T.Type,
        url: String,
        headers: [String: String] = [:],
        fast: Bool = false
    ) async throws -> T {
        let body = try await string(url: url, headers: headers, fast: fast)
        do {
            return try decoder.decode(T.self, from: Data(body.utf8))
        } catch {
            throw AppException(code: AppError.parse, detail: "decode failed: \(error)")
        }
    }

    /// POST 表单并解码 JSON
    static func postFormJSON<T: Decodable>(
        _ type: T.Type,
        url: String,
        form: [String: String],
        headers: [String: String] = [:],
        fast: Bool = false
    ) async throws -> T {
        let body = try await string(
            url: url,
            method: "POST",
            headers: headers,
            formBody: form,
            fast: fast
        )
        do {
            return try decoder.decode(T.self, from: Data(body.utf8))
        } catch {
            throw AppException(code: AppError.parse, detail: "decode failed: \(error)")
        }
    }

    /// 请求返回原始文本（body 供手工解析，如 backup_url / LX 代理多形态响应）
    static func string(
        url: String,
        method: String = "GET",
        headers: [String: String] = [:],
        formBody: [String: String]? = nil,
        fast: Bool = false
    ) async throws -> String {
        guard let u = URL(string: url) else {
            throw AppException(code: AppError.parse, detail: "bad url: \(url)")
        }
        var request = URLRequest(url: u)
        request.httpMethod = method
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        if let form = formBody {
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            let encoded = form
                .map { "\($0.key)=\(percentEncode($0.value))" }
                .joined(separator: "&")
            request.httpBody = Data(encoded.utf8)
        }
        let session = fast ? Http.fastSession : Http.session
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw AppException(code: AppError.network, detail: "no http response")
            }
            guard (200..<300).contains(http.statusCode) else {
                throw AppException(code: AppError.network, detail: "http \(http.statusCode) for \(url)")
            }
            return String(data: data, encoding: .utf8) ?? ""
        } catch let e as AppException {
            throw e
        } catch is URLError {
            throw AppException(code: AppError.timeout, detail: "request failed: \(url)")
        } catch {
            throw AppException(code: AppError.network, detail: "\(error) for \(url)")
        }
    }

    /// 表单值 percent-encode（保守：空格用 %20）
    static func percentEncode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }
}

// MARK: - 兼容多形态 JSON 的值类型（backup_url 可能是数组/对象/字符串）

enum JSONValue: Decodable {
    case string(String)
    case array([JSONValue])
    case other

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let s = try? c.decode(String.self) { self = .string(s); return }
        if let a = try? c.decode([JSONValue].self) { self = .array(a); return }
        self = .other
    }

    /// 数组里第一个非空字符串（对齐 firstBackupUrl）
    var firstString: String? {
        switch self {
        case .string(let s): return s.isEmpty ? nil : s
        case .array(let items):
            for item in items {
                if case .string(let s) = item, !s.isEmpty { return s }
            }
            return nil
        case .other: return nil
        }
    }
}
