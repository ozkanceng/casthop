#!/usr/bin/env python3
"""protocol/vectors/vectors.json üretir. Deterministiktir (rastgelelik yok).

Kullanım:  python3 -I protocol/gen/gen_vectors.py            # yazar
           python3 -I protocol/gen/gen_vectors.py --check    # fark varsa hata (CI)
"""
import base64
import hashlib
import hmac
import json
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import schema as S  # noqa: E402

OUT = HERE.parent / "vectors" / "vectors.json"


# ---------------------------------------------------------------- kodlayıcılar
def frame(ftype, flags, pts, seq, payload, *, magic=S.MAGIC, version=S.VERSION, reserved=0, length=None):
    if length is None:
        length = len(payload)
    return struct.pack(">HBBHHQII", magic, version, ftype, flags, reserved, pts, seq, length) + payload


def tlv(tag, value: bytes):
    return struct.pack(">BH", tag, len(value)) + value


def enc_value(ftype, v):
    if ftype == "u8":
        return struct.pack(">B", v)
    if ftype == "bool":
        return struct.pack(">B", 1 if v else 0)
    if ftype == "u16":
        return struct.pack(">H", v)
    if ftype == "u32":
        return struct.pack(">I", v)
    if ftype == "u64":
        return struct.pack(">Q", v)
    if ftype == "i64":
        return struct.pack(">q", v)
    if ftype in ("b16", "b32"):
        b = bytes.fromhex(v)
        assert len(b) == (16 if ftype == "b16" else 32)
        return b
    if ftype == "str":
        b = v.encode("utf-8")
        assert len(b) <= S.MAX_STR_BYTES
        return b
    if ftype == "list":
        return bytes(v)
    if ftype == "pairs":
        return b"".join(bytes([c, f]) for c, f in v)
    raise ValueError(ftype)


def control(name, fields, *, msg_flags=0):
    msg_type, spec = S.MESSAGES[name]
    known = {n for _, n, _, _ in spec}
    unknown = set(fields) - known
    assert not unknown, f"{name}: bilinmeyen alan {unknown}"
    out = bytes([msg_type, msg_flags])
    for tag, fname, ftype, required in spec:
        if fname in fields:
            out += tlv(tag, enc_value(ftype, fields[fname]))
        else:
            assert not required, f"{name}: zorunlu alan eksik {fname}"
    return out


def b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def hx(b: bytes) -> str:
    return b.hex()


def seq_bytes(start, n):
    return bytes((start + i) & 0xFF for i in range(n))


# ---------------------------------------------------------------- sabit test verisi
NONCE_S = hx(seq_bytes(0x10, 16))
NONCE_C = hx(seq_bytes(0x20, 16))
DEVICE_ID = hx(seq_bytes(0x30, 16))
SESSION_ID = hx(seq_bytes(0x40, 16))
KEY_ID = hx(seq_bytes(0x50, 16))
PROOF = hx(seq_bytes(0x60, 32))
ATTACH_KEY = hx(seq_bytes(0x80, 32))
RESUME_TOKEN = hx(seq_bytes(0xA0, 32))
PAIR_KEY = hx(seq_bytes(0xC0, 32))
CERT_FP = hashlib.sha256(b"yansit test certificate").hexdigest()
QR_TOKEN = hx(seq_bytes(0xE0, 16))

