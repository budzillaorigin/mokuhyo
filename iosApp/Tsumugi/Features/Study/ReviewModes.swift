import Shared
import SwiftUI
import UIKit

/// MEANING_CHOICE (G-05): the sentence with the construction in 【】, four meanings as buttons.
struct MeaningChoiceAnswer: View {
    let choices: [String]
    let onPick: (String) -> Void

    var body: some View {
        VStack(spacing: 8) {
            Text("What does the marked part mean?").font(.subheadline).foregroundStyle(.secondary)
            ForEach(Array(choices.enumerated()), id: \.offset) { i, choice in
                Button {
                    onPick(String(i))
                } label: {
                    Text(choice).frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.bordered)
            }
        }
    }
}

/// PRODUCTION (G-05): write the English sentence in Japanese with the grammar point.
struct ProductionAnswer: View {
    let onSubmit: (String) -> Void
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Say it in Japanese using this grammar.").font(.subheadline).foregroundStyle(.secondary)
            TextField("日本語で", text: $text, axis: .vertical)
                .font(.japanese(size: 20))
                .lineLimit(1...4)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                .accessibilityLabel(Text("Your Japanese sentence"))
            Button("Check") {
                let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if !t.isEmpty { onSubmit(t) }
            }
            .buttonStyle(.borderedProminent)
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }
}

/// The production grade: the model answer always; the AI rubric (labeled) when a model graded it; otherwise why
/// not, and the learner grades themselves.
struct ProductionFeedback: View {
    let grade: ProductionGrade
    let given: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let given { Text("You wrote: \(given)").font(.japanese(size: 17)) }
            Text("Model answer").font(.caption.weight(.semibold))
            Text(grade.modelAnswer).font(.japanese(size: 20)).japaneseSpeech().textSelection(.enabled)
            if !grade.english.isEmpty { Text(grade.english).font(.caption).foregroundStyle(.secondary) }
            if let rubric = grade.rubric {
                AIBadge(engine: grade.engine)
                HStack(spacing: 14) {
                    score("Meaning", Int(rubric.meaning))
                    score("Grammar", Int(rubric.grammar))
                    score("Form", Int(rubric.form))
                }
                if !rubric.corrected.isEmpty {
                    Text("Corrected: \(rubric.corrected)").font(.japanese(size: 17))
                }
                if !rubric.feedback.isEmpty { Text(rubric.feedback).font(.subheadline) }
            } else {
                Text(grade.unavailable.map { String(localized: "Not graded by AI: \($0)") } ?? String(localized: "No AI model is set up, so compare with the model answer and grade yourself."))
                    .font(.caption).foregroundStyle(.secondary)
            }
            if grade.checks.constructionFound?.boolValue == false {
                Label("The grammar point's construction wasn't found in your answer.", systemImage: "exclamationmark.circle")
                    .font(.caption).foregroundStyle(.orange)
            }
        }
    }

    private func score(_ label: LocalizedStringKey, _ value: Int) -> some View {
        VStack {
            Text("\(value)/2").font(.headline.monospacedDigit())
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
    }
}

/// MINIMAL_PAIR (G-06): play one word of the pair, pick which one it was.
struct MinimalPairAnswer: View {
    let prompt: MinimalPairPrompt
    let onPick: (String) -> Void

    @State private var player = TtsClipPlayer()

    var body: some View {
        VStack(spacing: 10) {
            Button {
                Task { await player.play(prompt.played.text) }
            } label: {
                Label("Play", systemImage: "speaker.wave.2.fill").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            Text("Which word did you hear?").font(.subheadline).foregroundStyle(.secondary)
            HStack {
                side(prompt.pair.a, code: "a")
                side(prompt.pair.b, code: "b")
            }
        }
        .task(id: prompt.played.text) { await player.play(prompt.played.text) }
        .onDisappear { player.stop() }
    }

    private func side(_ word: PairSide, code: String) -> some View {
        Button {
            player.stop()
            onPick(code)
        } label: {
            VStack {
                Text(word.text).font(.japanese(size: 26))
                if word.reading != word.text { Text(word.reading).font(.japanese(size: 14)).foregroundStyle(.secondary) }
                if !word.gloss.isEmpty { Text(word.gloss).font(.caption2).foregroundStyle(.secondary) }
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
    }
}

/// The front of a LISTENING card: plays the learner's own recording, a media clip's cut audio, or the reading with
/// the system voice. The text stays hidden until the answer is shown.
struct ListeningFront: View {
    @Environment(AppModel.self) private var app
    let cardId: String
    let item: StudyItem

    @State private var voice = VoicePlayer()
    @State private var path: String?
    @State private var fallback: String?
    @State private var loaded = false
    @State private var note: String?

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "ear").font(.system(size: 44)).foregroundStyle(.tint).accessibilityHidden(true)
            Button {
                Task { await play() }
            } label: {
                Label("Play again", systemImage: "speaker.wave.2.fill")
            }
            .buttonStyle(.bordered)
            .disabled(!loaded)
            if let note { Text(note).font(.caption).foregroundStyle(.secondary) }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 20)
        .background(item.kind.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 16))
        .task(id: cardId) {
            await resolve()
            await play()
        }
        .onDisappear { voice.stop() }
    }

    private func resolve() async {
        let graph = app.graph
        if let clip = graph.clips.contextOf(itemContext: item.context),
           let rec = try? await graph.recordings.recordingsFor(kind: .clip, ref: clip.clipId).first,
           (try? await graph.recordings.fileExists(recording: rec))?.boolValue == true {
            path = graph.recordings.pathOf(recording: rec)
        } else if let render = try? await graph.personalCards.render(cardId: cardId) {
            path = render.front.audioPath
            fallback = render.front.speakFallback
        }
        if fallback == nil { fallback = item.reading ?? item.primaryText }
        if path == nil { note = String(localized: "The recorded audio isn't on this device; the system voice reads it instead.") }
        loaded = true
    }

    private func play() async {
        if let path, await voice.play(file: path) { return }
        if let fallback { await voice.say(fallback) }
    }
}

/// A personal (picture) card's face: the learner's picture on the front; word, reading and note on the back.
struct PersonalCardFace: View {
    @Environment(AppModel.self) private var app
    let cardId: String
    let back: Bool

    @State private var face: CardFace?
    @State private var missing: [String] = []
    @State private var voice = VoicePlayer()

    var body: some View {
        VStack(spacing: 8) {
            if let path = face?.imagePath, let image = UIImage(contentsOfFile: path) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 260)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel(Text("Your picture"))
            }
            if let text = face?.text {
                Text(text).font(.japanese(size: 34)).japaneseSpeech()
            }
            if let reading = face?.reading { Text(reading).font(.japanese(size: 18)).foregroundStyle(.secondary) }
            if let note = face?.note { Text(note).font(.subheadline) }
            if back, face?.audioPath != nil || face?.speakFallback != nil {
                Button {
                    Task {
                        if let p = face?.audioPath, await voice.play(file: p) { return }
                        if let s = face?.speakFallback { await voice.say(s) }
                    }
                } label: {
                    Label("Play", systemImage: "speaker.wave.2")
                }
                .buttonStyle(.bordered)
            }
            if !missing.isEmpty {
                Text("Not on this device yet: \(missing.joined(separator: ", ")). Turn on recordings sync in Settings.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .task(id: "\(cardId)-\(back)") {
            guard let render = try? await app.graph.personalCards.render(cardId: cardId) else { return }
            face = back ? render.back : render.front
            missing = render.missing
        }
        .onDisappear { voice.stop() }
    }
}
