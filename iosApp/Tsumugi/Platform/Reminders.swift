import Shared
import SwiftUI
import UserNotifications

/// Local review reminders. The shared ReminderPlanner decides when; this schedules a single local notification
/// whenever the app goes to the background. No server push, no background refresh.
///
/// Permission is asked once, after the learner's first finished review session, from [ReminderPermissionSheet]
/// (BRIEF_V2 F-34), never at first launch.
enum Reminders {
    private static let identifier = "reviews-ready"
    /// Device-local: whether the explanation sheet was already shown on this device.
    private static let askedKey = "reminders.permissionAsked"

    static func requestPermission() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])
    }

    /// True when the learner hasn't been asked yet on this device and the system hasn't recorded an answer.
    static func shouldAsk() async -> Bool {
        if UserDefaults.standard.bool(forKey: askedKey) { return false }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        return settings.authorizationStatus == .notDetermined
    }

    static func markAsked() {
        UserDefaults.standard.set(true, forKey: askedKey)
    }

    static func reschedule(graph: AppGraph) async {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        guard let reminder = try? await graph.reminders.next() else { return }
        let seconds = Double(reminder.at.toEpochMilliseconds()) / 1000 - Date().timeIntervalSince1970
        guard seconds > 1 else { return }
        let content = UNMutableNotificationContent()
        content.title = reminder.title
        content.body = reminder.body
        content.sound = .default
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
        try? await center.add(UNNotificationRequest(identifier: identifier, content: content, trigger: trigger))
    }
}

/// Explains review reminders before the system permission prompt appears.
struct ReminderPermissionSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image(systemName: "bell.badge").font(.largeTitle).foregroundStyle(.tint)
                .accessibilityHidden(true)
            Text("Get a nudge when reviews are ready?").font(.title2.weight(.semibold))
            Text("Reviews work best close to when they come due. Tsumugi can send one notification when your next reviews are ready, and a gentle reminder to keep your streak.")
            Text("It's scheduled on this iPhone; nothing goes to a server. You can turn it off any time in the Settings app.")
                .font(.subheadline).foregroundStyle(.secondary)
            Button {
                Reminders.markAsked()
                Task {
                    await Reminders.requestPermission()
                    dismiss()
                }
            } label: {
                Text("Allow reminders").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            Button {
                Reminders.markAsked()
                dismiss()
            } label: {
                Text("Not now").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .padding(24)
        .presentationDetents([.medium, .large])
    }
}
