import Shared
import SwiftUI

/// Localized wording for the shared coverage and difficulty numbers (BRIEF_V2 §6.1, §6.4). The numbers come from
/// `CoverageService` / `DifficultyScorer`; only the sentence is built here, because the shared `summary` is English.
enum CoverageText {
    static func summary(_ c: TextCoverage) -> String {
        let words = Int(c.knownPercent).formatted()
        let kanji = Int(c.kanjiPercent).formatted()
        let missing = Int(SwiftSupport.shared.coverageWordsTo95(coverage: c))
        if missing > 0 {
            return String(localized: "You know \(words)% of the words · \(kanji)% of the kanji · \(missing.formatted()) new words to reach 95%")
        }
        return String(localized: "You know \(words)% of the words · \(kanji)% of the kanji")
    }

    /// "N3 · ILR 1+" ("above N1 · ILR 3").
    static func level(jlpt: Int32, ilr: String) -> String {
        let band = jlpt == 0 ? String(localized: "above N1") : "N\(jlpt)"
        return "\(band) · ILR \(ilr)"
    }

    static func level(_ d: DifficultyScore) -> String { level(jlpt: d.jlpt, ilr: d.ilr) }
}

/// "N3 · ILR 1+ · 42" as a small capsule: the §6.4 difficulty of a text for this learner.
struct DifficultyBadge: View {
    let score: DifficultyScore

    var body: some View {
        Text("\(CoverageText.level(score)) · \(Int(score.score).formatted())")
            .font(.caption2.weight(.semibold).monospacedDigit())
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Self.color(score).opacity(0.15), in: Capsule())
            .foregroundStyle(Self.color(score))
            .accessibilityLabel(Text("Difficulty \(CoverageText.level(score)), score \(Int(score.score).formatted()) of 100"))
    }

    static func color(_ score: DifficultyScore) -> Color {
        switch score.score {
        case ..<25: .green
        case ..<45: .teal
        case ..<60: .orange
        default: .red
        }
    }
}

/// The coverage overlay card: known words and kanji, words to reach 95%, and the difficulty badge.
struct CoverageCard: View {
    let coverage: DocumentCoverage

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("Coverage", systemImage: "chart.bar.doc.horizontal").font(.subheadline.weight(.semibold))
                Spacer()
                DifficultyBadge(score: coverage.difficulty)
            }
            ProgressView(value: min(1, max(0, coverage.coverage.knownRatio)))
                .tint(.green)
            Text(CoverageText.summary(coverage.coverage)).font(.caption)
            if coverage.sampled {
                Text("Measured on the first 250,000 characters.").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityElement(children: .combine)
    }
}

/// Known / learning / new, for word rows.
struct WordStateBadge: View {
    let state: WordState

    var body: some View {
        switch state {
        case .known:
            TagView(String(localized: "Known"))
        case .learning:
            TagView(String(localized: "Learning"))
        default:
            TagView(String(localized: "New"))
        }
    }
}

/// An error message with a Retry button (F-33): no screen fails silently.
struct ErrorRetryView: View {
    let message: String
    let retry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(message, systemImage: "exclamationmark.triangle").font(.subheadline).foregroundStyle(.orange)
            Button("Retry") { retry() }.buttonStyle(.bordered)
        }
    }
}

/// Progress with Cancel for long shared-core work (rule 15).
struct CancellableProgress: View {
    let label: String
    let fraction: Double?
    let cancel: () -> Void

    var body: some View {
        HStack {
            if let fraction {
                ProgressView(value: min(1, max(0, fraction))) { Text(label).font(.caption) }
            } else {
                ProgressView()
                Text(label).font(.caption)
            }
            Button("Cancel") { cancel() }.font(.caption)
        }
    }
}
