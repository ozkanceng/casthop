import CastHopProtocol
import Foundation
import Security

public enum IdentityError: Error, Equatable {
    case keychain(OSStatus)
    case keyGeneration
    case signing
    case incomplete
}

/// The receiver's TLS identity (PROTOCOL.md §11): a self-signed ECDSA P-256 certificate, valid for ten years.
public struct ReceiverIdentity: @unchecked Sendable {
    public let secIdentity: SecIdentity
    public let certificateDER: Data
    /// SHA-256 of the DER certificate.
    public let fingerprint: Data
    /// Runs before each TLS handshake.
    public var prepare: @Sendable () -> Void = {}
}

/// Builds the minimal X.509 v3 certificate a receiver needs. Senders compare only the fingerprint, so subject, issuer
/// and extensions just have to be well formed for TLS stacks.
public enum SelfSignedCertificate {
    public static let validityYears = 10
    private static let ecdsaWithSHA256: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x04, 0x03, 0x02]
    private static let ecPublicKey: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01]
    private static let prime256v1: [UInt8] = [0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07]
    private static let commonName: [UInt8] = [0x55, 0x04, 0x03]
    private static let basicConstraints: [UInt8] = [0x55, 0x1D, 0x13]
    private static let keyUsage: [UInt8] = [0x55, 0x1D, 0x0F]
    private static let extKeyUsage: [UInt8] = [0x55, 0x1D, 0x25]
    private static let serverAuth: [UInt8] = [0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01]

    private static func der(_ tag: UInt8, _ content: [UInt8]) -> [UInt8] {
        var out = [tag]
        if content.count < 0x80 {
            out.append(UInt8(content.count))
        } else {
            let length = withUnsafeBytes(of: UInt32(content.count).bigEndian) { Array($0) }.drop { $0 == 0 }
            out.append(0x80 | UInt8(length.count))
            out += length
        }
        return out + content
    }

    private static func sequence(_ parts: [UInt8]...) -> [UInt8] { der(0x30, parts.flatMap { $0 }) }
    private static func oid(_ value: [UInt8]) -> [UInt8] { der(0x06, value) }

    private static func time(_ date: Date) -> [UInt8] {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        let year = Calendar(identifier: .gregorian).dateComponents(in: f.timeZone, from: date).year ?? 2000
        // RFC 5280: UTCTime through 2049, GeneralizedTime after.
        f.dateFormat = year < 2050 ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'"
        return der(year < 2050 ? 0x17 : 0x18, Array(f.string(from: date).utf8))
    }

    /// `publicKey` is the 65 byte X9.63 point. `sign` returns an X9.62 ECDSA signature over SHA-256 of its input.
    public static func make(publicKey: Data, name: String, notBefore: Date, notAfter: Date,
                            sign: (Data) throws -> Data) throws -> Data {
        var serial = [UInt8](Auth.randomBytes(16))
        serial[0] &= 0x7F
        serial[0] |= 0x40 // positive, and no leading zero byte
        let algorithm = sequence(oid(ecdsaWithSHA256))
        let rdn = der(0x31, sequence(oid(commonName), der(0x0C, Array(name.utf8))))
        let dn = sequence(rdn)
        let spki = sequence(sequence(oid(ecPublicKey), oid(prime256v1)), der(0x03, [0x00] + [UInt8](publicKey)))
        let extensions = der(0xA3, sequence(
            sequence(oid(basicConstraints), der(0x01, [0xFF]), der(0x04, sequence())),
            sequence(oid(keyUsage), der(0x01, [0xFF]), der(0x04, der(0x03, [0x07, 0x80]))),
            sequence(oid(extKeyUsage), der(0x04, sequence(oid(serverAuth))))))
        let tbs = sequence(der(0xA0, der(0x02, [0x02])), der(0x02, serial), algorithm, dn,
                           sequence(time(notBefore), time(notAfter)), dn, spki, extensions)
        let signature = try sign(Data(tbs))
        return Data(sequence(tbs, algorithm, der(0x03, [0x00] + [UInt8](signature))))
    }

    static func sign(with key: SecKey) -> (Data) throws -> Data {
        { tbs in
            var error: Unmanaged<CFError>?
            guard let s = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, tbs as CFData, &error) else {
                throw IdentityError.signing
            }
            return s as Data
        }
    }

    static func make(key: SecKey, name: String, now: Date) throws -> Data {
        guard let pub = SecKeyCopyPublicKey(key), let point = SecKeyCopyExternalRepresentation(pub, nil) else {
            throw IdentityError.keyGeneration
        }
        let notAfter = Calendar(identifier: .gregorian).date(byAdding: .year, value: validityYears, to: now) ?? now
        return try make(publicKey: point as Data, name: name, notBefore: now.addingTimeInterval(-86_400),
                        notAfter: notAfter, sign: sign(with: key))
    }
}

