const scanButton = document.getElementById("scanButton");
const subnetInput = document.getElementById("subnetInput");
const portInput = document.getElementById("portInput");
const deviceList = document.getElementById("deviceList");
const statusEl = document.getElementById("status");
const fileList = document.getElementById("fileList");
const currentPathEl = document.getElementById("currentPath");
const upButton = document.getElementById("upButton");
const refreshButton = document.getElementById("refreshButton");
const uploadButton = document.getElementById("uploadButton");
const fileInput = document.getElementById("fileInput");
const filesHint = document.getElementById("filesHint");
const uploadRow = document.querySelector(".upload-row");

const DEFAULT_PORT = 8000;
const MAX_PORT_LIST = 256;
let devices = [];
let activeDevice = null;
let currentPath = "/";
let hasLoadedDirectory = false;

const storage = chrome.storage.local;

function setStatus(message, isError = false) {
  statusEl.textContent = message;
  statusEl.style.color = isError ? "#c62828" : "";
}

function updateFilesVisibility() {
  const showFiles = Boolean(activeDevice) && hasLoadedDirectory;
  filesHint.classList.toggle("hidden", showFiles);
  fileList.classList.toggle("hidden", !showFiles);
  uploadRow.classList.toggle("hidden", !showFiles);
  currentPathEl.classList.toggle("hidden", !showFiles);
  upButton.disabled = !showFiles || currentPath === "/";
  refreshButton.disabled = !activeDevice;
}

function normalizePath(path) {
  if (!path) return "/";
  if (!path.startsWith("/")) return `/${path}`;
  return path;
}

function joinPath(base, name) {
  const cleanBase = normalizePath(base).replace(/\/$/, "");
  return `${cleanBase}/${name}`.replace(/\/+/, "/");
}

function parseTarget(input) {
  const trimmed = input.trim();
  if (!trimmed) return null;
  const [hostPart] = trimmed.split(/
/);
  if (hostPart.includes("/")) {
    return { type: "subnet", value: hostPart };
  }
  return { type: "single", value: hostPart };
}

function parseHost(host) {
  const [ip, port] = host.split(":");
  return { ip, port: port ? Number(port) : null };
}

function parsePortList(input) {
  const trimmed = input.trim();
  if (!trimmed) return [DEFAULT_PORT];

  const segments = trimmed.split(",").map((segment) => segment.trim()).filter(Boolean);
  const ports = [];

  for (const segment of segments) {
    if (segment.includes("-")) {
      const [startText, endText] = segment.split("-").map((value) => value.trim());
      const start = Number(startText);
      const end = Number(endText);
      if (!Number.isInteger(start) || !Number.isInteger(end) || start < 1 || end > 65535 || start > end) {
        throw new Error("Invalid port range.");
      }
      for (let port = start; port <= end; port += 1) {
        ports.push(port);
        if (ports.length > MAX_PORT_LIST) {
          throw new Error(`Port list too large. Limit to ${MAX_PORT_LIST} ports.`);
        }
      }
    } else {
      const port = Number(segment);
      if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error("Invalid port number.");
      }
      ports.push(port);
    }
  }

  return Array.from(new Set(ports));
}

function listToHtml(items, onClick) {
  const fragment = document.createDocumentFragment();
  items.forEach((item) => {
    const li = document.createElement("li");
    li.textContent = item.label;
    li.addEventListener("click", () => onClick(item));
    if (item.active) li.classList.add("active");
    fragment.appendChild(li);
  });
  return fragment;
}

function renderDevices() {
  deviceList.innerHTML = "";
  const items = devices.map((device) => ({
    ...device,
    label: `${device.name} (${device.ip}:${device.port})`,
    active: activeDevice && device.ip === activeDevice.ip && device.port === activeDevice.port,
  }));
  deviceList.appendChild(
    listToHtml(items, (device) => {
      activeDevice = device;
      hasLoadedDirectory = false;
      storage.set({ lastDevice: device });
      renderDevices();
      loadDirectory(currentPath);
    })
  );
  updateFilesVisibility();
}

function renderFiles(entries) {
  fileList.innerHTML = "";
  fileList.appendChild(
    listToHtml(entries, (entry) => {
      if (entry.type === "dir") {
        currentPath = normalizePath(entry.path);
        storage.set({ lastPath: currentPath });
        loadDirectory(currentPath);
      } else if (entry.type === "file") {
        downloadFile(entry.path, entry.name);
      }
    })
  );
}

async function fetchJson(url, options = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 1500);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal });
    if (!response.ok) {
      throw new Error(`Request failed: ${response.status}`);
    }
    return await response.json();
  } finally {
    clearTimeout(timeout);
  }
}

async function pingDevice(ip, port) {
  try {
    const data = await fetchJson(`http://${ip}:${port}/api/v1/ping`);
    return {
      name: data.name || "CornerNAS",
      ip,
      port: data.port || port,
    };
  } catch (error) {
    return null;
  }
}