CONTROL_OK = [
    ("challenge", "CHALLENGE", {"min_version": 1, "max_version": 1, "server_nonce": NONCE_S,
                                "receiver_name": "5-A Sınıfı Tahta"}),
    ("challenge_min", "CHALLENGE", {"min_version": 1, "max_version": 3, "server_nonce": NONCE_S}),
    ("hello_qr", "HELLO", {"version": 1, "device_name": "Ayşe Öğretmen – Galaxy Tab S9", "device_id": DEVICE_ID,
                           "app_version": "0.1.0", "auth_method": 1, "client_nonce": NONCE_C, "auth_proof": PROOF,
                           "video_codecs": [0x02, 0x01], "audio_codecs": [0x10, 0x11], "max_width": 2560,
                           "max_height": 1600, "max_fps": 60, "request_pairing": True}),
    ("hello_paired", "HELLO", {"version": 1, "device_name": "Tablet", "device_id": DEVICE_ID, "app_version": "1.2.3",
                               "auth_method": 3, "client_nonce": NONCE_C, "auth_proof": PROOF, "key_id": KEY_ID,
                               "video_codecs": [0x01], "max_width": 1920, "max_height": 1080, "max_fps": 30}),
    ("welcome_full", "WELCOME", {"version": 1, "receiver_name": "Tahta", "session_id": SESSION_ID,
                                 "display_width": 3840, "display_height": 2160, "display_refresh": 60,
                                 "video_decoders": [[0x01, 0x03], [0x02, 0x01]], "audio_decoders": [[0x10, 0x00], [0x11, 0x00]],
                                 "attach_key": ATTACH_KEY, "resume_token": RESUME_TOKEN, "pair_key": PAIR_KEY,
                                 "key_id": KEY_ID, "audio_enabled": True, "rotated_frames": True}),
    ("welcome_min", "WELCOME", {"version": 1, "receiver_name": "", "session_id": SESSION_ID,
                                "display_width": 1920, "display_height": 1080, "display_refresh": 60,
                                "video_decoders": [[0x01, 0x00]], "audio_decoders": [],
                                "attach_key": ATTACH_KEY, "resume_token": RESUME_TOKEN, "audio_enabled": False}),
    ("reject", "REJECT", {"reason": 5, "retry_after_ms": 30000}),
    ("reject_min", "REJECT", {"reason": 2}),
    ("pending_approval", "PENDING_APPROVAL", {"timeout_ms": 20000}),
    ("stream_config_video", "STREAM_CONFIG", {"stream_kind": 1, "codec": 0x01, "width": 1920, "height": 1200,
                                              "fps": 60, "bitrate_bps": 16000000, "rotation_deg": 90}),
    ("stream_config_audio", "STREAM_CONFIG", {"stream_kind": 2, "codec": 0x10, "bitrate_bps": 128000,
                                              "sample_rate": 48000, "channels": 2}),
    ("keyframe_request", "KEYFRAME_REQUEST", {"reason": 2}),
    ("ping", "PING", {"ping_id": 7, "t0_us": 1000000}),
    ("ping_offset_negative", "PING", {"ping_id": 0xFFFFFFFF, "t0_us": 0x7FFFFFFFFFFFFFFF, "offset_us": -1234567}),
    ("pong", "PONG", {"ping_id": 7, "t0_us": 1000000, "t1_us": 5001200, "t2_us": 5001300}),
    ("receiver_report", "RECEIVER_REPORT", {"interval_ms": 500, "frames_received": 30, "frames_rendered": 29,
                                            "frames_dropped": 1, "bytes_received": 1000000, "c2r_avg_us": 21000,
                                            "c2r_min_us": 15000, "c2r_max_us": 40000, "c2d_avg_us": 38000,
                                            "decode_backlog": 0, "audio_late": 2}),
    ("sender_stats", "SENDER_STATS", {"bitrate_bps": 12000000, "fps_x100": 5994, "frames_dropped": 3,
                                      "encoder_name": "c2.exynos.h264.encoder"}),
    ("pause", "PAUSE", {}),
    ("unpause", "UNPAUSE", {}),
    ("attach", "ATTACH", {"session_id": SESSION_ID, "stream_kind": 1, "proof": PROOF}),
    ("attach_result", "ATTACH_RESULT", {"status": 0}),
    ("resume", "RESUME", {"session_id": SESSION_ID, "proof": PROOF}),
    ("bye", "BYE", {"reason": 1}),
]


