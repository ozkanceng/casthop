#pragma once

#include <stdexcept>
#include <string>
#include <string_view>

namespace yansit::protocol {

/// Protokol ayrıştırma hataları. Adlar Kotlin uygulaması ve protocol/vectors ile birebir aynıdır.
enum class ProtocolError {
    BadMagic,
    UnsupportedVersion,
    UnknownFrameType,
    FrameTooLarge,
    Truncated,
    EmptyControl,
    MalformedTlv,
    DuplicateTag,
    MissingTag,
    BadFieldLength,
    BadUtf8,
    StringTooLong,
    UnknownCriticalMessage,
};

/// Vektörlerdeki ad, ör. "BAD_MAGIC".
std::string_view errorName(ProtocolError e);

/// Geçersiz protokol verisi. Bağlantı kapatılmalı; uygulama çökmemelidir.
class ProtocolException : public std::runtime_error {
public:
    ProtocolException(ProtocolError error, const std::string& detail = {});

    ProtocolError error() const noexcept { return m_error; }

private:
    ProtocolError m_error;
};

} // namespace yansit::protocol
