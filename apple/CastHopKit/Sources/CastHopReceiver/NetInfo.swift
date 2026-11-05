import CastHopProtocol
import Foundation
import Network

/// Picks the addresses for the QR: local IPv4 only (PROTOCOL.md §8, §11), wired before Wi-Fi, no virtual interfaces.
public enum AddressSelection {
    public struct Candidate: Equatable, Sendable {
        public var name: String
        public var address: String
        public var wired: Bool

        public init(name: String, address: String, wired: Bool) {
            self.name = name
            self.address = address
            self.wired = wired
        }
    }

    private static let virtualPrefixes = ["docker", "veth", "virbr", "br-", "vmnet", "vboxnet", "lxc", "tun", "tap", "wg",
                                          "utun", "awdl", "llw", "bridge", "gif", "stf", "anpi", "ipsec", "ppp"]

    public static func isVirtual(_ name: String) -> Bool { virtualPrefixes.contains { name.hasPrefix($0) } }

    public static func isPrivate(_ address: String) -> Bool {
        let p = address.split(separator: ".").compactMap { UInt8($0) }
        guard p.count == 4 else { return false }
        return p[0] == 10 || (p[0] == 172 && (16...31).contains(p[1])) || (p[0] == 192 && p[1] == 168)
            || (p[0] == 100 && (64...127).contains(p[1]))
    }

    public static func select(_ candidates: [Candidate]) -> [String] {
        let usable = candidates.filter { !isVirtual($0.name) && isPrivate($0.address) }
        let ordered = usable.filter(\.wired) + usable.filter { !$0.wired }
        var seen = Set<String>()
        return ordered.map(\.address).filter { seen.insert($0).inserted }.prefix(QrPayload.maxHosts).map { $0 }
    }
}

/// Watches the network and reports the QR addresses.
final class NetInfo: @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let queue: DispatchQueue
    private(set) var addresses: [String] = []
    var onChange: (() -> Void)?

    init(queue: DispatchQueue) { self.queue = queue }

    func start() {
        monitor.pathUpdateHandler = { [weak self] path in self?.update(path) }
        monitor.start(queue: queue)
    }

    func stop() { monitor.cancel() }

    private func update(_ path: NWPath) {
        let wired = Set(path.availableInterfaces.filter { $0.type == .wiredEthernet }.map(\.name))
        let fresh = AddressSelection.select(Self.interfaceAddresses(wired: wired))
        guard fresh != addresses else { return }
        addresses = fresh
        onChange?()
    }

    private static func interfaceAddresses(wired: Set<String>) -> [AddressSelection.Candidate] {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0 else { return [] }
        defer { freeifaddrs(head) }
        var out: [AddressSelection.Candidate] = []
        var cursor = head
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let flags = Int32(entry.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0,
                  let sa = entry.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else {
                continue
            }
            let name = String(cString: entry.pointee.ifa_name)
            out.append(.init(name: name, address: String(cString: host), wired: wired.contains(name)))
        }
        return out
    }
}
