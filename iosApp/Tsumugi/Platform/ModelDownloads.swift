import Foundation
import Observation
import Shared
import UIKit

/// On-device model downloads that keep going in the background (BRIEF_V2 F-13, DECISIONS D-072).
///
/// A background `URLSession` fetches each missing file of a model. iOS continues the transfer while the app is
/// suspended, and relaunches the app to deliver the result. The finished file is moved to the target the shared
/// `DownloadedModelInstaller` named, and that installer verifies its SHA-256 off the main thread and moves it into
/// the models folder, where `ModelManager.isInstalled` sees it.
///
/// - Cancel pauses: the task produces resume data, stored next to the target as `<target>.resume`. Download again
///   resumes from it. Resume data is also kept when a transfer fails or the app was force-quit.
/// - Timeouts (rule 13): a stalled transfer fails after 120 s without data; a whole file after 3 days.
/// - "Not enough storage" (1.5 × the remaining size) fails before anything starts and isn't retried.
@MainActor
@Observable
final class ModelDownloads {
    static let shared = ModelDownloads()
    static let sessionIdentifier = "app.tsumugi.model-downloads"

    struct Status: Equatable {
        var fraction: Double?
        var text: String
        var active: Bool
        var failed: Bool
    }

    /// Per model id; nil when nothing is going on for that model.
    private(set) var status: [String: Status] = [:]
    /// The model whose last file was just installed (the settings screen refreshes and may select it).
    private(set) var lastInstalled: String?
    private(set) var installCount = 0

    /// iOS's "background events handled" callback, from `AppDelegate`.
    @ObservationIgnored var backgroundCompletion: (() -> Void)?
    @ObservationIgnored private var graph: AppGraph?
    @ObservationIgnored private var session: URLSession?
    @ObservationIgnored private let delegate = ModelDownloadDelegate()
    /// Bytes written per running task, and the bytes of each model already installed before this run.
    @ObservationIgnored private var written: [Int: (model: String, bytes: Int64)] = [:]
    @ObservationIgnored private var baseBytes: [String: Int64] = [:]

    private init() {}

    /// Called once at launch: remembers the graph and reconnects to transfers that ran while the app was away.
    func attach(graph: AppGraph) {
        self.graph = graph
        reconnect()
    }

    /// Makes sure the background session exists (iOS delivers pending events to it) and shows its running tasks.
    func reconnect() {
        let session = ensureSession()
        Task {
            let tasks = await session.allTasks
            for task in tasks where task.state == .running {
                guard let info = DownloadTaskInfo(task.taskDescription) else { continue }
                written[task.taskIdentifier] = (info.modelId, task.countOfBytesReceived)
                if status[info.modelId] == nil {
                    status[info.modelId] = Status(fraction: nil, text: String(localized: "Downloading…"), active: true, failed: false)
                }
                if baseBytes[info.modelId] == nil, let model = modelInfo(info.modelId) {
                    await computeBase(model)
                }
            }
            updateProgress()
        }
    }

    func isActive(_ modelId: String) -> Bool { status[modelId]?.active == true }

    /// Starts or resumes every missing file of [model].
    func start(_ model: ModelInfo) {
        guard let graph, !isActive(model.id) else { return }
        let id = model.id
        status[id] = Status(fraction: nil, text: String(localized: "Starting…"), active: true, failed: false)
        let installer = SwiftSupport.shared.modelInstaller(graph: graph)
        Task {
            let plan: ModelDownloadPlan
            do {
                plan = try await Self.detachedPlan(installer, model)
            } catch {
                status[id] = Status(fraction: nil, text: String(localized: "Couldn't prepare the download: \(error.localizedDescription)"), active: false, failed: true)
                return
            }
            if let error = plan.error {
                // Not retried automatically (e.g. not enough storage): the learner frees space first.
                status[id] = Status(fraction: nil, text: error, active: false, failed: true)
                return
            }
            baseBytes[id] = model.totalBytes - plan.files.reduce(Int64(0)) { $0 + $1.bytes }
            let pending = plan.files.filter { !$0.downloaded }
            for file in pending { startTask(modelId: id, file: file) }
            // Files finished earlier but never verified (the app was closed while hashing): install them now.
            for file in plan.files where file.downloaded {
                await install(model: model, fileName: file.name, path: file.targetPath)
            }
            if pending.isEmpty { finishIfDone(model) }
        }
    }

    /// Pauses [modelId]'s transfers, keeping resume data so the next start continues where it stopped.
    func pause(_ modelId: String) {
        guard let session else { return }
        Task {
            for task in await session.allTasks {
                guard let download = task as? URLSessionDownloadTask, let info = DownloadTaskInfo(task.taskDescription), info.modelId == modelId else { continue }
                let resume = info.resumeURL
                download.cancel(byProducingResumeData: { data in
                    if let data { try? data.write(to: resume, options: .atomic) }
                })
            }
            status[modelId] = nil
        }
    }

