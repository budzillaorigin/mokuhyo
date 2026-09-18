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

/// Player state for the media screen: AVPlayer, current cue, A-B loop.
@MainActor
@Observable
final class MediaModel {
    let player = AVPlayer()
    private(set) var mediaName: String?
    private(set) var cues: [CueData] = []
    private(set) var jaName: String?
    private(set) var enName: String?
    private(set) var positionMs: Int64 = 0
    private(set) var currentIndex: Int?
    var loopA: Int64?
    var loopB: Int64?

    @ObservationIgnored private var kotlinCues: [Cue] = []
    @ObservationIgnored private var englishCues: [Cue] = []
    @ObservationIgnored private var observer: Any?
    @ObservationIgnored private var scopedURL: URL?

    func open(media url: URL) {
        release()
        if url.startAccessingSecurityScopedResource() { scopedURL = url }
        player.replaceCurrentItem(with: AVPlayerItem(url: url))
        claimAudio()
        mediaName = url.lastPathComponent
        loopA = nil
        loopB = nil
        if observer == nil {
            observer = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] time in
                let ms = Int64(max(0, time.seconds.isFinite ? time.seconds : 0) * 1000)
                MainActor.assumeIsolated { self?.tick(ms) }
            }
        }
    }

    /// Parses a picked .srt/.vtt (copied out of its security scope first). Returns an error message or nil.
    func loadSubtitles(from url: URL, english: Bool) -> String? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return "Couldn't read \(url.lastPathComponent)." }
        let text = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .utf16)
            ?? String(data: data, encoding: .shiftJIS)
        guard let text else { return "\(url.lastPathComponent) isn't UTF-8, UTF-16 or Shift-JIS text." }
        let parsed = Subtitles.shared.parse(text: text)
        if parsed.isEmpty { return "No subtitle cues found in \(url.lastPathComponent)." }
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

    private func rebuild() {
        let dual = Subtitles.shared.dual(japanese: kotlinCues, english: englishCues)
        cues = dual.enumerated().map { i, c in
            CueData(id: i, startMs: c.startMs, endMs: c.endMs, japanese: c.japanese, english: c.english)
        }
        tick(positionMs)
    }

    private func tick(_ ms: Int64) {
        positionMs = ms
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

/// Media player (BRIEF §5.9): the learner's own video/audio with .srt/.vtt subtitles, dual lines, tap-to-look-up,
/// A-B loop, previous/next line. Nothing is downloaded or streamed.
struct MediaPlayerView: View {
    @Environment(AppModel.self) private var app

    private enum Pick { case media, japanese, english }

    @State private var model = MediaModel()
    @State private var picking = false
    @State private var pick = Pick.media
    @State private var message: String?
    @State private var hideSubtitles = false
    @State private var showEnglish = true
    @State private var words: [(surface: String, base: String)] = []

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
                        Text("Open a video or audio file from Files (iCloud Drive, On My iPhone, a network share), then add .srt or .vtt subtitles. Tsumugi doesn't download from streaming services.")
                    } actions: {
                        Button("Open media") { start(.media) }.buttonStyle(.borderedProminent)
                    }
                } else {
                    VideoPlayer(player: model.player)
                        .frame(height: 220)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    controls
                    subtitlePanel
                }
                HStack {
                    Button("Open media") { start(.media) }
                    Button("Japanese subs") { start(.japanese) }
                    Button("English subs") { start(.english) }
                }
                .buttonStyle(.bordered)
                .font(.caption)
                if let ja = model.jaName { Text("Japanese: \(ja)").font(.caption2).foregroundStyle(.secondary) }
                if let en = model.enName { Text("English: \(en)").font(.caption2).foregroundStyle(.secondary) }
                if let message { Text(message).font(.caption).foregroundStyle(.orange) }
                if !model.cues.isEmpty { cueList }
            }
            .padding()
        }
        .navigationTitle(model.mediaName ?? "Media")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $picking, allowedContentTypes: pick == .media ? [.audiovisualContent, .movie, .audio] : subtitleTypes) { result in
            handle(result)
        }
        .onAppear { if model.mediaName != nil { model.claimAudio() } }
        .onDisappear { model.pauseAndReleaseAudio() }
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

    @ViewBuilder
    private var subtitlePanel: some View {
        VStack(alignment: .leading, spacing: 6) {
            if model.cues.isEmpty {
                Text("Add Japanese subtitles to see lines here.").font(.caption).foregroundStyle(.secondary)
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
            model.open(media: url)
        case .japanese:
            message = model.loadSubtitles(from: url, english: false)
        case .english:
            message = model.loadSubtitles(from: url, english: true)
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