def control_vectors():
    out = []
    for name, msg, fields in CONTROL_OK:
        out.append({"name": f"control_{name}", "hex": hx(control(msg, fields)),
                    "expect": {"message": msg, "fields": fields}, "roundtrip": True})

    ping = control("PING", {"ping_id": 7, "t0_us": 1000000})
    out += [
        {"name": "control_unknown_tag_ignored", "hex": hx(ping + tlv(0x7F, b"\x01\x02\x03")),
         "expect": {"message": "PING", "fields": {"ping_id": 7, "t0_us": 1000000}}, "roundtrip": False},
        {"name": "control_unknown_message_ignored", "hex": hx(bytes([0x99, 0x00]) + tlv(0x01, b"\xAA")),
         "expect": {"message": "UNKNOWN", "fields": {"msg_type": 0x99}}, "roundtrip": False},
        {"name": "control_unknown_message_critical", "hex": hx(bytes([0x99, S.MSG_FLAG_CRITICAL])),
         "expect_error": "UNKNOWN_CRITICAL_MESSAGE"},
        {"name": "control_empty", "hex": "", "expect_error": "EMPTY_CONTROL"},
        {"name": "control_only_type", "hex": "20", "expect_error": "TRUNCATED"},
        {"name": "control_duplicate_tag", "hex": hx(ping + tlv(0x01, struct.pack(">I", 8))),
         "expect_error": "DUPLICATE_TAG"},
        {"name": "control_missing_tag", "hex": hx(bytes([0x20, 0x00]) + tlv(0x01, struct.pack(">I", 7))),
         "expect_error": "MISSING_TAG"},
        {"name": "control_tlv_overflow", "hex": hx(bytes([0x20, 0x00]) + struct.pack(">BH", 0x01, 10) + b"\x00\x00"),
         "expect_error": "MALFORMED_TLV"},
        {"name": "control_tlv_truncated_header", "hex": hx(ping + b"\x05\x00"), "expect_error": "MALFORMED_TLV"},
        {"name": "control_bad_field_length", "hex": hx(bytes([0x20, 0x00]) + tlv(0x01, b"\x00\x00\x07")
                                                       + tlv(0x02, struct.pack(">Q", 1))),
         "expect_error": "BAD_FIELD_LENGTH"},
        {"name": "control_bad_pairs_length", "hex": hx(
            control("WELCOME", dict(CONTROL_OK[5][2]))[:2]
            + b"".join(tlv(t, v) for t, v in [
                (0x01, b"\x00\x01"), (0x02, b""), (0x03, bytes.fromhex(SESSION_ID)), (0x04, b"\x07\x80"),
                (0x05, b"\x04\x38"), (0x06, b"\x3c"), (0x07, b"\x01\x00\x02"), (0x08, b""),
                (0x09, bytes.fromhex(ATTACH_KEY)), (0x0A, bytes.fromhex(RESUME_TOKEN)), (0x0D, b"\x00")])),
         "expect_error": "BAD_FIELD_LENGTH"},
        {"name": "control_bad_utf8", "hex": hx(bytes([0x31, 0x00]) + tlv(0x01, struct.pack(">I", 1))
                                               + tlv(0x02, struct.pack(">H", 1)) + tlv(0x03, struct.pack(">I", 0))
                                               + tlv(0x04, b"\xC3\x28")),
         "expect_error": "BAD_UTF8"},
        {"name": "control_string_too_long", "hex": hx(bytes([0x31, 0x00]) + tlv(0x01, struct.pack(">I", 1))
                                                      + tlv(0x02, struct.pack(">H", 1)) + tlv(0x03, struct.pack(">I", 0))
                                                      + tlv(0x04, b"a" * 256)),
         "expect_error": "STRING_TOO_LONG"},
    ]
    return out


