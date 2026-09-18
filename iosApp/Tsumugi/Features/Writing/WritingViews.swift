import Shared
import SwiftUI

/// Drawing surface in KanjiVG units (0…109): optional faded template, accepted strokes in ink, an optional hint
/// stroke, and the stroke in progress. Works with a finger or Apple Pencil.
struct WritingCanvas: View {
    var template: [[CGPoint]] = []
    var showTemplate = false
    var inked: [[CGPoint]] = []
    var hint: [CGPoint]?
    var error = false
    let onStroke: ([CGPoint]) -> Void

    @State private var current: [CGPoint] = []

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            let scale = side / 109
            Canvas { ctx, size in
                var guide = Path()
                guide.move(to: CGPoint(x: size.width / 2, y: 0)); guide.addLine(to: CGPoint(x: size.width / 2, y: size.height))
                guide.move(to: CGPoint(x: 0, y: size.height / 2)); guide.addLine(to: CGPoint(x: size.width, y: size.height / 2))
                ctx.stroke(guide, with: .color(.secondary.opacity(0.3)), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                func draw(_ pts: [CGPoint], _ color: Color, _ width: CGFloat) {
                    guard pts.count > 1 else { return }
                    var p = Path()
                    p.addLines(pts.map { CGPoint(x: $0.x * scale, y: $0.y * scale) })
                    ctx.stroke(p, with: .color(color), style: StrokeStyle(lineWidth: width * scale, lineCap: .round, lineJoin: .round))
                }
                if showTemplate { template.forEach { draw($0, .secondary.opacity(0.25), 6) } }
                inked.forEach { draw($0, .primary, 5) }
                if let hint { draw(hint, .accentColor, 5) }
                draw(current, .primary, 5)
            }
            .frame(width: side, height: side)
            .overlay(Rectangle().stroke(error ? Color.red : Color.secondary.opacity(0.4)))
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { v in current.append(CGPoint(x: v.location.x / scale, y: v.location.y / scale)) }
                    .onEnded { _ in
                        if current.count > 1 { onStroke(current) }
                        current = []
                    }
            )
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityLabel("Writing area")
    }
}

extension Array where Element == CGPoint {
    var kotlin: [Shared.Point] { map { Shared.Point(x: Float($0.x), y: Float($0.y)) } }
}

extension Array where Element == Shared.Point {
    var cgPoints: [CGPoint] { map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) } }
}

/// Skritter-style guided practice (BRIEF §5.7): each stroke is graded as you write it.
struct WritingPracticeView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let kanji: [String]

    @State private var index = 0
    @State private var session: WritingSession?
    @State private var template: [[CGPoint]] = []
    @State private var inked: [[CGPoint]] = []
    @State private var feedback: String?
    @State private var error = false
    @State private var showTemplate = true
    @State private var missing = false
    @State private var done = false

    /// Mirrors WritingSession.HINT_AFTER_FAILURES in shared code.
    private let hintAfterFailures = 3

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if index >= kanji.count {
                    Text("Writing practice done.").font(.headline)
                    Button("Done") { dismiss() }.buttonStyle(.borderedProminent)
                } else {
                    Text("\(index + 1) of \(kanji.count): \(kanji[index])").font(.japanese(size: 22))
                    if missing {
                        Text("No stroke data for \(kanji[index]).")
                    } else {
                        Toggle("Show template", isOn: $showTemplate)
                        WritingCanvas(
                            template: template, showTemplate: showTemplate, inked: inked,
                            hint: hintStroke, error: error
                        ) { stroke in submit(stroke) }
                        if let feedback { Text(feedback).foregroundStyle(error ? .red : .green) }
                    }
                    HStack {
                        Button("Restart") { Task { await load() } }.buttonStyle(.bordered)
                        Button(index < kanji.count - 1 ? "Next kanji" : "Finish") { index += 1 }
                            .buttonStyle(.borderedProminent)
                            .disabled(!(done || missing))
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Writing")
        .task(id: index) { await load() }
    }

    private var hintStroke: [CGPoint]? {
        guard let session, !session.done, session.failuresOnCurrent >= hintAfterFailures else { return nil }
        return session.hint().cgPoints
    }

    private func load() async {
        guard index < kanji.count else { return }
        let writing = try? await app.graph.writing()
        template = ((try? await writing?.template(kanji: kanji[index])) ?? []).map { $0.cgPoints }
        session = try? await writing?.guided(kanji: kanji[index], strictness: .normal)
        missing = session == nil
        inked = []
        feedback = nil
        error = false
        done = false
    }

    private func submit(_ stroke: [CGPoint]) {
        guard let session, !session.done else { return }
        let result = session.submit(stroke: stroke.kotlin)
        error = !result.accepted
        if result.accepted {
            inked.append(template[Int(result.index)])
            done = session.done
            feedback = session.done ? "Done! Rating: \(session.suggestedRating)/4" : nil
        } else {
            var text: String
            switch result.problem {
            case .wrongDirection: text = "Wrong direction"
            case .wrongOrder: text = "Wrong stroke order"
            case .wrongPosition: text = "Right shape, wrong place"
            case .tooShort: text = "Too short"
            default: text = "Not quite — try again"
            }
            if session.failuresOnCurrent >= hintAfterFailures { text += " (hint shown)" }
            feedback = text
        }
    }
}

/// Draw a kanji to find it (BRIEF §5.3).
struct HandwritingSearchView: View {
    @Environment(AppModel.self) private var app
    @State private var strokes: [[CGPoint]] = []
    @State private var candidates: [Candidate] = []

    var body: some View {
        VStack(spacing: 12) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    if candidates.isEmpty { Text("Draw a kanji below").foregroundStyle(.secondary) }
                    ForEach(candidates, id: \.kanji) { c in
                        NavigationLink(value: Route.kanji(c.kanji)) { Text(c.kanji).font(.japanese(size: 30)) }
                            .buttonStyle(.bordered)
                    }
                }
            }
            .frame(height: 56)
            WritingCanvas(inked: strokes) { stroke in
                strokes.append(stroke)
                recognize()
            }
            HStack {
                Button("Undo stroke") { _ = strokes.popLast(); recognize() }.disabled(strokes.isEmpty)
                Button("Clear") { strokes = []; candidates = [] }
                Spacer()
                Text("Strokes: \(strokes.count)").font(.caption)
            }
        }
        .padding()
        .navigationTitle("Draw to search")
        .task { try? await app.graph.writing()?.recognizer.warmUp() }
    }

    private func recognize() {
        let current = strokes.map { $0.kotlin }
        Task {
            guard !current.isEmpty else { candidates = []; return }
            candidates = (try? await app.graph.writing()?.recognizer.recognize(strokes: current, limit: 10)) ?? []
        }
    }
}

/// Raw writing for a WRITING review card: draw from memory, then check.
struct WritingAnswer: View {
    @Environment(AppModel.self) private var app
    let kanji: String
    let onChecked: (RawResult?) -> Void
    @State private var strokes: [[CGPoint]] = []

    var body: some View {
        VStack(spacing: 8) {
            WritingCanvas(inked: strokes) { strokes.append($0) }
            HStack {
                Button("Undo") { _ = strokes.popLast() }.disabled(strokes.isEmpty)
                Button("Check") {
                    let drawn = strokes.map { $0.kotlin }
                    Task { onChecked(try? await app.graph.writing()?.checkRaw(kanji: kanji, strokes: drawn)) }
                }
                .buttonStyle(.borderedProminent)
                .disabled(strokes.isEmpty)
            }
        }
    }
}
