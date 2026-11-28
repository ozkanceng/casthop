import CastHopProtocol
import XCTest

final class UpdateCheckTests: XCTestCase {
    func testSemVerParsingAndComparison() {
        let v042 = SemVer.parse("0.4.2")!
        let v043 = SemVer.parse("v0.4.3")!
        let v0410 = SemVer.parse("0.4.10")!
        let v043rc = SemVer.parse("0.4.3-rc1")!

        XCTAssertTrue(v043.isNewer(than: v042))
        XCTAssertTrue(v0410.isNewer(than: v043))
        XCTAssertFalse(v042.isNewer(than: v042))
        XCTAssertFalse(v042.isNewer(than: v043))
        XCTAssertFalse(v043rc.isNewer(than: v042))
        XCTAssertTrue(v043.isNewer(than: v043rc))
        XCTAssertNil(SemVer.parse("bad"))
        XCTAssertNil(SemVer.parse("0.4"))
    }

    func testManifestParsingChannelsAndHttpsValidation() {
        let json = """
        {
          "schema": 1,
          "latest": "0.5.0",
          "releaseUrl": "https://github.com/ozkanceng/casthop/releases/latest",
          "notes": { "tr": "Türkçe özet", "en": "English summary" },
          "channels": {
            "macos": { "version": "0.5.1", "url": "https://github.com/ozkanceng/casthop/releases/latest/download/casthop-mac.dmg" },
            "bad": { "version": "0.5.0", "url": "http://insecure.example/a.dmg" }
          }
        }
        """.data(using: .utf8)!

        let mac = UpdateClient.parseManifest(data: json, channel: "macos", currentVersion: "0.4.2", preferTurkish: true)
        XCTAssertEqual(mac?.latestVersion, "0.5.1")
        XCTAssertEqual(mac?.notes, "Türkçe özet")
        XCTAssertEqual(mac?.url.absoluteString, "https://github.com/ozkanceng/casthop/releases/latest/download/casthop-mac.dmg")

        let insecure = UpdateClient.parseManifest(data: json, channel: "bad", currentVersion: "0.4.2", preferTurkish: false)
        XCTAssertEqual(insecure?.url, UpdateClient.fallbackReleaseURL)
        XCTAssertEqual(insecure?.notes, "English summary")
    }

    func testITunesLookupParsing() {
        let json = """
        {
          "resultCount": 1,
          "results": [{
            "version": "0.4.3",
            "trackViewUrl": "https://apps.apple.com/tr/app/casthop/id6740000000?uo=4",
            "releaseNotes": "Bağlantı iyileştirmeleri"
          }]
        }
        """.data(using: .utf8)!

        let rel = UpdateClient.parseITunesLookup(data: json, currentVersion: "0.4.2")
        XCTAssertNotNil(rel)
        XCTAssertEqual(rel?.latestVersion, "0.4.3")
        XCTAssertEqual(rel?.isAppStore, true)
        XCTAssertEqual(rel?.notes, "Bağlantı iyileştirmeleri")

        let spoofed = """
        {
          "resultCount": 1,
          "results": [{
            "version": "0.4.3",
            "trackViewUrl": "https://evil.example/fake"
          }]
        }
        """.data(using: .utf8)!
        XCTAssertNil(UpdateClient.parseITunesLookup(data: spoofed, currentVersion: "0.4.2"))
    }
}
