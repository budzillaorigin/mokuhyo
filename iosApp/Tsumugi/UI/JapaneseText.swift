import Shared
import SwiftUI

extension Font {
    /// Hiragino Sans, so Han-unified characters always render with Japanese glyph shapes. Scales with Dynamic Type.
    static func japanese(size: CGFloat, weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        let name: String
        switch weight {
        case .bold, .heavy, .black: name = "HiraginoSans-W6"
        case .semibold, .medium: name = "HiraginoSans-W5"
        default: name = "HiraginoSans-W3"
        }
        return .custom(name, size: size, relativeTo: style)
    }
}

/// Text with ruby readings above kanji runs.
struct FuriganaText: View {
    let segments: [FuriganaSegment]
    var size: CGFloat = 34
    var showFurigana = true

    var body: some View {
        HStack(alignment: .bottom, spacing: 0) {
            ForEach(Array(segments.enumerated()), id: \.offset) { _, seg in
                VStack(spacing: 0) {
                    if showFurigana {
                        Text(seg.rt ?? " ")
                            .font(.japanese(size: size * 0.42, relativeTo: .caption))
                            .foregroundStyle(.secondary)
                    }
                    Text(seg.ruby).font(.japanese(size: size, relativeTo: .largeTitle))
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(segments.map(\.ruby).joined())
    }
}

/// Pitch-accent diagram: one dot per mora plus the following particle, joined by lines.
struct PitchDiagram: View {
    let reading: String
    let accent: PitchAccent

    private let step: CGFloat = 26

    var body: some View {
        let morae = Mora.shared.split(kana: reading)
        let heights = accent.heights().map { $0.boolValue }
        VStack(alignment: .leading, spacing: 2) {
            Canvas { ctx, _ in
                let points = heights.enumerated().map { i, high in
                    CGPoint(x: step * CGFloat(i) + step / 2, y: high ? 6 : 24)
                }
                var line = Path()
                line.addLines(points)
                ctx.stroke(line, with: .color(.accentColor), lineWidth: 2)
                for (i, p) in points.enumerated() {
                    let dot = Path(ellipseIn: CGRect(x: p.x - 4, y: p.y - 4, width: 8, height: 8))
                    if i == points.count - 1 {
                        ctx.stroke(dot, with: .color(.accentColor), lineWidth: 2)
                    } else {
                        ctx.fill(dot, with: .color(.accentColor))
                    }
                }
            }
            .frame(width: step * CGFloat(heights.count), height: 30)
            HStack(spacing: 0) {
                ForEach(Array(morae.enumerated()), id: \.offset) { _, m in
                    Text(m).font(.japanese(size: 15)).frame(width: step)
                }
            }
        }
        .accessibilityLabel("Pitch accent \(accent.downstep)")
    }
}

struct TagView: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(.tint.opacity(0.15), in: RoundedRectangle(cornerRadius: 6))
    }
}

func jlptLabel(_ level: KotlinInt?) -> String? {
    level.map { "N\($0.intValue)" }
}
