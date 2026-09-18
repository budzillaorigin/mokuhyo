import SwiftUI

/// Placeholder until the Today planner lands (Phase 3).
struct TodayView: View {
    var body: some View {
        VStack(spacing: 8) {
            Text("今日")
                .font(.japanese(size: 48, weight: .semibold))
            Text("Your daily plan appears here once lessons and reviews are set up.")
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(24)
        .navigationTitle("Today")
    }
}
