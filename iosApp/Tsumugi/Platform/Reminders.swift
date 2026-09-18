import Shared
import UserNotifications

/// Local review reminders. The shared ReminderPlanner decides when; this schedules a single local notification
/// whenever the app goes to the background. No server push, no background refresh.
enum Reminders {
    private static let identifier = "reviews-ready"

    static func requestPermission() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound])
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
