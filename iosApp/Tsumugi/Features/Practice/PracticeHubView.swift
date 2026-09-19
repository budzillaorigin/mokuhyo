import Shared
import SwiftUI

/// Practice tab (BRIEF §6): Speak · Listen · Write · Exams.
struct PracticeHubView: View {
    @Environment(AppModel.self) private var app
    @State private var levelKanji: [String] = []
    @State private var pathLevel: Int?
    /// The pitch-accent test shows only with the pitch audio pack (rule 20, D-284).
    @State private var pitchAvailable = false

    var body: some View {
        List {
            Section {
                AiStatusBanner(scripted: "Role-plays and the OPI simulator still work: they follow scripted turns and question banks.")
                    .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
            }
            Section("Speak") {
                NavigationLink(value: Route.freeTalk) {
                    LabeledContent("Free talk", value: "Open conversation at your level")
                }
                NavigationLink(value: Route.scenarios) {
                    LabeledContent("Role-play scenarios", value: "Shop, station, doctor…")
                }
                NavigationLink(value: Route.drillSets) {
                    LabeledContent("Speaking drills", value: "Hands-free, screen off")
                }
                NavigationLink(value: Route.pronunciation("")) {
                    LabeledContent("Pronunciation check", value: "Mora, pitch, fluency")
                }
                NavigationLink(value: Route.pomodoro) {
                    LabeledContent("Speaking session", value: "25-minute Pomodoro")
                }
                NavigationLink(value: Route.opi) {
                    LabeledContent("OPI simulator", value: "Practice interview")
                }
            }
            Section("Listen") {
                NavigationLink(value: Route.dialogues) {
                    LabeledContent("Dialogues", value: "Listen, fill gaps, order")
                }
                if pitchAvailable {
                    NavigationLink(value: Route.pitchTest) {
                        LabeledContent("Pitch-accent test", value: "Hear it, name the pattern")
                    }
                }
                NavigationLink(value: Route.minimalPairs) {
                    LabeledContent("Minimal pairs", value: "Train your ear")
                }
                NavigationLink(value: Route.media) {
                    LabeledContent("Media player", value: "Your videos + subtitles")
                }
                NavigationLink(value: Route.podcasts) {
                    LabeledContent("Podcasts", value: "RSS, downloads, transcripts")
                }
                NavigationLink(value: Route.lyrics) {
                    LabeledContent("Lyrics", value: "Karaoke reading of your songs")
                }
            }
            Section("Write") {
                if levelKanji.isEmpty {
                    Text("Writing practice uses the kanji of your current path level. It appears here once the kanji path pack is installed.")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    NavigationLink(value: Route.writingPractice(levelKanji)) {
                        LabeledContent("Kanji writing", value: pathLevel.map { "Level \($0) · \(levelKanji.count) kanji" } ?? "\(levelKanji.count) kanji")
                    }
                }
                NavigationLink(value: Route.handwriting) {
                    LabeledContent("Draw to search", value: "Handwrite a kanji")
                }
            }
            Section("Games") {
                NavigationLink(value: Route.games) {
                    LabeledContent("Reflex and Atom", value: "Quick word games")
                }
            }
            Section("Translate") {
                NavigationLink(value: Route.translation) {
                    LabeledContent("Translation workbench", value: "Written and sight translation")
                }
            }
            Section("Exams") {
                NavigationLink(value: Route.exams) {
                    LabeledContent("Exam simulators", value: "JLPT · DLPT · OPI")
                }
            }
            Section {
                NavigationLink("AI & speech settings", value: Route.aiSettings)
            }
        }
        .navigationTitle("Practice")
        .task {
            await loadWriting()
            pitchAvailable = (try? await app.graph.pitchTest()) != nil
        }
    }

    private func loadWriting() async {
        guard let path = try? await app.graph.path(), let status = try? await path.status() else { return }
        let entries = (try? await path.level(level: status.currentLevel)) ?? []
        levelKanji = entries.filter { $0.item.kind == .kanji }.map { $0.item.display }
        pathLevel = Int(status.currentLevel)
    }
}
