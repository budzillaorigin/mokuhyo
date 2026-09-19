import SwiftUI

/// The onomatopoeia theme glyphs (D-236, iOS D-266): small stroked SVG documents, viewBox 64×64, `currentColor`.
/// iOS has no SVG view, so this parses the few element types and path commands the glyphs use (path, circle, rect,
/// line, ellipse; M L H V C S Q T A Z in both cases; a rotate/translate/scale transform; stroke-dasharray) into
/// SwiftUI paths. Anything else makes [parse] return nil, and the caller shows a system symbol instead, so an
/// unexpected glyph never draws wrong.
struct SvgGlyph {
    struct Element {
        let path: Path
        let dash: [CGFloat]
    }

    let elements: [Element]
    let strokeWidth: CGFloat

    static let viewBox: CGFloat = 64

    static func parse(_ svg: String) -> SvgGlyph? {
        guard let tags = try? NSRegularExpression(pattern: "<(path|circle|rect|line|ellipse|polyline|polygon|text|image|use|g)\\b([^>]*?)/?>") else {
            return nil
        }
        let ns = svg as NSString
        var elements: [Element] = []
        for match in tags.matches(in: svg, range: NSRange(location: 0, length: ns.length)) {
            let name = ns.substring(with: match.range(at: 1))
            let attrs = attributes(ns.substring(with: match.range(at: 2)))
            var path: Path?
            switch name {
            case "path":
                guard let d = attrs["d"] else { return nil }
                path = PathData.parse(d)
            case "circle":
                guard let cx = number(attrs["cx"]), let cy = number(attrs["cy"]), let r = number(attrs["r"]) else { return nil }
                path = Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: 2 * r, height: 2 * r))
            case "ellipse":
                guard let cx = number(attrs["cx"]), let cy = number(attrs["cy"]), let rx = number(attrs["rx"]), let ry = number(attrs["ry"]) else { return nil }
                path = Path(ellipseIn: CGRect(x: cx - rx, y: cy - ry, width: 2 * rx, height: 2 * ry))
            case "rect":
                guard let w = number(attrs["width"]), let h = number(attrs["height"]) else { return nil }
                let rect = CGRect(x: number(attrs["x"]) ?? 0, y: number(attrs["y"]) ?? 0, width: w, height: h)
                let rx = number(attrs["rx"]) ?? number(attrs["ry"]) ?? 0
                let ry = number(attrs["ry"]) ?? rx
                path = rx > 0 ? Path(roundedRect: rect, cornerSize: CGSize(width: rx, height: ry)) : Path(rect)
            case "line":
                var p = Path()
                p.move(to: CGPoint(x: number(attrs["x1"]) ?? 0, y: number(attrs["y1"]) ?? 0))
                p.addLine(to: CGPoint(x: number(attrs["x2"]) ?? 0, y: number(attrs["y2"]) ?? 0))
                path = p
            default:
                return nil // an element these glyphs don't use: fall back rather than draw part of it
            }
            guard var shape = path else { return nil }
            if let t = attrs["transform"] {
                guard let transform = transform(t) else { return nil }
                shape = shape.applying(transform)
            }
            let dash = attrs["stroke-dasharray"].map { numbers($0) } ?? []
            elements.append(Element(path: shape, dash: dash))
        }
        guard !elements.isEmpty else { return nil }
        let width = number(rootAttribute(svg, "stroke-width")) ?? 3
        return SvgGlyph(elements: elements, strokeWidth: width)
    }

    private static func attributes(_ text: String) -> [String: String] {
        guard let re = try? NSRegularExpression(pattern: "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*\"([^\"]*)\"") else { return [:] }
        let ns = text as NSString
        var out: [String: String] = [:]
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            out[ns.substring(with: m.range(at: 1))] = ns.substring(with: m.range(at: 2))
        }
        return out
    }

    /// An attribute of the root `<svg>` element (stroke-width is set there).
    private static func rootAttribute(_ svg: String, _ name: String) -> String? {
        guard let start = svg.range(of: "<svg"), let end = svg.range(of: ">", range: start.upperBound..<svg.endIndex) else { return nil }
        return attributes(String(svg[start.upperBound..<end.lowerBound]))[name]
    }

    private static func number(_ text: String?) -> CGFloat? {
        guard let text, let v = Double(text.trimmingCharacters(in: .whitespaces)) else { return nil }
        return CGFloat(v)
    }

    private static func numbers(_ text: String) -> [CGFloat] {
        text.split(whereSeparator: { $0 == " " || $0 == "," }).compactMap { Double($0).map { CGFloat($0) } }
    }

    /// `rotate(a [cx cy])`, `translate(x [y])` or `scale(sx [sy])`, one function only.
    private static func transform(_ text: String) -> CGAffineTransform? {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard let open = t.firstIndex(of: "("), t.hasSuffix(")") else { return nil }
        let name = String(t[t.startIndex..<open])
        let args = numbers(String(t[t.index(after: open)..<t.index(before: t.endIndex)]))
        switch name {
        case "rotate":
            guard let a = args.first else { return nil }
            let rad = a * .pi / 180
            if args.count >= 3 {
                return CGAffineTransform(translationX: args[1], y: args[2]).rotated(by: rad).translatedBy(x: -args[1], y: -args[2])
            }
            return CGAffineTransform(rotationAngle: rad)
        case "translate":
            guard let x = args.first else { return nil }
            return CGAffineTransform(translationX: x, y: args.count > 1 ? args[1] : 0)
        case "scale":
            guard let x = args.first else { return nil }
            return CGAffineTransform(scaleX: x, y: args.count > 1 ? args[1] : x)
        default:
            return nil
        }
    }
}

