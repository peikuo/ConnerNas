import Combine
import Foundation
import Network
import UIKit
import UniformTypeIdentifiers

final class LocalServer: NSObject, ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var ipAddress: String = "-"
    @Published private(set) var port: Int = 8080

    private let serviceType = "_cornernas._tcp"
    private let domain = "local."
    private var netService: NetService?
    private var listener: NWListener?
    private let sharedFolderStore: SharedFolderStore

    init(sharedFolderStore: SharedFolderStore) {
        self.sharedFolderStore = sharedFolderStore
        super.init()
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true
        ipAddress = localIPAddress() ?? "-"

        do {
            let listener = try NWListener(using: .tcp, on: .any)
            listener.newConnectionHandler = { [weak self] connection in
                self?.handle(connection: connection)
            }
            listener.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                if case .ready = state {
                    if let port = listener.port?.rawValue {
                        self.port = Int(port)
                        self.publishService()
                    }
                }
            }
            listener.start(queue: .main)
            self.listener = listener
        } catch {
            isRunning = false
        }
    }

    func stop() {
        netService?.stop()
        netService = nil
        listener?.cancel()
        listener = nil
        isRunning = false
        ipAddress = "-"
    }

    private func deviceName() -> String {
        UIDevice.current.name
    }

    private func publishService() {
        let service = NetService(domain: domain, type: serviceType, name: deviceName(), port: Int32(port))
        service.includesPeerToPeer = true
        service.publish()
        netService = service
    }
}

private func localIPAddress() -> String? {
    var address: String?
    var ifaddr: UnsafeMutablePointer<ifaddrs>?

    guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else {
        return nil
    }

    for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
        let interface = ptr.pointee
        let addrFamily = interface.ifa_addr.pointee.sa_family
        if addrFamily == UInt8(AF_INET) {
            let name = String(cString: interface.ifa_name)
            if name == "en0" {
                var addr = interface.ifa_addr.pointee
                var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                getnameinfo(&addr, socklen_t(interface.ifa_addr.pointee.sa_len),
                            &hostname, socklen_t(hostname.count),
                            nil, socklen_t(0), NI_NUMERICHOST)
                address = String(cString: hostname)
                break
            }
        }
    }

    freeifaddrs(ifaddr)
    return address
}

private struct ListResponse: Codable {
    let items: [ListItem]
}

private struct ListItem: Codable {
    let name: String
    let path: String
    let isDir: Bool
    let size: Int64?
}

private extension LocalServer {
    func handle(connection: NWConnection) {
        connection.start(queue: .main)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { [weak self] data, _, _, _ in
            guard let self, let data, let request = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }
            let response = self.handle(request: request)
            connection.send(content: response, completion: .contentProcessed { _ in
                connection.cancel()
            })
        }
    }

    func handle(request: String) -> Data {
        guard let line = request.split(separator: "\r\n").first else {
            return httpResponse(status: "400 Bad Request", body: Data())
        }
        let parts = line.split(separator: " ")
        guard parts.count >= 2 else {
            return httpResponse(status: "400 Bad Request", body: Data())
        }

        let path = String(parts[1])
        let (route, query) = splitPathAndQuery(path)
        switch route {
        case "/api/v1/ping":
            return httpResponse(status: "200 OK", body: Data("pong".utf8), contentType: "text/plain")
        case "/api/v1/list":
            return handleList(query: query)
        case "/api/v1/file":
            return handleFile(query: query)
        default:
            return httpResponse(status: "404 Not Found", body: Data())
        }
    }

    func handleList(query: [String: String]) -> Data {
        if let rootId = query["root"], let uuid = UUID(uuidString: rootId),
           let folder = sharedFolderStore.folder(for: uuid) {
            var items: [ListItem] = []
            let relative = sanitizeRelativePath(query["path"])
            let ok = sharedFolderStore.withScopedURL(for: folder) { rootURL in
                let targetURL = relative.isEmpty ? rootURL : rootURL.appendingPathComponent(relative)
                let fm = FileManager.default
                guard let contents = try? fm.contentsOfDirectory(at: targetURL, includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey]) else {
                    return
                }
                for url in contents {
                    let resourceValues = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
                    let isDir = resourceValues?.isDirectory ?? false
                    let size = resourceValues?.fileSize.map { Int64($0) }
                    let name = url.lastPathComponent
                    let relPath = relative.isEmpty ? name : "\(relative)/\(name)"
                    let token = "\(rootId)/\(relPath)".addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? relPath
                    items.append(ListItem(name: name, path: token, isDir: isDir, size: size))
                }
            }
            if !ok {
                return httpResponse(status: "404 Not Found", body: Data())
            }
            let body = (try? JSONEncoder().encode(ListResponse(items: items))) ?? Data()
            return httpResponse(status: "200 OK", body: body, contentType: "application/json")
        } else {
            let items = sharedFolderStore.folders.map {
                ListItem(name: $0.name, path: $0.id.uuidString, isDir: true, size: nil)
            }
            let body = (try? JSONEncoder().encode(ListResponse(items: items))) ?? Data()
            return httpResponse(status: "200 OK", body: body, contentType: "application/json")
        }
    }

    func handleFile(query: [String: String]) -> Data {
        guard let rootId = query["root"], let uuid = UUID(uuidString: rootId),
              let folder = sharedFolderStore.folder(for: uuid) else {
            return httpResponse(status: "404 Not Found", body: Data())
        }

        let relative = sanitizeRelativePath(query["path"])
        guard !relative.isEmpty else {
            return httpResponse(status: "400 Bad Request", body: Data())
        }

        var fileData: Data?
        var mimeType = "application/octet-stream"
        let ok = sharedFolderStore.withScopedURL(for: folder) { rootURL in
            let fileURL = rootURL.appendingPathComponent(relative)
            fileData = try? Data(contentsOf: fileURL)
            if let type = UTType(filenameExtension: fileURL.pathExtension) {
                mimeType = type.preferredMIMEType ?? mimeType
            }
        }

        guard ok, let data = fileData else {
            return httpResponse(status: "404 Not Found", body: Data())
        }

        return httpResponse(status: "200 OK", body: data, contentType: mimeType)
    }

    func httpResponse(status: String, body: Data, contentType: String = "application/octet-stream") -> Data {
        var headers = "HTTP/1.1 \(status)\r\n"
        headers += "Content-Length: \(body.count)\r\n"
        headers += "Content-Type: \(contentType)\r\n"
        headers += "Connection: close\r\n\r\n"
        return Data(headers.utf8) + body
    }

    func splitPathAndQuery(_ path: String) -> (String, [String: String]) {
        let parts = path.split(separator: "?", maxSplits: 1)
        let route = String(parts.first ?? "/")
        var query: [String: String] = [:]
        if parts.count > 1 {
            for pair in parts[1].split(separator: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1)
                let key = String(kv[0])
                let value = kv.count > 1 ? String(kv[1]) : ""
                query[key] = value.removingPercentEncoding ?? value
            }
        }
        return (route, query)
    }

    func sanitizeRelativePath(_ path: String?) -> String {
        let raw = (path ?? "").replacingOccurrences(of: "\\", with: "/")
        let trimmed = raw.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if trimmed.contains("..") {
            return ""
        }
        return trimmed
    }
}
