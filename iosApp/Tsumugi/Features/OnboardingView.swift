import Shared
import SwiftUI

/// First-run setup: goal, daily budget, a quick kanji check for the starting level. Skippable throughout.
struct OnboardingView: View {
    @Environment(AppModel.self) private var app
    let onDone: (_ openImport: Bool) -> Void

    @State private var step = 0
    @State private var goal: LearningGoal = .general
    @State private var budget = 20
    @State private var questions: [PlacementQuestion] = []
    @State private var answers: [PlacementQuestion: Bool] = [:]
    @State private var index = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                switch step {
                case 0:
                    Text(verbatim: "紡ぎ").font(.japanese(size: 56, weight: .semibold, relativeTo: .largeTitle)).japaneseSpeech()
                    Text("Welcome to Tsumugi").font(.title2.weight(.semibold))
                    Text("Kanji, vocabulary, grammar, reading and speaking in one offline app. Nothing needs an account or a subscription.")
                    Text("What are you working towards?").font(.headline)
                    ForEach([LearningGoal.jlpt, .dlpt, .general], id: \.self) { g in
                        Button { goal = g } label: {
                            HStack {
                                // Shared-core label used as the localization key (Localizable.xcstrings).
                                Text(LocalizedStringKey(g.label))
                                Spacer()
                                if goal == g { Image(systemName: "checkmark").accessibilityHidden(true) }
                            }
                        }
                        .buttonStyle(.bordered)
                        .accessibilityAddTraits(goal == g ? .isSelected : [])
                    }
                    Button("Next") { step = 1 }.buttonStyle(.borderedProminent)
                case 1:
                    Text("How much time a day?").font(.title2.weight(.semibold))
                    Text("Today's plan is sized to this. You can change it any day.")
                    Picker("Daily budget", selection: $budget) {
                        ForEach([10, 20, 40, 60], id: \.self) { Text("\($0) min").tag($0) }
                    }
                    .pickerStyle(.segmented)
                    Button("Next") { step = questions.isEmpty ? 3 : 2 }.buttonStyle(.borderedProminent)
                case 2:
                    if index < questions.count {
                        let q = questions[index]
                        Text("Quick kanji check (\(index + 1)/\(questions.count))").font(.headline)
                        Text("Do you know this kanji's meaning and a reading?")
                        Text(q.item.display).font(.japanese(size: 96, relativeTo: .largeTitle)).frame(maxWidth: .infinity).japaneseSpeech()
                        HStack {
                            Button("I know it") { answers[q] = true; index += 1 }.buttonStyle(.borderedProminent)
                            Button("Not yet") { answers[q] = false; index += 1 }.buttonStyle(.bordered)
                        }
                        Button("Skip — start from level 1") { answers = [:]; step = 3 }
                    } else {
                        ProgressView().onAppear { step = 3 }
                    }
                case 3:
                    // Optional (BRIEF_V2 §6.1 "I know these"): an intermediate learner isn't drilled on 猫.
                    KnownWordsStep { step = 4 }
                case 4:
                    // Interest and domain tracks (BRIEF_V2 §6.5, D-218): optional, changeable any time.
                    TrackOnboardingStep { step = 5 }
                default:
                    Text("You're set").font(.title2.weight(.semibold))
                    Text("Kanji path starts at level \(suggestedLevel). Earlier items stay available if you want them.")
                    Text("Coming from WaniKani, Anki (NihongoShark), Bunpro or imiwa? Import your progress so it carries over.")
                    Button("Start learning") { finish(openImport: false) }.buttonStyle(.borderedProminent)
                    Button("Import my progress first") { finish(openImport: true) }.buttonStyle(.bordered)
                }
            }
            .padding(24)
        }
        .task { questions = (try? await app.graph.onboarding.placementQuestions(seed: Int64(Date().timeIntervalSince1970))) ?? [] }
    }

    private var suggestedLevel: Int {
        answers.isEmpty ? 1 : Int(app.graph.onboarding.suggestedLevel(answers: answers.mapValues { KotlinBoolean(bool: $0) }))
    }

    private func finish(openImport: Bool) {
        let level = Int32(suggestedLevel)
        // G-13: how many kanji the learner knew; nil when the check was skipped. Zero starts Today with the kana course.
        let known: KotlinInt? = answers.isEmpty ? nil : KotlinInt(int: Int32(answers.values.filter { $0 }.count))
        Task {
            try? await app.graph.onboarding.finish(goal: goal, budgetMinutes: Int32(budget), startLevel: level, kanjiKnown: known)
            onDone(openImport)
        }
    }
}

