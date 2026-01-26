import Combine
import Foundation

final class SharedFolderStore: ObservableObject {
    @Published private(set) var folders: [SharedFolder] = []

    private let storageKey = "shared_folders_v1"

    init() {
        load()
    }

    func addFolder(from url: URL) throws {
        let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        let folder = SharedFolder(name: url.lastPathComponent, bookmarkData: bookmark)
        folders.append(folder)
        save()
    }

    func removeFolders(at offsets: IndexSet) {
        for index in offsets.sorted(by: >) {
            folders.remove(at: index)
        }
        save()
    }

    func resolveURL(for folder: SharedFolder) -> URL? {
        var isStale = false
        do {
            let url = try URL(
                resolvingBookmarkData: folder.bookmarkData,
                options: [.withoutUI],
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            )
            if isStale {
                let newBookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
                replaceBookmark(for: folder.id, bookmarkData: newBookmark)
            }
            return url
        } catch {
            return nil
        }
    }

    func folder(for id: UUID) -> SharedFolder? {
        folders.first { $0.id == id }
    }

    func withScopedURL(for folder: SharedFolder, _ body: (URL) -> Void) -> Bool {
        guard let url = resolveURL(for: folder) else { return false }
        let started = url.startAccessingSecurityScopedResource()
        defer {
            if started {
                url.stopAccessingSecurityScopedResource()
            }
        }
        body(url)
        return true
    }

    private func replaceBookmark(for id: UUID, bookmarkData: Data) {
        guard let index = folders.firstIndex(where: { $0.id == id }) else { return }
        let current = folders[index]
        folders[index] = SharedFolder(id: current.id, name: current.name, bookmarkData: bookmarkData)
        save()
    }

    private func load() {
        guard let data = UserDefaults.standard.data(forKey: storageKey) else { return }
        do {
            folders = try JSONDecoder().decode([SharedFolder].self, from: data)
        } catch {
            folders = []
        }
    }

    private func save() {
        do {
            let data = try JSONEncoder().encode(folders)
            UserDefaults.standard.set(data, forKey: storageKey)
        } catch {
            // Ignore persistence failure; user can re-add folders.
        }
    }
}
