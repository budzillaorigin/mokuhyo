import SwiftUI

/// Tab bar from BRIEF.md §6: Today · Reviews · Learn · Practice · Me.
struct RootView: View {
    var body: some View {
        TabView {
            TodayView()
                .tabItem { Label("Today", systemImage: "sun.max") }
            ComingSoonView(title: "Reviews")
                .tabItem { Label("Reviews", systemImage: "arrow.triangle.2.circlepath") }
            ComingSoonView(title: "Learn")
                .tabItem { Label("Learn", systemImage: "book") }
            ComingSoonView(title: "Practice")
                .tabItem { Label("Practice", systemImage: "mic") }
            ComingSoonView(title: "Me")
                .tabItem { Label("Me", systemImage: "person.crop.circle") }
        }
    }
}

#Preview {
    RootView()
}
