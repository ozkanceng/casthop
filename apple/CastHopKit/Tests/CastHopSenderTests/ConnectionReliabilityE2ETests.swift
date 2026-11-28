import CastHopProtocol
import CastHopReceiver
@testable import CastHopSender
import CastHopTransport
import Foundation
import XCTest

/// Live loopback TLS 1.3 end-to-end reliability tests exercising real `ReceiverService` + `ConnectFlow` + `PinnedTLS`.
final class ConnectionReliabilityE2ETests: XCTestCase {
    private func startReceiver(port: UInt16, name: String) throws -> (ReceiverService, ReceiverSnapshot) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("casthop-e2e-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }

        var options = ReceiverOptions()
        options.name = name
        options.port = port
        options.dataDirectory = dir
        options.qrHosts = ["127.0.0.1"]
        options.headless = true
        options.audio = false

        let service = ReceiverService(options: options)
        let ready = expectation(description: "receiver \(name) waiting")
        var firstSnapshot: ReceiverSnapshot?
        let lock = NSLock()
        service.onChange = { snap in
            lock.lock()
            let done = firstSnapshot != nil
            if !done && snap.state == .waiting && !snap.qrUri.isEmpty {
                firstSnapshot = snap
                lock.unlock()
                ready.fulfill()
            } else {
                lock.unlock()
            }
        }
        service.start()
        wait(for: [ready], timeout: 5)
        addTeardownBlock { service.shutdown() }
        return (service, try XCTUnwrap(firstSnapshot))
    }

    private func freePort() throws -> UInt16 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        XCTAssertGreaterThanOrEqual(fd, 0)
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let bindRc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        XCTAssertEqual(bindRc, 0)
        var bound = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameRc = withUnsafeMutablePointer(to: &bound) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) }
        }
        XCTAssertEqual(nameRc, 0)
        return UInt16(bigEndian: bound.sin_port)
    }

    private func makeStore() -> SharedStore {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("casthop-store-\(UUID().uuidString)")
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return SharedStore(directory: dir)
    }

    private func device(_ store: SharedStore) -> DeviceInfo {
        DeviceInfo(name: "Test-iPad", deviceId: store.deviceId, appVersion: "0.4.2", videoCodecs: [Codec.h264],
                   audioCodecs: nil, maxWidth: 1920, maxHeight: 1080, maxFps: 60)
    }

    func testQrPairSpentTokenFallbackAndSameDeviceTakeoverEndToEnd() async throws {
        let portA = try freePort()
        let portB = try freePort()
        let (_, snapA) = try startReceiver(port: portA, name: "ETAP-A")
        let (_, snapB) = try startReceiver(port: portB, name: "ETAP-B")

        let qrA = try QrPayload.parse(snapA.qrUri)
        let qrB = try QrPayload.parse(snapB.qrUri)
        let store = makeStore()
        let dev = device(store)

        // 1. Initial QR pairing via ConnectFlow.pair (flushes BYE cleanly).
        let pairedResult = await ConnectFlow.pair(target: .qr(qrA), device: dev, store: store, nowMs: 1000)
        let remembered: RememberedReceiver
        switch pairedResult {
        case .success(let r): remembered = r
        case .failure(let f): return XCTFail("Initial pair failed: \(f)")
        }

        // 2. Five consecutive one-tap remembered connections succeed on first try.
        for i in 1...5 {
            let res = await ConnectFlow.open(target: .remembered(remembered), device: dev, requestPairing: false, store: store)
            switch res {
            case .success(let s):
                await s.control.sendAndWait(.bye(reason: ByeReason.userStop), timeoutMs: 250)
                s.control.close()
            case .failure(let f):
                return XCTFail("Remembered connect #\(i) failed: \(f)")
            }
        }

        // 3. Unclean session still open + scanning spent QR token from the SAME tablet:
        //    (a) TLS verifies Board A's fingerprint,
        //    (b) Spent QR token triggers silent fallback to stored pairKey,
        //    (c) Same device auto-takes over its own open session without approval!
        let lingering: Session
        switch await ConnectFlow.open(target: .remembered(remembered), device: dev, requestPairing: false, store: store) {
        case .success(let s): lingering = s
        case .failure(let f): return XCTFail("Open lingering session failed: \(f)")
        }
        defer { lingering.control.abort() }

        final class Flag: @unchecked Sendable {
            private let lock = NSLock()
            private var value = false
            func set() { lock.lock(); value = true; lock.unlock() }
            func get() -> Bool { lock.lock(); defer { lock.unlock() }; return value }
        }
        let pendingFired = Flag()
        let spentQrResult = await ConnectFlow.open(target: .qr(qrA), device: dev, requestPairing: true, store: store) { _ in
            pendingFired.set()
        }
        switch spentQrResult {
        case .success(let s):
            XCTAssertFalse(pendingFired.get(), "Same device must take over its own session without approval")
            await s.control.sendAndWait(.bye(reason: ByeReason.userStop), timeoutMs: 250)
            s.control.close()
        case .failure(let f):
            return XCTFail("Spent QR fallback to paired key failed: \(f)")
        }

        // 4. DHCP IP-swap detection: racing Board B's port with Board A's expected fingerprint reports fingerprintMismatch.
        let swapQueue = DispatchQueue(label: "casthop.test.swap")
        let race = await PinnedTLS.connectFirst(hosts: ["127.0.0.1"], port: qrB.port, expected: qrA.certFingerprint,
                                                queue: swapQueue, timeoutMs: 3000)
        switch race {
        case .connected(_, let c):
            c.framed.abort()
            XCTFail("Must never connect to a board with a different certificate fingerprint")
        case .failed(_, let mismatch, _):
            XCTAssertNotNil(mismatch)
        }
    }
}
