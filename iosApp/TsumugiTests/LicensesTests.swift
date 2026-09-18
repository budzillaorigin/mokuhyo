import Foundation
import Testing
@testable import Tsumugi

/// F-40: the Licenses screen renders docs/LICENSES.md, which the "Bundle Content Packs" build phase copies into the
/// app bundle. The copy tolerates failure (`|| true`), so without this test a build could ship with no attributions,
/// which CC BY-SA data (JMdict, KanjiVG, Tatoeba) does not allow. Unlike the pack tests this one never skips.
@Suite
struct LicensesTests {
    @Test func bundleContainsLicenses() throws {
        let url = try #require(Bundle.main.url(forResource: "LICENSES", withExtension: "md"))
        let text = try String(contentsOf: url, encoding: .utf8)
        #expect(text.hasPrefix("# Third-party licenses"))
        #expect(text.contains("JMdict"))
        #expect(text.contains("KanjiVG"))
        #expect(text.contains("Tatoeba"))
    }
}
