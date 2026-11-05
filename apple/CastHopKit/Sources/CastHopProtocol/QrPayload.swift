import Foundation

public enum QrError: String, Error, Sendable {
    case badScheme = "BAD_SCHEME"
    case unsupportedVersion = "UNSUPPORTED_VERSION"
    case missingField = "MISSING_FIELD"
    case badField = "BAD_FIELD"
}

/// The receiver's QR content (PROTOCOL.md §8):
/// `yansit://c?v=1&h=<ip,ip>&p=<port>&t=<token>&f=<fingerprint>&n=<name>`
public struct QrPayload: Equatable, Sendable {
    public static let version = 1
    public static let maxHosts = 4
    public static let tokenSize = 16
    private static let prefix = "yansit://c?"

    public var hosts: [String]
    public var port: UInt16
    public var token: Data
    public var certFingerprint: Data
    public var name: String

    public init(hosts: [String], port: UInt16, token: Data, certFingerprint: Data, name: String) {
        precondition(!hosts.isEmpty && hosts.count <= Self.maxHosts && hosts.allSatisfy(Self.isIPv4))
        precondition(port > 0 && token.count == Self.tokenSize && certFingerprint.count == Auth.fingerprintSize)
        self.hosts = hosts
        self.port = port
        self.token = token
        self.certFingerprint = certFingerprint
        self.name = name
    }

    public func toUri() -> String {
        "\(Self.prefix)v=\(Self.version)&h=\(hosts.joined(separator: ","))&p=\(port)" +
            "&t=\(Base64Url.encode(token))&f=\(Base64Url.encode(certFingerprint))&n=\(Self.percentEncode(name))"
    }

    public static func parse(_ uri: String) throws -> QrPayload {
        guard uri.hasPrefix(prefix) else { throw QrError.badScheme }
        var params: [String: String] = [:]
        for part in uri.dropFirst(prefix.count).split(separator: "&", omittingEmptySubsequences: true) {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { throw QrError.badField }
            let key = String(part[..<eq])
            if params[key] == nil { params[key] = String(part[part.index(after: eq)...]) }
        }
        func need(_ key: String) throws -> String {
            guard let v = params[key] else { throw QrError.missingField }
            return v
        }
        guard try need("v") == String(version) else { throw QrError.unsupportedVersion }
        let hosts = try need("h").split(separator: ",", omittingEmptySubsequences: false).map(String.init)
        guard !hosts.isEmpty, hosts.count <= maxHosts, hosts.allSatisfy(isIPv4) else { throw QrError.badField }
        guard let port = UInt16(try need("p")), port > 0 else { throw QrError.badField }
        guard let token = Base64Url.decode(try need("t")), token.count == tokenSize else { throw QrError.badField }
        guard let fp = Base64Url.decode(try need("f")), fp.count == Auth.fingerprintSize else {
            throw QrError.badField
        }
        let name = try params["n"].map(percentDecode) ?? ""
        return QrPayload(hosts: hosts, port: port, token: token, certFingerprint: fp, name: name)
    }

    public static func isIPv4(_ s: String) -> Bool {
        let parts = s.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        return parts.allSatisfy { p in
            guard !p.isEmpty, p.utf8.count <= 3, p.utf8.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return false }
            guard p.utf8.count == 1 || p.utf8.first != 0x30 else { return false }
            return (Int(p) ?? 256) <= 255
        }
    }

    static func percentEncode(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            let unreserved = (b >= 0x41 && b <= 0x5A) || (b >= 0x61 && b <= 0x7A) || (b >= 0x30 && b <= 0x39) ||
                b == 0x2D || b == 0x5F || b == 0x2E || b == 0x7E
            if unreserved {
                out.unicodeScalars.append(UnicodeScalar(b))
            } else {
                out += String(format: "%%%02X", b)
            }
        }
        return out
    }

    static func percentDecode(_ s: String) throws -> String {
        var out: [UInt8] = []
        let bytes = Array(s.utf8)
        var i = 0
        while i < bytes.count {
            if bytes[i] == 0x25 {
                guard i + 2 < bytes.count, let hi = hexValue(bytes[i + 1]), let lo = hexValue(bytes[i + 2]) else {
                    throw QrError.badField
                }
                out.append(hi << 4 | lo)
                i += 3
            } else {
                out.append(bytes[i])
                i += 1
            }
        }
        guard isValidUtf8(out) else { throw QrError.badField }
        return String(decoding: out, as: UTF8.self)
    }

    private static func hexValue(_ c: UInt8) -> UInt8? {
        switch c {
        case 0x30...0x39: return c - 0x30
        case 0x41...0x46: return c - 0x41 + 10
        case 0x61...0x66: return c - 0x61 + 10
        default: return nil
        }
    }
}

public enum Base64Url {
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Only the URL alphabet without padding is accepted.
    public static func decode(_ s: String) -> Data? {
        let valid = s.utf8.allSatisfy {
            ($0 >= 0x41 && $0 <= 0x5A) || ($0 >= 0x61 && $0 <= 0x7A) || ($0 >= 0x30 && $0 <= 0x39) ||
                $0 == 0x2D || $0 == 0x5F
        }
        guard valid, s.utf8.count % 4 != 1 else { return nil }
        var b64 = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        b64 += String(repeating: "=", count: (4 - b64.utf8.count % 4) % 4)
        return Data(base64Encoded: b64)
    }
}

/// `_yansit._tcp` TXT record (PROTOCOL.md §8): `v=1`, `name=<name>`, `fp=<first 16 fingerprint bytes>`.
public struct DiscoveryTxt: Equatable, Sendable {
    public static let serviceType = "_yansit._tcp"
    public static let prefixSize = 16

    public var name: String?
    public var fingerprintPrefix: Data

    public init(name: String?, fingerprintPrefix: Data) {
        self.name = name
        self.fingerprintPrefix = fingerprintPrefix
    }

    /// Nil for another version or a bad fingerprint prefix.
    public static func parse(_ attributes: [String: String]) -> DiscoveryTxt? {
        guard attributes["v"] == "1", let fp = attributes["fp"], let prefix = Base64Url.decode(fp),
              prefix.count == prefixSize else { return nil }
        let name = attributes["name"].flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        return DiscoveryTxt(name: name, fingerprintPrefix: prefix)
    }

    public static func record(name: String, fingerprint: Data) -> [String: String] {
        ["v": "1", "name": name, "fp": Base64Url.encode(fingerprint.prefix(prefixSize))]
    }

    /// Cuts a service name to 63 bytes without splitting a character; an empty name becomes `CastHop`.
    public static func instanceName(_ name: String) -> String {
        if name.isEmpty { return "CastHop" }
        let bytes = Array(name.utf8)
        guard bytes.count > 63 else { return name }
        var cut = 63
        while cut > 0 && bytes[cut] & 0xC0 == 0x80 { cut -= 1 }
        return String(decoding: bytes[..<cut], as: UTF8.self)
    }
}
