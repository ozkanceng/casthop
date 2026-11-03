package io.github.ozkanceng.yansit.protocol

/** Protokol ayrıştırma hataları. Adlar C++ uygulaması ve `protocol/vectors` ile birebir aynıdır. */
public enum class ProtocolError {
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    UNKNOWN_FRAME_TYPE,
    FRAME_TOO_LARGE,
    TRUNCATED,
    EMPTY_CONTROL,
    MALFORMED_TLV,
    DUPLICATE_TAG,
    MISSING_TAG,
    BAD_FIELD_LENGTH,
    BAD_UTF8,
    STRING_TOO_LONG,
    UNKNOWN_CRITICAL_MESSAGE,
}

/** Geçersiz protokol verisi. Bağlantı kapatılmalı; uygulama çökmemelidir. */
public class ProtocolException(
    public val error: ProtocolError,
    detail: String? = null,
) : Exception(if (detail == null) error.name else "${error.name}: $detail")
