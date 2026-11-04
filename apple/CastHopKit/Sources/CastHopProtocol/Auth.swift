import CryptoKit
import Foundation
import Security

/// Identity proofs (PROTOCOL.md §7.1.1). Comparisons run in constant time.
public enum Auth {
    public static let pinLength = 6
    public static let fingerprintSize = 32

    public static func authProof(secret: Data, authMethod: UInt8, serverNonce: Data, clientNonce: Data,
                                 receiverCertFp: Data, deviceId: Data) -> Data {
        hmac(key: secret, Data("yansit-auth-v1".utf8), Data([authMethod]), serverNonce, clientNonce, receiverCertFp,
             deviceId)
    }

    public static func attachProof(attachKey: Data, sessionId: Data, streamKind: UInt8, serverNonce: Data) -> Data {
        hmac(key: attachKey, Data("yansit-attach-v1".utf8), sessionId, Data([streamKind]), serverNonce)
    }

    public static func resumeProof(resumeToken: Data, sessionId: Data, serverNonce: Data) -> Data {
        hmac(key: resumeToken, Data("yansit-resume-v1".utf8), sessionId, serverNonce)
    }

    /// The HMAC key of a PIN: six ASCII digits. Nil if it is not six digits.
    public static func pinSecret(_ pin: String) -> Data? {
        let bytes = Array(pin.utf8)
        guard bytes.count == pinLength, bytes.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
        return Data(bytes)
    }

    public static func sha256(_ data: Data) -> Data { Data(SHA256.hash(data: data)) }

    public static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }

    public static func randomBytes(_ size: Int) -> Data {
        var out = Data(count: size)
        let status = out.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, size, $0.baseAddress!) }
        precondition(status == errSecSuccess, "the system random source failed")
        return out
    }

    private static func hmac(key: Data, _ parts: Data...) -> Data {
        var mac = HMAC<SHA256>(key: SymmetricKey(data: key))
        for p in parts { mac.update(data: p) }
        return Data(mac.finalize())
    }
}
