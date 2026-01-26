# CornerNAS

**Turn an unused Android device into a quiet NAS in the corner of your room.**

**把一台闲置的 Android 设备，变成房间角落里安静工作的 NAS。**

---

CornerNAS is a lightweight Android app that transforms an old tablet or phone into a **local-only NAS**.

CornerNAS 是一个轻量级 Android 应用，  
它让旧平板、闲置手机，在不联网、不上云的前提下，  
变成一个只属于你自己的 **局域网存储节点**。

No cloud.  
No subscription.  
Just files, quietly served over your local network.

不依赖云服务，不需要订阅。  
只有文件，在你的局域网里，安静地被访问。

---

## Features  
## 功能特性

- 📂 Share one or more folders (Storage Access Framework)  
  📂 通过 SAF 共享一个或多个目录  

- 🌐 Access files over local network (HTTP)  
  🌐 通过局域网访问文件（HTTP）  

- ⬇️ Download files  
  ⬇️ 文件下载  

- ⬆️ Upload files (write supported)  
  ⬆️ 文件上传，支持写入  

- 🔋 Foreground service for stable long-running use  
  🔋 前台服务模式，适合长期运行  

- 🏠 LAN-only, private by design  
  🏠 仅限局域网，天然私有  

---

## What CornerNAS is for  
## CornerNAS 适合做什么

- Reusing abandoned Android tablets  
  利用闲置、性能过时但仍可用的 Android 平板  

- Simple home file sharing  
  家庭环境下的简单文件共享  

- Room-level private storage  
  房间级别的私有存储节点  

- A quiet NAS that lives in the corner  
  放在房间角落里、不打扰你的 NAS  

---

## What CornerNAS is NOT  
## CornerNAS 不是什么

- ❌ SMB / RAID / Enterprise NAS  
  ❌ 企业级 NAS 或复杂存储系统  

- ❌ Cloud storage  
  ❌ 云存储服务  

- ❌ Public internet access  
  ❌ 面向公网的文件服务  

CornerNAS 不试图替代专业 NAS，  
它只解决一个更小、更克制的问题。

---

## Tech Stack  
## 技术栈

- Kotlin / Android 11+  
- Storage Access Framework (SAF)  
- Embedded Ktor HTTP Server  
- Foreground Service  

遵循 Android 官方存储与后台策略，  
不使用 root，不绕权限，不走捷径。

---

## Project Status  
## 项目状态

- V1: Basic file sharing (in progress)  
  V1：基础文件共享（开发中）  

- V2: WebDAV support  
  V2：WebDAV 支持  

- V3: Sync / API extensions  
  V3：同步 / API 扩展  

---

## Philosophy  
## 项目理念

CornerNAS is built on a simple idea:

> Devices don't become useless just because they are old.

CornerNAS 的出发点很简单：

> **设备不会因为变旧，就失去继续工作的价值。**

---

## License  
## 许可证

MIT