def frame_vectors():
    opus_head = b"OpusHead" + bytes([1, 2]) + struct.pack("<HIhB", 312, 48000, 0, 0)
    assert len(opus_head) == 19
    ping_payload = control("PING", {"ping_id": 7, "t0_us": 1000000})
    big = S.MAX_PAYLOAD
    v = [
        {"name": "frame_video_keyframe", "hex": hx(frame(0x01, 0x0002, 1000000, 1, b"\x00\x00\x00\x01")),
         "expect": {"type": 1, "flags": 2, "pts_us": 1000000, "seq": 1, "length": 4, "payload": "00000001"}},
        {"name": "frame_video_rotated", "hex": hx(frame(0x01, 0x000A, 2000000, 2, b"\x00\x00\x00\x01\x65")),
         "expect": {"type": 1, "flags": 0x000A, "pts_us": 2000000, "seq": 2, "length": 5, "payload": "0000000165"}},
        {"name": "frame_control_ping", "hex": hx(frame(0x03, 0, 1000000, 7, ping_payload)),
         "expect": {"type": 3, "flags": 0, "pts_us": 1000000, "seq": 7, "length": len(ping_payload),
                    "payload": hx(ping_payload)}},
        {"name": "frame_audio_config", "hex": hx(frame(0x02, 0x0001, 0, 0, b"\x10" + opus_head)),
         "expect": {"type": 2, "flags": 1, "pts_us": 0, "seq": 0, "length": 20, "payload": hx(b"\x10" + opus_head)}},
        {"name": "frame_ignores_reserved_and_unknown_flags",
         "hex": hx(frame(0x01, 0x8006, 0x0123456789ABCDEF, 0xFFFFFFFF, b"\xAB", reserved=0xBEEF)),
         "expect": {"type": 1, "flags": 0x8006, "pts_us": 0x0123456789ABCDEF, "seq": 0xFFFFFFFF, "length": 1,
                    "payload": "ab"}},
        {"name": "frame_control_max_length", "hex": hx(frame(0x03, 0, 0, 0, b"", length=big[0x03])),
         "header_only": True,
         "expect": {"type": 3, "flags": 0, "pts_us": 0, "seq": 0, "length": big[0x03]}},
        {"name": "frame_bad_magic", "hex": hx(frame(0x01, 0, 0, 0, b"", magic=0x5955)), "expect_error": "BAD_MAGIC"},
        {"name": "frame_unsupported_version", "hex": hx(frame(0x01, 0, 0, 0, b"", version=2)),
         "expect_error": "UNSUPPORTED_VERSION"},
        {"name": "frame_unknown_type", "hex": hx(frame(0x04, 0, 0, 0, b"")), "expect_error": "UNKNOWN_FRAME_TYPE"},
        {"name": "frame_type_zero", "hex": hx(frame(0x00, 0, 0, 0, b"")), "expect_error": "UNKNOWN_FRAME_TYPE"},
        {"name": "frame_control_too_large", "hex": hx(frame(0x03, 0, 0, 0, b"", length=big[0x03] + 1)),
         "header_only": True, "expect_error": "FRAME_TOO_LARGE"},
        {"name": "frame_audio_too_large", "hex": hx(frame(0x02, 0, 0, 0, b"", length=big[0x02] + 1)),
         "header_only": True, "expect_error": "FRAME_TOO_LARGE"},
        {"name": "frame_video_too_large", "hex": hx(frame(0x01, 0, 0, 0, b"", length=big[0x01] + 1)),
         "header_only": True, "expect_error": "FRAME_TOO_LARGE"},
        {"name": "frame_truncated_header", "hex": hx(frame(0x01, 0, 0, 0, b"")[:23]), "expect_error": "TRUNCATED"},
        {"name": "frame_truncated_payload", "hex": hx(frame(0x01, 0, 0, 0, b"\x00\x01", length=3)),
         "expect_error": "TRUNCATED"},
    ]
    return v


