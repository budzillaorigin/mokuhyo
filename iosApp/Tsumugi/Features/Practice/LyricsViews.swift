import AVFoundation
import Shared
import SwiftUI
import UniformTypeIdentifiers

/// Lyrics and karaoke reading (BRIEF_V2 §6.3, D-163): songs the learner owns (Files / iCloud Drive) with .lrc synced
/// lyrics or plain lyrics. Nothing is streamed or downloaded. Songs stay on this device (D-163); the audio is copied
/// into the app's own storage so it can be played again later (D-192).
struct LyricsListView: View {
    @Environment(AppModel.self) private var app
    @State private var songs: [LyricsSongSummary]?
    @State private var loadError: String?
    @State private var importing = false
    @State private var opened: String?

    var body: some View {
        List {
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await load() } }
            }
            if let songs, songs.isEmpty {
                ContentUnavailableView {
                    Label("No songs yet", systemImage: "music.note.list")
                } description: {
                    Text("Import a song you own: an audio file from Files plus its .lrc synced lyrics or the plain lyrics. Tsumugi doesn't stream or download music.")
                } actions: {
                    Button("Import a song") { importing = true }.buttonStyle(.borderedProminent)
                }
            }
            ForEach(songs ?? [], id: \.id) { song in
                NavigationLink(value: Route.song(song.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(song.title).font(.japanese(size: 17)).lineLimit(1)
                        Text([song.artist, Self.isTimed(song.timing) ? String(localized: "timed") : String(localized: "not timed")].compactMap { $0 }.joined(separator: " · "))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .swipeActions {
                    Button("Delete", role: .destructive) { delete(song.id) }
                }
            }
        }
        .navigationTitle("Lyrics")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { importing = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel(Text("Import a song"))
            }
        }
        .task { await load() }
        .sheet(isPresented: $importing) {
            NavigationStack {
                LyricsImportView { id in
                    importing = false
                    Task {
                        await load()
                        opened = id
                    }
                }
            }
            .environment(app)
        }
        .navigationDestination(item: $opened) { KaraokeView(songId: $0) }
    }

    /// Never names the `NONE` case (a Swift enum case called `none` is easily confused with `Optional.none`).
    static func isTimed(_ timing: LyricsTiming) -> Bool {
        switch timing {
        case .lrcWords, .lrcLines, .aligned: true
        default: false
        }
    }

    private func load() async {
        loadError = nil
        do {
            songs = try await app.graph.lyrics.songs()
        } catch {
            loadError = String(localized: "Couldn't load your songs: \(error.localizedDescription)")
        }
    }

    private func delete(_ id: String) {
        Task {
            if let song = try? await app.graph.lyrics.song(id: id), let file = MediaLocator.open(song.audioLocator) {
                MediaLocator.close(file)
                if song.audioLocator.hasPrefix("home:") { try? FileManager.default.removeItem(at: file.url) }
            }
            try? await app.graph.lyrics.delete(id: id)
            await load()
        }
    }
}

