import AVFoundation
import AVKit
import Shared
import SwiftUI
import UniformTypeIdentifiers

/// One subtitle cue pair for display.
struct CueData: Identifiable {
    let id: Int
    let startMs: Int64
    let endMs: Int64
    let japanese: String
    let english: String?
}

/// Player state for the media screen: AVPlayer, current cue, A-B loop, speed, generated subtitles, quiz stops.
@MainActor
@Observable
final class MediaModel {
    let player = AVPlayer()
    private(set) var mediaName: String?
    private(set) var mediaURL: URL?
    private(set) var cues: [CueData] = []
    private(set) var jaName: String?
    private(set) var enName: String?
    private(set) var positionMs: Int64 = 0
    private(set) var currentIndex: Int?
    private(set) var speed: Double = MediaPlayback.shared.SPEED_DEFAULT
    var loopA: Int64?
    var loopB: Int64?
    /// The quiz plays one cue and pauses at its end.
    var stopAtMs: Int64?

    @ObservationIgnored private(set) var kotlinCues: [Cue] = []
    @ObservationIgnored private var englishCues: [Cue] = []
    @ObservationIgnored private var observer: Any?
    @ObservationIgnored private var scopedURL: URL?
    @ObservationIgnored private var hash: String?

    func open(media url: URL, title: String? = nil) {
        release()
        if url.startAccessingSecurityScopedResource() { scopedURL = url }
        player.replaceCurrentItem(with: AVPlayerItem(url: url))
        player.defaultRate = Float(speed)
        claimAudio()
        mediaURL = url
        mediaName = title ?? url.lastPathComponent
        hash = nil
        kotlinCues = []
        englishCues = []
        jaName = nil
        enName = nil
        cues = []
        loopA = nil
        loopB = nil
        if observer == nil {
            observer = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] time in
                let ms = Int64(max(0, time.seconds.isFinite ? time.seconds : 0) * 1000)
                MainActor.assumeIsolated { self?.tick(ms) }
            }
        }
    }

    /// The content key of the open file (D-113), computed once off the main actor by the shared code.
    func mediaHash(graph: AppGraph) async throws -> String {
        if let hash { return hash }
        guard let mediaURL else { throw MediaDecodingError.noAudio }
        let value = try await SwiftSupport.shared.mediaHash(graph: graph, path: mediaURL.path)
        hash = value
        return value
    }

    /// Parses a picked .srt/.vtt (copied out of its security scope first). Returns an error message or nil.
    func loadSubtitles(from url: URL, english: Bool) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return String(localized: "Couldn't read \(url.lastPathComponent).") }
        let text = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .utf16)
            ?? String(data: data, encoding: .shiftJIS)
        guard let text else { return String(localized: "\(url.lastPathComponent) isn't UTF-8, UTF-16 or Shift-JIS text.") }
        let parsed = Subtitles.shared.parse(text: text)
        if parsed.isEmpty { return String(localized: "No subtitle cues found in \(url.lastPathComponent).") }
        if english {
            englishCues = parsed
            enName = url.lastPathComponent
        } else {
            kotlinCues = parsed
            jaName = url.lastPathComponent
        }
        rebuild()
        return nil
    }

    /// Generated (Whisper) subtitles become the Japanese track.
    func useGenerated(_ generated: GeneratedSubtitles) {
        kotlinCues = generated.cues
        jaName = generated.fromCache
            ? String(localized: "Generated with \(generated.engine) (cached)")
            : String(localized: "Generated with \(generated.engine)")
        rebuild()
    }

    private func rebuild() {
        let dual = Subtitles.shared.dual(japanese: kotlinCues, english: englishCues)
        cues = dual.enumerated().map { i, c in
            CueData(id: i, startMs: c.startMs, endMs: c.endMs, japanese: c.japanese, english: c.english)
        }
        tick(positionMs)
    }

    private func tick(_ ms: Int64) {
        positionMs = ms
        if let stop = stopAtMs, ms >= stop {
            player.pause()
            stopAtMs = nil
        }
        if let a = loopA, let b = loopB, b > a, ms >= b {
            seek(ms: a)
            return
        }
        guard !kotlinCues.isEmpty else { currentIndex = nil; return }
        let i = Int(Subtitles.shared.indexAt(cues: kotlinCues, positionMs: ms))
        currentIndex = (i < cues.count && cues[i].startMs <= ms && cues[i].endMs > ms) ? i : nil
    }

    func seek(ms: Int64) {
        player.seek(to: CMTime(value: max(0, ms), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        positionMs = ms
    }

    /// Plays [startMs, endMs) and pauses (the hide-subtitle quiz).
    func playSpan(startMs: Int64, endMs: Int64) {
        seek(ms: startMs)
        stopAtMs = endMs
        player.play()
    }

    func setSpeed(_ value: Double) {
        speed = MediaPlayback.shared.clampSpeed(speed: value)
        player.defaultRate = Float(speed)
        if player.timeControlStatus == .playing { player.rate = Float(speed) }
    }

    /// Replays the current line (or the previous one when already at its start) / jumps to the next line.
    func previousLine() {
        guard !kotlinCues.isEmpty else { seek(ms: positionMs - 5000); return }
        var i = Int(Subtitles.shared.indexAt(cues: kotlinCues, positionMs: positionMs))
        if i < cues.count, positionMs - cues[i].startMs < 800, i > 0 { i -= 1 }
        if i < cues.count { seek(ms: cues[i].startMs) }
    }

    func nextLine() {
        guard !kotlinCues.isEmpty else { seek(ms: positionMs + 5000); return }
        if let next = cues.first(where: { $0.startMs > positionMs + 50 }) { seek(ms: next.startMs) }
    }

    func markLoop() {
        if loopA == nil {
            loopA = positionMs
        } else if loopB == nil {
            if positionMs > (loopA ?? 0) { loopB = positionMs } else { loopA = positionMs }
        } else {
            loopA = nil
            loopB = nil
        }
    }

    /// Playback session (plays with the silent switch on, keeps going in the background with UIBackgroundModes audio);
    /// a call or unplugged headphones pause the player (F-14).
    func claimAudio() {
        AudioSessionController.shared.beginPlayback(self) { [weak self] in self?.player.pause() }
    }

    /// Leaving the screen: pause and let other apps' audio resume.
    func pauseAndReleaseAudio() {
        player.pause()
        AudioSessionController.shared.end(self)
    }

    func release() {
        AudioSessionController.shared.end(self)
        player.pause()
        player.replaceCurrentItem(with: nil)
        scopedURL?.stopAccessingSecurityScopedResource()
        scopedURL = nil
    }
}

/// Media player (BRIEF §5.9, BRIEF_V2 G-04): the learner's own video/audio (or a downloaded podcast episode) with
/// .srt/.vtt or Whisper-generated subtitles, dual lines, tap-to-look-up, A-B loop, previous/next line, speed
/// 0.7–1.2×, "save clip to SRS", and a hide-subtitle quiz. Nothing is streamed from a service.
struct MediaPlayerView: View {
    @Environment(AppModel.self) private var app

    /// A local file to open right away (a downloaded podcast episode).
    var initialFile: String?
    var initialTitle: String?
    var episodeId: String?

    private enum Pick { case media, japanese, english }

    @State private var model = MediaModel()
    @State private var picking = false
    @State private var pick = Pick.media
    @State private var message: String?
    @State private var hideSubtitles = false
    @State private var showEnglish = true
    @State private var words: [(surface: String, base: String)] = []
    @State private var generating: Task<Void, Never>?
    @State private var genProgress: Double?
    @State private var clipping = false
    @State private var quizActive = false

    private var subtitleTypes: [UTType] {
        [UTType(filenameExtension: "srt"), UTType(filenameExtension: "vtt")].compactMap { $0 } + [.plainText, .data]
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if model.mediaName == nil {
                    ContentUnavailableView {
                        Label("No media open", systemImage: "play.rectangle")
                    } description: {
                        Text("Open a video or audio file from Files (iCloud Drive, On My iPhone, a network share), then add .srt or .vtt subtitles or generate them on this device. Tsumugi doesn't download from streaming services.")
                    } actions: {
                        Button("Open media") { start(.media) }.buttonStyle(.borderedProminent)
                        NavigationLink("Podcasts", value: Route.podcasts)
                    }
                } else {
                    VideoPlayer(player: model.player)
                        .frame(height: 220)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    controls
                    speedControl
                    if quizActive {
                        SubtitleQuizPanel(model: model) { quizActive = false }
                    } else {
                        subtitlePanel
                    }
                }
                HStack {
                    Button("Open media") { start(.media) }
                    Button("Japanese subs") { start(.japanese) }
                    Button("English subs") { start(.english) }
                }
                .buttonStyle(.bordered)
                .font(.caption)
                if model.mediaName != nil { subtitleTools }
                if let ja = model.jaName { Text("Japanese: \(ja)").font(.caption2).foregroundStyle(.secondary) }
                if let en = model.enName { Text("English: \(en)").font(.caption2).foregroundStyle(.secondary) }
                if let message { Text(message).font(.caption).foregroundStyle(.orange) }
                if !model.cues.isEmpty && !quizActive { cueList }
            }
            .padding()
        }
        .navigationTitle(model.mediaName ?? String(localized: "Media"))
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $picking, allowedContentTypes: pick == .media ? [.audiovisualContent, .movie, .audio] : subtitleTypes) { result in
            handle(result)
        }
        .onAppear {
            if model.mediaName != nil {
                model.claimAudio()
            } else if let initialFile {
                openEpisode(path: initialFile)
            }
        }
        .onDisappear {
            model.pauseAndReleaseAudio()
            generating?.cancel()
            if let episodeId {
                let ms = model.positionMs
                let podcasts = app.graph.podcasts
                Task { try? await podcasts.setPosition(episodeId: episodeId, positionMs: ms) }
            }
        }
        .task(id: model.currentIndex) { await tokenizeCurrent() }
    }

    private var controls: some View {
        HStack(spacing: 18) {
            Button { model.previousLine() } label: { Image(systemName: "backward.end.fill") }
                .accessibilityLabel("Previous line")
            Button {
                if model.player.timeControlStatus == .playing { model.player.pause() } else { model.player.play() }
            } label: { Image(systemName: "playpause.fill") }
                .accessibilityLabel("Play or pause")
            Button { model.nextLine() } label: { Image(systemName: "forward.end.fill") }
                .accessibilityLabel("Next line")
            Button { model.markLoop() } label: {
                Text(model.loopA == nil ? "A-B" : (model.loopB == nil ? "B?" : "A-B ✕")).font(.caption.weight(.bold))
            }
            .accessibilityLabel("A-B loop")
            Spacer()
            Toggle("Hide", isOn: $hideSubtitles).toggleStyle(.button).font(.caption)
            Toggle("EN", isOn: $showEnglish).toggleStyle(.button).font(.caption)
        }
        .font(.title3)
    }

    /// Speed 0.7–1.2× in the shared steps (G-04).
    private var speedControl: some View {
        HStack {
            Button {
                model.setSpeed(MediaPlayback.shared.step(speed: model.speed, up: false))
            } label: { Image(systemName: "tortoise") }
                .accessibilityLabel(Text("Slower"))
                .disabled(model.speed <= MediaPlayback.shared.SPEED_MIN + 0.001)
            Text(String(format: "%.1f×", model.speed)).font(.subheadline.monospacedDigit()).frame(minWidth: 44)
                .accessibilityLabel(Text("Speed"))
            Button {
                model.setSpeed(MediaPlayback.shared.step(speed: model.speed, up: true))
            } label: { Image(systemName: "hare") }
                .accessibilityLabel(Text("Faster"))
                .disabled(model.speed >= MediaPlayback.shared.SPEED_MAX - 0.001)
            Spacer()
            if let i = model.currentIndex, i < model.cues.count {
                Button(clipping ? "Saving…" : "Save clip") { saveClip(model.cues[i]) }
                    .buttonStyle(.bordered).font(.caption)
                    .disabled(clipping)
            }
        }
    }

    @ViewBuilder
    private var subtitleTools: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let generating, !generating.isCancelled {
                HStack {
                    if let genProgress {
                        ProgressView(value: genProgress) { Text("Generating subtitles on this device…").font(.caption) }
                    } else {
                        ProgressView()
                        Text("Preparing…").font(.caption)
                    }
                    Button("Cancel") { cancelGeneration() }.font(.caption)
                }
            } else {
                HStack {
                    Button("Generate subtitles") { generateSubtitles() }
                    if model.cues.count >= 4 {
                        Button("Quiz: hide subtitles") { quizActive = true }
                    }
                }
                .buttonStyle(.bordered)
                .font(.caption)
            }
        }
    }

    @ViewBuilder
    private var subtitlePanel: some View {
        VStack(alignment: .leading, spacing: 6) {
            if model.cues.isEmpty {
                Text("Add Japanese subtitles, or generate them, to see lines here.").font(.caption).foregroundStyle(.secondary)
            } else if hideSubtitles {
                Text("Subtitles hidden").font(.caption).foregroundStyle(.secondary)
            } else if let i = model.currentIndex, i < model.cues.count {
                let cue = model.cues[i]
                if words.isEmpty {
                    NavigationLink(value: Route.lookup(cue.japanese)) {
                        Text(cue.japanese).font(.japanese(size: 20)).multilineTextAlignment(.leading)
                    }
                    .buttonStyle(.plain)
                } else {
                    FlowWords(words: words)
                }
                if showEnglish, let en = cue.english {
                    Text(en).font(.subheadline).foregroundStyle(.secondary)
                }
            } else {
                Text(" ").font(.japanese(size: 20))
            }
        }
        .frame(maxWidth: .infinity, minHeight: 70, alignment: .topLeading)
        .padding(10)
        .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
    }

    private var cueList: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Lines").font(.headline)
            ForEach(model.cues) { cue in
                Button {
                    model.seek(ms: cue.startMs)
                } label: {
                    HStack(alignment: .top) {
                        Text(clockText(seconds: cue.startMs / 1000)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        Text(hideSubtitles ? "…" : cue.japanese).font(.japanese(size: 15)).multilineTextAlignment(.leading)
                    }
                }
                .buttonStyle(.plain)
                .padding(.vertical, 2)
                .background(model.currentIndex == cue.id ? Color.accentColor.opacity(0.12) : Color.clear)
            }
        }
    }

    private func start(_ target: Pick) {
        pick = target
        picking = true
    }

    private func handle(_ result: Result<URL, Error>) {
        guard case .success(let url) = result else { return }
        message = nil
        switch pick {
        case .media:
            cancelGeneration()
            quizActive = false
            model.open(media: url)
        case .japanese:
            message = model.loadSubtitles(from: url, english: false)
        case .english:
            message = model.loadSubtitles(from: url, english: true)
        }
    }

    /// A downloaded podcast episode: open it, resume where the learner left off, and use a cached transcript.
    private func openEpisode(path: String) {
        model.open(media: URL(fileURLWithPath: path), title: initialTitle)
        let graph = app.graph
        let player = model
        Task {
            if let episodeId, let episode = try? await graph.podcasts.episode(id: episodeId), episode.positionMs > 0 {
                player.seek(ms: episode.positionMs)
            }
            if let hash = try? await player.mediaHash(graph: graph), let cached = try? await graph.subtitles.cached(mediaHash: hash, language: "ja") {
                player.useGenerated(cached)
            }
        }
    }

    /// Whisper subtitles on this device (G-04): decoded window by window, with progress and Cancel.
    private func generateSubtitles() {
        guard let url = model.mediaURL else { return }
        message = nil
        genProgress = nil
        let graph = app.graph
        let player = model
        generating = Task {
            do {
                let hash = try await player.mediaHash(graph: graph)
                let decoder = try await PcmDecoder.open(url: url)
                let result = try await SwiftSupport.shared.generateSubtitles(graph: graph, mediaHash: hash, reader: decoder) { p in
                    let fraction = p.fraction
                    Task { @MainActor in genProgress = fraction }
                }
                player.useGenerated(result)
                if result.cues.isEmpty { message = String(localized: "No speech was recognized in this file.") }
            } catch is CancellationError {
                message = String(localized: "Subtitle generation cancelled.")
            } catch {
                if !Task.isCancelled {
                    message = String(localized: "Couldn't generate subtitles: \(error.localizedDescription)")
                }
            }
            generating = nil
            genProgress = nil
        }
    }

    private func cancelGeneration() {
        generating?.cancel()
        generating = nil
        genProgress = nil
    }

    /// "Save clip to SRS" (G-04, D-113): the line becomes a LISTENING card; its audio is cut from the file.
    private func saveClip(_ cue: CueData) {
        guard let url = model.mediaURL else { return }
        clipping = true
        message = nil
        let graph = app.graph
        let player = model
        let title = model.mediaName ?? url.lastPathComponent
        Task {
            do {
                let mediaId = (try? await player.mediaHash(graph: graph)) ?? url.lastPathComponent
                let draft = try await graph.clips.saveClip(
                    mediaId: mediaId, mediaTitle: title, startMs: cue.startMs, endMs: cue.endMs, text: cue.japanese,
                    translation: cue.english, reading: nil, paddingMs: ClipService.companion.DEFAULT_PADDING_MS
                )
                do {
                    try await ClipCutter.cut(source: url, startMs: draft.startMs, endMs: draft.endMs, to: draft.audio.path)
                    _ = try await graph.clips.attachAudio(clipId: draft.clip.id, audio: draft.audio, durationMs: draft.endMs - draft.startMs)
                    message = String(localized: "Clip saved. It waits in your lessons as a listening card.")
                } catch {
                    message = String(localized: "Clip saved without audio (\(error.localizedDescription)); the review reads the line with the system voice.")
                }
            } catch {
                message = String(localized: "Couldn't save the clip: \(error.localizedDescription)")
            }
            clipping = false
        }
    }

    /// Splits the current line into words (tokenizer pack) so each can be tapped for the dictionary.
    private func tokenizeCurrent() async {
        guard let i = model.currentIndex, i < model.cues.count else { words = []; return }
        let text = model.cues[i].japanese
        guard let analyzer = try? await app.graph.analyzer(), let morphemes = try? await analyzer.analyze(text: text) else {
            words = []
            return
        }
        words = morphemes
            .filter { !$0.surface.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map { (surface: $0.surface, base: $0.baseForm) }
    }
}

