import Foundation

struct SharedFolder: Identifiable, Codable, Hashable {
    let id: UUID
    let name: String
    let bookmarkData: Data

    init(id: UUID = UUID(), name: String, bookmarkData: Data) {
        self.id = id
        self.name = name
        self.bookmarkData = bookmarkData
    }
}
