import Shared
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Me → Export (G-10): the review log as CSV, a PDF study report, and a JSON backup that restores by merging
/// (D-116: never deletes a review or lowers a level). Files are written off the main actor and shared with the system
/// share sheet; nothing leaves the device unless the learner sends it somewhere.
struct ExportView: View {
    @Environment(AppModel.self) private var app

    @State private var csv: URL?
    @State private var pdf: URL?
    @State private var backup: URL?
    @State private var busy: String?
    @State private var message: String?
    @State private var failed: (label: String, retry: () -> Void)?
    @State private var reportDays = 30
    @State private var restoring = false
    @State private var restoreProgress: Double?
    @State private var work: Task<Void, Never>?

    var body: some View {
        Form {
            if let busy {
                Section {
                    HStack {
                        ProgressView()
                        Text(busy)
                        Spacer()
                        Button("Cancel") { work?.cancel(); work = nil; self.busy = nil }
                    }
                    if let restoreProgress { ProgressView(value: restoreProgress) }
                }
            }
            if let message { Section { Text(message).font(.subheadline) } }
            if let failed {
                Section {
                    Label(failed.label, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button("Retry") { failed.retry() }
                }
            }
            Section {
                Button("Export reviews as CSV") { exportCsv() }.disabled(busy != nil)
                if let csv { ShareLink(item: csv) { Label("Share CSV", systemImage: "square.and.arrow.up") } }
            } header: {
                Text("Review log")
            } footer: {
                Text("Every answer you've given, oldest first, UTF-8 so spreadsheets show the Japanese.")
            }
            Section {
                Picker("Period", selection: $reportDays) {
                    Text("7 days").tag(7)
                    Text("30 days").tag(30)
                    Text("90 days").tag(90)
                    Text("1 year").tag(365)
                }
                Button("Create PDF report") { exportPdf() }.disabled(busy != nil)
                if let pdf { ShareLink(item: pdf) { Label("Share PDF", systemImage: "square.and.arrow.up") } }
            } header: {
                Text("Study report")
            } footer: {
                Text("Streak, study days, accuracy, items by stage and kind, exam trend and daily reviews.")
            }
            Section {
                Button("Export backup (JSON)") { exportBackup() }.disabled(busy != nil)
                if let backup { ShareLink(item: backup) { Label("Share backup", systemImage: "square.and.arrow.up") } }
                Button("Restore from backup…") { restoring = true }.disabled(busy != nil)
            } header: {
                Text("Backup")
            } footer: {
                Text("A restore merges the backup into what's here: nothing is deleted, no review is lost and no level goes down. Restoring the same file twice changes nothing.")
            }
            Section {
                NavigationLink("Anki (.apkg) and other imports", value: Route.importExport)
            }
        }
        .navigationTitle("Export")
        .onDisappear { work?.cancel() }
        .fileImporter(isPresented: $restoring, allowedContentTypes: [.json, .data]) { result in
            guard case .success(let url) = result else { return }
            restore(url)
        }
    }

    private func run(_ label: String, _ block: @escaping () async throws -> String) {
        busy = label
        message = nil
        failed = nil
        work = Task {
            do {
                message = try await block()
            } catch {
                if !Task.isCancelled {
                    failed = (label: String(localized: "\(label) failed: \(error.localizedDescription)"), retry: { run(label, block) })
                }
            }
            busy = nil
            restoreProgress = nil
        }
    }

    private static func tempFile(_ name: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        return url
    }

    private func exportCsv() {
        let graph = app.graph
        run(String(localized: "Exporting reviews")) {
            let url = Self.tempFile("tsumugi-reviews.csv")
            let count = try await SwiftSupport.shared.exportReviewCsv(graph: graph, outPath: url.path)
            csv = url
            return String(localized: "Exported \(Int(truncating: count)) reviews.")
        }
    }

    private func exportBackup() {
        let graph = app.graph
        run(String(localized: "Exporting backup")) {
            let url = Self.tempFile("tsumugi-backup.json")
            let rows = try await SwiftSupport.shared.exportBackup(graph: graph, outPath: url.path)
            backup = url
            return String(localized: "Backup written: \(Int(truncating: rows)) rows.")
        }
    }

    private func exportPdf() {
        let graph = app.graph
        let days = reportDays
        run(String(localized: "Creating the report")) {
            let report = try await graph.studyReport(periodDays: Int32(days))
            let url = Self.tempFile("tsumugi-report.pdf")
            let model = PdfReport.Model(report)
            try await Task.detached(priority: .userInitiated) {
                try PdfReport.render(model).write(to: url, options: .atomic)
            }.value
            pdf = url
            return String(localized: "Report ready.")
        }
    }

    private func restore(_ picked: URL) {
        let target = Self.tempFile("restore.json")
        let scoped = picked.startAccessingSecurityScopedResource()
        defer { if scoped { picked.stopAccessingSecurityScopedResource() } }
        do {
            try FileManager.default.copyItem(at: picked, to: target)
        } catch {
            failed = (label: String(localized: "Couldn't read the file: \(error.localizedDescription)"), retry: { restoring = true })
            return
        }
        let graph = app.graph
        restoreProgress = 0
        run(String(localized: "Restoring")) {
            let result = try await graph.restoreBackup(path: target.path) { p in
                let fraction = p.total > 0 ? Double(p.done) / Double(p.total) : 0
                Task { @MainActor in restoreProgress = fraction }
            }
            return String(localized: "Restored: \(Int(result.applied)) rows merged, \(Int(result.unchanged)) already up to date, \(Int(result.cardsRecomputed)) cards rescheduled.")
        }
    }
}

/// The study report as a PDF (UIGraphicsPDFRenderer): the shared report's sections in order, then a bar chart of
/// daily reviews. A value-type copy of the report is made on the main actor so rendering can run detached.
enum PdfReport {
    struct Model: Sendable {
        let periodDays: Int
        let sections: [(title: String, rows: [(String, String)])]
        let daily: [Int]