/// The hide-subtitle quiz (G-04): a cue plays with its line hidden, the learner types or picks what was said, then
/// the line is revealed. Grading (kana-folded similarity, 0.8 pass) is the shared `SubtitleQuiz`.
private struct SubtitleQuizPanel: View {
    let model: MediaModel
    let onClose: () -> Void

    @State private var quiz: SubtitleQuiz?
    @State private var mode: QuizMode = .pick
    @State private var order: [Int] = []
    @State private var position = 0
    @State private var question: QuizQuestion?
    @State private var typed = ""
    @State private var result: QuizResult?
    @State private var revealed = false
    @State private var right = 0
    @State private var asked = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Hidden-subtitle quiz").font(.headline)
                Spacer()
                Button("Close") { onClose() }.font(.caption)
            }
            Picker("Answer by", selection: $mode) {
                Text("Pick").tag(QuizMode.pick)
                Text("Type").tag(QuizMode.type)
            }
            .pickerStyle(.segmented)
            .onChange(of: mode) { _, _ in setUp() }
            if order.isEmpty {
                Text("These subtitles have no lines long enough to quiz.").font(.caption).foregroundStyle(.secondary)
            } else if position >= order.count {
                Text("Quiz done: \(right) of \(asked) right.").font(.subheadline.weight(.semibold))
                Button("Again") { setUp() }.buttonStyle(.bordered)
            } else if let question {
                Text("Line \(position + 1) of \(order.count) · \(right)/\(asked) right").font(.caption).foregroundStyle(.secondary)
                Button {
                    model.playSpan(startMs: question.cue.startMs, endMs: question.cue.endMs)
                } label: {
                    Label("Play the line", systemImage: "play.circle")
                }
                .buttonStyle(.bordered)
                if result == nil && !revealed {
                    if mode == .pick {
                        ForEach(Array(question.choices.enumerated()), id: \.offset) { _, choice in
                            Button {
                                answer(choice)
                            } label: {
                                Text(choice).font(.japanese(size: 16)).frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .buttonStyle(.bordered)
                        }
                    } else {
                        TextField("What did you hear?", text: $typed, axis: .vertical)
                            .font(.japanese(size: 17))
                            .textFieldStyle(.roundedBorder)
                        Button("Check") { answer(typed) }.buttonStyle(.borderedProminent)
                            .disabled(typed.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                    Button("Show the line") { reveal() }.font(.caption)
                } else {
                    if let result {
                        Label(result.correct ? String(localized: "Right") : String(localized: "Not quite"),
                              systemImage: result.correct ? "checkmark.circle" : "xmark.circle")
                            .foregroundStyle(result.correct ? .green : .red)
                        if mode == .type { Text("Match: \(Int(result.score * 100))%").font(.caption) }
                    }
                    Text(question.cue.text).font(.japanese(size: 20)).textSelection(.enabled)
                    Button("Next line") { next() }.buttonStyle(.borderedProminent)
                }
            }
        }
        .padding(10)
        .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
        .onAppear { setUp() }
    }

    private func setUp() {
        let q = SubtitleQuiz(cues: model.kotlinCues, mode: mode, seed: Int64(Date().timeIntervalSince1970), minChars: 4)
        quiz = q
        order = q.questionIndices.map { Int($0.intValue) }.shuffled()
        position = 0
        right = 0
        asked = 0
        load()
    }

    private func load() {
        typed = ""
        result = nil
        revealed = false
        guard let quiz, position < order.count else { question = nil; return }
        let q = quiz.question(index: Int32(order[position]))
        question = q
        model.playSpan(startMs: q.cue.startMs, endMs: q.cue.endMs)
    }

    private func answer(_ given: String) {
        guard let quiz, let question else { return }
        let r = quiz.answer(index: question.index, given: given)
        result = r
        asked += 1
        if r.correct { right += 1 }
        _ = quiz.reveal(index: question.index)
    }

    private func reveal() {
        guard let quiz, let question else { return }
        _ = quiz.reveal(index: question.index)
        revealed = true
    }

    private func next() {
        position += 1
        load()
    }
}

/// Tappable words of a subtitle line; each opens the dictionary on its dictionary form.
private struct FlowWords: View {
    let words: [(surface: String, base: String)]

    var body: some View {
        FlowLayout(spacing: 2) {
            ForEach(Array(words.enumerated()), id: \.offset) { _, w in
                NavigationLink(value: Route.lookup(w.base.isEmpty || w.base == "*" ? w.surface : w.base)) {
                    Text(w.surface).font(.japanese(size: 20))
                }
                .buttonStyle(.plain)
            }
        }
    }
}
