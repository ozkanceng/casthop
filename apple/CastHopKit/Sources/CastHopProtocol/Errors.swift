/// Parse errors. Names match `protocol/vectors` and the Kotlin and C++ implementations.
public enum ProtocolError: String, CaseIterable, Error, Sendable {
    case badMagic = "BAD_MAGIC"
    case unsupportedVersion = "UNSUPPORTED_VERSION"
    case unknownFrameType = "UNKNOWN_FRAME_TYPE"
    case frameTooLarge = "FRAME_TOO_LARGE"
    case truncated = "TRUNCATED"
    case emptyControl = "EMPTY_CONTROL"
    case malformedTlv = "MALFORMED_TLV"
    case duplicateTag = "DUPLICATE_TAG"
    case missingTag = "MISSING_TAG"
    case badFieldLength = "BAD_FIELD_LENGTH"
    case badUtf8 = "BAD_UTF8"
    case stringTooLong = "STRING_TOO_LONG"
    case unknownCriticalMessage = "UNKNOWN_CRITICAL_MESSAGE"

    /// Frame boundaries are lost, so the connection closes without a reply (PROTOCOL.md §9.1).
    public var breaksFraming: Bool {
        switch self {
        case .badMagic, .unsupportedVersion, .unknownFrameType, .frameTooLarge, .truncated: return true
        default: return false
        }
    }
}
