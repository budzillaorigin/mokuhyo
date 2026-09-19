import Shared
import SwiftUI
import UniformTypeIdentifiers

/// Settings → Audio packs (BRIEF_V2 §5.6, rule 20, D-095/D-096): the pre-rendered VOICEVOX audio installed on this
/// device, with sizes and the voice credits they owe; install from Files or from a server the learner types in (no
/// default URL, D-096), with progress and Cancel (rule 15); remove. Without a pack, the app uses the system voice.
struct AudioPacksView: View {
    /// Device-local (rule 16): the folder URL holding `audio-manifest.json`.
    static let baseUrlKey = "audio.packsUrl"

    @Environment(AppModel.self) private var app
    @State private var installed: [AudioPackRow] = []
    @State private var setIds: [String] = []
    @State private var picking = false
    @State private var baseUrl = ""
    @State private var offered: [AudioManifestRow]?
    @State private var checking = false
    @State private var task: Task<Void, Never>?
    @State private var progress: AudioProgressRow?
    @State private var working: String?
    @State private var message: String?
    @State private var failure: String?
    @State private var retry: (() -> Void)?

    var body: some View {
        Form {
            Section {
                ForEach(setIds, id: \.self) { id in
                    packRow(id)
                }
            } header: {
                Text("Installed")
            } footer: {
                Text("Exam listening, dialogues, minimal pairs, the pitch test and grammar examples play these recordings when they are installed. Anything else uses your device's Japanese voice.")
            }
            if let working {
                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(working).font(.subheadline)
                        if let progress {
                            if progress.bytesTotal > 0 {
                                ProgressView(value: min(1, max(0, progress.fraction))) {
                                    Text(phaseLabel(progress.phase)).font(.caption)
                                }
                                Text("\(bytes(progress.bytesDone)) of \(bytes(progress.bytesTotal))")
                                    .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                            } else {
                                ProgressView { Text(phaseLabel(progress.phase)).font(.caption) }
                                Text(bytes(progress.bytesDone)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
                            }
                        } else {
                            ProgressView()
                        }
                        Button("Cancel", role: .cancel) { cancel() }
                    }
                }
            }
            if let message {
                Section { Label(message, systemImage: "checkmark.circle").foregroundStyle(.green) }
            }
            if let failure {
                Section {
                    Text(failure).foregroundStyle(.red).font(.subheadline)
                    if let retry { Button("Retry") { retry() } }
                }
            }
            Section {
                Button {
                    picking = true
                } label: {
                    Label("Install from Files", systemImage: "folder")
                }
                .disabled(working != nil)
            } footer: {
                Text("Pick an audio-<set>.zip that you copied to this device (iCloud Drive, On My iPhone, a network share). It is checked before it replaces anything.")
            }
            Section {
                TextField("https://…/audio/", text: $baseUrl)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                Button(checking ? "Checking…" : "Show available packs") { check() }
                    .disabled(checking || working != nil || baseUrl.trimmingCharacters(in: .whitespaces).isEmpty)
                if let offered {
                    if offered.isEmpty {
                        Text("That server lists no audio packs this version understands.").font(.caption).foregroundStyle(.secondary)
                    }
                    ForEach(offered, id: \.setId) { row in
                        offeredRow(row)
                    }
                }
            } header: {
                Text("Download from a server")
            } footer: {
                Text("The folder that holds audio-manifest.json on a server you run (a NAS, your sync server, any static file host). There is no built-in address. Downloads are checked against the manifest's checksum. This setting stays on this device.")
            }
            Section {
                Text("Voices: VOICEVOX. The credit lines each installed pack requires are listed with the pack, and all of them are on the Licenses screen.")
                    .font(.caption)
                NavigationLink("Licenses", value: Route.licenses)
            }
        }
        .navigationTitle("Audio packs")
        .task { await load() }
        .task { for await p in app.graph.audio.progress { progress = p.map { SwiftSupport.shared.audioProgress(progress: $0) } } }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.zip, .data]) { result in
            guard case .success(let url) = result else { return }
            installPicked(url)
        }
        .onDisappear { task?.cancel() }
    }

    @ViewBuilder
    private func packRow(_ id: String) -> some View {
        let pack = installed.first { $0.setId == id }
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(Self.setName(id)).font(.body.weight(.medium))
                Spacer()
                if let pack {
                    Text(bytes(pack.bytesOnDisk)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                } else {
                    Text("Not installed").font(.caption).foregroundStyle(.secondary)
                }
            }
            if let pack {
                Text("\(Int(pack.clips).formatted()) clips · version \(pack.version)").font(.caption).foregroundStyle(.secondary)
                if !pack.credits.isEmpty {
                    Text(pack.credits.joined(separator: " · ")).font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
        .swipeActions {
            if pack != nil {
                Button("Remove", role: .destructive) { remove(id) }
            }
        }
        .contextMenu {
            if pack != nil {
                Button("Remove", role: .destructive) { remove(id) }
            }
        }
    }

    @ViewBuilder
    private func offeredRow(_ row: AudioManifestRow) -> some View {
        let upToDate = row.installedVersion == row.version
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.setName(row.setId))
                Text("\(bytes(row.bytes)) · \(Int(row.clips).formatted()) clips").font(.caption).foregroundStyle(.secondary)
                if row.installedVersion != nil && !upToDate {
                    Text("Update available").font(.caption2).foregroundStyle(.orange)
                }
            }
            Spacer()
            if upToDate {
                Label("Installed", systemImage: "checkmark.circle").labelStyle(.iconOnly).foregroundStyle(.green)
                    .accessibilityLabel(Text("Installed"))
            } else {
                Button(row.installedVersion == nil ? "Download" : "Update") { download(row) }
                    .buttonStyle(.bordered)
                    .disabled(working != nil)
            }
        }
    }

    static func setName(_ id: String) -> String {
        switch id {
        case "exam": String(localized: "Exam listening")
        case "dialogues": String(localized: "Dialogues")
        case "minimal-pairs": String(localized: "Minimal pairs")
        case "pitch": String(localized: "Pitch accent test")
        case "grammar": String(localized: "Grammar examples")
        default: id
        }
    }

    private func phaseLabel(_ phase: String) -> String {
        switch phase {
        case "DOWNLOADING": String(localized: "Downloading…")
        case "COPYING": String(localized: "Copying…")
        default: String(localized: "Unpacking…")
        }
    }

    private func bytes(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: n, countStyle: .file) }

    private func load() async {
        let graph = app.graph
        setIds = SwiftSupport.shared.audioSetIds()
        // A few small JSON files; still read off the main actor (rule 15).
        installed = await Task.detached(priority: .userInitiated) { SwiftSupport.shared.audioPacks(graph: graph) }.value
        if baseUrl.isEmpty {
            baseUrl = (try? await graph.deviceSettings.get(key: Self.baseUrlKey)) ?? ""
        }
    }

    private func begin(_ label: String) {
        working = label
        message = nil
        failure = nil
        retry = nil
        progress = nil
    }

    private func cancel() {
        task?.cancel()
        task = nil
        working = nil
        progress = nil
    }

    /// Copies the picked archive out of its security scope (off the main actor), then installs the copy.
    private func installPicked(_ url: URL) {
        begin(String(localized: "Installing \(url.lastPathComponent)…"))
        let graph = app.graph
        task = Task {
            let temp = FileManager.default.temporaryDirectory.appendingPathComponent("audio-\(UUID().uuidString).zip")
            defer { try? FileManager.default.removeItem(at: temp) }
            do {
                try await Task.detached(priority: .userInitiated) {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    try FileManager.default.copyItem(at: url, to: temp)
                }.value
                try Task.checkCancellation()
                let row = try await SwiftSupport.shared.installAudioFile(graph: graph, path: temp.path)
                message = String(localized: "Installed \(Self.setName(row.setId)): \(Int(row.clips).formatted()) clips.")
            } catch is CancellationError {
                message = nil
            } catch {
                if !Task.isCancelled {
                    failure = String(localized: "Couldn't install that file: \(error.localizedDescription)")
                    retry = { installPicked(url) }
                }
            }
            working = nil
            task = nil
            await load()
        }
    }

    private func check() {
        let url = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !url.isEmpty else { return }
        checking = true
        failure = nil
        retry = nil
        let graph = app.graph
        Task {
            try? await graph.deviceSettings.put(key: Self.baseUrlKey, value: url)
            do {
                offered = try await SwiftSupport.shared.fetchAudioManifest(graph: graph, baseUrl: url)
            } catch {
                offered = nil
                failure = String(localized: "Couldn't read the pack list: \(error.localizedDescription)")
                retry = { check() }
            }
            checking = false
        }
    }

    private func download(_ row: AudioManifestRow) {
        let url = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        begin(String(localized: "Downloading \(Self.setName(row.setId))…"))
        let graph = app.graph
        task = Task {
            do {
                let pack = try await SwiftSupport.shared.downloadAudioPack(graph: graph, baseUrl: url, row: row)
                message = String(localized: "Installed \(Self.setName(pack.setId)): \(Int(pack.clips).formatted()) clips.")
            } catch is CancellationError {
                message = nil
            } catch {
                if !Task.isCancelled {
                    failure = String(localized: "Download failed: \(error.localizedDescription)")
                    retry = { download(row) }
                }
            }
            working = nil
            task = nil
            await load()
            if offered != nil, let fresh = try? await SwiftSupport.shared.fetchAudioManifest(graph: graph, baseUrl: url) {
                offered = fresh
            }
        }
    }

    private func remove(_ id: String) {
        let graph = app.graph
        Task {
            do {
                try await SwiftSupport.shared.removeAudioPack(graph: graph, setId: id)
                message = String(localized: "Removed \(Self.setName(id)). The system voice is used instead.")
            } catch {
                failure = String(localized: "Couldn't remove it: \(error.localizedDescription)")
            }
            await load()
        }
    }
}
