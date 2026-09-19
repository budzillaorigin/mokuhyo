import PhotosUI
import Shared
import SwiftUI
import UIKit

/// Personal picture/audio cards (G-12, Fluent Forever style, D-112): the learner's own picture on the front and
/// their own voice saying the word. Pictures are downscaled on device and stored with the shared image store;
/// nothing is uploaded unless recordings sync is switched on.
struct PersonalCardsView: View {
    @Environment(AppModel.self) private var app

    @State private var word = ""
    @State private var reading = ""
    @State private var meaning = ""
    @State private var note = ""
    @State private var photo: PhotosPickerItem?
    @State private var image: UIImage?
    @State private var recorder = Recorder()
    @State private var samples: [Float]?
    @State private var voice = VoicePlayer()
    @State private var saving = false
    @State private var message: String?
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                TextField("Word (日本語)", text: $word).font(.japanese(size: 20))
                TextField("Reading (optional)", text: $reading).font(.japanese(size: 17))
                TextField("Meaning (optional)", text: $meaning)
                TextField("Note (optional)", text: $note, axis: .vertical)
            } footer: {
                Text("Fluent Forever-style cards skip the translation: a picture that means the word to you works better.")
            }
            Section("Picture") {
                PhotosPicker(selection: $photo, matching: .images) {
                    Label(image == nil ? "Choose a picture" : "Change picture", systemImage: "photo")
                }
                if let image {
                    Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 200)
                        .accessibilityLabel(Text("Chosen picture"))
                    Button("Remove picture", role: .destructive) { self.image = nil; photo = nil }
                }
            }
            Section {
                RecordButton(recorder: recorder, label: samples == nil ? "Record the word" : "Record again") { s in
                    samples = s.count > Int(AudioCapture.sampleRate * 0.3) ? s : nil
                    if samples == nil { error = String(localized: "That recording was too short. Tap Stop after you speak.") }
                }
                if samples != nil {
                    Label("Recorded", systemImage: "checkmark.circle").foregroundStyle(.green)
                }
            } header: {
                Text("Your voice")
            } footer: {
                Text("With a picture the card shows it on the front; with only a recording it plays your voice.")
            }
            Section {
                Button(saving ? "Saving…" : "Create card") { create() }
                    .disabled(saving || word.trimmingCharacters(in: .whitespaces).isEmpty)
                if let message { Text(message).foregroundStyle(.green) }
                if let error { Text(error).foregroundStyle(.red) }
            }
        }
        .navigationTitle("Personal card")
        .onChange(of: photo) { _, item in load(item) }
        .onDisappear { if recorder.isRecording { _ = recorder.stop() } }
    }

    private func load(_ item: PhotosPickerItem?) {
        guard let item else { return }
        Task {
            if let data = try? await item.loadTransferable(type: Data.self), let picked = UIImage(data: data) {
                image = picked
            } else {
                error = String(localized: "Couldn't read that picture.")
            }
        }
    }

    private func create() {
        saving = true
        error = nil
        message = nil
        let graph = app.graph
        let w = word, r = reading, m = meaning, n = note
        let picture = image
        let take = samples
        Task {
            do {
                var imageId: String?
                if let picture {
                    imageId = try await Self.savePicture(picture, graph: graph)
                }
                var recordingId: String?
                if let take {
                    recordingId = try await RecordingSaver.save(take, graph: graph, kind: .free, ref: nil, referenceKey: nil)
                }
                _ = try await graph.personalCards.create(
                    word: w, reading: r.isEmpty ? nil : r, meaning: m.isEmpty ? nil : m, note: n,
                    imageId: imageId, audioRecordingId: recordingId
                )
                message = String(localized: "Card created. It waits in your lessons like any new card.")
                word = ""; reading = ""; meaning = ""; note = ""
                image = nil; photo = nil; samples = nil
            } catch {
                self.error = String(localized: "Couldn't create the card: \(error.localizedDescription)")
            }
            saving = false
        }
    }

    /// Downscales to 1600 px and writes a JPEG into the shared image store (off the main actor), then indexes it.
    private static func savePicture(_ image: UIImage, graph: AppGraph) async throws -> String {
        let pending = try await graph.images.startImage(fileExtension: "jpg")
        let path = pending.path
        let ok = await Task.detached(priority: .userInitiated) { () -> Bool in
            let maxSide: CGFloat = 1600
            let size = image.size
            let scale = min(1, maxSide / max(size.width, size.height, 1))
            let target = CGSize(width: size.width * scale, height: size.height * scale)
            let format = UIGraphicsImageRendererFormat.default()
            format.scale = 1
            let scaled = UIGraphicsImageRenderer(size: target, format: format).image { _ in
                image.draw(in: CGRect(origin: .zero, size: target))
            }
            guard let data = scaled.jpegData(compressionQuality: 0.8) else { return false }
            return (try? data.write(to: URL(fileURLWithPath: path), options: .atomic)) != nil
        }.value
        guard ok else { throw CocoaError(.fileWriteUnknown) }
        return try await graph.images.register(pending: pending).id
    }
}
