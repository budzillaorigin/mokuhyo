import Foundation
import Shared
import SwiftUI

/// Downloads podcast enclosures with URLSession into the shared store's target file and reports through the
/// `mark*` calls (G-04, D-113). Rule 13: 120 s without bytes fails the request; a whole episode may take up to 2 h.
/// Progress is polled from the task's `Progress`; Cancel cancels the task and marks the episode failed.
@MainActor
@Observable
final class PodcastDownloader {
    static let shared = PodcastDownloader()

    private(set) var progress: [String: Double] = [:]
    @ObservationIgnored private var tasks: [String: URLSessionDownloadTask] = [:]

    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 2 * 60 * 60
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    func isDownloading(_ id: String) -> Bool { tasks[id] != nil }

    func download(_ episode: PodcastEpisode, graph: AppGraph, onChange: @escaping () -> Void) {
        guard tasks[episode.id] == nil, let url = URL(string: episode.audioUrl) else { return }
        let id = episode.id
        let podcasts = graph.podcasts
        Task {
            do {
                try await podcasts.queue(episodeId: id)
                let target = try await podcasts.targetFile(episodeId: id)
                let task = session.downloadTask(with: url) { tmp, response, error in
                    // The temporary file is deleted when this handler returns: move it first.
                    var failure: String?
                    if let error {
                        failure = error.localizedDescription
                    } else if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                        failure = String(localized: "The server answered \(http.statusCode).")
                    } else if let tmp {
                        do {
                            let dest = URL(fileURLWithPath: target)
                            try? FileManager.default.removeItem(at: dest)
                            try FileManager.default.moveItem(at: tmp, to: dest)
                        } catch {
                            failure = error.localizedDescription
                        }
                    } else {
                        failure = String(localized: "Nothing was downloaded.")
                    }
                    Task { @MainActor in
                        await PodcastDownloader.shared.finished(id: id, failure: failure, podcasts: podcasts)
                        onChange()
                    }
                }
                tasks[id] = task
                progress[id] = 0
                task.resume()
                onChange()
                await poll(id: id, task: task, podcasts: podcasts)
            } catch {
                try? await podcasts.markFailed(episodeId: id, error: error.localizedDescription)
                onChange()
            }
        }
    }

    func cancel(_ id: String) {
        tasks[id]?.cancel()
    }

    private func poll(id: String, task: URLSessionDownloadTask, podcasts: PodcastService) async {
        while tasks[id] === task {
            progress[id] = task.progress.fractionCompleted
            try? await podcasts.markDownloading(episodeId: id, bytes: task.countOfBytesReceived)
            try? await Task.sleep(nanoseconds: 1_000_000_000)
        }
    }

    private func finished(id: String, failure: String?, podcasts: PodcastService) async {
        tasks[id] = nil
        progress[id] = nil
        if let failure {
            try? await podcasts.markFailed(episodeId: id, error: failure)
        } else {
            do {
                _ = try await podcasts.markDownloaded(episodeId: id)
            } catch {
                try? await podcasts.markFailed(episodeId: id, error: error.localizedDescription)
            }
        }
    }
}

/// Podcasts (G-04): add an RSS feed, list its episodes, download them, play them in the media player and generate
/// their transcripts there with on-device Whisper. Feeds and downloads stay on this device.
struct PodcastsView: View {
    @Environment(AppModel.self) private var app
    @State private var podcasts: [Podcast]?
    @State private var adding = false
    @State private var url = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        List {
            if let error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button("Retry") { Task { await load() } }
                }
            }
            if busy { ProgressView("Fetching the feed…") }
            if let podcasts {
                if podcasts.isEmpty {
                    Text("Add a podcast's RSS feed address. Episodes download to this device; transcripts are generated on it too.")
                        .foregroundStyle(.secondary)
                }
                ForEach(podcasts, id: \.id) { p in
                    NavigationLink(value: Route.podcast(p.id)) {
                        VStack(alignment: .leading) {
                            Text(p.title).font(.japanese(size: 17))
                            if let author = p.author { Text(author).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                    .swipeActions {
                        Button("Remove", role: .destructive) {
                            Task { try? await app.graph.podcasts.unsubscribe(podcastId: p.id); await load() }
                        }
                    }
                }
            } else if error == nil {
                ProgressView()
            }
        }
        .navigationTitle("Podcasts")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { url = ""; adding = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel(Text("Add podcast"))
            }
        }
        .alert("Add a podcast", isPresented: $adding) {
            TextField("https://…/feed.xml", text: $url).textInputAutocapitalization(.never).keyboardType(.URL)
            Button("Add") { add(url) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Paste the RSS feed address.")
        }
        .task { await load() }
    }

    private func load() async {
        error = nil
        do {
            podcasts = try await app.graph.podcasts.podcasts()
        } catch {
            self.error = String(localized: "Couldn't load your podcasts: \(error.localizedDescription)")
        }
    }

    private func add(_ address: String) {
        busy = true
        error = nil
        let graph = app.graph
        Task {
            do {
                _ = try await graph.podcasts.subscribe(url: address)
            } catch {
                self.error = String(localized: "Couldn't add the feed: \(error.localizedDescription)")
            }
            busy = false
            await load()
        }
    }
}

