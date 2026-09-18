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
    /// One key per endpoint (F-12, rule 14): the STT and TTS servers never receive the LLM key.
    @State private var sttKey = ""
    @State private var hasSttKey = false
    @State private var ttsKey = ""
    @State private var hasTtsKey = false
    @State private var probing = false
    @State private var probe: ProbeOutcome?
    @State private var voicevoxProbing = false
    @State private var voicevoxProbe: String?
    @State private var voicevoxOk = false
    @State private var status: String?
    @State private var loadError: String?
    @State private var voice = VoicePlayer()

    private var llmChoices: [EngineChoice] { SwiftSupport.shared.llmChoices() }
    private var sttChoices: [EngineChoice] { SwiftSupport.shared.sttChoices() }
    private var ttsChoices: [EngineChoice] { SwiftSupport.shared.ttsChoices() }

    var body: some View {
        Form {
            if let loadError {
                Section {
                    Label("Couldn't load AI settings: \(loadError)", systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.red)
                    Button("Retry") { Task { await load() } }
                }
            } else if !loaded {
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
                SecureField(hasSttKey ? "Whisper API key (saved; type to replace)" : "Whisper API key (optional)", text: $sttKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { save() }
                if hasSttKey {
                    Button("Remove Whisper key", role: .destructive) {
                        app.graph.ai.sttEndpointKey = nil
                        hasSttKey = false
                    }
                }
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
                SecureField(hasTtsKey ? "VOICEVOX key (saved; type to replace)" : "VOICEVOX key (optional, for a proxy)", text: $ttsKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { save() }
                HStack {
                    Button(voicevoxProbing ? "Testing…" : "Test") { testVoicevox() }
                        .disabled(voicevoxProbing || voicevoxUrl.trimmingCharacters(in: .whitespaces).isEmpty)
                    Spacer()
                    if hasTtsKey {
                        Button("Remove key", role: .destructive) {
                            app.graph.ai.ttsEndpointKey = nil
                            hasTtsKey = false
                        }
                    }
                }
                if let voicevoxProbe {
                    Text(voicevoxProbe).font(.caption).foregroundStyle(voicevoxOk ? Color.green : Color.red)
                }
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
        guard !loaded else { return }
        loadError = nil
        let config: AiConfig
        do {
            config = try await app.graph.ai.config()
        } catch {
            loadError = error.localizedDescription
            return
        }
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
        hasSttKey = app.graph.ai.sttEndpointKey != nil
        hasTtsKey = app.graph.ai.ttsEndpointKey != nil
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
        let whisperKey = sttKey.trimmingCharacters(in: .whitespaces)
        if !whisperKey.isEmpty {
            app.graph.ai.sttEndpointKey = whisperKey
            sttKey = ""
            hasSttKey = true
        }
        let voicevoxKey = ttsKey.trimmingCharacters(in: .whitespaces)
        if !voicevoxKey.isEmpty {
            app.graph.ai.ttsEndpointKey = voicevoxKey
            ttsKey = ""
            hasTtsKey = true
        }
        let ai = app.graph.ai
        Task {
            try? await ai.save(config: config)
            status = try? await ai.unavailableReason()
        }
    }

    /// VOICEVOX "Test": `GET /version` within 3 s (D-050), with the TTS key only.
    private func testVoicevox() {
        save()
        let url = voicevoxUrl.trimmingCharacters(in: .whitespaces)
        let ai = app.graph.ai
        voicevoxProbing = true
        voicevoxProbe = nil
        Task {
            do {
                let ok = try await ai.probeVoicevox(url: url).boolValue
                voicevoxOk = ok
                voicevoxProbe = ok
                    ? String(localized: "VOICEVOX answered. Tap “Play a sample” to hear it.")
                    : String(localized: "No VOICEVOX engine answered at that address within 3 seconds.")
            } catch {
                voicevoxOk = false
                voicevoxProbe = String(localized: "Couldn't reach VOICEVOX: \(error.localizedDescription)")
            }
            voicevoxProbing = false
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
    @State private var partial: Set<String> = []
    @State private var note: [String: String] = [:]
    @State private var available = true
    private var downloads: ModelDownloads { ModelDownloads.shared }

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
            Text("Downloads continue in the background and are checked with SHA-256. Cancel pauses; downloading again resumes.")
        }
        .task { refresh() }
        .onChange(of: downloads.installCount) { _, _ in
            refresh()
            if let id = downloads.lastInstalled, installed.contains(id), models.contains(where: { $0.id == id }),
               selectedId == nil, recommendedId != id {
                selectedId = id
                onSelect()
            }
        }
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
            let state = downloads.status[model.id]
            let active = state?.active == true
            if let state {
                if let f = state.fraction { ProgressView(value: f) }
                Text(state.text).font(.caption).foregroundStyle(state.failed ? Color.red : Color.primary)
            } else if let text = note[model.id] {
                Text(text).font(.caption)
            } else if !isInstalled, partial.contains(model.id) {
                Text("Paused").font(.caption)
            }
            HStack {
                if active {
                    Button("Cancel") { downloads.pause(model.id) }.buttonStyle(.bordered)
                } else if !isInstalled {
                    Button(partial.contains(model.id) ? "Resume download" : "Download") { download(model) }.buttonStyle(.borderedProminent)
                }
                if isInstalled && !isSelected {
                    Button("Use this model") {
                        selectedId = model.id
                        onSelect()
                    }
                    .buttonStyle(.borderedProminent)
                }
                Spacer()
                if !active && (isInstalled || partial.contains(model.id)) {
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
        partial = Set(models.filter { !installed.contains($0.id) && downloads.hasPartial($0) }.map(\.id))
    }

    /// Background URLSession download (F-13): keeps going when the app is in the background or suspended.
    private func download(_ model: ModelInfo) {
        note[model.id] = nil
        downloads.start(model)
    }

    private func delete(_ model: ModelInfo) {
        let ai = app.graph.ai
        let id = model.id
        Task {
            await downloads.discard(id)
            if selectedId == id { try? await ai.unload() }
            if let error = SwiftSupport.shared.deleteModel(ai: ai, model: model) {
                note[id] = String(localized: "Couldn't delete: \(error)")
            } else {
                note[id] = nil
            }
            refresh()
        }
    }
}
