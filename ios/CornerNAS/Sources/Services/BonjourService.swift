import Combine
import Foundation
import Network

final class BonjourService: NSObject, ObservableObject {
    @Published private(set) var devices: [DeviceInfo] = []

    private let serviceType = "_cornernas._tcp"
    private let domain = "local."
    private var browser: NetServiceBrowser?
    private var resolving: Set<NetService> = []
    private var resolvedServices: [String: DeviceInfo] = [:]

    func startBrowsing() {
        stopBrowsing()
        let browser = NetServiceBrowser()
        browser.includesPeerToPeer = true
        browser.delegate = self
        browser.searchForServices(ofType: serviceType, inDomain: domain)
        self.browser = browser
    }

    func stopBrowsing() {
        browser?.stop()
        browser = nil
        resolving.removeAll()
        resolvedServices.removeAll()
        devices = []
    }

    private func updateDevices() {
        devices = Array(resolvedServices.values).sorted { $0.name < $1.name }
    }
}

extension BonjourService: NetServiceBrowserDelegate, NetServiceDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        resolving.insert(service)
        service.delegate = self
        service.resolve(withTimeout: 5)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        resolving.remove(service)
        resolvedServices.removeValue(forKey: service.name)
        updateDevices()
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        resolving.remove(sender)
        if let address = resolveIPAddress(from: sender) {
            let device = DeviceInfo(name: sender.name, host: address, port: sender.port)
            resolvedServices[sender.name] = device
            updateDevices()
        }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolving.remove(sender)
    }
}

private func resolveIPAddress(from service: NetService) -> String? {
    guard let addresses = service.addresses else {
        return service.hostName
    }

    for address in addresses {
        var ip: String?
        address.withUnsafeBytes { rawBuffer in
            let sockaddr = rawBuffer.bindMemory(to: sockaddr.self)
            guard let base = sockaddr.baseAddress else { return }
            if base.pointee.sa_family == sa_family_t(AF_INET) {
                let addr = base.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee }
                var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                var ipv4 = addr.sin_addr
                inet_ntop(AF_INET, &ipv4, &buffer, socklen_t(INET_ADDRSTRLEN))
                ip = String(cString: buffer)
            }
        }
        if let ip {
            return ip
        }
    }

    return service.hostName
}