/// SVG path data (`d`) to a SwiftUI path.
enum PathData {
    private enum Token {
        case command(Character)
        case number(CGFloat)
    }

    static func parse(_ d: String) -> Path? {
        guard let tokens = tokenize(d) else { return nil }
        var path = Path()
        var i = 0
        var command: Character?
        var current = CGPoint.zero
        var start = CGPoint.zero
        var lastCubic: CGPoint?
        var lastQuad: CGPoint?

        func next() -> CGFloat? {
            guard i < tokens.count, case .number(let v) = tokens[i] else { return nil }
            i += 1
            return v
        }

        while i < tokens.count {
            if case .command(let c) = tokens[i] {
                command = c
                i += 1
            }
            guard let c = command else { return nil }
            let relative = c.isLowercase
            let base = relative ? current : .zero
            switch Character(c.uppercased()) {
            case "M":
                guard let x = next(), let y = next() else { return nil }
                current = CGPoint(x: base.x + x, y: base.y + y)
                path.move(to: current)
                start = current
                command = relative ? "l" : "L" // further pairs are line-tos
                lastCubic = nil
                lastQuad = nil
            case "L":
                guard let x = next(), let y = next() else { return nil }
                current = CGPoint(x: base.x + x, y: base.y + y)
                path.addLine(to: current)
                lastCubic = nil
                lastQuad = nil
            case "H":
                guard let x = next() else { return nil }
                current = CGPoint(x: relative ? current.x + x : x, y: current.y)
                path.addLine(to: current)
                lastCubic = nil
                lastQuad = nil
            case "V":
                guard let y = next() else { return nil }
                current = CGPoint(x: current.x, y: relative ? current.y + y : y)
                path.addLine(to: current)
                lastCubic = nil
                lastQuad = nil
            case "C":
                guard let x1 = next(), let y1 = next(), let x2 = next(), let y2 = next(), let x = next(), let y = next() else { return nil }
                let c1 = CGPoint(x: base.x + x1, y: base.y + y1)
                let c2 = CGPoint(x: base.x + x2, y: base.y + y2)
                current = CGPoint(x: base.x + x, y: base.y + y)
                path.addCurve(to: current, control1: c1, control2: c2)
                lastCubic = c2
                lastQuad = nil
            case "S":
                guard let x2 = next(), let y2 = next(), let x = next(), let y = next() else { return nil }
                let c1 = lastCubic.map { CGPoint(x: 2 * current.x - $0.x, y: 2 * current.y - $0.y) } ?? current
                let c2 = CGPoint(x: base.x + x2, y: base.y + y2)
                current = CGPoint(x: base.x + x, y: base.y + y)
                path.addCurve(to: current, control1: c1, control2: c2)
                lastCubic = c2
                lastQuad = nil
            case "Q":
                guard let x1 = next(), let y1 = next(), let x = next(), let y = next() else { return nil }
                let c1 = CGPoint(x: base.x + x1, y: base.y + y1)
                current = CGPoint(x: base.x + x, y: base.y + y)
                path.addQuadCurve(to: current, control: c1)
                lastQuad = c1
                lastCubic = nil
            case "T":
                guard let x = next(), let y = next() else { return nil }
                let c1 = lastQuad.map { CGPoint(x: 2 * current.x - $0.x, y: 2 * current.y - $0.y) } ?? current
                current = CGPoint(x: base.x + x, y: base.y + y)
                path.addQuadCurve(to: current, control: c1)
                lastQuad = c1
                lastCubic = nil
            case "A":
                guard let rx = next(), let ry = next(), let rotation = next(), let large = next(), let sweep = next(),
                      let x = next(), let y = next() else { return nil }
                let end = CGPoint(x: base.x + x, y: base.y + y)
                arc(&path, from: current, rx: rx, ry: ry, rotation: rotation, large: large != 0, sweep: sweep != 0, to: end)
                current = end
                lastCubic = nil
                lastQuad = nil
            case "Z":
                path.closeSubpath()
                current = start
                command = nil // numbers straight after Z are an error, not an endless loop
                lastCubic = nil
                lastQuad = nil
            default:
                return nil
            }
        }
        return path
    }

