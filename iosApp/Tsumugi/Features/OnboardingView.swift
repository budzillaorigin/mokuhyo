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
                    Text("紡ぎ").font(.japanese(size: 56, weight: .semibold, relativeTo: .largeTitle))
                    Text("Welcome to Tsumugi").font(.title2.weight(.semibold))
                    Text("Kanji, vocabulary, grammar, reading and speaking in one offline app. Nothing needs an account or a subscription.")
                    Text("What are you working towards?").font(.headline)
                    ForEach([LearningGoal.jlpt, .dlpt, .general], id: \.self) { g in
                        Button { goal = g } label: {
                            HStack {
                                Text(g.label)
                                Spacer()
                                if goal == g { Image(systemName: "checkmark") }
                            }
                        }
                        .buttonStyle(.bordered)
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
                        Text(q.item.display).font(.japanese(size: 96, relativeTo: .largeTitle)).frame(maxWidth: .infinity)
                        HStack {
                            Button("I know it") { answers[q] = true; index += 1 }.buttonStyle(.borderedProminent)
                            Button("Not yet") { answers[q] = false; index += 1 }.buttonStyle(.bordered)
                        }
                        Button("Skip — start from level 1") { answers = [:]; step = 3 }
                    } else {
                        ProgressView().onAppear { step = 3 }
                    }
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
        Task {
            try? await app.graph.onboarding.finish(goal: goal, budgetMinutes: Int32(budget), startLevel: level)
            onDone(openImport)
        }
    }
}
