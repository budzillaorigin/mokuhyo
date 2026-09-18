import PhotosUI
import SwiftUI
import Vision
import VisionKit

/// Camera and photo OCR (BRIEF §5.3, §5.8): live text recognition with tap-to-look-up (VisionKit), or a photo
/// from the library (Vision). Recognition runs on device.
struct ScanView: View {
    @State private var recognized: [String] = []
    @State private var photo: PhotosPickerItem?
    @State private var scanning = false
    @State private var status: String?

    var body: some View {
        List {
            Section {
                if DataScannerViewController.isSupported {
                    Button { scanning = true } label: { Label("Scan with camera", systemImage: "camera.viewfinder") }
                }
                PhotosPicker(selection: $photo, matching: .images) {
                    Label("Choose a photo", systemImage: "photo")
                }
                if let status { Text(status).font(.caption) }
            } footer: {
                Text("Text is recognized on your device. Tap a line to look it up.")
            }
            if !recognized.isEmpty {
                Section("Recognized text") {
                    ForEach(Array(recognized.enumerated()), id: \.offset) { _, line in
                        NavigationLink(value: Route.lookup(line)) { Text(line).font(.japanese(size: 18)) }
                    }
                }
            }
        }
        .navigationTitle("Scan text")
        .sheet(isPresented: $scanning) {
            LiveScanner { text in
                recognized.insert(text, at: 0)
                scanning = false
            }
            .ignoresSafeArea()
        }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            Task {
                status = "Reading the photo…"
                guard let data = try? await item.loadTransferable(type: Data.self), let image = UIImage(data: data)?.cgImage else {
                    status = "Couldn't open that photo."
                    return
                }
                let lines = await Self.recognize(image)
                recognized = lines
                status = lines.isEmpty ? "No Japanese text found." : nil
            }
        }
    }

    /// Vision OCR on a still image, Japanese first.
    static func recognize(_ image: CGImage) async -> [String] {
        await withCheckedContinuation { continuation in
            let request = VNRecognizeTextRequest { request, _ in
                let lines = (request.results as? [VNRecognizedTextObservation] ?? []).compactMap { $0.topCandidates(1).first?.string }
                continuation.resume(returning: lines)
            }
            request.recognitionLanguages = ["ja-JP", "en-US"]
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            do {
                try VNImageRequestHandler(cgImage: image).perform([request])
            } catch {
                continuation.resume(returning: [])
            }
        }
    }
}

/// VisionKit live text scanner: tapping a recognized line returns it.
private struct LiveScanner: UIViewControllerRepresentable {
    let onPick: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.text(languages: ["ja", "en"])],
            qualityLevel: .accurate,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onPick: (String) -> Void
        init(onPick: @escaping (String) -> Void) { self.onPick = onPick }

        func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
            if case .text(let text) = item { onPick(text.transcript) }
        }
    }
}
