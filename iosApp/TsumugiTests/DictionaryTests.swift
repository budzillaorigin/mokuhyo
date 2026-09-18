import Foundation
import Shared
import Testing
@testable import Tsumugi

/// Runs against the dictionary pack bundled into the app (CI builds it; see .github/workflows/ci.yml).
/// Skips when the build has no pack. Serialized: the tests share one graph and one installed pack.
@MainActor
@Suite(.serialized)
struct DictionaryTests {
    private static let graph = AppModel().graph

    private static func repository() async -> DictionaryRepository? {
        try? await graph.dictionary()
    }

    @Test func searchFindsDeinflectedVerb() async throws {
        guard let repo = await Self.repository() else { return }
        let results = try await repo.search(rawQuery: "食べさせられなかった", limit: 40)
        #expect(results.hits.first?.entry.headword == "食べる")
    }

    @Test func englishAndRomajiSearch() async throws {
        guard let repo = await Self.repository() else { return }
        let cat = try await repo.search(rawQuery: "cat", limit: 40)
        #expect(cat.hits.first?.entry.headword == "猫")
        let kanji = try await repo.search(rawQuery: "kanji", limit: 40)
        #expect(kanji.hits.prefix(3).contains { $0.entry.headword == "漢字" })
    }

    @Test func kanjiHasStrokes() async throws {
        guard let repo = await Self.repository() else { return }
        let detail = try await repo.kanji(literal: "語")
        #expect(detail?.strokes.count == 14)
    }

    /// BRIEF §11: dictionary lookup < 5 ms (the budget is for an iPhone 12; simulators on CI are comparable).
    @Test func lookupIsFast() async throws {
        guard let repo = await Self.repository() else { return }
        let words = ["食べる", "たべる", "学校", "がっこう", "行きました", "猫", "漢字", "見られない", "ねこ", "東京"]
        for w in words { _ = try await repo.search(rawQuery: w, limit: 40) }
        let clock = ContinuousClock()
        let elapsed = try await clock.measure {
            for _ in 0..<10 { for w in words { _ = try await repo.search(rawQuery: w, limit: 40) } }
        }
        let perLookup = elapsed / 100
        print("Dictionary lookup: \(perLookup)")
        #expect(perLookup < .milliseconds(5))
    }
}
