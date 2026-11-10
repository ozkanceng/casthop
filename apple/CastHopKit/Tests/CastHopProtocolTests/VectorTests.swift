import Foundation
import XCTest
@testable import CastHopProtocol

/// Runs all of `protocol/vectors/vectors.json`, the set the Kotlin and C++ implementations pass.
final class VectorTests: XCTestCase {
    private static let root: [String: Any] = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("protocol/vectors/vectors.json")
        let data = try! Data(contentsOf: url)
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }()

    private func section(_ name: String) -> [[String: Any]] { Self.root[name] as! [[String: Any]] }

    func testErrorCodesMatchVectors() {
        XCTAssertEqual(Self.root["error_codes"] as! [String], ProtocolError.allCases.map(\.rawValue))
    }

    func testFrames() throws {
        for v in section("frames") {
            let name = v["name"] as! String
            let bytes = Data(hex: v["hex"] as! String)!
            let headerOnly = v["header_only"] as? Bool ?? false
            let decode: () throws -> FrameHeader = {
                let h = try Frames.decodeHeader(bytes)
                if !headerOnly && bytes.count - Frames.headerSize < h.length { throw ProtocolError.truncated }
                return h
            }
            if let expected = v["expect_error"] as? String {
                XCTAssertThrowsError(try decode(), name) { XCTAssertEqual(($0 as! ProtocolError).rawValue, expected, name) }
                continue
            }
            let h = try decode()
            let ex = v["expect"] as! [String: Any]
            XCTAssertEqual(ex["type"] as! Int, Int(h.type.rawValue), name)
            XCTAssertEqual(ex["flags"] as! Int, Int(h.flags), name)
            XCTAssertEqual((ex["pts_us"] as! NSNumber).uint64Value, h.ptsUs, name)
            XCTAssertEqual((ex["seq"] as! NSNumber).uint32Value, h.seq, name)
            XCTAssertEqual(ex["length"] as! Int, h.length, name)
            if !headerOnly {
                let payload = bytes.dropFirst(Frames.headerSize)
                XCTAssertEqual(ex["payload"] as! String, Data(payload).hex, name)
                if bytes[6] == 0 && bytes[7] == 0 {
                    XCTAssertEqual(bytes, Frames.encode(type: h.type, flags: h.flags, ptsUs: h.ptsUs, seq: h.seq,
                                                        payload: Data(payload)), name)
                }
            }
        }
    }

    func testControl() throws {
        for v in section("control") {
            let name = v["name"] as! String
            let bytes = Data(hex: v["hex"] as! String)!
            if let expected = v["expect_error"] as? String {
                XCTAssertThrowsError(try ControlCodec.decode(bytes), name) {
                    XCTAssertEqual(($0 as! ProtocolError).rawValue, expected, name)
                }
                continue
            }
            let msg = try ControlCodec.decode(bytes)
            let ex = v["expect"] as! [String: Any]
            let (vectorName, fields) = describe(msg)
            XCTAssertEqual(ex["message"] as! String, vectorName, name)
            XCTAssertEqual(normalize(ex["fields"]!) as! NSDictionary, normalize(fields) as! NSDictionary, name)
            if v["roundtrip"] as! Bool {
                XCTAssertEqual(v["hex"] as! String, ControlCodec.encode(msg).hex, name)
            }
        }
    }

    func testCrypto() {
        for v in section("crypto") {
            let i = v["inputs"] as! [String: Any]
            func b(_ k: String) -> Data { Data(hex: i[k] as! String)! }
            let actual: Data
            switch v["kind"] as! String {
            case "auth":
                actual = Auth.authProof(secret: b("secret"), authMethod: UInt8(i["auth_method"] as! Int),
                                        serverNonce: b("server_nonce"), clientNonce: b("client_nonce"),
                                        receiverCertFp: b("receiver_cert_fp"), deviceId: b("device_id"))
            case "attach":
                actual = Auth.attachProof(attachKey: b("attach_key"), sessionId: b("session_id"),
                                          streamKind: UInt8(i["stream_kind"] as! Int), serverNonce: b("server_nonce"))
            case "resume":
                actual = Auth.resumeProof(resumeToken: b("resume_token"), sessionId: b("session_id"),
                                          serverNonce: b("server_nonce"))
            case "sha256":
                actual = Auth.sha256(b("data"))
            default:
                return XCTFail("unknown kind")
            }
            XCTAssertEqual(v["expect"] as! String, actual.hex, v["name"] as? String ?? "")
        }
    }

    func testQr() throws {
        for v in section("qr") {
            let name = v["name"] as! String
            let uri = v["uri"] as! String
            if let expected = v["expect_error"] as? String {
                XCTAssertThrowsError(try QrPayload.parse(uri), name) {
                    XCTAssertEqual(($0 as! QrError).rawValue, expected, name)
                }
                continue
            }
            let f = v["fields"] as! [String: Any]
            let expected = QrPayload(hosts: f["hosts"] as! [String], port: UInt16(f["port"] as! Int),
                                     token: Data(hex: f["token"] as! String)!,
                                     certFingerprint: Data(hex: f["cert_fp"] as! String)!, name: f["name"] as! String)
            XCTAssertEqual(expected, try QrPayload.parse(uri), name)
            XCTAssertEqual(uri, expected.toUri(), name)
        }
    }

    /// Numbers compare through Int64 or UInt64 so that NSNumber bridging cannot hide a difference.
    private func normalize(_ value: Any) -> Any {
        switch value {
        case let d as [String: Any]: return d.mapValues(normalize) as NSDictionary
        case let a as [Any]: return a.map(normalize) as NSArray
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            return n.stringValue
        default: return value
        }
    }

    private func describe(_ m: ControlMessage) -> (String, [String: Any]) {
        func clean(_ d: [String: Any?]) -> [String: Any] { d.compactMapValues { $0 } }
        func pairs(_ v: [CodecSupport]) -> [[Int]] { v.map { [Int($0.codec), Int($0.flags)] } }
        func ints(_ v: [UInt8]?) -> [Int]? { v?.map(Int.init) }
        switch m {
        case .challenge(let c):
            return ("CHALLENGE", clean(["min_version": c.minVersion, "max_version": c.maxVersion,
                                        "server_nonce": c.serverNonce.hex, "receiver_name": c.receiverName]))
        case .hello(let h):
            return ("HELLO", clean(["version": h.version, "device_name": h.deviceName, "device_id": h.deviceId.hex,
                                    "app_version": h.appVersion, "auth_method": h.authMethod,
                                    "client_nonce": h.clientNonce.hex, "auth_proof": h.authProof.hex,
                                    "key_id": h.keyId?.hex, "video_codecs": ints(h.videoCodecs),
                                    "audio_codecs": ints(h.audioCodecs), "max_width": h.maxWidth,
                                    "max_height": h.maxHeight, "max_fps": h.maxFps,
                                    "request_pairing": h.requestPairing]))
        case .welcome(let w):
            return ("WELCOME", clean(["version": w.version, "receiver_name": w.receiverName,
                                      "session_id": w.sessionId.hex, "display_width": w.displayWidth,
                                      "display_height": w.displayHeight, "display_refresh": w.displayRefresh,
                                      "video_decoders": pairs(w.videoDecoders), "audio_decoders": pairs(w.audioDecoders),
                                      "attach_key": w.attachKey.hex, "resume_token": w.resumeToken.hex,
                                      "pair_key": w.pairKey?.hex, "key_id": w.keyId?.hex,
                                      "audio_enabled": w.audioEnabled,
                                      "rotated_frames": w.rotatedFrames ? true : nil]))
        case .reject(let reason, let retry):
            return ("REJECT", clean(["reason": reason, "retry_after_ms": retry]))
        case .pendingApproval(let t):
            return ("PENDING_APPROVAL", ["timeout_ms": t])
        case .streamConfig(let s):
            return ("STREAM_CONFIG", clean(["stream_kind": s.streamKind, "codec": s.codec, "width": s.width,
                                            "height": s.height, "fps": s.fps, "bitrate_bps": s.bitrateBps,
                                            "sample_rate": s.sampleRate, "channels": s.channels,
                                            "rotation_deg": s.rotationDeg]))
        case .keyframeRequest(let r):
            return ("KEYFRAME_REQUEST", ["reason": r])
        case .ping(let id, let t0, let offset):
            return ("PING", clean(["ping_id": id, "t0_us": t0, "offset_us": offset]))
        case .pong(let id, let t0, let t1, let t2):
            return ("PONG", ["ping_id": id, "t0_us": t0, "t1_us": t1, "t2_us": t2])
        case .receiverReport(let r):
            return ("RECEIVER_REPORT", ["interval_ms": r.intervalMs, "frames_received": r.framesReceived,
                                        "frames_rendered": r.framesRendered, "frames_dropped": r.framesDropped,
                                        "bytes_received": r.bytesReceived, "c2r_avg_us": r.c2rAvgUs,
                                        "c2r_min_us": r.c2rMinUs, "c2r_max_us": r.c2rMaxUs, "c2d_avg_us": r.c2dAvgUs,
                                        "decode_backlog": r.decodeBacklog, "audio_late": r.audioLate])
        case .senderStats(let s):
            return ("SENDER_STATS", clean(["bitrate_bps": s.bitrateBps, "fps_x100": s.fpsX100,
                                           "frames_dropped": s.framesDropped, "encoder_name": s.encoderName]))
        case .pause: return ("PAUSE", [:])
        case .unpause: return ("UNPAUSE", [:])
        case .attach(let a):
            return ("ATTACH", ["session_id": a.sessionId.hex, "stream_kind": a.streamKind, "proof": a.proof.hex])
        case .attachResult(let s): return ("ATTACH_RESULT", ["status": s])
        case .resume(let r): return ("RESUME", ["session_id": r.sessionId.hex, "proof": r.proof.hex])
        case .bye(let r): return ("BYE", ["reason": r])
        case .unknown(let t, _): return ("UNKNOWN", ["msg_type": t])
        }
    }
}
