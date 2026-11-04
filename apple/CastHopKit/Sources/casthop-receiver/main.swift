import CastHopReceiver
import Foundation

// Headless receiver: the PROTOCOL.md §11 test contract, and a way to run a receiver without the app.

func usage() -> Never {
    let text = """
    usage: casthop-receiver [--headless] [--port N] [--name NAME] [--data-dir DIR] [--pairing-file F] [--stats-file F]
                            [--qr-host HOST]... [--approval-timeout MS] [--no-audio]

    """
    FileHandle.standardError.write(Data(text.utf8))
    exit(2)
}

var options = ReceiverOptions()
options.headless = true
var dataDir: URL?
var args = Array(CommandLine.arguments.dropFirst())

func value() -> String {
    guard !args.isEmpty else { usage() }
    return args.removeFirst()
}

while !args.isEmpty {
    switch args.removeFirst() {
    case "--headless": options.headless = true
    case "--port":
        guard let port = UInt16(value()) else { usage() }
        options.port = port
    case "--name": options.name = value()
    case "--data-dir": dataDir = URL(fileURLWithPath: value())
    case "--pairing-file": options.pairingFile = URL(fileURLWithPath: value())
    case "--stats-file": options.statsFile = URL(fileURLWithPath: value())
    case "--qr-host": options.qrHosts.append(value())
    case "--approval-timeout":
        guard let ms = Int64(value()) else { usage() }
        options.approvalTimeoutMs = ms
    case "--no-audio": options.audio = false
    default: usage()
    }
}

options.dataDirectory = dataDir ?? FileManager.default.homeDirectoryForCurrentUser
    .appendingPathComponent("Library/Application Support/CastHop")

let service = ReceiverService(options: options)
service.onChange = { snapshot in
    guard snapshot.state == .failed else { return }
    FileHandle.standardError.write(Data("casthop-receiver: \(String(describing: snapshot.failure))\n".utf8))
    exit(1)
}
service.start()

signal(SIGTERM, SIG_IGN)
signal(SIGINT, SIG_IGN)
let signalQueue = DispatchQueue(label: "casthop.signals")
var signalSources: [DispatchSourceSignal] = []
for number in [SIGTERM, SIGINT] {
    let source = DispatchSource.makeSignalSource(signal: number, queue: signalQueue)
    source.setEventHandler {
        service.shutdown()
        exit(0)
    }
    source.resume()
    signalSources.append(source)
}
dispatchMain()
