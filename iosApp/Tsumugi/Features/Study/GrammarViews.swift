import Shared
import SwiftUI

/// CLAUDE.md rule 10: anything an LLM wrote carries this badge until a human verifies it.
struct AiBadge: View {
    var body: some View { TagView("AI-generated · unreviewed") }
}

struct GrammarLevelsView: View {
    @Environment(AppModel.self) private var app
    @State private var levels: [Int]?

    var body: some View {
        List {
            if let levels {
                if levels.isEmpty {
                    Text("The grammar pack isn't installed in this build.").foregroundStyle(.secondary)
                } else {
                    NavigationLink("Learn new grammar", value: Route.grammarLessons)
                    ForEach(levels.sorted(by: >), id: \.self) { level in
                        NavigationLink("JLPT N\(level)", value: Route.grammarLevel(level))
                    }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Grammar")
        .task {
            let service = try? await app.graph.grammar()
            levels = ((try? await service?.levels()) ?? []).map { $0.intValue }
        }
    }
}

struct GrammarLevelView: View {
    @Environment(AppModel.self) private var app
    let level: Int
    @State private var points: [GrammarPointStatus] = []

    var body: some View {
        List(points, id: \.point.id) { p in
            NavigationLink(value: Route.grammarPoint(p.point.id)) {
                HStack {
                    VStack(alignment: .leading) {
                        Text(p.point.title).font(.japanese(size: 18))
                        Text(p.point.meaning).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if let stage = p.stage { Text(stage.label).font(.caption2) }
                }
            }
        }
        .navigationTitle("N\(level) grammar")
        .task { points = (try? await app.graph.grammar()?.points(jlpt: Int32(level))) ?? [] }
    }
}

struct GrammarPointView: View {
    @Environment(AppModel.self) private var app
    let id: String
    @State private var detail: GrammarPointDetail?

    var body: some View {
        ScrollView {
            if let detail {
                VStack(alignment: .leading, spacing: 12) {
                    GrammarPointContent(point: detail.point, examples: detail.examples)
                    if let stage = detail.stage {
                        Text("In reviews · \(stage.label)").foregroundStyle(.tint)
                    } else {
                        Button("Add to reviews") {
                            Task {
                                try? await app.graph.grammar()?.learn(points: [detail.point])
                                self.detail = try? await app.graph.grammar()?.point(id: id)
                            }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .padding()
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Grammar")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: id) { detail = try? await app.graph.grammar()?.point(id: id) }
    }
}

struct GrammarPointContent: View {
    let point: GrammarPoint
    let examples: [GrammarExample]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(point.title).font(.japanese(size: 34, weight: .semibold, relativeTo: .largeTitle))
            HStack {
                TagView("N\(point.jlpt)")
                if point.source != .verified { AiBadge() }
            }
            Text(point.meaning).font(.headline)
            Text(point.structure).font(.japanese(size: 17)).foregroundStyle(.tint)
            Text(point.nuance)
            if !point.mistakes.isEmpty {
                SectionHeader("Watch out")
                ForEach(point.mistakes, id: \.self) { Text("• \($0)").font(.subheadline) }
            }
            SectionHeader("Examples")
            ForEach(Array(examples.enumerated()), id: \.offset) { _, ex in
                VStack(alignment: .leading, spacing: 2) {
                    (Text(ex.before) + Text(ex.answer).bold().foregroundColor(.accentColor) + Text(ex.after))
                        .font(.japanese(size: 18))
                    HStack {
                        Text(ex.english).font(.caption).foregroundStyle(.secondary)
                        if ex.isAiGenerated { AiBadge() }
                    }
                }
            }
            if examples.contains(where: { !$0.isAiGenerated }) {
                Text("Example sentences from Tatoeba (CC BY 2.0 FR).").font(.caption2).foregroundStyle(.tertiary)
            }
        }
    }
}

/// Grammar lessons: read each point, then add the batch to reviews.
struct GrammarLessonView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    @State private var points: [GrammarPoint]?
    @State private var examples: [GrammarExample] = []
    @State private var index = 0
    @State private var done = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let points {
                    if points.isEmpty {
                        Text("No grammar left to learn (or the grammar pack isn't installed).")
                        Button("OK") { dismiss() }
                    } else if done {
                        Text("Added \(points.count) grammar points to reviews.").font(.headline)
                        Button("Done") { dismiss() }.buttonStyle(.borderedProminent)
                    } else {
                        Text("Grammar \(index + 1) of \(points.count)").font(.caption.weight(.semibold))
                        GrammarPointContent(point: points[index], examples: examples)
                        HStack {
                            if index > 0 { Button("Back") { index -= 1 }.buttonStyle(.bordered) }
                            Button(index < points.count - 1 ? "Next" : "Add to reviews") {
                                if index < points.count - 1 {
                                    index += 1
                                } else {
                                    Task {
                                        try? await app.graph.grammar()?.learn(points: points)
                                        done = true
                                    }
                                }
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                } else {
                    ProgressView()
                }
            }
            .padding()
        }
        .navigationTitle("Grammar lessons")
        .task { points = (try? await app.graph.grammarLessons()) ?? [] }
        .task(id: index) {
            guard let points, index < points.count else { return }
            examples = (try? await app.graph.grammar()?.examples(pointId: points[index].id)) ?? []
        }
        .onChange(of: points?.count) { _, _ in
            guard let points, !points.isEmpty else { return }
            Task { examples = (try? await app.graph.grammar()?.examples(pointId: points[index].id)) ?? [] }
        }
    }
}

/// Sentence building: tap chunks in order.
struct BuildAnswer: View {
    let exercise: GrammarExercise
    let onSubmit: (String) -> Void
    @State private var picked: [Int] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(picked.isEmpty ? "Tap the pieces in order" : picked.map { exercise.chunks[$0] }.joined())
                .font(.japanese(size: 22))
            FlowLayout(spacing: 8) {
                ForEach(Array(exercise.chunks.enumerated()), id: \.offset) { i, chunk in
                    if !picked.contains(i) {
                        Button(chunk) { picked.append(i) }.buttonStyle(.bordered).font(.japanese(size: 18))
                    }
                }
            }
            HStack {
                Button("Undo") { _ = picked.popLast() }.disabled(picked.isEmpty)
                Button("Check") { onSubmit(picked.map { exercise.chunks[$0] }.joined()) }
                    .buttonStyle(.borderedProminent)
                    .disabled(picked.count != exercise.chunks.count)
            }
        }
    }
}

/// Minimal wrapping layout for chips.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += rowHeight + spacing; rowHeight = 0 }
            x += size.width + spacing
            maxX = max(maxX, x)
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: min(maxX, width), height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += rowHeight + spacing; rowHeight = 0 }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}