/// Stores the identity in the keychain and creates it on first use. A certificate past its end date is replaced, after
/// which paired senders must scan the QR again (PROTOCOL.md §11).
public enum IdentityStore {
    private static let subject = "CastHop"
    private static let renewAfter = TimeInterval(SelfSignedCertificate.validityYears) * 365 * 86_400 - 86_400

    private struct Metadata: Codable {
        var created: Date
    }

    private static func expired(created: Date, now: Date) -> Bool { now.timeIntervalSince(created) >= renewAfter }

#if os(macOS)
    /// A private file keychain inside `directory`. The data protection keychain needs an entitlement that unsigned
    /// command line builds cannot have, and tests must not touch the login keychain.
    public static func loadOrCreate(directory: URL, now: Date = Date()) throws -> ReceiverIdentity {
        let fm = FileManager.default
        try fm.createDirectory(at: directory, withIntermediateDirectories: true,
                               attributes: [.posixPermissions: 0o700])
        let keychainPath = directory.appendingPathComponent("identity.keychain-db").path
        let passwordURL = directory.appendingPathComponent("keychain.secret")
        let certURL = directory.appendingPathComponent("certificate.der")
        let metaURL = directory.appendingPathComponent("identity.json")

        if let der = try? Data(contentsOf: certURL), let secret = try? Data(contentsOf: passwordURL),
           let metaData = try? Data(contentsOf: metaURL), let meta = try? JSONDecoder().decode(Metadata.self, from: metaData),
           !expired(created: meta.created, now: now), let existing = try? open(path: keychainPath, secret: secret, der: der) {
            return existing
        }

        // Nothing usable: start over. Removing the file alone would leave the keychain known to the system.
        var stale: SecKeychain?
        if fm.fileExists(atPath: keychainPath), SecKeychainOpen(keychainPath, &stale) == errSecSuccess, let stale {
            SecKeychainDelete(stale)
        }
        for url in [certURL, passwordURL, metaURL, URL(fileURLWithPath: keychainPath)] { try? fm.removeItem(at: url) }
        let secret = Data(Base64Url.encode(Auth.randomBytes(32)).utf8)
        try FileStore.writePrivate(secret, to: passwordURL)

        var keychain: SecKeychain?
        let status = secret.withUnsafeBytes {
            SecKeychainCreate(keychainPath, UInt32(secret.count), $0.baseAddress, false, nil, &keychain)
        }
        guard status == errSecSuccess, let keychain else { throw IdentityError.keychain(status) }

        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrIsPermanent as String: true,
            kSecUseKeychain as String: keychain,
            kSecAttrLabel as String: subject,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else { throw IdentityError.keyGeneration }
        let der = try SelfSignedCertificate.make(key: key, name: subject, now: now)
        guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else { throw IdentityError.incomplete }
        let added = SecCertificateAddToKeychain(certificate, keychain)
        guard added == errSecSuccess else { throw IdentityError.keychain(added) }

        try FileStore.writePrivate(der, to: certURL)
        try FileStore.writePrivate(try JSONEncoder().encode(Metadata(created: now)), to: metaURL)
        return try identity(keychain: keychain, secret: secret, certificate: certificate, der: der)
    }

    private static func open(path: String, secret: Data, der: Data) throws -> ReceiverIdentity {
        var keychain: SecKeychain?
        let opened = SecKeychainOpen(path, &keychain)
        guard opened == errSecSuccess, let keychain else { throw IdentityError.keychain(opened) }
        let unlocked = secret.withUnsafeBytes { SecKeychainUnlock(keychain, UInt32(secret.count), $0.baseAddress, true) }
        guard unlocked == errSecSuccess else { throw IdentityError.keychain(unlocked) }
        guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else { throw IdentityError.incomplete }
        return try identity(keychain: keychain, secret: secret, certificate: certificate, der: der)
    }

