import SwiftUI

struct SharedFolderRowView: View {
    let folder: SharedFolder

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "folder")
                .font(.title2)
            Text(folder.name)
                .font(.body)
            Spacer()
        }
        .padding(.vertical, 6)
    }
}