/// Import: the audio file, then the lyrics (.lrc / .txt, or pasted), a title and an artist.
struct LyricsImportView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let onImported: (String) -> Void

    private enum Pick { case audio, lyrics }

    @State private var picking = false
    @State private var pick = Pick.audio
    @State private var audioURL: URL?
    @State private var lyrics = ""
    @State private var lyricsName: String?
    @State private var title = ""
    @State private var artist = ""
    @State private var working = false
    @State private var failure: String?

    private var lyricTypes: [UTType] {
        [UTType(filenameExtension: "lrc"), UTType(filenameExtension: "txt")].compactMap { $0 } + [.plainText, .data]
    }

    var body: some View {
        Form {
            Section("Audio") {
                Button {
                    pick = .audio
                    picking = true
                } label: {
                    Label(audioURL?.lastPathComponent ?? String(localized: "Choose an audio file"), systemImage: "music.note")
                }
            }
            Section {
                Button {
                    pick = .lyrics
                    picking = true
                } label: {
                    Label(lyricsName ?? String(localized: "Choose a .lrc or .txt file"), systemImage: "doc.text")
                }
                TextEditor(text: $lyrics).font(.japanese(size: 15)).frame(minHeight: 160)
            } header: {
                Text("Lyrics")
            } footer: {
                Text("Synced .lrc lyrics highlight line by line (word by word with enhanced LRC). Plain lyrics can be timed afterwards with Whisper on this device.")
            }
            Section {
                TextField("Title", text: $title)
                TextField("Artist (optional)", text: $artist)
            }
            if let failure { Text(failure).foregroundStyle(.red).font(.caption) }
        }
        .navigationTitle("Import a song")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(working ? "Importing…" : "Import") { save() }
                    .disabled(working || audioURL == nil || lyrics.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: pick == .audio ? [.audio, .mpeg4Audio, .mp3] : lyricTypes) { result in
            guard case .success(let url) = result else { return }
            switch pick {
            case .audio:
                audioURL = url
                if title.isEmpty { title = url.deletingPathExtension().lastPathComponent }
            case .lyrics:
                if let text = SubtitleFile.read(url) {
                    lyrics = text
                    lyricsName = url.lastPathComponent
                } else {
                    failure = String(localized: "\(url.lastPathComponent) isn't UTF-8, UTF-16 or Shift-JIS text.")
                }
            }
        }
    }

    /// Copies the audio into Application Support/lyrics (off the main actor), hashes it, then imports the song.
    private func save() {
        guard let source = audioURL else { return }
        working = true
        failure = nil
        let graph = app.graph
        let text = lyrics
        let songTitle = title
        let songArtist = artist.trimmingCharacters(in: .whitespaces)
        Task {
            do {
                let target = try await Task.detached(priority: .userInitiated) { () -> URL in
                    let dir = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                        .appendingPathComponent("lyrics", isDirectory: true)
                    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    var values = URLResourceValues()
                    values.isExcludedFromBackup = true
                    var mutableDir = dir
                    try? mutableDir.setResourceValues(values)
                    let ext = source.pathExtension.isEmpty ? "m4a" : source.pathExtension
                    let out = dir.appendingPathComponent("\(UUID().uuidString).\(ext)")
                    let scoped = source.startAccessingSecurityScopedResource()
                    defer { if scoped { source.stopAccessingSecurityScopedResource() } }
                    try FileManager.default.copyItem(at: source, to: out)
                    return out
                }.value
                guard let locator = MediaLocator.make(for: target) else { throw ClipCache.MissingMedia() }
                let hash = try? await SwiftSupport.shared.mediaHash(graph: graph, path: target.path)
                let song = try await graph.lyrics.importSong(
                    title: songTitle, artist: songArtist.isEmpty ? nil : songArtist, audioLocator: locator, lyrics: text, mediaHash: hash
                )
                onImported(song.id)
            } catch {
                failure = String(localized: "Couldn't import the song: \(error.localizedDescription)")
            }
            working = false
        }
    }
}

/// Playback for the karaoke view: AVPlayer over the song's own file, the position ten times a second, and the time
/// actually played for the immersion log.
@MainActor
@Observable
final class KaraokeModel {
    let player = AVPlayer()
    private(set) var positionMs: Int64 = 0
    private(set) var isPlaying = false
    @ObservationIgnored private var observer: Any?
    @ObservationIgnored private var opened: (url: URL, scoped: Bool)?
    @ObservationIgnored private var playedSeconds: Double = 0
    @ObservationIgnored private var lastTick: Date?
    @ObservationIgnored var onTick: ((Int64) -> Void)?

    var url: URL? { opened?.url }

    func open(locator: String) -> Bool {
        release()
        guard let file = MediaLocator.open(locator) else { return false }
        opened = file
        player.replaceCurrentItem(with: AVPlayerItem(url: file.url))
        if observer == nil {
            observer = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] time in
                let ms = Int64(max(0, time.seconds.isFinite ? time.seconds : 0) * 1000)
                MainActor.assumeIsolated { self?.tick(ms) }
            }
        }
        return true
    }

    private func tick(_ ms: Int64) {
        positionMs = ms
        isPlaying = player.rate > 0
        if isPlaying {
            let now = Date()
            if let lastTick { playedSeconds += min(1, max(0, now.timeIntervalSince(lastTick))) }
            lastTick = now
        } else {
            lastTick = nil
        }
        onTick?(ms)
    }

    func play() {
        AudioSessionController.shared.beginPlayback(self) { [weak self] in self?.pause() }
        player.play()
        isPlaying = true
    }

    func pause() {
        player.pause()
        isPlaying = false
    }

    func seek(ms: Int64) {
        player.seek(to: CMTime(value: max(0, ms), timescale: 1000), toleranceBefore: .zero, toleranceAfter: .zero)
        positionMs = ms
    }

    func takePlayedSeconds() -> Int64 {
        let seconds = Int64(playedSeconds)
        playedSeconds = 0
        lastTick = nil
        return seconds
    }

    func release() {
        player.pause()
        AudioSessionController.shared.end(self)
        player.replaceCurrentItem(with: nil)
        if let opened { MediaLocator.close(opened) }
        opened = nil
    }
}

