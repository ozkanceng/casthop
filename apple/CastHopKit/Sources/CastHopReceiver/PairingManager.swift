import CastHopProtocol
import Foundation

/// A paired sender (auth_method = 3).
public struct TrustedSender: Equatable, Codable, Sendable {
    public var keyId: Data
    public var pairKey: Data
    public var deviceId: Data
    public var deviceName: String

    public init(keyId: Data, pairKey: Data, deviceId: Data, deviceName: String) {
        self.keyId = keyId
        self.pairKey = pairKey
        self.deviceId = deviceId
        self.deviceName = deviceName
    }
}

/// Receiver side authentication policy (PROTOCOL.md §7.1, §11): single-use QR token and PIN, PIN lockout, global HELLO
/// rate limit, pairing keys. Time comes from the caller, in milliseconds.
public final class PairingManager {
    public struct Policy: Sendable {
        public var tokenLifetimeMs: Int64 = 10 * 60 * 1000
        public var maxPinFailures = 5
        public var pinLockoutMs: Int64 = 30_000
        public var maxHellosPerWindow = 10
        public var helloWindowMs: Int64 = 10_000

        public init() {}
    }

    public enum Outcome: Equatable, Sendable {
        case accepted
        case authFailed
        case rateLimited
        case lockedOut
        case unsupportedMethod
    }

    public struct Verdict: Equatable, Sendable {
        public var outcome: Outcome
        public var retryAfterMs: Int64?
        public var trusted: TrustedSender?

        init(_ outcome: Outcome, retryAfterMs: Int64? = nil, trusted: TrustedSender? = nil) {
            self.outcome = outcome
            self.retryAfterMs = retryAfterMs
            self.trusted = trusted
        }
    }

    private let policy: Policy
    public private(set) var qrToken: Data
    /// Six digits, leading zeros included.
    public private(set) var pin: String
    private var tokenIssuedMs: Int64
    private var pinFailures = 0
    private var pinLockedUntilMs: Int64?
    private var recentHellos: [Int64] = []
    public var trustedSenders: [TrustedSender] = []

    /// The token or PIN changed, so the waiting screen must refresh.
    public var onCredentialsChanged: (() -> Void)?
    /// The trusted list changed, so it must be stored.
    public var onTrustedChanged: (() -> Void)?

    public init(policy: Policy = Policy(), nowMs: Int64) {
        self.policy = policy
        qrToken = Auth.randomBytes(QrPayload.tokenSize)
        tokenIssuedMs = nowMs
        pin = Self.generatePin()
    }

    /// A uniform value in [0, 999999], by rejection sampling.
    public static func generatePin() -> String {
        let range: UInt32 = 1_000_000
        let limit = UInt32.max - (UInt32.max % range)
        var v: UInt32
        repeat {
            v = Auth.randomBytes(4).reduce(UInt32(0)) { $0 << 8 | UInt32($1) }
        } while v >= limit
        return String(format: "%06u", v % range)
    }

    /// Renews an expired token. True if the QR must refresh.
    @discardableResult
    public func tick(nowMs: Int64) -> Bool {
        guard nowMs - tokenIssuedMs >= policy.tokenLifetimeMs else { return false }
        rotateToken(nowMs)
        onCredentialsChanged?()
        return true
    }

    private func rotateToken(_ nowMs: Int64) {
        qrToken = Auth.randomBytes(QrPayload.tokenSize)
        tokenIssuedMs = nowMs
    }

    private func rotatePin() {
        pin = Self.generatePin()
        pinFailures = 0
    }

    public func verifyHello(_ hello: Hello, serverNonce: Data, certFingerprint: Data, nowMs: Int64) -> Verdict {
        recentHellos.removeAll { nowMs - $0 >= policy.helloWindowMs }
        if recentHellos.count >= policy.maxHellosPerWindow {
            return Verdict(.rateLimited, retryAfterMs: recentHellos[0] + policy.helloWindowMs - nowMs)
        }
        recentHellos.append(nowMs)

        func expected(_ secret: Data) -> Data {
            Auth.authProof(secret: secret, authMethod: hello.authMethod, serverNonce: serverNonce,
                           clientNonce: hello.clientNonce, receiverCertFp: certFingerprint, deviceId: hello.deviceId)
        }

        switch hello.authMethod {
        case AuthMethod.qrToken:
            guard Auth.constantTimeEquals(expected(qrToken), hello.authProof) else { return Verdict(.authFailed) }
            rotateToken(nowMs)
            onCredentialsChanged?()
            return Verdict(.accepted)
        case AuthMethod.pin:
            if let until = pinLockedUntilMs, nowMs < until {
                return Verdict(.lockedOut, retryAfterMs: until - nowMs)
            }
            pinLockedUntilMs = nil
            guard Auth.constantTimeEquals(expected(Data(pin.utf8)), hello.authProof) else {
                pinFailures += 1
                if pinFailures >= policy.maxPinFailures {
                    rotatePin()
                    pinLockedUntilMs = nowMs + policy.pinLockoutMs
                    onCredentialsChanged?()
                    return Verdict(.lockedOut, retryAfterMs: policy.pinLockoutMs)
                }
                return Verdict(.authFailed)
            }
            rotatePin()
            onCredentialsChanged?()
            return Verdict(.accepted)
        case AuthMethod.paired:
            guard let keyId = hello.keyId,
                  let index = trustedSenders.firstIndex(where: { $0.keyId == keyId }),
                  trustedSenders[index].deviceId == hello.deviceId,
                  Auth.constantTimeEquals(expected(trustedSenders[index].pairKey), hello.authProof) else {
                return Verdict(.authFailed)
            }
            if trustedSenders[index].deviceName != hello.deviceName {
                trustedSenders[index].deviceName = hello.deviceName
                onTrustedChanged?()
            }
            return Verdict(.accepted, trusted: trustedSenders[index])
        default:
            return Verdict(.unsupportedMethod)
        }
    }

    /// Creates a pairing for an accepted HELLO. One key per device: a new pairing replaces the old one.
    public func createPairing(_ hello: Hello) -> TrustedSender {
        trustedSenders.removeAll { $0.deviceId == hello.deviceId }
        let t = TrustedSender(keyId: Auth.randomBytes(ControlCodec.idSize), pairKey: Auth.randomBytes(ControlCodec.keySize),
                              deviceId: hello.deviceId, deviceName: hello.deviceName)
        trustedSenders.append(t)
        onTrustedChanged?()
        return t
    }

    @discardableResult
    public func removeTrusted(keyId: Data) -> Bool {
        let before = trustedSenders.count
        trustedSenders.removeAll { $0.keyId == keyId }
        guard trustedSenders.count != before else { return false }
        onTrustedChanged?()
        return true
    }
}
