# CornerNAS Chrome Extension

CornerNAS is a simple Chrome Extension (Manifest V3) for discovering CornerNAS devices on your LAN and transferring files via the CornerNAS HTTP API.

## Install (Developer Mode)

1. Open Chrome and go to `chrome://extensions`.
2. Enable **Developer mode**.
3. Click **Load unpacked** and select the `chrome/` folder in this repo.
4. The CornerNAS icon should appear in your toolbar.

## Usage

1. Click the CornerNAS icon to open the popup.
2. Enter an IP address (e.g. `192.168.31.10`) or a `/24` subnet (e.g. `192.168.31.0/24`).
3. Enter the port, a port range (e.g. `10000-10999`), or a comma list (e.g. `8000,9000`).
4. Click **Scan** to discover devices.
5. Select a device to browse files.
6. Click folders to drill down; click files to download.
7. Use **Upload** to send a file to the current directory.

## API Endpoints

- `GET /api/v1/ping`
- `GET /api/v1/list?path=/path`
- `GET /api/v1/file?path=/path/file`
- `POST /api/v1/upload?path=/path` (if your server uses a different upload path, update `popup.js`)

## Notes

- Chrome extensions cannot use mDNS, so discovery is done via subnet scan or direct IP.
- The last used device and path are saved with `chrome.storage.local`.
- Port scans are capped to 256 ports per scan to keep the UI responsive.
