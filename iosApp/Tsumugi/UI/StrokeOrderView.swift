import Shared
import SwiftUI

/// Animated KanjiVG stroke order. Completed strokes in the primary colour, the current stroke drawn
/// progressively in the accent colour with a start dot. Tap to replay.
struct StrokeOrderView: View {
    let strokes: [KanjiStroke]
    var secondsPerStroke = 0.55

    @State private var start = Date()

    private var polylines: [[CGPoint]] {
        strokes.map { stroke in
            SvgPath.shared.flatten(d: stroke.path, step: 1.5).map { CGPoint(x: CGFloat($0.x), y: CGFloat($0.y)) }
        }
    }

    var body: some View {
        let lines = polylines
        TimelineView(.animation) { timeline in
            let progress = min(Double(lines.count), timeline.date.timeIntervalSince(start) / secondsPerStroke)
            Canvas { ctx, size in
                let scale = min(size.width, size.height) / 109
                var guide = Path()
                guide.move(to: CGPoint(x: size.width / 2, y: 0)); guide.addLine(to: CGPoint(x: size.width / 2, y: size.height))
                guide.move(to: CGPoint(x: 0, y: size.height / 2)); guide.addLine(to: CGPoint(x: size.width, y: size.height / 2))
                ctx.stroke(guide, with: .color(.secondary.opacity(0.3)), style: StrokeStyle(lineWidth: 1, dash: [4, 4]))

                let done = Int(progress)
                let partial = progress - Double(done)
                for (i, pts) in lines.enumerated() where !pts.isEmpty {
                    let scaled = pts.map { CGPoint(x: $0.x * scale, y: $0.y * scale) }
                    let style = StrokeStyle(lineWidth: 4 * scale, lineCap: .round, lineJoin: .round)
                    if i < done {
                        ctx.stroke(polyline(scaled, count: scaled.count), with: .color(.primary), style: style)
                    } else if i == done && partial > 0 {
                        let n = max(1, Int(Double(scaled.count) * partial))
                        ctx.stroke(polyline(scaled, count: n), with: .color(.accentColor), style: style)
                        let s = scaled[0]
                        ctx.fill(Path(ellipseIn: CGRect(x: s.x - 3.5 * scale, y: s.y - 3.5 * scale, width: 7 * scale, height: 7 * scale)), with: .color(.accentColor))
                    } else {
                        ctx.stroke(polyline(scaled, count: scaled.count), with: .color(.secondary.opacity(0.25)), style: style)
                    }
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .overlay(Rectangle().stroke(.secondary.opacity(0.3)))
        .contentShape(Rectangle())
        .onTapGesture { start = Date() }
        .accessibilityLabel("Stroke order, \(strokes.count) strokes. Double-tap to replay.")
    }

    private func polyline(_ pts: [CGPoint], count: Int) -> Path {
        var p = Path()
        p.addLines(Array(pts.prefix(count)))
        return p
    }
}
