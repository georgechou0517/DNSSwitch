# DNSSwitch

> **完全免费 · 无任何广告 · 无任何追踪 · 传统 DNS 与 DoH / DoT 可同时启用的 Android 加密 DNS 客户端**

[![License: MIT-0](https://img.shields.io/badge/License-MIT--0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-brightgreen.svg)](https://www.android.com)
[![Language](https://img.shields.io/badge/Language-100%25%20Kotlin-purple.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-WebView%20%2B%20Material%20Design%203-orange.svg)](assets/www)

**语言切换 / Language：** 点击下方任意一行「▸」即可展开对应语言（默认中文）。

---

<details open>
<summary>🇨🇳 简体中文</summary>

## 一、当前安卓 DNS 工具的痛点

市面上的 DNS 切换 / 加密 DNS 应用，几乎都陷入「二选一」的尴尬局面：

| 工具类型 | 能做到 | 做不到 |
| --- | --- | --- |
| **支持自定义 DNS 地址的**（比如把 DNS 改成 `223.5.5.5`、自定义端口） | 自由填写你信任的传统 DNS 服务器 | ❌ 不支持 DoH / DoT 加密查询 |
| **支持 DoT / DoH 加密的** | 加密你的 DNS 查询，防窃听、防污染 | ❌ 不支持「自定义传统 DNS + DoH + DoT」三者同时开启、并行工作 |

带来的现实困境：

- 你想用自己信任的**传统 DNS**（运营商给的、某个公共 DNS、或带自定义端口的）？那基本就要和 DoH / DoT 加密说再见。
- 你想上 **DoH / DoT 加密**？那这类应用往往只允许你在它内置的寥寥几个服务器里挑，没法同时保留你自己的传统 DNS，更别说三者一起跑。

> **DNSSwitch 要解决的，正是这一个痛点：让「传统 DNS（UDP/TCP，端口可自定义）+ DoH + DoT」三者可以同时开启、并行工作，而不是二选一。**

---

## 二、功能简介（小白向）

### DNS 是什么？为什么要在意它？

每次你在手机上打开一个网站、用一个 App，系统都要先把「网址」翻译成「服务器 IP 地址」——这个翻译过程就叫 **DNS 查询**。默认情况下，这个查询是**明文**的，你的运营商、局域网里的人，理论上都能看到你访问了哪些网站。

**加密 DNS（DoH / DoT）** 就是把这段查询加密，别人看不到你在访问什么。而 **DNSSwitch** 就是一个让你在安卓上轻松用上加密 DNS 的小工具——而且它最特别的地方是：**你可以把传统 DNS 和加密 DNS 一起开着**。

### DNSSwitch 能做什么？

- **🌐 三条通道一起跑**：传统 DNS（UDP/TCP，端口随便改）、DoH、DoT 任意组合同时启用，谁快用谁。
- **🔧 端口全自定义**：传统 DNS、DoT 的服务器地址和端口（1–65535）随便填；DoH 还能自定义完整 URL（可带端口）。
- **📡 IPv4 / IPv6 分别设置**：传统 DNS 和 DoT 都能分别填 IPv4 和 IPv6；两个都填时优先 IPv4，失败自动切 IPv6。
- **⭐ 自定义预设 + txt 一键导入**：常用服务器存成预设，点一下就切换；还能从一个 txt 文件批量导入几十上百个服务器。
- **🧭 五种分流策略**（不用懂也能用，默认「智能分流」就很好）：
  - 全部走传统 DNS / 全部走 DoH / 全部走 DoT
  - **智能分流（默认）**：按顺序 DoH → DoT → 传统 DNS 尝试，哪条通走哪条
  - **并行竞速**：每条查询同时发往所有通道，取最快的回答
- **🔒 只接管 DNS，不碰你的其他流量**：它只拦截 DNS 查询，网页、视频、游戏的流量照常走原路，不会变成全局代理。
- **🏠 完全本地、零追踪**：所有配置存在你手机上，无账号、无服务器、无广告、无统计 SDK。

---

## 三、快速上手（3 分钟）

1. 下载 [`DNSSwitch-26.10.1-release.apk`](dist/) 安装；首次打开时系统会弹出 **VPN 权限** 请求（仅用于接管 DNS，不会转发你的其他流量）。
2. 在「**配置**」页打开你想用的通道（传统 DNS / DoH / DoT），填服务器地址与端口，或直接点「＋ 存为预设」套用现成服务器。
3. 点「**启动 DNS 服务**」，顶栏出现「运行中」即生效。
4. 切到「**日志**」页可查看最近 100 条查询记录；「**关于**」页可看版本与隐私政策。

> 不会配？应用内置了常见公共 DNS 的预设，点一下就能用。更多预设 / txt 批量导入格式见下方「技术参考」。

---

## 四、工作原理（一句话）

DNSSwitch 在手机里建了一个**虚拟 DNS 服务器**，所有 App 的 DNS 查询都被它接住，再由你配置的「传统 DNS / DoH / DoT」通道并行发出去取回结果——你的查询从此可以走加密通道，也可以和传统 DNS 并肩作战。

---

## 五、感谢使用的开源项目

DNSSwitch 站在开源巨人的肩膀上，特此致谢：

| 项目 | 用途 | 协议 |
| --- | --- | --- |
| [OkHttp](https://github.com/square/okhttp)（Square） | DoH（RFC 8484）等 HTTP 请求 | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin)（JetBrains） | 应用开发语言 | Apache-2.0 |
| [Android](https://source.android.com/) / [AndroidX Core KTX](https://developer.android.com/jetpack/androidx)（Google） | 运行平台与核心库 | Apache-2.0 |
| [Android Gradle Plugin](https://developer.android.com/build) + [Gradle](https://gradle.org)（Google） | 构建工具链 | Apache-2.0 |
| [JUnit](https://github.com/junit-team/junit4) | 单元测试框架 | EPL-2.0 |
| [org.json](https://github.com/stleary/JSON-java) | 测试用的 JSON 解析 | MIT / 公有领域 |

---

## 六、开源协议

本项目以 **[MIT-0](LICENSE)（MIT No Attribution）** 协议开源：你可以自由地使用、修改、分发、甚至商用，**无需署名、无需保留版权声明**。

---

</details>

<details>
<summary>🇬🇧 English</summary>

## 1. The pain points of today's Android DNS tools

Almost every DNS-switcher / encrypted-DNS app on Android forces you into an **either/or** choice:

| Type of tool | Can do | Cannot do |
| --- | --- | --- |
| **Supports custom DNS addresses** (e.g. set DNS to `223.5.5.5`, custom port) | Freely use the traditional DNS server you trust | ❌ No DoH / DoT encryption |
| **Supports DoT / DoH encryption** | Encrypt your DNS queries (anti-snooping, anti-poisoning) | ❌ Cannot run "custom traditional DNS + DoH + DoT" all at once, in parallel |

The real-world dilemma:

- Want to use a **traditional DNS** you trust (your ISP's, a public resolver, or one with a custom port)? You basically have to give up DoH / DoT encryption.
- Want **DoH / DoT encryption**? Those apps usually let you pick from only a handful of built-in servers — you can't keep your own traditional DNS, let alone run all three together.

> **DNSSwitch exists to fix exactly this: it lets "Traditional DNS (UDP/TCP, custom ports) + DoH + DoT" all be enabled at once and work in parallel — not an either/or choice.**

---

## 2. Feature guide (for beginners)

### What is DNS, and why should you care?

Every time you open a website or use an app, your phone must translate the "domain name" into a "server IP address" — that lookup is called a **DNS query**. By default it is sent in **plaintext**, so your carrier or anyone on the local network can, in theory, see which sites you visit.

**Encrypted DNS (DoH / DoT)** encrypts that lookup so nobody can see what you're resolving. **DNSSwitch** is a small tool that makes encrypted DNS easy on Android — and its standout feature is that **you can keep traditional DNS running alongside encrypted DNS**.

### What DNSSwitch does

- **🌐 Three channels at once**: Traditional DNS (UDP/TCP, any port), DoH and DoT can be enabled in any combination and work together.
- **🔧 Fully custom ports**: server address and port (1–65535) for Traditional DNS and DoT; DoH accepts a full custom URL (with optional port).
- **📡 Separate IPv4 / IPv6**: Traditional DNS and DoT each accept distinct IPv4 and IPv6; when both are set, IPv4 is tried first and IPv6 is the fallback.
- **⭐ Custom presets + txt bulk import**: save favorite servers as presets and apply with one tap; import dozens or hundreds of servers from a txt file.
- **🧭 Five routing strategies** (zero-config friendly — the default "Smart failover" just works):
  - All traditional / All DoH / All DoT
  - **Smart failover (default)**: tries DoH → DoT → Traditional in order, using whichever answers.
  - **Parallel race**: sends every query to all enabled channels at once and takes the fastest answer.
- **🔒 DNS-only, no traffic hijack**: only DNS queries are intercepted; web, video and game traffic flows as usual — no global proxy.
- **🏠 100% local, zero tracking**: configs live on your device; no accounts, no servers, no ads, no analytics SDK.

---

## 3. Quick start (3 minutes)

1. Install [`DNSSwitch-26.10.1-release.apk`](dist/); on first launch the system asks for **VPN permission** (used only to take over DNS — no other traffic is forwarded).
2. On the **Config** tab, enable the channels you want (Traditional DNS / DoH / DoT), fill in server & port, or tap "＋ Save as preset" to apply a ready-made server.
3. Tap **Start DNS Service** — "Running" in the app bar means it's active.
4. The **Logs** tab shows the last 100 queries; **About** shows version and the privacy policy.

> Not sure what to enter? The app ships with presets for common public resolvers — one tap and you're done. See "Technical Reference" below for the txt bulk-import format.

---

## 4. How it works (in one sentence)

DNSSwitch creates a **virtual DNS server** on your phone: every app's DNS query is caught, then resolved in parallel through the Traditional DNS / DoH / DoT channels you configured — so your lookups can travel over encrypted channels while still standing shoulder-to-shoulder with traditional DNS.

---

## 5. Open-source projects we use

DNSSwitch stands on the shoulders of open source — with thanks to:

| Project | Used for | License |
| --- | --- | --- |
| [OkHttp](https://github.com/square/okhttp) (Square) | DoH (RFC 8484) and other HTTP requests | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) (JetBrains) | The app's language | Apache-2.0 |
| [Android](https://source.android.com/) / [AndroidX Core KTX](https://developer.android.com/jetpack/androidx) (Google) | Runtime platform & core library | Apache-2.0 |
| [Android Gradle Plugin](https://developer.android.com/build) + [Gradle](https://gradle.org) (Google) | Build toolchain | Apache-2.0 |
| [JUnit](https://github.com/junit-team/junit4) | Unit-test framework | EPL-2.0 |
| [org.json](https://github.com/stleary/JSON-java) | JSON parsing in tests | MIT / public domain |

---

## 6. License

This project is open source under **[MIT-0](LICENSE) (MIT No Attribution)** — free to use, modify, redistribute and even commercialize, **with no attribution required**.

---

</details>

---

# 技术参考 / Technical Reference

> 以下为开发者向内容（语言无关）。中英双语的「痛点 / 功能简介 / 快速上手 / 致谢 / 协议」见上方切换按钮。

## 架构概览

```
┌─────────────────────────────────────────────┐
│ UI 层：assets/www/index.html + app.js        │
│ （WebView 加载，NativeBridge 桥接）            │
├─────────────────────────────────────────────┤
│ 原生层（Kotlin）                              │
│  DnsVpnService   TUN 接管 / 报文读写循环       │
│  PacketParser    IPv4 / UDP / TCP 报文解析     │
│  ResponseBuilder 响应构造（IP 分片 / TCP RST）  │
│  DnsResolver     分流策略（故障转移 / 竞速）     │
│  Channel 实现     Traditional / Doh / Dot      │
│  ConfigManager   SharedPreferences 持久化      │
└─────────────────────────────────────────────┘
```

- **传统 DNS**：UDP 优先，TC 位置位自动转 TCP 重试；每次查询独立 socket 并 `protect()` 防环路
- **DoH**：OkHttp 发起 `POST application/dns-message`（RFC 8484），失败回退 GET `?dns=base64url`
- **DoT**：SSLSocket + 2 字节长度前缀 framing（RFC 7858）。填**主机名**时设置 SNI 并做证书主机名校验；填 IP 时跳过主机名校验
- **bootstrap 解析**：VPN 启动后系统 DNS 已被自身接管，若再用系统解析器解析 DoH / DoT 的主机名会形成自查询死循环。主机名改由 `Bootstrapper` 用 protected socket 向传统 DNS 直发原生 A / AAAA 查询获得
- **IP 分片**：响应超过 MTU 1500 时按 8 字节倍数分片回传；TCP DNS 查询直接回 RST 让 App 快速回退 UDP

## 预设与 txt 导入

在任一通道卡片点「**＋ 存为预设**」可把当前填写的服务器存成预设，之后以快捷标签形式出现在卡片下方，点击一键切换。「**管理预设**」可查看全部、应用、删除，以及批量导入。

导入的 txt **每行一条**，格式：`地址 空格 名字 空格 分类`

- 分类写在**行末**，取值 `dns` / `doh` / `dot`，**不区分大小写**
- 名字是中间 token，可带空格（`1.1.1.1 Cloudflare 家庭版 DNS`）
- 省略分类时按地址形态推断：`http(s)://` → DoH，IP → 传统 DNS，其他主机名 → DoT
- **同分类 + 同名字的条目自动合并**，IPv4 与 IPv6 两行合成一条预设
- 以 `#` 或 `//` 开头的行、空行忽略；地址不合法的行被跳过并提示

示例：

```
223.6.6.6 阿里云 DNS
2400:3200::1 阿里云 DNS
dns.alidns.com 阿里云 DoT
https://dns.alidns.com/dns-query 阿里云 DoH
119.29.29.29 腾讯 DNS
https://doh.pub/dns-query 腾讯 DoH
```

上面 6 行生成 4 条预设，其中「阿里云 DNS」同时带 IPv4 与 IPv6。

## 项目结构

```
app/src/main/
├── java/com/dnsswitch/
│   ├── MainActivity.kt          WebView 宿主 / VPN 授权 / 文件选择器
│   ├── web/WebAppInterface.kt   JS ↔ 原生桥接
│   ├── config/                  配置模型 + 预设模型 + SharedPreferences 持久化
│   ├── dns/                     DNS 报文工具 / 三通道 / bootstrap / 分流 / 统计
│   └── vpn/                     VpnService / 报文解析与响应构造
├── assets/www/                  index.html / app.css / app.js / privacy.html
└── res/                         图标 / 主题 / 字符串
```

## 路线图

- [x] P0：VpnService 接管、传统 DNS 自定义、DoH、DoT、三者同开、列表式 UI、配置持久化
- [x] P1：状态展示、日志（最近 100 条）、通道连通性测试、按钮动效
- [ ] P2：域名分流、开机自启、DNS 缓存、多配置切换
- [ ] 网络共享下游设备 DNS 接管（USB 网卡 / 路由器 / 热点）：多数设备**无需 root**——手机开共享后，系统的「共享 DNS 代理」会把下游设备的 DNS 查询经系统解析器转发，而本应用 VPN 激活时已把系统 DNS 指向内置虚拟 DNS，下游 DNS 自动被接管；个别 ROM 若让共享流量绕过 VPN，可关开发者选项里的「网络共享硬件加速」或 root + iptables 透明重定向兜底。真机验证中

## 常见问题

- **点「启动」提示启动失败**：具体原因会显示在运行控制卡片下方并记入「日志」页。常见原因：设备上已有其他 VPN 在运行、VPN 授权被撤销、厂商安全中心拦截 VPN 权限。关闭其他 VPN 后重试。
- **启动成功但解析没变化**：确认至少启用一条通道且服务器/端口正确；用运行状态卡片里的「测试」按钮验证单通道连通性。部分 ROM 需把本应用加入电池优化白名单。
- **服务运行一段时间后被系统杀掉**：加入电池优化白名单，并在最近任务里锁定后台。
- **可以让共享给下游设备（USB 网卡 / 路由器 / 热点）的流量也走我设的 DNS 吗？**：可以，而且多数设备**无需 root**。手机开启网络共享后，系统自带的「共享 DNS 代理」会把下游设备发来的 DNS 查询经系统解析器转发；而本应用 VPN 激活时已把系统 DNS 指向内置虚拟 DNS（如 `10.111.222.2`），因此下游设备的 DNS 同样被接管，且只拦 DNS、不影响局域网内 IP 互访。**不需要**像某些方案那样去 root 或装 Shizuku。个别 ROM 会让共享流量绕过 VPN，可在开发者选项关闭「网络共享硬件加速」再试；若系统「私人 DNS（DoT）」开着会直连上游、绕过虚拟 DNS，请先关闭。极少数仍不生效的机型，root + iptables 透明重定向（把 `:53` 流量 REDIRECT 到本地转发器）作为兜底——届时将复用现有三通道解析逻辑。

## 从源码构建

环境要求：JDK 17、Android SDK（Platform 34、Build-Tools 34.0.0）。

```bash
git clone https://github.com/georgechou0517/DNSSwitch.git
cd DNSSwitch
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug    # → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease  # 需先生成签名密钥
```