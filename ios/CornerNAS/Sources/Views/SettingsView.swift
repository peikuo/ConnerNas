import SwiftUI

struct SettingsView: View {
    @State private var language = "System"

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Language: \(language)")
                }
            }
            .navigationTitle("Settings")
        }
    }
}
