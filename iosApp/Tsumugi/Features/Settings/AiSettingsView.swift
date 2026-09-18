import Shared
import SwiftUI

/// Settings → AI (BRIEF §7): the LLM engine (off / on-device / the learner's own server), the model manager,
/// speech-to-text and text-to-speech engines. Nothing here requires a paid or keyed service.
struct AiSettingsView: View {
    @Environment(AppModel.self) private var app

    @State private var loaded = false
    @State private var llm = "NONE"
    @State private var localModelId: String?
    @State private var endpointUrl = ""
    @State private var endpointModel = ""
    @State private var stt = "SYSTEM"
    @State private var localSttModelId: String?
    @State private var sttEndpointUrl = ""
    @State private var tts = "SYSTEM"
    @State private var voicevoxUrl = ""
    @State private var voicevoxSpeaker = 3

    @State private var apiKey = ""
    @State private var hasKey = false
    @State private var probing = false
    @State private var probe: ProbeOutcome?
    @State private var status: String?
    @State private var voice = VoicePlayer()

    private var llmChoices: [EngineChoice] { SwiftSupport.shared.llmChoices() }
    private var sttChoices: [EngineChoice] { SwiftSupport.shared.sttChoices() }
    private var ttsChoices: [EngineChoice] { SwiftSupport.shared.ttsChoices() }