def auth_vectors():
    out = []

    def proof(key, msg):
        return hmac.new(key, msg, hashlib.sha256).hexdigest()

    for name, method, secret in [
        ("auth_qr_token", 1, bytes.fromhex(QR_TOKEN)),
        ("auth_pin", 2, b"042917"),
        ("auth_paired", 3, bytes.fromhex(PAIR_KEY)),
    ]:
        msg = (S.AUTH_LABEL + bytes([method]) + bytes.fromhex(NONCE_S) + bytes.fromhex(NONCE_C)
               + bytes.fromhex(CERT_FP) + bytes.fromhex(DEVICE_ID))
        out.append({"name": name, "kind": "auth", "inputs": {
            "secret": hx(secret), "auth_method": method, "server_nonce": NONCE_S, "client_nonce": NONCE_C,
            "receiver_cert_fp": CERT_FP, "device_id": DEVICE_ID}, "expect": proof(secret, msg)})
    for kind in (1, 2):
        msg = S.ATTACH_LABEL + bytes.fromhex(SESSION_ID) + bytes([kind]) + bytes.fromhex(NONCE_S)
        out.append({"name": f"attach_proof_kind{kind}", "kind": "attach", "inputs": {
            "attach_key": ATTACH_KEY, "session_id": SESSION_ID, "stream_kind": kind, "server_nonce": NONCE_S},
            "expect": proof(bytes.fromhex(ATTACH_KEY), msg)})
    msg = S.RESUME_LABEL + bytes.fromhex(SESSION_ID) + bytes.fromhex(NONCE_S)
    out.append({"name": "resume_proof", "kind": "resume", "inputs": {
        "resume_token": RESUME_TOKEN, "session_id": SESSION_ID, "server_nonce": NONCE_S},
        "expect": proof(bytes.fromhex(RESUME_TOKEN), msg)})
    out.append({"name": "cert_fingerprint", "kind": "sha256", "inputs": {"data": hx(b"yansit test certificate")},
                "expect": CERT_FP})
    return out


def qr_uri(hosts, port, token_hex, fp_hex, name):
    from urllib.parse import quote
    return (f"yansit://c?v=1&h={','.join(hosts)}&p={port}&t={b64url(bytes.fromhex(token_hex))}"
            f"&f={b64url(bytes.fromhex(fp_hex))}&n={quote(name, safe='')}")


def qr_vectors():
    ok = [
        ("qr_two_hosts", ["192.168.1.20", "10.42.0.1"], 47800, "5-A Sınıfı"),
        ("qr_one_host_ascii", ["10.0.0.5"], 50000, "Lab"),
        ("qr_four_hosts_special_name", ["10.0.0.1", "10.0.0.2", "172.16.5.4", "192.168.43.1"], 47800,
         "Fen & Matematik / Kat 2"),
    ]
    out = []
    for name, hosts, port, bname in ok:
        out.append({"name": name, "kind": "qr", "fields": {"hosts": hosts, "port": port, "token": QR_TOKEN,
                                                             "cert_fp": CERT_FP, "name": bname},
                    "uri": qr_uri(hosts, port, QR_TOKEN, CERT_FP, bname)})
    good = qr_uri(["10.0.0.5"], 47800, QR_TOKEN, CERT_FP, "Lab")
    bad = [
        ("qr_wrong_scheme", good.replace("yansit://", "http://"), "BAD_SCHEME"),
        ("qr_unsupported_version", good.replace("v=1", "v=2"), "UNSUPPORTED_VERSION"),
        ("qr_missing_token", good.replace(f"&t={b64url(bytes.fromhex(QR_TOKEN))}", ""), "MISSING_FIELD"),
        ("qr_bad_token_length", good.replace(f"t={b64url(bytes.fromhex(QR_TOKEN))}", "t=AAAA"), "BAD_FIELD"),
        ("qr_bad_fp_base64", good.replace(f"f={b64url(bytes.fromhex(CERT_FP))}", "f=!!!"), "BAD_FIELD"),
        ("qr_bad_host", good.replace("h=10.0.0.5", "h=10.0.0.256"), "BAD_FIELD"),
        ("qr_too_many_hosts", good.replace("h=10.0.0.5", "h=10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5"),
         "BAD_FIELD"),
        ("qr_bad_port", good.replace("p=47800", "p=70000"), "BAD_FIELD"),
    ]
    for name, uri, err in bad:
        out.append({"name": name, "kind": "qr", "uri": uri, "expect_error": err})
    return out


def build():
    return {
        "protocol_version": S.VERSION,
        "generator": "protocol/gen/gen_vectors.py",
        "error_codes": S.ERRORS,
        "frames": frame_vectors(),
        "control": control_vectors(),
        "crypto": auth_vectors(),
        "qr": qr_vectors(),
    }


def main():
    text = json.dumps(build(), ensure_ascii=False, indent=2) + "\n"
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print("vectors.json güncel değil: python3 -I protocol/gen/gen_vectors.py", file=sys.stderr)
            return 1
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"yazıldı: {OUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
