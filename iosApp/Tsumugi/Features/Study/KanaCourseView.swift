import Shared
import SwiftUI

/// The kana course for absolute beginners (G-13, D-117): 15 hiragana and 15 katakana lessons with original
/// mnemonics (AI-drafted until reviewed, so badged), stroke practice on the writing canvas, and a placement check
/// per script that skips it.
struct KanaCourseView: View {
    @Environment(AppModel.self) private var app
    @State private var course: KanaCourse?
    @State private var status: KanaCourseStatus?
    @State private var queue: [String] = []
    @State private var loadError: String?

    var body: some View {
        List {
            if let loadError {
                Section {
                    Label("Couldn't load the kana course: \(loadError)", systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button("Retry") { Task { await load() } }
                }
            }
            if let status {
                Section {
                    LabeledContent("Hiragana", value: scriptStatus(done: Int(status.hiraganaLessonsDone), total: Int(status.lessonsPerScript), skipped: status.hiraganaSkipped))
                    LabeledContent("Katakana", value: scriptStatus(done: Int(status.katakanaLessonsDone), total: Int(status.lessonsPerScript), skipped: status.katakanaSkipped))
                    NavigationLink("Placement check: I already know hiragana") { KanaPlacementView(script: .hiragana) }
                    NavigationLink("Placement check: I already know katakana") { KanaPlacementView(script: .katakana) }
                } footer: {
                    Text("Get at least 9 of 10 right on a script's check to skip its lessons. Each finished lesson adds its kana to your reviews: see the kana, type the romaji.")
                }
            }
            if let course {
                ForEach([KanaScript.hiragana, .katakana], id: \.self) { script in
                    Section(script == .hiragana ? "Hiragana" : "Katakana") {
                        ForEach(course.lessons().filter { $0.script == script }, id: \.id) { lesson in
                            NavigationLink {
                                KanaLessonView(lesson: lesson) { Task { await load() } }
                            } label: {
                                HStack {
                                    VStack(alignment: .leading) {
                                        Text(lesson.title).font(.japanese(size: 17))
                                        Text(lesson.chars.map(\.kana).joined(separator: " ")).font(.japanese(size: 13)).foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    if queue.first == lesson.id { TagView(String(localized: "Next")) }
                                }
                            }
                        }
                    }
                }
            } else if loadError == nil {
                ProgressView()
            }
        }
        .navigationTitle("Kana")
        .task { await load() }
    }

    private func scriptStatus(done: Int, total: Int, skipped: Bool) -> String {
        skipped ? String(localized: "Skipped (placement passed)") : String(localized: "\(done) of \(total) lessons")
    }

    private func load() async {
        loadError = nil
        do {
            let kana = try await app.graph.kana()
            course = kana
            status = try await kana.status(settings: app.graph.settings)
            queue = try await kana.lessonQueue(settings: app.graph.settings, limit: 1).map(\.id)
        } catch {
            loadError = error.localizedDescription
        }
    }
}

/// Today's kana block: the next lessons of the course in order, then the block is finished.
struct KanaTodayView: View {
    @Environment(AppModel.self) private var app
    let count: Int
    let onFinished: () -> Void

    @State private var lessons: [KanaLesson]?
    @State private var index = 0
    @State private var error: String?

    var body: some View {
        Group {
            if let error {
                ContentUnavailableView {
                    Label("Couldn't load the kana lessons", systemImage: "exclamationmark.triangle")
                } description: {
                    Text(error)
                } actions: {
                    Button("Retry") { Task { await load() } }.buttonStyle(.borderedProminent)
                }
            } else if let lessons {
                if lessons.isEmpty || index >= lessons.count {
                    VStack(spacing: 12) {
                        Label("Kana lessons done for today", systemImage: "checkmark.circle").font(.title3.weight(.semibold))
                        Button("Finish") { onFinished() }.buttonStyle(.borderedProminent)
                    }
                } else {
                    KanaLessonView(lesson: lessons[index]) { index += 1 }
                        .id(lessons[index].id)
                }
            } else {
                ProgressView()
            }
        }
        .task { if lessons == nil { await load() } }
    }

    private func load() async {
        error = nil
        do {
            let kana = try await app.graph.kana()
            lessons = try await kana.lessonQueue(settings: app.graph.settings, limit: Int32(max(1, count)))
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// One kana lesson: each kana with its mnemonic and stroke practice, then "Add to reviews".
struct KanaLessonView: View {
    @Environment(AppModel.self) private var app
    let lesson: KanaLesson
    let onCompleted: () -> Void

    @State private var index = 0
    @State private var saving = false
    @State private var done = false
    @State private var error: String?
    @State private var voice = VoicePlayer()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(lesson.title).font(.japanese(size: 22, weight: .semibold))
                if index == 0 {
                    Text(lesson.intro).font(.subheadline)
                    if lesson.introSource == "llm" { AiBadge() }
                }
                if done {
                    Label("Added to your reviews", systemImage: "checkmark.circle").font(.headline).foregroundStyle(.green)
                    Button("Continue") { onCompleted() }.buttonStyle(.borderedProminent)
                } else if index < lesson.chars.count {
                    charView(lesson.chars[index])
                    HStack {
                        if index > 0 { Button("Back") { index -= 1 }.buttonStyle(.bordered) }
                        Button(index < lesson.chars.count - 1 ? "Next" : "Add to reviews") {
                            if index < lesson.chars.count - 1 { index += 1 } else { complete() }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(saving)
                    }
                    Text("\(index + 1) of \(lesson.chars.count)").font(.caption).foregroundStyle(.secondary)
                }
                if let error { Text(error).font(.caption).foregroundStyle(.red) }
            }
            .padding()
        }
        .navigationTitle("Kana lesson")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear { voice.stop() }
    }

    @ViewBuilder
    private func charView(_ c: KanaChar) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 16) {
                Text(c.kana).font(.japanese(size: 96, relativeTo: .largeTitle)).japaneseSpeech()
                VStack(alignment: .leading) {
                    Text(c.romaji.first ?? "").font(.title.weight(.semibold))
                    if c.romaji.count > 1 {
                        Text("Also: \(c.romaji.dropFirst().joined(separator: ", "))").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            Button {
                Task { await voice.say(c.kana) }
            } label: {
                Label("Listen", systemImage: "speaker.wave.2")
            }
            .buttonStyle(.bordered)
            if !c.mnemonic.isEmpty {
                Text(c.mnemonic).font(.body)
                if c.mnemonicSource == "llm" { AiBadge() }
            }
            NavigationLink(value: Route.writingPractice(c.strokeChars)) {
                Label("Practise writing \(c.kana)", systemImage: "pencil.and.outline")
            }
            .buttonStyle(.bordered)
        }
    }

    private func complete() {
        saving = true
        error = nil
        let graph = app.graph
        let lesson = self.lesson
        Task {
            do {
                let kana = try await graph.kana()
                _ = try await kana.completeLesson(lesson: lesson, settings: graph.settings)
                done = true
            } catch {
                self.error = String(localized: "Couldn't add the lesson: \(error.localizedDescription)")
            }
            saving = false
        }
    }
}

/// Placement check (G-13): read 10 random kana; 90% or better skips that script's lessons.
struct KanaPlacementView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let script: KanaScript

    @State private var questions: [KanaChar] = []
    @State private var answers: [KanaPlacementAnswer] = []
    @State private var typed = ""
    @State private var result: KanaPlacementResult?
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let result {
                Text("\(Int(result.correct)) of \(Int(result.total)) correct").font(.title2.weight(.semibold))
                Text(result.passed
                     ? String(localized: "Passed: these lessons are skipped. The kana still come up if you add them later.")
                     : String(localized: "Not quite: the lessons stay in your course. They go quickly for kana you know."))
                Button("Done") { dismiss() }.buttonStyle(.borderedProminent)
            } else if answers.count < questions.count {
                let q = questions[answers.count]
                Text("\(answers.count + 1) / \(questions.count)").font(.caption.weight(.semibold))
                Text(q.kana).font(.japanese(size: 96, relativeTo: .largeTitle)).frame(maxWidth: .infinity)
                TextField("Romaji", text: $typed)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.asciiCapable)
                    .textFieldStyle(.roundedBorder)
                    .focused($focused)
                    .onSubmit { answer(q) }
                HStack {
                    Button("Next") { answer(q) }.buttonStyle(.borderedProminent)
                    Button("I don't know") { typed = ""; answer(q) }.buttonStyle(.bordered)
                }
            } else if questions.isEmpty {
                ProgressView()
            }
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
            Spacer()
        }
        .padding()
        .navigationTitle(script == .hiragana ? "Hiragana check" : "Katakana check")
        .task {
            guard questions.isEmpty, let kana = try? await app.graph.kana() else { return }
            questions = kana.placementQuestions(script: script, seed: Int64(Date().timeIntervalSince1970), count: 10)
            focused = true
        }
    }

    private func answer(_ q: KanaChar) {
        answers.append(KanaPlacementAnswer(kana: q, typed: typed))
        typed = ""
        guard answers.count == questions.count else { return }
        let graph = app.graph
        let all = answers
        let s = script
        Task {
            do {
                let kana = try await graph.kana()
                result = try await kana.gradePlacement(script: s, answers: all, settings: graph.settings)
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}