    var body: some View {
        Form {
            if !loaded {
                ProgressView()
            } else {
                statusSection
                llmSection
                if llm == "LOCAL" {
                    ModelListSection(title: "On-device language models", speech: false, selectedId: $localModelId) { save() }
                }
                if llm == "ENDPOINT" {
                    endpointSection
                }
                sttSection
                if stt == "WHISPER_LOCAL" {
                    ModelListSection(title: "On-device speech models", speech: true, selectedId: $localSttModelId) { save() }
                }
                ttsSection
                Section {
                    Text("Device memory: about \(Int(app.graph.ai.deviceRamGb)) GB. Models download over the network once and then run offline; downloads resume where they stopped.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("AI & speech")
        .task { await load() }
    }

    private var statusSection: some View {
        Section {
            if let status {
                Label(status, systemImage: "exclamationmark.triangle").font(.subheadline)
            } else {
                Label("AI is ready.", systemImage: "checkmark.circle").font(.subheadline)
            }
        } footer: {
            Text("Anything a model writes is marked “AI-generated” with the engine's name.")
        }
    }

    private var llmSection: some View {
        Section {
            Picker("Engine", selection: $llm) {
                ForEach(llmChoices, id: \.key) { Text($0.label).tag($0.key) }
            }
            .onChange(of: llm) { _, _ in save() }
            if let choice = llmChoices.first(where: { $0.key == llm }) {
                Text(choice.detail).font(.caption).foregroundStyle(.secondary)
            }
        } header: {
            Text("Language model")
        }
    }

    private var endpointSection: some View {
        Section {
            TextField("Server URL, e.g. http://<lan-ip>:11434/v1", text: $endpointUrl)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { save() }
            SecureField(hasKey ? "API key (saved; type to replace)" : "API key (optional)", text: $apiKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            HStack {
                Button(probing ? "Testing…" : "Test connection") { testConnection() }
                    .disabled(probing || endpointUrl.trimmingCharacters(in: .whitespaces).isEmpty)
                Spacer()
                if hasKey {
                    Button("Remove key", role: .destructive) {
                        app.graph.ai.endpointKey = nil
                        hasKey = false
                    }
                }
            }
            if let probe {
                if let error = probe.error {
                    Text("Couldn't connect: \(error)").font(.caption).foregroundStyle(.red)
                } else if probe.models.isEmpty {
                    Text("Connected, but the server lists no models.").font(.caption)
                } else {
                    Picker("Model", selection: $endpointModel) {
                        if !probe.models.contains(endpointModel) { Text(endpointModel.isEmpty ? "Choose…" : endpointModel).tag(endpointModel) }
                        ForEach(probe.models, id: \.self) { Text($0).tag($0) }
                    }
                    .onChange(of: endpointModel) { _, _ in save() }
                }
            }
            TextField("Model name", text: $endpointModel)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { save() }
        } header: {
            Text("My own server")
        } footer: {
            Text("Any OpenAI-compatible server you run: Ollama (…/v1), LM Studio, llama-server, vLLM. The key is kept in the Keychain and only sent to this server.")
        }
    }

    private var sttSection: some View {
        Section {
            Picker("Speech to text", selection: $stt) {
                ForEach(sttChoices, id: \.key) { Text($0.label).tag($0.key) }
            }
            .onChange(of: stt) { _, _ in save() }
            if let choice = sttChoices.first(where: { $0.key == stt }) {
                Text(choice.detail).font(.caption).foregroundStyle(.secondary)
            }
            if stt == "WHISPER_ENDPOINT" {
                TextField("Whisper server URL, e.g. http://<lan-ip>:8000/v1", text: $sttEndpointUrl)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { save() }
            }
        } header: {
            Text("Speech recognition")
        } footer: {
            Text("Recordings stay on this device unless you choose your own Whisper server. If the system recognizer can't work offline for Japanese, Tsumugi tells you instead of using Apple's servers.")
        }
    }

    private var ttsSection: some View {
        Section {
            Picker("Voice", selection: $tts) {
                ForEach(ttsChoices, id: \.key) { Text($0.label).tag($0.key) }
            }
            .onChange(of: tts) { _, _ in save() }
            if tts == "VOICEVOX" {
                TextField("VOICEVOX URL, e.g. http://<lan-ip>:50021", text: $voicevoxUrl)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { save() }
                Stepper("Speaker ID: \(voicevoxSpeaker)", value: $voicevoxSpeaker, in: 0...200)
                    .onChange(of: voicevoxSpeaker) { _, _ in save() }
            }
            Button(voice.isSpeaking ? "Stop" : "Play a sample") {
                if voice.isSpeaking {
                    voice.stop()
                } else {
                    save()
                    let graph = app.graph
                    Task { await voice.sayPartner("こんにちは。今日もいっしょに練習しましょう。", graph: graph) }
                }
            }
        } header: {
            Text("Text to speech")
        } footer: {
            Text("VOICEVOX is a free engine you run yourself; check each voice's terms of use. If it can't be reached, the system voice is used. Two-voice dialogues always use system voices.")
        }
    }

    private func load() async {
        guard !loaded, let config = try? await app.graph.ai.config() else { return }
        let s = SwiftSupport.shared
        llm = s.llmKey(config: config)
        localModelId = config.localModelId
        endpointUrl = config.endpointUrl
        endpointModel = config.endpointModel
        stt = s.sttKey(config: config)
        localSttModelId = config.localSttModelId
        sttEndpointUrl = config.sttEndpointUrl
        tts = s.ttsKey(config: config)
        voicevoxUrl = config.voicevoxUrl
        voicevoxSpeaker = Int(config.voicevoxSpeaker)
        hasKey = app.graph.ai.endpointKey != nil
        status = try? await app.graph.ai.unavailableReason()
        loaded = true
    }

    private func save() {
        guard loaded else { return }
        let config = SwiftSupport.shared.buildConfig(
            llm: llm, localModelId: localModelId, endpointUrl: endpointUrl, endpointModel: endpointModel,
            stt: stt, localSttModelId: localSttModelId, sttEndpointUrl: sttEndpointUrl,
            tts: tts, voicevoxUrl: voicevoxUrl, voicevoxSpeaker: Int32(voicevoxSpeaker)
        )
        let key = apiKey.trimmingCharacters(in: .whitespaces)
        if !key.isEmpty {
            app.graph.ai.endpointKey = key
            apiKey = ""
            hasKey = true
        }
        let ai = app.graph.ai
        Task {
            try? await ai.save(config: config)
            status = try? await ai.unavailableReason()
        }
    }

    private func testConnection() {
        save()
        let url = endpointUrl.trimmingCharacters(in: .whitespaces)
        let key = app.graph.ai.endpointKey
        let ai = app.graph.ai
        probing = true
        probe = nil
        Task {
            probe = try? await SwiftSupport.shared.probe(ai: ai, url: url, apiKey: key)
            if let models = probe?.models, endpointModel.isEmpty, let first = models.first {
                endpointModel = first
                save()
            }
            probing = false
        }
    }
}

/// Downloadable models of one kind with size, license, RAM needs, recommendation, progress, cancel and delete.
private struct ModelListSection: View {
    @Environment(AppModel.self) private var app
    let title: String
    let speech: Bool
    @Binding var selectedId: String?
    let onSelect: () -> Void

    @State private var models: [ModelInfo] = []
    @State private var recommendedId: String?
    @State private var installed: Set<String> = []
    @State private var partial: [String: Int64] = [:]
    @State private var progress: [String: String] = [:]
    @State private var fraction: [String: Double] = [:]
    @State private var tasks: [String: Task<Void, Never>] = [:]
    @State private var available = true

    var body: some View {
        Section {
            if !available {
                Text("This build has no model list (content/models/manifest.json wasn't bundled).").font(.caption)
            } else if models.isEmpty {
                Text("No models of this kind are listed.").font(.caption)
            }
            ForEach(models, id: \.id) { model in
                row(model)
            }
        } header: {
            Text(title)
        } footer: {
            Text("Downloads are checked with SHA-256. Cancel pauses; downloading again resumes.")
        }
        .task { refresh() }
    }

    @ViewBuilder
    private func row(_ model: ModelInfo) -> some View {
        let isInstalled = installed.contains(model.id)
        let isSelected = selectedId == model.id || (selectedId == nil && model.id == recommendedId)
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(model.name).font(.headline)
                if model.id == recommendedId {
                    Text("Recommended").font(.caption2.weight(.semibold))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.green.opacity(0.18), in: Capsule())
                }
                Spacer()
                if isInstalled && isSelected { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
            }
            Text("\(ByteCountFormatter.string(fromByteCount: model.totalBytes, countStyle: .file)) · needs \(model.minRamGb) GB RAM · \(model.license)")
                .font(.caption).foregroundStyle(.secondary)
            if Double(model.minRamGb) > app.graph.ai.deviceRamGb {
                Text("This device may not have enough memory for it.").font(.caption).foregroundStyle(.orange)
            }
            if let text = progress[model.id] {
                if let f = fraction[model.id] { ProgressView(value: f) }
                Text(text).font(.caption)
            } else if !isInstalled, let bytes = partial[model.id], bytes > 0 {
                Text("Paused at \(ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file))").font(.caption)
            }
            HStack {
                if tasks[model.id] != nil {
                    Button("Cancel") { tasks[model.id]?.cancel() }.buttonStyle(.bordered)
                } else if !isInstalled {
                    Button((partial[model.id] ?? 0) > 0 ? "Resume download" : "Download") { download(model) }.buttonStyle(.borderedProminent)
                }
                if isInstalled && !isSelected {
                    Button("Use this model") {
                        selectedId = model.id
                        onSelect()
                    }
                    .buttonStyle(.borderedProminent)
                }
                Spacer()
                if tasks[model.id] == nil && (isInstalled || (partial[model.id] ?? 0) > 0) {
                    Button("Delete", role: .destructive) { delete(model) }.buttonStyle(.bordered)
                }
            }
            .font(.subheadline)
        }
        .padding(.vertical, 4)
    }

    private func refresh() {
        let ai = app.graph.ai
        guard let manager = ai.models else {
            available = false
            return
        }
        models = SwiftSupport.shared.models(ai: ai, speech: speech)
        recommendedId = SwiftSupport.shared.recommended(ai: ai, speech: speech)?.id
        installed = Set(models.filter { manager.isInstalled(model: $0) }.map(\.id))
        var sizes: [String: Int64] = [:]
        for m in models { sizes[m.id] = manager.bytesOnDisk(model: m) }
        partial = sizes
    }

    private func download(_ model: ModelInfo) {
        guard let manager = app.graph.ai.models else { return }
        let id = model.id
        progress[id] = "Starting…"
        tasks[id] = Task {
            var finished = false
            for await p in manager.download(model: model, progressStepBytes: 1 << 20) {
                switch onEnum(of: p) {
                case .downloading(let d):
                    fraction[id] = d.fraction
                    progress[id] = "\(ByteCountFormatter.string(fromByteCount: d.bytesDone, countStyle: .file)) of \(ByteCountFormatter.string(fromByteCount: d.bytesTotal, countStyle: .file))"
                case .verifying:
                    fraction[id] = nil
                    progress[id] = "Verifying…"
                case .done:
                    finished = true
                    progress[id] = nil
                    fraction[id] = nil
                case .failed(let f):
                    finished = true
                    fraction[id] = nil
                    progress[id] = "Failed: \(f.message)"
                }
            }
            if !finished {
                progress[id] = nil
                fraction[id] = nil
            }
            tasks[id] = nil
            refresh()
            if installed.contains(id) && selectedId == nil && recommendedId != id {
                selectedId = id
                onSelect()
            }
        }
    }

    private func delete(_ model: ModelInfo) {
        let ai = app.graph.ai
        let id = model.id
        if selectedId == id {
            Task { try? await ai.unload() }
        }
        if let error = SwiftSupport.shared.deleteModel(ai: ai, model: model) {
            progress[id] = "Couldn't delete: \(error)"
        } else {
            progress[id] = nil
        }
        refresh()
    }
}
