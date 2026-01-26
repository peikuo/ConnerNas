import SwiftUI
import UIKit

struct HomeView: View {
    @EnvironmentObject private var appState: AppState

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Text(UIDevice.current.name)
                            .font(.headline)
                        Spacer()
                        Text(appState.localServer.isRunning ? "Running" : "Stopped")
                            .font(.subheadline)
                            .foregroundColor(appState.localServer.isRunning ? .green : .secondary)
                    }
                    HStack {
                        Text("IP")
                            .foregroundColor(.secondary)
                        Spacer()
                        Text(appState.localServer.ipAddress)
                    }
                    HStack {
                        Button(appState.localServer.isRunning ? "Stop Service" : "Start Service") {
                            if appState.localServer.isRunning {
                                appState.localServer.stop()
                            } else {
                                appState.localServer.start()
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        Spacer()
                    }
                } header: {
                    Text("Status")
                }

                Section {
                    NavigationLink("Manage Shared Folders") {
                        SharedFoldersView()
                    }
                } header: {
                    Text("Shared Folders")
                }

                Section {
                    NavigationLink("Nearby Devices") {
                        SharesView()
                    }
                } header: {
                    Text("Nearby")
                }
            }
            .navigationTitle("CornerNAS")
            .onAppear {
                appState.bonjourService.startBrowsing()
            }
            .onDisappear {
                appState.bonjourService.stopBrowsing()
            }
        }
    }
}