/// The karaoke view: line-by-line (and word-by-word) highlight from the shared `Karaoke` model, tap a line to jump,
/// per-line study (words, grammar, translation), Whisper alignment for untimed lyrics, and cloze mode.
struct KaraokeView: View {
    @Environment(AppModel.self) private var app
    let songId: String

    @State private var song: LyricsSong?
    @State private var model = KaraokeModel()
    @State private var loadError: String?
    @State private var missingAudio = false
    @State private var position: KaraokePosition?
    @State private var aligning: Task<Void, Never>?
    @State private var alignProgress: Double?
    @State private var message: String?
    @State private var studying: Int?
    // Cloze mode (AxTongue's mechanic, D-163)
    @State private var cloze: ClozeSession?
    @State private var clozeVersion = 0
    @State private var dueBlank: ClozeBlank?
    @State private var answer = ""
    @State private var lastAnswer: String?
    @State private var lrc: String?

    var body: some View {
        Group {
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await load() } }.padding()
            } else if let song {
                content(song)
            } else {
                ProgressView()
            }
        }
        .navigationTitle(song?.title ?? String(localized: "Song"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let lrc {
                    ShareLink(item: lrc) { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel(Text("Export as LRC"))
                }
            }
        }
        .task { await load() }
        .onDisappear {
            aligning?.cancel()
            flushImmersion()
            model.release()
        }
        .sheet(item: Binding(get: { studying.map { LineRef(index: $0) } }, set: { studying = $0?.index })) { ref in
            NavigationStack { LyricLineStudyView(songId: songId, lineIndex: ref.index) { Task { await reloadSong() } } }
                .environment(app)
        }
    }

    private struct LineRef: Identifiable {
        let index: Int
        var id: Int { index }
    }

    @ViewBuilder
    private func content(_ song: LyricsSong) -> some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        header(song)
                        ForEach(Array(song.lines.enumerated()), id: \.offset) { i, line in
                            lineView(line, index: i)
                                .id(i)
                                .onTapGesture {
                                    if let start = line.startMs?.int64Value { model.seek(ms: start) }
                                }
                                .onLongPressGesture { studying = i }
                        }
                    }
                    .padding()
                }
                .onChange(of: position?.lineIndex) { _, index in
                    if let index, index >= 0 { withAnimation { proxy.scrollTo(Int(index), anchor: .center) } }
                }
            }
            controls(song)
        }
    }

    @ViewBuilder
    private func header(_ song: LyricsSong) -> some View {
        if let artist = song.artist { Text(artist).font(.subheadline).foregroundStyle(.secondary) }
        if missingAudio {
            Label("The audio file for this song isn't on this device any more. Import it again.", systemImage: "exclamationmark.triangle")
                .font(.caption).foregroundStyle(.orange)
        }
        if !song.timed {
            VStack(alignment: .leading, spacing: 6) {
                Text("These lyrics have no timings yet.").font(.subheadline.weight(.semibold))
                if let aligning, !aligning.isCancelled {
                    CancellableProgress(label: String(localized: "Aligning with Whisper on this device…"), fraction: alignProgress) { cancelAlign() }
                } else {
                    Button("Align with Whisper") { align(song) }.buttonStyle(.borderedProminent).disabled(missingAudio)
                    Text("Listens to the song with the speech model and times each line. Needs a Whisper model (Settings → AI & speech).")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(10)
            .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
        }
        clozeControls(song)
        if let message { Text(message).font(.caption).foregroundStyle(.orange) }
        Text("Tap a line to jump there; press and hold for its words, grammar and translation.").font(.caption2).foregroundStyle(.secondary)
    }

    private func lineView(_ line: LyricLine, index: Int) -> some View {
        let current = position.map { Int($0.lineIndex) == index } ?? false
        let active = current && (position?.active ?? false)
        let text = cloze.map { _ in maskedText(index) } ?? line.text
        return VStack(alignment: .leading, spacing: 2) {
            if active && cloze == nil && !line.words.isEmpty {
                sungText(line)
            } else {
                Text(text)
                    .font(.japanese(size: active ? 22 : 18, weight: active ? .semibold : .regular))
                    .foregroundStyle(current ? Color.primary : Color.secondary)
            }
            if let translation = line.translation, !translation.isEmpty, current {
                HStack(spacing: 4) {
                    Text(translation).font(.caption).foregroundStyle(.secondary)
                    if line.aiTranslated { AIBadge(engine: line.translationEngine) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 2)
        .background(active ? Color.accentColor.opacity(0.08) : Color.clear, in: RoundedRectangle(cornerRadius: 6))
        .japaneseSpeech()
    }

    /// Word-by-word sweep: sung words in the accent colour (word times from enhanced LRC, or spread by morae).
    private func sungText(_ line: LyricLine) -> some View {
        let ns = line.text as NSString
        let now = model.positionMs
        var result = Text("")
        var cursor = 0
        for w in line.words {
            let start = max(cursor, min(Int(w.start), ns.length))
            let end = max(start, min(Int(w.end), ns.length))
            if start > cursor { result = result + Text(ns.substring(with: NSRange(location: cursor, length: start - cursor))) }
            let piece = Text(ns.substring(with: NSRange(location: start, length: end - start)))
            result = result + (w.startMs <= now ? piece.foregroundColor(.accentColor) : piece)
            cursor = end
        }
        if cursor < ns.length { result = result + Text(ns.substring(from: cursor)) }
        return result.font(.japanese(size: 22, weight: .semibold))
    }

    private func controls(_ song: LyricsSong) -> some View {
        HStack(spacing: 24) {
            Button {
                model.seek(ms: max(0, model.positionMs - 5000))
            } label: { Image(systemName: "gobackward.5") }
                .accessibilityLabel(Text("Back 5 seconds"))
            Button {
                if model.isPlaying { model.pause() } else { model.play() }
            } label: { Image(systemName: model.isPlaying ? "pause.circle.fill" : "play.circle.fill").font(.largeTitle) }
                .accessibilityLabel(Text("Play or pause"))
                .disabled(missingAudio)
            Text(clockText(seconds: model.positionMs / 1000)).font(.caption.monospacedDigit())
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(.bar)
    }

    // MARK: Cloze

    @ViewBuilder
    private func clozeControls(_ song: LyricsSong) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if let cloze {
                let blanks = cloze.blanks
                let right = blanks.filter { $0.state == .correct || $0.state == .close }.count
                let answered = blanks.filter { $0.state == .correct || $0.state == .close || $0.state == .wrong }.count
                HStack {
                    Text("Cloze: \(right.formatted()) of \(answered.formatted()) right · \(blanks.count.formatted()) hidden words").font(.caption)
                    Spacer()
                    Button("Stop cloze") {
                        self.cloze = nil
                        dueBlank = nil
                    }
                    .font(.caption)
                }
                if let blank = dueBlank {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("What was just sung?").font(.subheadline.weight(.semibold))
                        Text(maskedText(Int(blank.lineIndex))).font(.japanese(size: 17))
                        HStack {
                            TextField("Type the word (kana or kanji)", text: $answer)
                                .textFieldStyle(.roundedBorder)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .onSubmit { submit(blank) }
                            Button("Check") { submit(blank) }.buttonStyle(.borderedProminent)
                        }
                        Button("Show it") { reveal(blank) }.font(.caption)
                    }
                    .padding(10)
                    .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
                }
                if let lastAnswer { Text(lastAnswer).font(.caption) }
            } else {
                HStack {
                    Button("Cloze mode") { startCloze(song) }.buttonStyle(.bordered)
                    Text("Words disappear until they're sung; then you type them.").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func maskedText(_ index: Int) -> String {
        _ = clozeVersion
        guard let cloze else { return song?.lines[index].text ?? "" }
        return cloze.masked(lineIndex: Int32(index))
    }

    private func startCloze(_ song: LyricsSong) {
        let service = app.graph.lyrics
        let id = songId
        let hasSpans = song.lines.contains { !$0.cloze.isEmpty }
        Task {
            do {
                if !hasSpans { self.song = try await service.autoCloze(songId: id, everyNthLine: 1) }
                cloze = try await service.clozeSession(songId: id)
                if cloze?.blanks.isEmpty ?? true {
                    cloze = nil
                    message = String(localized: "No words to hide: cloze needs the dictionary pack.")
                }
                clozeVersion += 1
                lastAnswer = nil
            } catch {
                message = String(localized: "Couldn't start cloze mode: \(error.localizedDescription)")
            }
        }
    }

    private func onTick(_ ms: Int64) {
        guard let song else { return }
        position = Karaoke.shared.at(lines: song.lines, positionMs: ms)
        guard let cloze, dueBlank == nil else { return }
        let due = cloze.update(positionMs: ms)
        if let first = due.first {
            model.pause()
            dueBlank = first
            answer = ""
        }
        if !due.isEmpty { clozeVersion += 1 }
    }

    private func submit(_ blank: ClozeBlank) {
        guard let cloze else { return }
        let result = cloze.answer(id: blank.id, given: answer)
        lastAnswer = result.accepted
            ? String(localized: "Right: \(result.expected)")
            : String(localized: "Not quite: it was \(result.expected)")
        nextDue()
    }

    private func reveal(_ blank: ClozeBlank) {
        guard let cloze else { return }
        let shown = cloze.reveal(id: blank.id)
        lastAnswer = String(localized: "It was \(shown.answer)")
        nextDue()
    }

    /// Other blanks due at the same moment are asked before playback resumes.
    private func nextDue() {
        clozeVersion += 1
        answer = ""
        if let next = cloze?.blanks.first(where: { $0.state == .due }) {
            dueBlank = next
        } else {
            dueBlank = nil
            model.play()
        }
    }

    // MARK: Loading, alignment, immersion

    private func load() async {
        loadError = nil
        do {
            guard let loaded = try await app.graph.lyrics.song(id: songId) else {
                loadError = String(localized: "This song is no longer in your list.")
                return
            }
            song = loaded
            if model.url == nil { missingAudio = !model.open(locator: loaded.audioLocator) }
            model.onTick = { ms in onTick(ms) }
            lrc = try? await app.graph.lyrics.exportLrc(songId: songId)
        } catch {
            loadError = String(localized: "Couldn't open the song: \(error.localizedDescription)")
        }
    }

    private func reloadSong() async {
        if let loaded = try? await app.graph.lyrics.song(id: songId) { song = loaded }
        lrc = try? await app.graph.lyrics.exportLrc(songId: songId)
    }

    /// Whisper segments of the song, then the shared aligner times each line (progress + Cancel, rule 15).
    private func align(_ song: LyricsSong) {
        guard let url = model.url else { return }
        message = nil
        alignProgress = nil
        let graph = app.graph
        let id = songId
        let known = song.mediaHash
        aligning = Task {
            do {
                let hash: String
                if let known { hash = known } else { hash = try await SwiftSupport.shared.mediaHash(graph: graph, path: url.path) }
                let decoder = try await PcmDecoder.open(url: url)
                let aligned = try await SwiftSupport.shared.alignLyrics(graph: graph, songId: id, mediaHash: hash, reader: decoder) { p in
                    let f = p.fraction
                    Task { @MainActor in alignProgress = f }
                }
                self.song = aligned
                lrc = try? await graph.lyrics.exportLrc(songId: id)
                if !aligned.timed { message = String(localized: "Whisper didn't recognize enough of the song to time the lines.") }
            } catch is CancellationError {
                message = String(localized: "Alignment cancelled.")
            } catch {
                if !Task.isCancelled { message = String(localized: "Couldn't align: \(error.localizedDescription)") }
            }
            aligning = nil
            alignProgress = nil
        }
    }

    private func cancelAlign() {
        aligning?.cancel()
        aligning = nil
        alignProgress = nil
    }

    private func flushImmersion() {
        let seconds = model.takePlayedSeconds()
        guard seconds > 0 else { return }
        let graph = app.graph
        let title = song?.title
        let id = songId
        Task { _ = try? await graph.immersion.report(source: .media, mode: .active, elapsedSeconds: seconds, ref: id, title: title) }
    }
}

/// One lyric line: its words (tap → dictionary), grammar notes, and the English line (the learner's own, or an
/// AI translation with its badge; rule 10).
struct LyricLineStudyView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let songId: String
    let lineIndex: Int
    let onChange: () -> Void

    @State private var study: LyricLineStudy?
    @State private var line: LyricLine?
    @State private var english = ""
    @State private var translating = false
    @State private var note: String?
    @State private var loadError: String?

    var body: some View {
        Form {
            if let loadError {
                ErrorRetryView(message: loadError) { Task { await load() } }
            }
            if let study {
                Section {
                    Text(study.text).font(.japanese(size: 20)).textSelection(.enabled)
                }
                if !study.tokens.isEmpty {
                    Section("Words") {
                        FlowLayout(spacing: 6) {
                            ForEach(Array(study.tokens.enumerated()), id: \.offset) { _, t in
                                if let id = t.entryId {
                                    NavigationLink(value: Route.entry(id.int64Value)) {
                                        Text(t.surface).font(.japanese(size: 18))
                                    }
                                    .buttonStyle(.bordered)
                                } else {
                                    Text(t.surface).font(.japanese(size: 18))
                                }
                            }
                        }
                    }
                }
                if !study.grammar.isEmpty {
                    Section("Grammar") {
                        ForEach(study.grammar, id: \.pointId) { g in
                            NavigationLink(value: Route.grammarPoint(g.pointId)) {
                                VStack(alignment: .leading, spacing: 2) {
                                    HStack {
                                        Text(g.title).font(.japanese(size: 16))
                                        TagView("N\(g.jlpt)")
                                        if g.aiGenerated { AiBadge() }
                                    }
                                    Text(g.meaning).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            } else if loadError == nil {
                ProgressView()
            }
            Section {
                TextField("English (your own)", text: $english, axis: .vertical)
                Button("Save my translation") { save() }
                    .disabled(english.trimmingCharacters(in: .whitespaces).isEmpty)
                Button(translating ? "Translating…" : "Translate with AI") { translate() }
                    .disabled(translating)
                if let line, line.aiTranslated, let t = line.translation {
                    HStack {
                        AIBadge(engine: line.translationEngine)
                        Text(t).font(.caption)
                    }
                }
                if let note { Text(note).font(.caption).foregroundStyle(.orange) }
            } header: {
                Text("Translation")
            } footer: {
                Text("Your own translation is never overwritten by the AI.")
            }
        }
        .navigationTitle("Line \((lineIndex + 1).formatted())")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        .task { await load() }
    }

    private func load() async {
        loadError = nil
        do {
            study = try await app.graph.lyrics.lineStudy(songId: songId, lineIndex: Int32(lineIndex))
            if let song = try await app.graph.lyrics.song(id: songId), lineIndex < song.lines.count {
                let l = song.lines[lineIndex]
                line = l
                if english.isEmpty, !l.aiTranslated { english = l.translation ?? "" }
            }
        } catch {
            loadError = String(localized: "Couldn't load this line: \(error.localizedDescription)")
        }
    }

    private func save() {
        let text = english
        Task {
            do {
                _ = try await app.graph.lyrics.setTranslation(songId: songId, lineIndex: Int32(lineIndex), english: text)
                note = nil
                onChange()
                await load()
            } catch {
                note = String(localized: "Couldn't save: \(error.localizedDescription)")
            }
        }
    }

    private func translate() {
        translating = true
        note = nil
        Task {
            do {
                let outcome = try await app.graph.lyrics.translateLine(songId: songId, lineIndex: Int32(lineIndex))
                if outcome.engine == nil {
                    note = outcome.unavailable ?? String(localized: "No AI engine is set up (Settings → AI & speech).")
                }
                onChange()
                await load()
            } catch {
                note = String(localized: "Couldn't translate: \(error.localizedDescription)")
            }
            translating = false
        }
    }
}
