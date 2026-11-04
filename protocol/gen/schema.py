"""CastHop protokol v1 şeması — docs/PROTOCOL.md'nin makine-okur karşılığı.

Bu dosya ve gen_vectors.py bağımsız (üçüncü) bir referans uygulamadır: Kotlin ve C++
kodlayıcıları bu çıktıyla bayt bayt aynı sonucu üretmek zorundadır.
"""

MAGIC = 0x5954
VERSION = 1
HEADER_SIZE = 24

FRAME_TYPES = {"VIDEO": 0x01, "AUDIO": 0x02, "CONTROL": 0x03}
FLAGS = {"CONFIG": 0x0001, "KEYFRAME": 0x0002, "DISCONTINUITY": 0x0004}
MAX_PAYLOAD = {0x01: 8 * 1024 * 1024, 0x02: 64 * 1024, 0x03: 64 * 1024}

MSG_FLAG_CRITICAL = 0x01
MAX_STR_BYTES = 255

# Alan tipleri: u8 u16 u32 u64 i64 bool b16 b32 str list pairs
# (tag, ad, tip, zorunlu)
MESSAGES = {
    "CHALLENGE": (0x00, [
        (0x01, "min_version", "u16", True),
        (0x02, "max_version", "u16", True),
        (0x03, "server_nonce", "b16", True),
        (0x04, "receiver_name", "str", False),
    ]),
    "HELLO": (0x01, [
        (0x01, "version", "u16", True),
        (0x02, "device_name", "str", True),
        (0x03, "device_id", "b16", True),
        (0x04, "app_version", "str", True),
        (0x05, "auth_method", "u8", True),
        (0x06, "client_nonce", "b16", True),
        (0x07, "auth_proof", "b32", True),
        (0x08, "key_id", "b16", False),
        (0x09, "video_codecs", "list", True),
        (0x0A, "audio_codecs", "list", False),
        (0x0B, "max_width", "u16", True),
        (0x0C, "max_height", "u16", True),
        (0x0D, "max_fps", "u8", True),
        (0x0E, "request_pairing", "bool", False),
    ]),
    "WELCOME": (0x02, [
        (0x01, "version", "u16", True),
        (0x02, "receiver_name", "str", True),
        (0x03, "session_id", "b16", True),
        (0x04, "display_width", "u16", True),
        (0x05, "display_height", "u16", True),
        (0x06, "display_refresh", "u8", True),
        (0x07, "video_decoders", "pairs", True),
        (0x08, "audio_decoders", "pairs", True),
        (0x09, "attach_key", "b32", True),
        (0x0A, "resume_token", "b32", True),
        (0x0B, "pair_key", "b32", False),
        (0x0C, "key_id", "b16", False),
        (0x0D, "audio_enabled", "bool", True),
    ]),
    "REJECT": (0x03, [
        (0x01, "reason", "u8", True),
        (0x02, "retry_after_ms", "u32", False),
    ]),
    "PENDING_APPROVAL": (0x04, [
        (0x01, "timeout_ms", "u32", True),
    ]),
    "STREAM_CONFIG": (0x10, [
        (0x01, "stream_kind", "u8", True),
        (0x02, "codec", "u8", True),
        (0x03, "width", "u16", False),
        (0x04, "height", "u16", False),
        (0x05, "fps", "u8", False),
        (0x06, "bitrate_bps", "u32", False),
        (0x07, "sample_rate", "u32", False),
        (0x08, "channels", "u8", False),
        (0x09, "rotation_deg", "u16", False),
    ]),
    "KEYFRAME_REQUEST": (0x11, [
        (0x01, "reason", "u8", True),
    ]),
    "PING": (0x20, [
        (0x01, "ping_id", "u32", True),
        (0x02, "t0_us", "u64", True),
        (0x03, "offset_us", "i64", False),
    ]),
    "PONG": (0x21, [
        (0x01, "ping_id", "u32", True),
        (0x02, "t0_us", "u64", True),
        (0x03, "t1_us", "u64", True),
        (0x04, "t2_us", "u64", True),
    ]),
    "RECEIVER_REPORT": (0x30, [
        (0x01, "interval_ms", "u16", True),
        (0x02, "frames_received", "u32", True),
        (0x03, "frames_rendered", "u32", True),
        (0x04, "frames_dropped", "u32", True),
        (0x05, "bytes_received", "u32", True),
        (0x06, "c2r_avg_us", "u32", True),
        (0x07, "c2r_min_us", "u32", True),
        (0x08, "c2r_max_us", "u32", True),
        (0x09, "c2d_avg_us", "u32", True),
        (0x0A, "decode_backlog", "u16", True),
        (0x0B, "audio_late", "u16", True),
    ]),
    "SENDER_STATS": (0x31, [
        (0x01, "bitrate_bps", "u32", True),
        (0x02, "fps_x100", "u16", True),
        (0x03, "frames_dropped", "u32", True),
        (0x04, "encoder_name", "str", False),
    ]),
    "PAUSE": (0x40, []),
    "UNPAUSE": (0x41, []),
    "ATTACH": (0x50, [
        (0x01, "session_id", "b16", True),
        (0x02, "stream_kind", "u8", True),
        (0x03, "proof", "b32", True),
    ]),
    "ATTACH_RESULT": (0x51, [
        (0x01, "status", "u8", True),
    ]),
    "RESUME": (0x60, [
        (0x01, "session_id", "b16", True),
        (0x02, "proof", "b32", True),
    ]),
    "BYE": (0x70, [
        (0x01, "reason", "u8", True),
    ]),
}

# Hata kodları: iki uygulamada da aynı adlarla bulunur.
ERRORS = [
    "BAD_MAGIC",
    "UNSUPPORTED_VERSION",
    "UNKNOWN_FRAME_TYPE",
    "FRAME_TOO_LARGE",
    "TRUNCATED",
    "EMPTY_CONTROL",
    "MALFORMED_TLV",
    "DUPLICATE_TAG",
    "MISSING_TAG",
    "BAD_FIELD_LENGTH",
    "BAD_UTF8",
    "STRING_TOO_LONG",
    "UNKNOWN_CRITICAL_MESSAGE",
]

AUTH_LABEL = b"yansit-auth-v1"
ATTACH_LABEL = b"yansit-attach-v1"
RESUME_LABEL = b"yansit-resume-v1"