async function scanSubnet(subnet, ports) {
  const [base] = subnet.split("/");
  const segments = base.split(".");
  if (segments.length !== 4) {
    throw new Error("Invalid subnet format.");
  }
  const prefix = segments.slice(0, 3).join(".");
  const ipList = Array.from({ length: 254 }, (_, i) => `${prefix}.${i + 1}`);
  const found = [];
  let scanned = 0;
  const concurrency = 20;

  async function worker(queue) {
    while (queue.length) {
      const ip = queue.shift();
      let device = null;
      for (const port of ports) {
        device = await pingDevice(ip, port);
        if (device) {
          break;
        }
      }
      scanned += 1;
      if (device) {
        found.push(device);
        setStatus(`Found ${found.length} device(s). Scanned ${scanned}/254...`);
      } else {
        setStatus(`Scanning ${scanned}/254...`);
      }
    }
  }

  const queue = [...ipList];
  const workers = Array.from({ length: concurrency }, () => worker(queue));
  await Promise.all(workers);
  return found;
}

async function scanDevices() {
  const target = parseTarget(subnetInput.value);
  if (!target) {
    setStatus("Enter a subnet or device IP.", true);
    return;
  }
  let ports = [];
  scanButton.disabled = true;
  setStatus("Scanning...");
  try {
    ports = parsePortList(portInput.value);
    if (target.type === "single") {
      const { ip, port } = parseHost(target.value);
      const portList = port ? [port] : ports;
      let device = null;
      for (const candidate of portList) {
        device = await pingDevice(ip, candidate);
        if (device) break;
      }
      devices = device ? [device] : [];
      setStatus(device ? "Device found." : "No device responded.");
    } else {
      devices = await scanSubnet(target.value, ports);
      setStatus(`Scan complete. ${devices.length} device(s) found.`);
    }
    if (devices.length === 0) {
      activeDevice = null;
    }
    renderDevices();
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    scanButton.disabled = false;
  }
}

async function loadDirectory(path) {
  if (!activeDevice) {
    setStatus("Select a device first.", true);
    return;
  }
  const safePath = normalizePath(path);
  currentPath = safePath;
  currentPathEl.textContent = safePath;
  storage.set({ lastPath: currentPath });
  setStatus("Loading directory...");
  try {
    const url = `http://${activeDevice.ip}:${activeDevice.port}/api/v1/list?path=${encodeURIComponent(safePath)}`;
    const data = await fetchJson(url, { cache: "no-store" });
    const entries = (data.entries || []).map((entry) => ({
      name: entry.name,
      type: entry.type,
      path: joinPath(safePath, entry.name),
      label: `${entry.type === "dir" ? "📁" : "📄"} ${entry.name}`,
    }));
    renderFiles(entries);
    hasLoadedDirectory = true;
    updateFilesVisibility();
    setStatus(`Loaded ${entries.length} item(s).`);
  } catch (error) {
    setStatus(`Failed to load directory: ${error.message}`, true);
    updateFilesVisibility();
  }
}

function goUp() {
  if (currentPath === "/") return;
  const parts = currentPath.split("/").filter(Boolean);
  parts.pop();
  const nextPath = `/${parts.join("/")}` || "/";
  loadDirectory(nextPath);
}

function downloadFile(filePath, name) {
  if (!activeDevice) return;
  const url = `http://${activeDevice.ip}:${activeDevice.port}/api/v1/file?path=${encodeURIComponent(filePath)}`;
  chrome.downloads.download({
    url,
    filename: name,
    saveAs: true,
  });
}

async function uploadFile() {
  if (!activeDevice) {
    setStatus("Select a device first.", true);
    return;
  }
  const file = fileInput.files[0];
  if (!file) {
    setStatus("Choose a file to upload.", true);
    return;
  }
  uploadButton.disabled = true;
  setStatus("Uploading...");
  try {
    const formData = new FormData();
    formData.append("file", file);
    const url = `http://${activeDevice.ip}:${activeDevice.port}/api/v1/upload?path=${encodeURIComponent(currentPath)}`;
    const response = await fetch(url, {
      method: "POST",
      body: formData,
    });
    if (!response.ok) {
      throw new Error(`Upload failed: ${response.status} (TODO: confirm upload endpoint)`);
    }
    setStatus("Upload complete.");
    loadDirectory(currentPath);
  } catch (error) {
    setStatus(error.message, true);
  } finally {
    uploadButton.disabled = false;
  }
}

scanButton.addEventListener("click", scanDevices);
refreshButton.addEventListener("click", () => loadDirectory(currentPath));
upButton.addEventListener("click", goUp);
uploadButton.addEventListener("click", uploadFile);

storage.get(["lastDevice", "lastPath", "lastSubnet"], (data) => {
  if (data.lastSubnet) {
    subnetInput.value = data.lastSubnet;
  }
  if (data.lastPortInput) {
    portInput.value = data.lastPortInput;
  }
  if (data.lastDevice) {
    activeDevice = data.lastDevice;
    devices = [activeDevice];
    renderDevices();
  }
  if (data.lastPath) {
    currentPath = normalizePath(data.lastPath);
    currentPathEl.textContent = currentPath;
  }
  if (activeDevice) {
    loadDirectory(currentPath);
  } else {
    updateFilesVisibility();
  }
});

subnetInput.addEventListener("change", () => {
  storage.set({ lastSubnet: subnetInput.value.trim() });
});

portInput.addEventListener("change", () => {
  storage.set({ lastPortInput: portInput.value.trim() });
});

updateFilesVisibility();