/// One podcast's episodes with download state.
struct PodcastEpisodesView: View {
    @Environment(AppModel.self) private var app
    let podcastId: String

    @State private var podcast: Podcast?
    @State private var episodes: [PodcastEpisode]?
    @State private var refreshing = false
    @State private var error: String?
    @State private var downloader = PodcastDownloader.shared

    var body: some View {
        List {
            if let error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    Button("Retry") { refresh() }
                }
            }
            if let episodes {
                if episodes.isEmpty { Text("This feed has no audio episodes.").foregroundStyle(.secondary) }
                ForEach(episodes, id: \.id) { e in row(e) }
            } else if error == nil {
                ProgressView()
            }
        }
        .navigationTitle(podcast?.title ?? String(localized: "Episodes"))
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { refresh() } label: { Image(systemName: "arrow.clockwise") }
                    .disabled(refreshing)
                    .accessibilityLabel(Text("Check for new episodes"))
            }
        }
        .task { await load() }
    }

    @ViewBuilder
    private func row(_ e: PodcastEpisode) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(e.title).font(.japanese(size: 16)).lineLimit(2)
            HStack {
                if let published = e.published { Text(published).font(.caption2).foregroundStyle(.secondary) }
                if let ms = e.durationMs?.int64Value { Text(clockText(seconds: ms / 1000)).font(.caption2.monospacedDigit()).foregroundStyle(.secondary) }
            }
            if let fraction = downloader.progress[e.id] {
                HStack {
                    ProgressView(value: fraction)
                    Button("Cancel") { downloader.cancel(e.id) }.font(.caption)
                }
            } else {
                switch e.downloadState {
                case .done:
                    if let path = e.localPath {
                        NavigationLink(value: Route.mediaFile(path: path, title: e.title, episodeId: e.id)) {
                            Label("Play", systemImage: "play.circle")
                        }
                    }
                    Button("Delete download", role: .destructive) {
                        Task { try? await app.graph.podcasts.deleteDownload(episodeId: e.id); await load() }
                    }
                    .font(.caption)
                case .failed:
                    Text("Download failed: \(e.downloadError ?? "")").font(.caption).foregroundStyle(.red)
                    Button("Try again") { download(e) }.font(.caption)
                default:
                    Button {
                        download(e)
                    } label: {
                        Label("Download", systemImage: "arrow.down.circle")
                    }
                }
            }
        }
        .buttonStyle(.borderless)
    }

    private func download(_ e: PodcastEpisode) {
        downloader.download(e, graph: app.graph) { Task { await load() } }
    }

    private func load() async {
        do {
            podcast = try await app.graph.podcasts.podcast(id: podcastId)
            episodes = try await app.graph.podcasts.episodes(podcastId: podcastId)
        } catch {
            self.error = String(localized: "Couldn't load the episodes: \(error.localizedDescription)")
        }
    }

    private func refresh() {
        refreshing = true
        error = nil
        let graph = app.graph
        let id = podcastId
        Task {
            do {
                episodes = try await graph.podcasts.refresh(podcastId: id)
            } catch {
                self.error = String(localized: "Couldn't refresh the feed: \(error.localizedDescription)")
            }
            refreshing = false
        }
    }
}
