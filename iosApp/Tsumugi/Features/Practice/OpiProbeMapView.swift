import Charts
import Shared
import SwiftUI

/// Swift copy of the shared probe map (`SwiftSupport.opiProbeMap`), so the chart never holds Kotlin objects.
struct ProbeMapData {
    struct Turn: Identifiable {
        let id: Int
        let phaseTitle: String
        let isProbe: Bool
        let isLevelCheck: Bool
        let question: String
        let domain: String
        let target: String
        let targetRank: Int
        let after: String
        let afterRank: Int
        let answerLength: Int
        let outcome: String
    }

    struct Level: Identifiable {
        let level: String
        let rank: Int
        let sustained: Int
        let partial: Int
        let breakdown: Int
        var id: Int { rank }
    }

    let floor: String
    let ceiling: String
    let turns: [Turn]
    let levels: [Level]
    let levelLabels: [String]
    let domains: [String]
    let missingDomains: [String]

    init(_ rows: OpiProbeRows) {
        floor = rows.floor
        ceiling = rows.ceiling
        turns = rows.turns.map { t in
            Turn(
                id: Int(t.index), phaseTitle: t.phaseTitle, isProbe: t.isProbe, isLevelCheck: t.isLevelCheck, question: t.question,
                domain: t.domain, target: t.target, targetRank: Int(t.targetRank), after: t.after, afterRank: Int(t.afterRank),
                answerLength: Int(t.answerLength), outcome: t.outcome
            )
        }
        levels = rows.levels.map { l in
            Level(level: l.level, rank: Int(l.rank), sustained: Int(l.sustained), partial: Int(l.partial), breakdown: Int(l.breakdown))
        }
        levelLabels = rows.levelLabels
        domains = rows.domains
        missingDomains = rows.missingDomains
    }

    func label(rank: Int) -> String {
        rank >= 0 && rank < levelLabels.count ? levelLabels[rank] : ""
    }
}

/// "Level check → probe" after an interview (BRIEF_V2 §6.16, D-229): the working level turn by turn, which turns
/// checked the floor and which probed above it, and where speech broke down. Practice feedback from answer length.
struct OpiProbeMapView: View {
    let data: ProbeMapData

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Level check → probe").font(.headline)
            Text("Where your answers held up and where they broke down, judged from answer length only. It's practice feedback, not a rating.")
                .font(.caption).foregroundStyle(.secondary)
            HStack(spacing: 16) {
                LabeledContent("Floor", value: data.floor.isEmpty ? "–" : "ILR \(data.floor)")
                LabeledContent("Ceiling", value: data.ceiling.isEmpty ? "–" : "ILR \(data.ceiling)")
            }
            .font(.subheadline)
            let rated = data.turns.filter { $0.targetRank >= 0 && $0.outcome != "NOT_RATED" }
            if rated.isEmpty {
                Text("No level checks or probes were answered, so there's nothing to map yet.")
                    .font(.subheadline).foregroundStyle(.secondary)
            } else {
                chart
                legend
            }
            if !data.levels.isEmpty {
                Text("By target level").font(.subheadline.weight(.semibold))
                ForEach(data.levels) { level in
                    HStack {
                        Text(verbatim: "ILR \(level.level)").font(.subheadline.monospacedDigit()).frame(width: 64, alignment: .leading)
                        tally(level)
                    }
                }
            }
            let breakdowns = data.turns.filter { $0.outcome == "BREAKDOWN" }
            if !breakdowns.isEmpty {
                Text("Where it broke down").font(.subheadline.weight(.semibold))
                ForEach(breakdowns) { turn in
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Question \(String(turn.id + 1)) · \(turn.phaseTitle) · ILR \(turn.target)")
                            .font(.caption.weight(.semibold))
                        Text(turn.question).font(.japanese(size: 15)).japaneseSpeech()
                    }
                }
            }
            if !data.domains.isEmpty {
                Text("Topics: \(Self.domainList(data.domains))")
                    .font(.caption)
            }
            if !data.missingDomains.isEmpty {
                Text("Next time, try: \(Self.domainList(data.missingDomains))")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 10))
    }

    private var chart: some View {
        let answered = data.turns.filter { $0.afterRank >= 0 }
        let rated = data.turns.filter { $0.targetRank >= 0 && $0.outcome != "NOT_RATED" }
        let ranks = (answered.map(\.afterRank) + rated.map(\.targetRank))
        let low = max(0, (ranks.min() ?? 0) - 1)
        let high = min(max(0, data.levelLabels.count - 1), (ranks.max() ?? 0) + 1)
        return Chart {
            ForEach(answered) { turn in
                LineMark(x: .value("Turn", turn.id + 1), y: .value("Level", turn.afterRank))
                    .interpolationMethod(.stepEnd)
                    .foregroundStyle(Color.secondary)
            }
            ForEach(rated) { turn in
                PointMark(x: .value("Turn", turn.id + 1), y: .value("Level", turn.targetRank))
                    .symbol(turn.isProbe ? BasicChartSymbolShape.triangle : BasicChartSymbolShape.circle)
                    .symbolSize(80)
                    .foregroundStyle(Self.color(turn.outcome))
            }
        }
        .chartYScale(domain: low...max(low + 1, high))
        .chartYAxis {
            AxisMarks(values: Array(low...max(low + 1, high))) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let rank = value.as(Int.self) { Text(verbatim: data.label(rank: rank)) }
                }
            }
        }
        .chartXAxisLabel(String(localized: "Question"))
        .frame(height: 180)
        .accessibilityLabel(Text("Chart of the working level after each answer"))
    }

    private var legend: some View {
        HStack(spacing: 12) {
            legendItem(color: .green, text: String(localized: "Sustained"))
            legendItem(color: .orange, text: String(localized: "Partial"))
            legendItem(color: .red, text: String(localized: "Breakdown"))
            Label("Probe", systemImage: "triangle.fill").font(.caption2)
        }
        .font(.caption2)
    }

    private func legendItem(color: Color, text: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text)
        }
    }

    private func tally(_ level: ProbeMapData.Level) -> some View {
        HStack(spacing: 8) {
            if level.sustained > 0 { Text(verbatim: "● \(level.sustained)").foregroundStyle(.green) }
            if level.partial > 0 { Text(verbatim: "◐ \(level.partial)").foregroundStyle(.orange) }
            if level.breakdown > 0 { Text(verbatim: "○ \(level.breakdown)").foregroundStyle(.red) }
        }
        .font(.caption.monospacedDigit())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(String(level.sustained)) sustained, \(String(level.partial)) partial, \(String(level.breakdown)) breakdown"))
    }

    /// Domain titles are the shared English names ("Current events"), localized through the string catalog.
    static func domainList(_ titles: [String]) -> String {
        titles.map { String(localized: String.LocalizationValue($0)) }.joined(separator: ", ")
    }

    static func color(_ outcome: String) -> Color {
        switch outcome {
        case "SUSTAINED": .green
        case "PARTIAL": .orange
        case "BREAKDOWN": .red
        default: .secondary
        }
    }
}