    /// Stops [modelId]'s transfers without keeping anything (before deleting the model).
    func discard(_ modelId: String) async {
        guard let session else { return }
        for task in await session.allTasks {
            if let info = DownloadTaskInfo(task.taskDescription), info.modelId == modelId { task.cancel() }
        }
        status[modelId] = nil
    }

    /// True when a paused or unverified download exists for [model] (the button then says "Resume").
    func hasPartial(_ model: ModelInfo) -> Bool {
        guard let graph, let plan = try? SwiftSupport.shared.modelInstaller(graph: graph).plan(model: model) else { return false }
        return plan.files.contains { $0.downloaded || FileManager.default.fileExists(atPath: $0.targetPath + DownloadTaskInfo.resumeSuffix) }
    }

    // MARK: - Delegate events (main actor)

    func progress(taskId: Int, modelId: String, bytes: Int64) {
        written[taskId] = (modelId, bytes)
        updateProgress()
    }

    func taskFinished(taskId: Int, info: DownloadTaskInfo, error: String?, paused: Bool) {
        written[taskId] = nil
        let id = info.modelId
        if paused {
            if !written.values.contains(where: { $0.model == id }) { status[id] = nil }
            return
        }
        if let error {
            status[id] = Status(fraction: nil, text: String(localized: "Download stopped: \(error). Tap Download to resume."), active: false, failed: true)
            return
        }
        guard let model = modelInfo(id) else { return }
        Task {
            await install(model: model, fileName: info.fileName, path: info.targetPath)
            finishIfDone(model)
        }
    }

    func backgroundEventsFinished() {
        let done = backgroundCompletion
        backgroundCompletion = nil
        done?()
    }

    // MARK: - Private

    private func ensureSession() -> URLSession {
        if let session { return session }
        let config = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.timeoutIntervalForRequest = 120
        config.timeoutIntervalForResource = 3 * 24 * 60 * 60
        let created = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
        session = created
        return created
    }

    private func startTask(modelId: String, file: ModelFileDownload) {
        let session = ensureSession()
        let info = DownloadTaskInfo(modelId: modelId, fileName: file.name, targetPath: file.targetPath)
        let task: URLSessionDownloadTask
        if let data = try? Data(contentsOf: info.resumeURL) {
            try? FileManager.default.removeItem(at: info.resumeURL)
            task = session.downloadTask(withResumeData: data)
        } else if let url = URL(string: file.url) {
            var request = URLRequest(url: url)
            request.timeoutInterval = 120
            task = session.downloadTask(with: request)
        } else {
            status[modelId] = Status(fraction: nil, text: String(localized: "The model list has a bad address for \(file.name)."), active: false, failed: true)
            return
        }
        task.taskDescription = info.description
        written[task.taskIdentifier] = (modelId, 0)
        task.resume()
    }

    private func install(model: ModelInfo, fileName: String, path: String) async {
        guard let graph else { return }
        let id = model.id
        if written.values.contains(where: { $0.model == id }) == false {
            status[id] = Status(fraction: nil, text: String(localized: "Verifying…"), active: true, failed: false)
        }
        let installer = SwiftSupport.shared.modelInstaller(graph: graph)
        let error: String?
        do {
            error = try await installer.install(model: model, fileName: fileName, downloadedPath: path)
        } catch {
            status[id] = Status(fraction: nil, text: String(localized: "Couldn't install \(fileName): \(error.localizedDescription)"), active: false, failed: true)
            return
        }
        if let error {
            status[id] = Status(fraction: nil, text: String(localized: "Failed: \(error). Tap Download to try again."), active: false, failed: true)
        }
    }

    private func finishIfDone(_ model: ModelInfo) {
        let id = model.id
        guard !written.values.contains(where: { $0.model == id }) else { return }
        if status[id]?.failed == true { return }
        if graph?.ai.models?.isInstalled(model: model) == true {
            status[id] = nil
            baseBytes[id] = nil
            lastInstalled = id
            installCount += 1
        }
    }

    private func updateProgress() {
        var perModel: [String: Int64] = [:]
        for entry in written.values { perModel[entry.model, default: 0] += entry.bytes }
        for (id, bytes) in perModel {
            guard let model = modelInfo(id) else { continue }
            let total = max(Int64(1), model.totalBytes)
            let done = min(total, (baseBytes[id] ?? 0) + bytes)
            let text = "\(ByteCountFormatter.string(fromByteCount: done, countStyle: .file)) of \(ByteCountFormatter.string(fromByteCount: total, countStyle: .file))"
            status[id] = Status(fraction: Double(done) / Double(total), text: text, active: true, failed: false)
        }
    }

