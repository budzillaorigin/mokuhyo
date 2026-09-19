import Shared
import SwiftUI

/// Mini-games (BRIEF_V2 §6.9, D-286, D-287, D-303): Reflex (timed word ↔ meaning true/false) and Atom (build the
/// reading from kana tiles). The rules, scoring and clocks are the shared `ReflexGame` / `AtomGame`; these screens only
/// draw them, poll the remaining time and report taps. Rounds are stored with `GameScores` (weekly challenge points).
enum GameText {
    static func name(_ code: String) -> String {
        switch code {
        case "REFLEX": String(localized: "Reflex")
        case "ATOM": String(localized: "Atom")
        default: code
        }
    }

    /// "12.3" seconds.
    static func seconds(_ ms: Int64) -> String {
        String(format: "%.1f", Double(max(0, ms)) / 1000)
    }
}

struct GamesHomeView: View {
    @Environment(AppModel.self) private var app

    @State private var loading = true
    @State private var error: String?
    @State private var bestReflex = 0
    @State private var bestAtom = 0
    @State private var week = 0
    @State private var recent: [GameScoreEntryRow] = []

    var body: some View {
        List {
            Section {
                NavigationLink(value: Route.reflex) {
                    gameRow("REFLEX", detail: String(localized: "Does the meaning match the word? Answer before the card runs out."), best: bestReflex)
                }
                NavigationLink(value: Route.atom) {
                    gameRow("ATOM", detail: String(localized: "Build the reading from kana tiles, look-alikes included."), best: bestAtom)
                }
            } footer: {
                Text("Words come from what you study, topped up with common words. Points count towards the weekly challenge.")
            }
            if loading {
                ProgressView()
            } else if let error {
                ErrorRetryView(message: error) { Task { await load() } }
            } else {
                Section("This week") {
                    LabeledContent("Points in both games", value: String(week))
                }
                if !recent.isEmpty {
                    Section("Recent rounds") {
                        ForEach(Array(recent.enumerated()), id: \.offset) { _, r in
                            HStack {
                                Text(verbatim: GameText.name(r.gameCode)).font(.subheadline.weight(.medium))
                                Text(verbatim: r.day).font(.caption).foregroundStyle(.secondary)
                                Spacer()
                                Text(verbatim: "\(r.correct)/\(r.total)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                Text(verbatim: String(r.score)).font(.body.monospacedDigit().weight(.semibold))
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Games")
        .task { await load() }
    }

    private func gameRow(_ code: String, detail: String, best: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(verbatim: GameText.name(code)).font(.headline)
                Spacer()
                if best > 0 { Text("Best \(String(best))").font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
            }
            Text(verbatim: detail).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func load() async {
        loading = true
        error = nil
        let graph = app.graph
        let s = SwiftSupport.shared
        do {
            let reflexBest = try await s.gameBest(graph: graph, gameCode: "REFLEX")
            let atomBest = try await s.gameBest(graph: graph, gameCode: "ATOM")
            let points = try await s.gameWeekPoints(graph: graph)
            bestReflex = Int(reflexBest.intValue)
            bestAtom = Int(atomBest.intValue)
            week = Int(points.intValue)
            let reflexRows = try await s.gameRecent(graph: graph, gameCode: "REFLEX", limit: 5)
            let atomRows = try await s.gameRecent(graph: graph, gameCode: "ATOM", limit: 5)
            recent = (reflexRows + atomRows).sorted { $0.day > $1.day }
        } catch {
            self.error = String(localized: "Couldn't load your scores: \(error.localizedDescription)")
        }
        loading = false
    }
}

/// The result of a finished round, with the best score ever in that game.
struct GameResultCard: View {
    let result: GameResult
    let best: Int
    let saveError: String?

    var body: some View {
        let code = SwiftSupport.shared.gameResultCode(result: result)
        VStack(alignment: .leading, spacing: 8) {
            Text("Round over").font(.headline)
            HStack(alignment: .firstTextBaseline) {
                Text(verbatim: String(result.score)).font(.largeTitle.weight(.bold).monospacedDigit())
                Text("points").foregroundStyle(.secondary)
                if Int(result.score) >= best && result.score > 0 {
                    TagView(String(localized: "New best"))
                }
            }
            if code == "ATOM" {
                Text("\(String(result.correct)) of \(String(result.total)) solved")
            } else {
                Text("\(String(result.correct)) of \(String(result.total)) right")
            }
            Text("Best streak: \(String(result.bestStreak))").font(.subheadline)
            if best > 0 { Text("Best ever: \(String(best))").font(.caption).foregroundStyle(.secondary) }
            if let saveError { Text(saveError).font(.caption).foregroundStyle(.red) }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Stores a finished round and returns the best score ever in its game (for the result card).
enum GameRecorder {
    static func save(_ result: GameResult, graph: AppGraph) async -> (best: Int, error: String?) {
        let s = SwiftSupport.shared
        do {
            _ = try await s.recordGame(graph: graph, result: result)
            let best = try await s.gameBest(graph: graph, gameCode: s.gameResultCode(result: result))
            return (Int(best.intValue), nil)
        } catch {
            return (0, String(localized: "Couldn't save the score: \(error.localizedDescription)"))
        }
    }
}

// MARK: - Reflex

struct ReflexGameView: View {
    @Environment(AppModel.self) private var app

    @State private var loading = true
    @State private var error: String?
    @State private var game: ReflexGame?
    @State private var result: GameResult?
    @State private var best = 0
    @State private var saveError: String?
    @State private var round = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if loading {
                    ProgressView()
                } else if let error {
                    ErrorRetryView(message: error) { Task { await load() } }
                } else if let result {
                    GameResultCard(result: result, best: best, saveError: saveError)
                    Button("Play again") { Task { await load() } }.buttonStyle(.borderedProminent)
                } else if let game {
                    ReflexPlayView(game: game) { finished in finish(finished) }
                        .id(round)
                } else {
                    ContentUnavailableView("Not enough words yet", systemImage: "square.stack.3d.up.slash",
                                           description: Text("Reflex needs a few words with meanings. Learn some words first, or install the dictionary pack."))
                }
            }
            .padding()
        }
        .navigationTitle("Reflex")
        .task { await load() }
    }

    private func load() async {
        loading = true
        error = nil
        result = nil
        saveError = nil
        do {
            game = try await app.graph.reflex()
            round += 1
        } catch {
            self.error = String(localized: "Couldn't start the game: \(error.localizedDescription)")
        }
        loading = false
    }

    private func finish(_ finished: GameResult) {
        let graph = app.graph
        Task {
            let saved = await GameRecorder.save(finished, graph: graph)
            best = saved.best
            saveError = saved.error
            result = finished
        }
    }
}

/// One Reflex round: a word and a meaning, "Match" or "No match" before the card's time runs out.
struct ReflexPlayView: View {
    let game: ReflexGame
    let onFinish: (GameResult) -> Void

    @State private var card: ReflexCard?
    @State private var remainingMs: Int64 = 0
    @State private var cardMs: Int64 = 0
    @State private var cardLimitMs: Int64 = 1
    @State private var flash: String?
    @State private var flashGood = true
    @State private var done = false
    @State private var started = false

    private let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label(GameText.seconds(remainingMs), systemImage: "timer").font(.headline.monospacedDigit())
                Spacer()
                Text("Streak \(String(game.streak))").font(.subheadline.monospacedDigit())
                Text(verbatim: String(game.score)).font(.title2.monospacedDigit().weight(.bold))
            }
            if let card {
                ProgressView(value: Double(cardMs), total: Double(max(1, cardLimitMs)))
                    .tint(cardMs * 3 < cardLimitMs ? .red : .accentColor)
                VStack(spacing: 10) {
                    Text(verbatim: card.word.text).font(.japanese(size: 40, relativeTo: .largeTitle)).japaneseSpeech()
                    if card.word.reading != card.word.text {
                        Text(verbatim: card.word.reading).font(.japanese(size: 16)).foregroundStyle(.secondary).japaneseSpeech()
                    }
                    Divider()
                    Text(verbatim: card.shownMeaning).font(.title3).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding()
                .background(.quaternary.opacity(0.3), in: RoundedRectangle(cornerRadius: 14))
                HStack(spacing: 12) {
                    Button { answer(false) } label: {
                        Label("No match", systemImage: "xmark").frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .tint(.red)
                    Button { answer(true) } label: {
                        Label("Match", systemImage: "checkmark").frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.green)
                }
            } else if !done {
                ProgressView()
            }
            if let flash {
                Text(verbatim: flash).font(.headline).foregroundStyle(flashGood ? .green : .red)
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            nextCard()
        }
        .onReceive(timer) { _ in tick() }
    }

    private func tick() {
        guard !done else { return }
        let s = SwiftSupport.shared
        remainingMs = s.reflexRemainingMs(game: game)
        if game.finished {
            finish()
            return
        }
        guard card != nil else { return }
        cardMs = s.reflexCardRemainingMs(game: game)
        if cardMs <= 0 {
            if game.timeout() != nil {
                flash = String(localized: "Too slow")
                flashGood = false
            }
            nextCard()
        }
    }

    private func answer(_ match: Bool) {
        guard !done, card != nil else { return }
        if let outcome = game.answer(saysMatch: match) {
            if outcome.timedOut {
                flash = String(localized: "Too slow")
                flashGood = false
            } else if outcome.correct {
                flash = "+\(outcome.points)"
                flashGood = true
            } else {
                flash = String(localized: "Wrong")
                flashGood = false
            }
        }
        nextCard()
    }

    private func nextCard() {
        card = game.next()
        if let card {
            cardLimitMs = SwiftSupport.shared.reflexCardLimitMs(card: card)
            cardMs = cardLimitMs
        } else {
            finish()
        }
    }

    private func finish() {
        guard !done else { return }
        done = true
        card = nil
        onFinish(game.result())
    }
}

// MARK: - Atom

struct AtomGameView: View {
    @Environment(AppModel.self) private var app

    @State private var loading = true
    @State private var error: String?
    @State private var game: AtomGame?
    @State private var result: GameResult?
    @State private var best = 0
    @State private var saveError: String?
    @State private var round = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if loading {
                    ProgressView()
                } else if let error {
                    ErrorRetryView(message: error) { Task { await load() } }
                } else if let result {
                    GameResultCard(result: result, best: best, saveError: saveError)
                    Button("Play again") { Task { await load() } }.buttonStyle(.borderedProminent)
                } else if let game {
                    AtomPlayView(game: game) { finished in finish(finished) }
                        .id(round)
                } else {
                    ContentUnavailableView("Not enough words yet", systemImage: "square.stack.3d.up.slash",
                                           description: Text("Atom needs words with kana readings. Learn some words first, or install the dictionary pack."))
                }
            }
            .padding()
        }
        .navigationTitle("Atom")
        .task { await load() }
    }

    private func load() async {
        loading = true
        error = nil
        result = nil
        saveError = nil
        do {
            game = try await app.graph.atom()
            round += 1
        } catch {
            self.error = String(localized: "Couldn't start the game: \(error.localizedDescription)")
        }
        loading = false
    }

    private func finish(_ finished: GameResult) {
        let graph = app.graph
        Task {
            let saved = await GameRecorder.save(finished, graph: graph)
            best = saved.best
            saveError = saved.error
            result = finished
        }
    }
}

/// One Atom round: tap the reading's kana in order before the puzzle's time runs out.
struct AtomPlayView: View {
    let game: AtomGame
    let onFinish: (GameResult) -> Void

    @State private var puzzle: AtomPuzzle?
    @State private var assembled: [String] = []
    @State private var placed: [Int32] = []
    @State private var wrongTile: Int32?
    @State private var remainingMs: Int64 = 0
    @State private var puzzleMs: Int64 = 0
    @State private var puzzleLimitMs: Int64 = 1
    @State private var flash: String?
    @State private var flashGood = true
    @State private var done = false
    @State private var started = false
    @State private var score: Int32 = 0

    private let timer = Timer.publish(every: 0.1, on: .main, in: .common).autoconnect()
    private let columns = [GridItem(.adaptive(minimum: 56), spacing: 8)]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label(GameText.seconds(remainingMs), systemImage: "timer").font(.headline.monospacedDigit())
                Spacer()
                Text("Streak \(String(game.streak))").font(.subheadline.monospacedDigit())
                Text(verbatim: String(score)).font(.title2.monospacedDigit().weight(.bold))
            }
            if let puzzle {
                ProgressView(value: Double(puzzleMs), total: Double(max(1, puzzleLimitMs)))
                    .tint(puzzleMs * 3 < puzzleLimitMs ? .red : .accentColor)
                Text(verbatim: puzzle.prompt).font(.japanese(size: 22)).japaneseSpeech()
                HStack(spacing: 4) {
                    ForEach(0..<puzzle.target.count, id: \.self) { i in
                        Text(verbatim: i < assembled.count ? assembled[i] : "＿")
                            .font(.japanese(size: 26))
                            .frame(minWidth: 34)
                    }
                }
                .frame(maxWidth: .infinity)
                LazyVGrid(columns: columns, spacing: 8) {
                    ForEach(puzzle.tiles, id: \.id) { tile in
                        Button {
                            tap(tile.id)
                        } label: {
                            Text(verbatim: tile.kana).font(.japanese(size: 24)).frame(maxWidth: .infinity, minHeight: 48)
                        }
                        .buttonStyle(.bordered)
                        .tint(wrongTile == tile.id ? .red : .accentColor)
                        .disabled(placed.contains(tile.id))
                    }
                }
                HStack {
                    Button("Undo") { undo() }.disabled(assembled.isEmpty)
                    Spacer()
                    Button("Skip") { skip() }
                }
                .buttonStyle(.bordered)
            } else if !done {
                ProgressView()
            }
            if let flash {
                Text(verbatim: flash).font(.headline).foregroundStyle(flashGood ? .green : .red)
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            nextPuzzle()
        }
        .onReceive(timer) { _ in tick() }
    }

    private func tick() {
        guard !done else { return }
        let s = SwiftSupport.shared
        remainingMs = s.atomRemainingMs(game: game)
        if game.finished {
            finish()
            return
        }
        guard let puzzle else { return }
        puzzleMs = s.atomPuzzleRemainingMs(game: game)
        if puzzleMs <= 0 {
            _ = s.atomSkip(game: game)
            timedOut(puzzle)
            nextPuzzle()
        }
    }

    private func tap(_ tileId: Int32) {
        guard !done, let puzzle, let row = SwiftSupport.shared.atomTap(game: game, tileId: tileId) else { return }
        score = row.score
        assembled = row.assembled
        switch row.resultCode {
        case "PLACED":
            placed.append(tileId)
            wrongTile = nil
        case "SOLVED":
            flash = String(localized: "Solved: \(puzzle.word.reading) +\(String(row.points))")
            flashGood = true
            nextPuzzle()
        case "TIMED_OUT":
            timedOut(puzzle)
            nextPuzzle()
        default:
            wrongTile = tileId
        }
    }

    private func undo() {
        guard let row = SwiftSupport.shared.atomUndo(game: game) else { return }
        assembled = row.assembled
        if !placed.isEmpty { placed.removeLast() }
        wrongTile = nil
    }

    private func skip() {
        guard let puzzle else { return }
        _ = SwiftSupport.shared.atomSkip(game: game)
        timedOut(puzzle)
        nextPuzzle()
    }

    private func timedOut(_ puzzle: AtomPuzzle) {
        flash = String(localized: "It was \(puzzle.word.reading)")
        flashGood = false
    }

    private func nextPuzzle() {
        assembled = []
        placed = []
        wrongTile = nil
        score = game.score
        puzzle = game.next()
        if let puzzle {
            puzzleLimitMs = SwiftSupport.shared.atomPuzzleLimitMs(puzzle: puzzle)
            puzzleMs = puzzleLimitMs
        } else {
            finish()
        }
    }

    private func finish() {
        guard !done else { return }
        done = true
        puzzle = nil
        onFinish(game.result())
    }
}
