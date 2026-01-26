import SwiftUI

struct SharedFoldersView: View {
    @EnvironmentObject private var appState: AppState
    @State private var showPicker = false
    @State private var errorMessage: String?

    var body: some View {
        List {
            ForEach(appState.sharedFolderStore.folders) { folder in
                SharedFolderRowView(folder: folder)
            }
            .onDelete(perform: appState.sharedFolderStore.removeFolders)
        }
        .navigationTitle("Shared Folders")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showPicker = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .sheet(isPresented: $showPicker) {
            FolderPicker { url in
                do {
                    try appState.sharedFolderStore.addFolder(from: url)
                } catch {
                    errorMessage = "Unable to add folder."
                }
                showPicker = false
            }
        }
        .alert("Error", isPresented: Binding(get: { errorMessage != nil }, set: { _ in errorMessage = nil })) {
            Button("OK") {}
        } message: {
            Text(errorMessage ?? "")
        }
    }
}