    private static func identity(keychain: SecKeychain, secret: Data, certificate: SecCertificate,
                                 der: Data) throws -> ReceiverIdentity {
        var identity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(keychain, certificate, &identity)
        guard status == errSecSuccess, let identity else { throw IdentityError.keychain(status) }
        // A file keychain locks itself after a while and on sleep, and a TLS handshake needs its key.
        let unlock: @Sendable () -> Void = {
            _ = secret.withUnsafeBytes { SecKeychainUnlock(keychain, UInt32(secret.count), $0.baseAddress, true) }
        }
        return ReceiverIdentity(secIdentity: identity, certificateDER: der, fingerprint: Auth.sha256(der), prepare: unlock)
    }
#else
    private static let keyTag = Data("io.github.ozkanceng.casthop.identity".utf8)
    private static let metaAccount = "identity-created"

    /// Key and certificate live in the app's keychain. tvOS has no durable files, so nothing is kept on disk.
    public static func loadOrCreate(directory: URL? = nil, now: Date = Date()) throws -> ReceiverIdentity {
        if let meta = KeychainBlob.read(account: metaAccount),
           let created = try? JSONDecoder().decode(Metadata.self, from: meta).created,
           !expired(created: created, now: now), let existing = try? find() {
            return existing
        }
        removeAll()
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecAttrIsPermanent as String: true,
            kSecAttrApplicationTag as String: keyTag,
            kSecAttrLabel as String: subject,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else { throw IdentityError.keyGeneration }
        let der = try SelfSignedCertificate.make(key: key, name: subject, now: now)
        guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else { throw IdentityError.incomplete }
        let added = SecItemAdd([kSecClass as String: kSecClassCertificate, kSecValueRef as String: certificate,
                                kSecAttrLabel as String: subject,
                                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly] as CFDictionary, nil)
        guard added == errSecSuccess else { throw IdentityError.keychain(added) }
        try KeychainBlob.write(try JSONEncoder().encode(Metadata(created: now)), account: metaAccount)
        return try find()
    }

    private static func find() throws -> ReceiverIdentity {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass as String: kSecClassIdentity, kSecReturnRef as String: true,
                                          kSecMatchLimit as String: kSecMatchLimitOne] as CFDictionary, &result)
        guard status == errSecSuccess, let result else { throw IdentityError.keychain(status) }
        let identity = result as! SecIdentity
        var certificate: SecCertificate?
        let copied = SecIdentityCopyCertificate(identity, &certificate)
        guard copied == errSecSuccess, let certificate else { throw IdentityError.keychain(copied) }
        let der = SecCertificateCopyData(certificate) as Data
        return ReceiverIdentity(secIdentity: identity, certificateDER: der, fingerprint: Auth.sha256(der))
    }

    private static func removeAll() {
        SecItemDelete([kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: keyTag] as CFDictionary)
        SecItemDelete([kSecClass as String: kSecClassCertificate, kSecAttrLabel as String: subject] as CFDictionary)
        KeychainBlob.delete(account: metaAccount)
    }
#endif
}

/// A small secret in the app's keychain.
public enum KeychainBlob {
    private static let service = "io.github.ozkanceng.casthop"

    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    public static func read(account: String) -> Data? {
        var q = query(account)
        q[kSecReturnData as String] = true
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    public static func write(_ data: Data, account: String) throws {
        SecItemDelete(query(account) as CFDictionary)
        var q = query(account)
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        guard status == errSecSuccess else { throw IdentityError.keychain(status) }
    }

    public static func delete(account: String) { SecItemDelete(query(account) as CFDictionary) }
}

/// Files only the owner may read, replaced atomically.
public enum FileStore {
    public static func writePrivate(_ data: Data, to url: URL) throws {
        let tmp = url.path + ".tmp"
        unlink(tmp)
        let fd = open(tmp, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0o600)
        guard fd >= 0 else { throw CocoaError(.fileWriteUnknown) }
        var offset = 0
        let failed: Bool = data.withUnsafeBytes { raw in
            while offset < raw.count {
                let n = write(fd, raw.baseAddress! + offset, raw.count - offset)
                if n < 0 {
                    if errno == EINTR { continue }
                    return true
                }
                offset += n
            }
            return false
        }
        close(fd)
        if failed || rename(tmp, url.path) != 0 {
            unlink(tmp)
            throw CocoaError(.fileWriteUnknown)
        }
    }
}