    private static func tokenize(_ d: String) -> [Token]? {
        let chars = Array(d)
        var out: [Token] = []
        var i = 0
        while i < chars.count {
            let c = chars[i]
            if "MmLlHhVvCcSsQqTtAaZz".contains(c) {
                out.append(.command(c))
                i += 1
            } else if c == " " || c == "," || c == "\n" || c == "\t" || c == "\r" {
                i += 1
            } else if c == "-" || c == "+" || c == "." || c.isASCII && c.isNumber {
                var text = String(c)
                var seenDot = c == "."
                var seenExp = false
                i += 1
                while i < chars.count {
                    let n = chars[i]
                    if n.isASCII && n.isNumber {
                        text.append(n)
                    } else if n == "." && !seenDot && !seenExp {
                        seenDot = true
                        text.append(n)
                    } else if (n == "e" || n == "E") && !seenExp {
                        seenExp = true
                        text.append(n)
                        if i + 1 < chars.count, chars[i + 1] == "-" || chars[i + 1] == "+" {
                            i += 1
                            text.append(chars[i])
                        }
                    } else {
                        break
                    }
                    i += 1
                }
                guard let v = Double(text) else { return nil }
                out.append(.number(CGFloat(v)))
            } else {
                return nil
            }
        }
        return out
    }

    /// An SVG elliptical arc (endpoint form) as cubic Béziers of at most 90° each (SVG 1.1 implementation notes F.6).
    private static func arc(_ path: inout Path, from p0: CGPoint, rx rxIn: CGFloat, ry ryIn: CGFloat, rotation: CGFloat, large: Bool, sweep: Bool, to p1: CGPoint) {
        if p0 == p1 { return }
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        if rx == 0 || ry == 0 {
            path.addLine(to: p1)
            return
        }
        let phi = rotation * .pi / 180
        let cosPhi = cos(phi)
        let sinPhi = sin(phi)
        let dx = (p0.x - p1.x) / 2
        let dy = (p0.y - p1.y) / 2
        let x1 = cosPhi * dx + sinPhi * dy
        let y1 = -sinPhi * dx + cosPhi * dy
        let lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if lambda > 1 {
            let s = sqrt(lambda)
            rx *= s
            ry *= s
        }
        let rx2 = rx * rx
        let ry2 = ry * ry
        let numerator = max(0, rx2 * ry2 - rx2 * y1 * y1 - ry2 * x1 * x1)
        let denominator = rx2 * y1 * y1 + ry2 * x1 * x1
        var coef = denominator == 0 ? 0 : sqrt(numerator / denominator)
        if large == sweep { coef = -coef }
        let cx1 = coef * rx * y1 / ry
        let cy1 = -coef * ry * x1 / rx
        let cx = cosPhi * cx1 - sinPhi * cy1 + (p0.x + p1.x) / 2
        let cy = sinPhi * cx1 + cosPhi * cy1 + (p0.y + p1.y) / 2

        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let length = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            guard length > 0 else { return 0 }
            var a = acos(max(-1, min(1, (ux * vx + uy * vy) / length)))
            if ux * vy - uy * vx < 0 { a = -a }
            return a
        }

