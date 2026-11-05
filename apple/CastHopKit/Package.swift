// swift-tools-version: 6.0
import PackageDescription

let swift5: [SwiftSetting] = [.swiftLanguageMode(.v5)]

let package = Package(
    name: "CastHopKit",
    defaultLocalization: "en",
    platforms: [.macOS(.v14), .iOS(.v17), .tvOS(.v17)],
    products: [
        .library(name: "CastHopProtocol", targets: ["CastHopProtocol"]),
        .library(name: "CastHopTransport", targets: ["CastHopTransport"]),
        .library(name: "CastHopSender", targets: ["CastHopSender"]),
        .library(name: "CastHopReceiver", targets: ["CastHopReceiver"]),
        .executable(name: "casthop-receiver", targets: ["casthop-receiver"]),
        .executable(name: "casthop-sender", targets: ["casthop-sender"]),
    ],
    targets: [
        .target(name: "CastHopProtocol", swiftSettings: swift5),
        .target(name: "CastHopTransport", dependencies: ["CastHopProtocol"], swiftSettings: swift5),
        .target(name: "CastHopSender", dependencies: ["CastHopProtocol", "CastHopTransport"], swiftSettings: swift5),
        .target(name: "CastHopReceiver", dependencies: ["CastHopProtocol", "CastHopTransport"], swiftSettings: swift5),
        .executableTarget(name: "casthop-receiver", dependencies: ["CastHopReceiver"], swiftSettings: swift5),
        .executableTarget(name: "casthop-sender", dependencies: ["CastHopSender"], swiftSettings: swift5),
        .testTarget(name: "CastHopProtocolTests", dependencies: ["CastHopProtocol"], swiftSettings: swift5),
        .testTarget(name: "CastHopSenderTests", dependencies: ["CastHopSender"], swiftSettings: swift5),
        .testTarget(name: "CastHopReceiverTests", dependencies: ["CastHopReceiver", "CastHopSender"], swiftSettings: swift5),
    ]
)
