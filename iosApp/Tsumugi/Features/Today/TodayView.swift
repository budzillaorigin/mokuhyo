import SwiftUI

struct TodayView: View {
    @State private var model = TodayViewModel()

    var body: some View {
        VStack(spacing: 8) {
            if let title = model.title {
                Text(title)
                    .font(.system(size: 48, weight: .semibold))
                Text(model.message ?? "")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            } else {
                ProgressView()
            }
        }
        .padding(24)
        .task { await model.observe() }
    }
}

#Preview {
    TodayView()
}
