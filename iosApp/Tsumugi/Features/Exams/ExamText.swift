import Shared
import SwiftUI

/// Renders exam-bank markup (presentation only):
/// - `<u>…</u>` underlines the target; the tags are never shown.
/// - `| a | b |` lines form a table (header row first); `|---|` separator rows are skipped.
/// - A line that is only `Ａ` or `Ｂ` labels one text of an integrated-reading passage.
/// - Blanks `（　　）`, assembly slots `＿＿＿` / `＿★＿` and markers `［1］` are shown as written.
struct ExamText: View {
    let text: String
    var size: CGFloat = 18

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(ExamMarkup.blocks(text).enumerated()), id: \.offset) { _, block in
                switch block {
                case .text(let paragraph):
                    Text(ExamMarkup.attributed(paragraph))
                        .font(.japanese(size: size))
                        .fixedSize(horizontal: false, vertical: true)
                case .label(let label):
                    Text(label).font(.japanese(size: size, weight: .bold))
                case .table(let rows):
                    ExamTable(rows: rows, size: size)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Inline version for choice buttons (no tables).
func examInline(_ text: String) -> AttributedString {
    ExamMarkup.attributed(text)
}

enum ExamMarkup {
    enum Block {
        case text(String)
        case label(String)
        case table([[String]])
    }

    static func blocks(_ text: String) -> [Block] {
        var blocks: [Block] = []
        var paragraph: [String] = []
        var table: [[String]] = []

        func flushParagraph() {
            if !paragraph.isEmpty {
                blocks.append(.text(paragraph.joined(separator: "\n")))
                paragraph = []
            }
        }
        func flushTable() {
            if !table.isEmpty {
                blocks.append(.table(table))
                table = []
            }
        }

        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.hasPrefix("|") {
                flushParagraph()
                let cells = line.split(separator: "|", omittingEmptySubsequences: false)
                    .dropFirst()
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                let trimmed = cells.last == "" ? Array(cells.dropLast()) : Array(cells)
                let isSeparator = !trimmed.isEmpty && trimmed.allSatisfy { cell in
                    !cell.isEmpty && cell.allSatisfy { $0 == "-" || $0 == ":" }
                }
                if !isSeparator { table.append(trimmed) }
            } else if line == "Ａ" || line == "Ｂ" {
                flushParagraph()
                flushTable()
                blocks.append(.label(line))
            } else {
                flushTable()
                paragraph.append(raw)
            }
        }
        flushParagraph()
        flushTable()
        return blocks
    }

    /// Text with `<u>…</u>` spans underlined and the tags removed.
    static func attributed(_ text: String) -> AttributedString {
        var result = AttributedString()
        var rest = Substring(text)
        while let open = rest.range(of: "<u>") {
            result += AttributedString(String(rest[rest.startIndex..<open.lowerBound]))
            let afterOpen = rest[open.upperBound...]
            if let close = afterOpen.range(of: "</u>") {
                var underlined = AttributedString(String(afterOpen[afterOpen.startIndex..<close.lowerBound]))
                underlined[AttributeScopes.SwiftUIAttributes.UnderlineStyleAttribute.self] = .single
                result += underlined
                rest = afterOpen[close.upperBound...]
            } else {
                rest = afterOpen
            }
        }
        result += AttributedString(String(rest).replacingOccurrences(of: "</u>", with: ""))
        return result
    }
}

private struct ExamTable: View {
    let rows: [[String]]
    let size: CGFloat

    var body: some View {
        let columns = rows.map(\.count).max() ?? 0
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 6) {
                ForEach(Array(rows.enumerated()), id: \.offset) { r, row in
                    GridRow {
                        ForEach(0..<columns, id: \.self) { c in
                            Text(ExamMarkup.attributed(c < row.count ? row[c] : ""))
                                .font(.japanese(size: size - 2, weight: r == 0 ? .semibold : .regular))
                        }
                    }
                    if r == 0 { Divider() }
                }
            }
            .padding(8)
        }
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 8))
    }
}
