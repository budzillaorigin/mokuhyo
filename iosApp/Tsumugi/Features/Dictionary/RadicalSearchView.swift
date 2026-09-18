import Shared
import SwiftUI

struct RadicalSearchView: View {
    var body: some View {
        DictionaryGate { repo in RadicalPicker(repo: repo) }
            .navigationTitle("Radical search")
            .navigationBarTitleDisplayMode(.inline)
    }
}

private struct RadicalPicker: View {
    let repo: DictionaryRepository

    @State private var radicals: [Radical] = []
    @State private var selected: Set<String> = []
    @State private var result: RadicalSearchResult?

    private var byStrokes: [(Int32, [Radical])] {
        Dictionary(grouping: radicals, by: \.strokeCount).sorted { $0.key < $1.key }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    if selected.isEmpty {
                        Text("Pick radicals to find a kanji").foregroundStyle(.secondary).padding(.horizontal)
                    }
                    ForEach((result?.kanji ?? []).prefix(120), id: \.literal) { k in
                        NavigationLink(value: Route.kanji(k.literal)) {
                            Text(k.literal).font(.japanese(size: 30, relativeTo: .title))
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 4)
                    }
                }
                .padding(8)
            }
            .frame(height: 60)
            Divider()
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 40))], spacing: 6) {
                    ForEach(byStrokes, id: \.0) { strokes, group in
                        Text("\(strokes)").font(.headline).foregroundStyle(.tint).frame(width: 40, height: 40)
                        ForEach(group, id: \.radical) { r in
                            let isSelected = selected.contains(r.radical)
                            let enabled = isSelected || selected.isEmpty || (result?.compatibleRadicals.contains(r.radical) ?? false)
                            Button {
                                if isSelected { selected.remove(r.radical) } else { selected.insert(r.radical) }
                            } label: {
                                Text(r.radical)
                                    .font(.japanese(size: 22))
                                    .frame(width: 40, height: 40)
                                    .background(isSelected ? AnyShapeStyle(.tint) : AnyShapeStyle(.quaternary.opacity(0.5)), in: RoundedRectangle(cornerRadius: 8))
                                    .foregroundStyle(isSelected ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
                            }
                            .buttonStyle(.plain)
                            .disabled(!enabled)
                            .opacity(enabled ? 1 : 0.25)
                        }
                    }
                }
                .padding(8)
            }
        }
        .task { radicals = (try? await repo.radicals()) ?? [] }
        .task(id: selected) { result = try? await repo.kanjiByRadicals(selected: selected) }
    }
}