    private func computeBase(_ model: ModelInfo) async {
        guard let graph else { return }
        let installer = SwiftSupport.shared.modelInstaller(graph: graph)
        if let plan = try? await Self.detachedPlan(installer, model) {
            baseBytes[model.id] = model.totalBytes - plan.files.reduce(Int64(0)) { $0 + $1.bytes }
        }
    }

    private func modelInfo(_ id: String) -> ModelInfo? {
        graph?.ai.models?.model(id: id)
    }

    /// The plan touches the file system (and deletes stale partial files), so it runs off the main actor (rule 15).
    private nonisolated static func detachedPlan(_ installer: DownloadedModelInstaller, _ model: ModelInfo) async throws -> ModelDownloadPlan {
        try await Task.detached(priority: .userInitiated) {
            try installer.plan(model: model)
        }.value
    }
}

/// What a download task is for, stored in `taskDescription` so it survives app relaunches.
struct DownloadTaskInfo: Sendable {
    static let resumeSuffix = ".resume"

    let modelId: String
    let fileName: String
    let targetPath: String

    init(modelId: String, fileName: String, targetPath: String) {
        self.modelId = modelId
        self.fileName = fileName
        self.targetPath = targetPath
    }

    init?(_ description: String?) {
        guard let parts = description?.components(separatedBy: "\n"), parts.count == 3 else { return nil }
        self.init(modelId: parts[0], fileName: parts[1], targetPath: parts[2])
    }

    var description: String { [modelId, fileName, targetPath].joined(separator: "\n") }
    var targetURL: URL { URL(fileURLWithPath: targetPath) }
    var resumeURL: URL { URL(fileURLWithPath: targetPath + Self.resumeSuffix) }
}

/// URLSession callbacks arrive on the session's own queue. File moves happen here (iOS deletes the temporary file
/// when `didFinishDownloadingTo` returns); everything else hops to the main actor.
final class ModelDownloadDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let lock = NSLock()
    /// Why a finished task's file couldn't be kept (bad HTTP status, move failed), by task id.
    private var problems: [Int: String] = [:]
    private var lastReported: [Int: Int64] = [:]

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let info = DownloadTaskInfo(downloadTask.taskDescription) else { return }
        var problem: String?
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            problem = "HTTP \(http.statusCode)"
        } else {
            let fm = FileManager.default
            do {
                try? fm.removeItem(at: info.targetURL)
                try fm.createDirectory(at: info.targetURL.deletingLastPathComponent(), withIntermediateDirectories: true)
                try fm.moveItem(at: location, to: info.targetURL)
            } catch {
                problem = error.localizedDescription
            }
        }
        if let problem {
            lock.lock(); problems[downloadTask.taskIdentifier] = problem; lock.unlock()
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let info = DownloadTaskInfo(downloadTask.taskDescription) else { return }
        let id = downloadTask.taskIdentifier
        // At most one main-actor update per MiB per task.
        lock.lock()
        let last = lastReported[id] ?? -1
        let report = last < 0 || totalBytesWritten - last >= 1 << 20
        if report { lastReported[id] = totalBytesWritten }
        lock.unlock()
        guard report else { return }
        let modelId = info.modelId
        Task { @MainActor in
            ModelDownloads.shared.progress(taskId: id, modelId: modelId, bytes: totalBytesWritten)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let info = DownloadTaskInfo(task.taskDescription) else { return }
        let id = task.taskIdentifier
        lock.lock()
        let problem = problems.removeValue(forKey: id)
        lastReported[id] = nil
        lock.unlock()
        var message = problem
        var paused = false
        if let error = error as NSError? {
            if let data = error.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                try? data.write(to: info.resumeURL, options: .atomic)
            }
            if error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled {
                paused = true
            } else {
                message = error.localizedDescription
            }
        }
        let finalMessage = message
        let wasPaused = paused
        Task { @MainActor in
            ModelDownloads.shared.taskFinished(taskId: id, info: info, error: finalMessage, paused: wasPaused)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in ModelDownloads.shared.backgroundEventsFinished() }
    }
}

/// Receives iOS's "your background downloads have news" launch event.
final class AppDelegate: NSObject, UIApplicationDelegate {
    @objc func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        guard identifier == ModelDownloads.sessionIdentifier else {
            completionHandler()
            return
        }
        ModelDownloads.shared.backgroundCompletion = completionHandler
        ModelDownloads.shared.reconnect()
    }
}
