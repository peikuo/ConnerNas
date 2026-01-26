import SwiftUI

struct DeviceRowView: View {
    let device: DeviceInfo

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "iphone")
                .font(.title2)
            VStack(alignment: .leading) {
                Text(device.name)
                    .font(.headline)
                Text("\(device.host):\(device.port)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 6)
    }
}
