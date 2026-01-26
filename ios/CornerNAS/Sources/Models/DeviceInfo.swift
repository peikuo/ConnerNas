import Foundation

struct DeviceInfo: Identifiable, Hashable {
    let name: String
    let host: String
    let port: Int

    var id: String {
        "\(host):\(port)|\(name)"
    }
}
