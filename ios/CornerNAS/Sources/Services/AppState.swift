import Combine
import Foundation

final class AppState: ObservableObject {
    let bonjourService: BonjourService
    let localServer: LocalServer
    let sharedFolderStore: SharedFolderStore

    init() {
        let store = SharedFolderStore()
        self.sharedFolderStore = store
        self.localServer = LocalServer(sharedFolderStore: store)
        self.bonjourService = BonjourService()
    }
}