/// Onboarding's optional "I know these" step (BRIEF_V2 §6.1, D-151): pages of the frequency list, most common first;
/// the words tapped are marked known (synced), so lessons and coverage skip them. Skippable at any point.
struct KnownWordsStep: View {
    @Environment(AppModel.self) private var app
    let onDone: () -> Void

    @State private var batch: FrequencyBatch?
    @State private var chosen: Set<Int64> = []
    @State private var marked = 0
    @State private var loading = false
    @State private var failure: String?

    private let columns = [GridItem(.adaptive(minimum: 88), spacing: 8)]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Words you already know").font(.title2.weight(.semibold))
            Text("Tap the ones you know. They count as known, so lessons and coverage skip them. You can skip this.")
            if let failure {
                ErrorRetryView(message: failure) { Task { await load(after: batch?.nextAfter ?? 0) } }
            } else if let batch {
                if batch.words.isEmpty {
                    Text("No more words to check.").foregroundStyle(.secondary)
                } else {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                        ForEach(batch.words, id: \.entryId) { w in
                            let on = chosen.contains(w.entryId)
                            Button {
                                if on { chosen.remove(w.entryId) } else { chosen.insert(w.entryId) }
                            } label: {
                                VStack(spacing: 1) {
                                    Text(w.headword).font(.japanese(size: 18)).lineLimit(1)
                                    Text(w.reading).font(.japanese(size: 10)).foregroundStyle(.secondary).lineLimit(1)
                                }
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .padding(4)
                                .background(on ? Color.accentColor.opacity(0.2) : Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("\(w.headword), \(w.gloss)"))
                            .accessibilityAddTraits(on ? .isSelected : [])
                        }
                    }
                    HStack {
                        Button("Select all") { chosen = Set(batch.words.map(\.entryId)) }
                        Button("Clear") { chosen = [] }
                    }
                    .font(.caption)
                }
                if marked > 0 { Text("\(marked.formatted()) words marked known so far.").font(.caption).foregroundStyle(.secondary) }
                if !batch.exhausted && !batch.words.isEmpty {
                    Button(loading ? "Saving…" : "Mark these and show more") { Task { await next() } }
                        .buttonStyle(.borderedProminent)
                        .disabled(loading)
                }
                Button(chosen.isEmpty ? "Done" : "Mark these and finish") {
                    Task {
                        await save()
                        onDone()
                    }
                }
                .buttonStyle(.bordered)
                .disabled(loading)
            } else {
                ProgressView()
            }
        }
        .task { if batch == nil { await load(after: 0) } }
    }

    private func load(after: Int32) async {
        failure = nil
        do {
            let next = try await app.graph.knownWords.frequencyBatch(afterOrd: after, size: 40)
            // Without the dictionary pack there's nothing to check: move on.
            if next.words.isEmpty && batch == nil { onDone(); return }
            batch = next
            chosen = []
        } catch {
            failure = String(localized: "Couldn't load the word list: \(error.localizedDescription)")
        }
    }

    private func save() async {
        guard !chosen.isEmpty else { return }
        loading = true
        defer { loading = false }
        do {
            try await app.graph.knownWords.markKnown(entryIds: chosen.map { KotlinLong(longLong: $0) }, source: "ONBOARDING")
            marked += chosen.count
            chosen = []
        } catch {
            failure = String(localized: "Couldn't save: \(error.localizedDescription)")
        }
    }

    private func next() async {
        await save()
        guard failure == nil, let after = batch?.nextAfter else { return }
        await load(after: after)
    }
}
