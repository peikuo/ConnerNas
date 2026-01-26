import Foundation

struct RemoteListResponse: Codable {
    let items: [RemoteListItem]
}

struct RemoteListItem: Identifiable, Codable, Hashable {
    let id: String
    let name: String
    let path: String
    let isDir: Bool
    let size: Int64?

    init(name: String, path: String, isDir: Bool, size: Int64?) {
        self.name = name
        self.path = path
        self.isDir = isDir
        self.size = size
        self.id = path
    }
}

final class RemoteBrowserClient {
    func fetchList(device: DeviceInfo, root: String? = nil, path: String? = nil) async throws -> [RemoteListItem] {
        var components = URLComponents()
        components.scheme = "http"
        components.host = device.host
        components.port = device.port
        components.path = "/api/v1/list"

        var query: [URLQueryItem] = []
        if let root {
            query.append(URLQueryItem(name: "root", value: root))
        }
        if let path, !path.isEmpty {
            query.append(URLQueryItem(name: "path", value: path))
        }
        components.queryItems = query.isEmpty ? nil : query

        guard let url = components.url else {
            return []
        }

        let (data, _) = try await URLSession.shared.data(from: url)
        let decoded = try JSONDecoder().decode(RemoteListResponse.self, from: data)
        return decoded.items
    }

    func fileURL(device: DeviceInfo, root: String, path: String) -> URL? {
        var components = URLComponents()
        components.scheme = "http"
        components.host = device.host
        components.port = device.port
        components.path = "/api/v1/file"
        components.queryItems = [
            URLQueryItem(name: "root", value: root),
            URLQueryItem(name: "path", value: path)
        ]
        return components.url
    }
}
