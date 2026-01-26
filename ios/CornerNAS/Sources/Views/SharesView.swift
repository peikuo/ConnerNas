import SwiftUI

struct SharesView: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        NavigationStack {
            List(appState.bonjourService.devices) { device in
                NavigationLink {
                    RemoteBrowserView(device: device)
                } label: {
                    DeviceRowView(device: device)
                }
            }
            .navigationTitle("All Shares")
            .onAppear {
                appState.bonjourService.startBrowsing()
            }
            .onDisappear {
                appState.bonjourService.stopBrowsing()
            }
        }
    }
}

struct RemoteBrowserView: View {
    let device: DeviceInfo
    private let client = RemoteBrowserClient()

    @State private var items: [RemoteListItem] = []
    @State private var pathStack: [String] = []
    @State private var errorMessage: String?
    @State private var isLoading = false

    var body: some View {
        List {
            ForEach(items) { item in
                if item.isDir {
                    Button {
                        pathStack.append(item.path)
                        Task { await loadList() }
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "folder")
                                .foregroundColor(.accentColor)
                            Text(item.name)
                        }
                    }
                } else {
                    if let fileURL = client.fileURL(device: device, root: currentRoot, path: item.path) {
                        Link(destination: fileURL) {
                            HStack(spacing: 12) {
                                Image(systemName: "doc")
                                Text(item.name)
                            }
                        }
                    } else {
                        Text(item.name)
                    }
                }
            }
        }
        .navigationTitle(device.name)
        .toolbar {
            if !pathStack.isEmpty {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Up") {
                        _ = pathStack.popLast()
                        Task { await loadList() }
                    }
                }
            }
        }
        .overlay {
            if isLoading {
                ProgressView()
            }
        }
        .task {
            await loadList()
        }
        .alert("Error", isPresented: Binding(get: { errorMessage != nil }, set: { _ in errorMessage = nil })) {
            Button("OK") {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private var currentRoot: String {
        pathStack.first ?? ""
    }

    private var currentPath: String? {
        if pathStack.count > 1 {
            return pathStack.dropFirst().joined(separator: "/")
        }
        return nil
    }

    @MainActor
    private func loadList() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let root = pathStack.first
            items = try await client.fetchList(device: device, root: root, path: currentPath)
        } catch {
            errorMessage = "Failed to load list."
            items = []
        }
    }
}
