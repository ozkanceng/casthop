#include "protocol/Errors.h"

namespace yansit::protocol {

std::string_view errorName(ProtocolError e) {
    switch (e) {
    case ProtocolError::BadMagic:
        return "BAD_MAGIC";
    case ProtocolError::UnsupportedVersion:
        return "UNSUPPORTED_VERSION";
    case ProtocolError::UnknownFrameType:
        return "UNKNOWN_FRAME_TYPE";
    case ProtocolError::FrameTooLarge:
        return "FRAME_TOO_LARGE";
    case ProtocolError::Truncated:
        return "TRUNCATED";
    case ProtocolError::EmptyControl:
        return "EMPTY_CONTROL";
    case ProtocolError::MalformedTlv:
        return "MALFORMED_TLV";
    case ProtocolError::DuplicateTag:
        return "DUPLICATE_TAG";
    case ProtocolError::MissingTag:
        return "MISSING_TAG";
    case ProtocolError::BadFieldLength:
        return "BAD_FIELD_LENGTH";
    case ProtocolError::BadUtf8:
        return "BAD_UTF8";
    case ProtocolError::StringTooLong:
        return "STRING_TOO_LONG";
    case ProtocolError::UnknownCriticalMessage:
        return "UNKNOWN_CRITICAL_MESSAGE";
    }
    return "UNKNOWN";
}

ProtocolException::ProtocolException(ProtocolError error, const std::string& detail)
    : std::runtime_error(detail.empty() ? std::string(errorName(error))
                                        : std::string(errorName(error)) + ": " + detail),
      m_error(error) {}

} // namespace yansit::protocol