        init(_ report: StudyReport) {
            periodDays = Int(report.periodDays)
            sections = report.sections.map { s in (title: s.title, rows: s.rows.map { ($0.label, $0.value) }) }
            daily = report.daily.map { Int($0.count) }
        }
    }

    static func render(_ model: Model) -> Data {
        let page = CGRect(x: 0, y: 0, width: 595, height: 842) // A4 in points
        let margin: CGFloat = 48
        let width = page.width - margin * 2
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        let title: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 22)]
        let heading: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 14)]
        let body: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 11)]
        let caption: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 9), .foregroundColor: UIColor.darkGray]

        return renderer.pdfData { ctx in
            var y = margin
            func newPageIfNeeded(_ needed: CGFloat) {
                if y + needed > page.height - margin {
                    ctx.beginPage()
                    y = margin
                }
            }
            func draw(_ text: String, _ attrs: [NSAttributedString.Key: Any], x: CGFloat = margin, w: CGFloat = width) -> CGFloat {
                let s = NSAttributedString(string: text, attributes: attrs)
                let h = ceil(s.boundingRect(with: CGSize(width: w, height: .greatestFiniteMagnitude), options: [.usesLineFragmentOrigin], context: nil).height)
                s.draw(with: CGRect(x: x, y: y, width: w, height: h), options: [.usesLineFragmentOrigin], context: nil)
                return h
            }

            ctx.beginPage()
            y += draw(String(localized: "Tsumugi study report"), title) + 4
            let date = Date().formatted(date: .long, time: .omitted)
            y += draw(String(localized: "\(date) · last \(model.periodDays) days"), caption) + 16

            for section in model.sections {
                newPageIfNeeded(40)
                y += draw(section.title, heading) + 6
                for (label, value) in section.rows {
                    newPageIfNeeded(18)
                    let h1 = draw(label, body, w: width * 0.62)
                    let h2 = draw(value, body, x: margin + width * 0.64, w: width * 0.36)
                    y += max(h1, h2) + 3
                }
                y += 12
            }

            if !model.daily.isEmpty {
                let chartHeight: CGFloat = 120
                newPageIfNeeded(chartHeight + 40)
                y += draw(String(localized: "Reviews per day"), heading) + 8
                let maxCount = CGFloat(max(1, model.daily.max() ?? 1))
                let barWidth = width / CGFloat(model.daily.count)
                UIColor.systemIndigo.setFill()
                for (i, count) in model.daily.enumerated() where count > 0 {
                    let h = chartHeight * CGFloat(count) / maxCount
                    UIBezierPath(rect: CGRect(x: margin + CGFloat(i) * barWidth, y: y + chartHeight - h, width: max(1, barWidth * 0.8), height: h)).fill()
                }
                UIColor.lightGray.setStroke()
                let axis = UIBezierPath()
                axis.move(to: CGPoint(x: margin, y: y + chartHeight))
                axis.addLine(to: CGPoint(x: margin + width, y: y + chartHeight))
                axis.stroke()
                y += chartHeight + 4
                y += draw(String(localized: "Oldest on the left; the tallest bar is \(Int(maxCount)) reviews."), caption)
            }
        }
    }
}