        let ux = (x1 - cx1) / rx
        let uy = (y1 - cy1) / ry
        let vx = (-x1 - cx1) / rx
        let vy = (-y1 - cy1) / ry
        let theta1 = angle(1, 0, ux, uy)
        var delta = angle(ux, uy, vx, vy)
        if !sweep && delta > 0 {
            delta -= 2 * .pi
        } else if sweep && delta < 0 {
            delta += 2 * .pi
        }
        let segments = max(1, Int((abs(delta) / (.pi / 2)).rounded(.up)))
        let step = delta / CGFloat(segments)
        let t = 4.0 / 3.0 * tan(step / 4)

        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            let sx = x * rx
            let sy = y * ry
            return CGPoint(x: cosPhi * sx - sinPhi * sy + cx, y: sinPhi * sx + cosPhi * sy + cy)
        }

        var theta = theta1
        for _ in 0..<segments {
            let theta2 = theta + step
            let c1 = point(cos(theta) - t * sin(theta), sin(theta) + t * cos(theta))
            let c2 = point(cos(theta2) + t * sin(theta2), sin(theta2) - t * cos(theta2))
            path.addCurve(to: point(cos(theta2), sin(theta2)), control1: c1, control2: c2)
            theta = theta2
        }
    }
}

/// Parsed glyphs by theme id, so a list doesn't re-parse on every redraw. Guarded by a lock (callable from any
/// context; `View.body` isn't main-actor isolated on older SDKs).
final class GlyphCache: @unchecked Sendable {
    private static let shared = GlyphCache()
    private let lock = NSLock()
    private var parsed: [String: SvgGlyph] = [:]
    private var failed: Set<String> = []

    static func glyph(id: String, svg: String) -> SvgGlyph? {
        shared.lookup(id: id, svg: svg)
    }

    private func lookup(id: String, svg: String) -> SvgGlyph? {
        lock.lock()
        defer { lock.unlock() }
        if let g = parsed[id] { return g }
        if failed.contains(id) { return nil }
        if let g = SvgGlyph.parse(svg) {
            parsed[id] = g
            return g
        }
        failed.insert(id)
        return nil
    }
}

/// A theme glyph in the current foreground colour, or a system symbol when the SVG can't be parsed.
struct ThemeGlyphView: View {
    let themeId: String
    let svg: String

    var body: some View {
        if let glyph = GlyphCache.glyph(id: themeId, svg: svg) {
            Canvas { context, size in
                let scale = min(size.width, size.height) / SvgGlyph.viewBox
                let offset = CGPoint(x: (size.width - SvgGlyph.viewBox * scale) / 2, y: (size.height - SvgGlyph.viewBox * scale) / 2)
                let transform = CGAffineTransform(translationX: offset.x, y: offset.y).scaledBy(x: scale, y: scale)
                for element in glyph.elements {
                    let style = StrokeStyle(
                        lineWidth: glyph.strokeWidth * scale, lineCap: .round, lineJoin: .round,
                        dash: element.dash.map { $0 * scale }
                    )
                    context.stroke(element.path.applying(transform), with: .foreground, style: style)
                }
            }
            .accessibilityHidden(true)
        } else {
            Image(systemName: Self.fallback(themeId))
                .resizable()
                .scaledToFit()
                .padding(4)
                .accessibilityHidden(true)
        }
    }

    /// One symbol per theme (D-266) for glyphs that don't parse.
    static func fallback(_ theme: String) -> String {
        switch theme {
        case "weather": "cloud.rain"
        case "sounds": "speaker.wave.3"
        case "voice": "bubble.left"
        case "feelings": "heart"
        case "pain": "bolt"
        case "body": "figure.stand"
        case "eating": "fork.knife"
        case "movement": "figure.run"
        case "texture": "hand.point.up.left"
        case "appearance": "eye"
        case "manner": "scribble"
        case "state": "square.stack"
        default: "sparkles"
        }
    }
}
