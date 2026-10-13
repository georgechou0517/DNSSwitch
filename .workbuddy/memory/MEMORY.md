# DNSSwitch 项目长期记忆

## 项目定位
`C:\Users\GeorgeChou\Desktop\DNSswitch` — Android 加密 DNS 客户端，完全免费无广告。
差异化卖点：**传统 DNS（UDP/TCP 端口可自定义）+ DoH + DoT 三者可同时启用并行工作**（市面 RethinkDNS/Nebulo 为二选一）。

## 架构要点（勿随意改）
- VpnService 虚拟 DNS 方案：TUN 10.111.222.1/**24** + FAKE_DNS 10.111.222.2 + addRoute 10.111.222.0/24，**只接管 DNS 不接管全部流量**
  - ⚠️ 必须用 /24（接口地址与 DNS 服务器同网段）。曾用 /32 接口 + 指向 .2 的 /32 路由 → 路由目标不在接口网段，部分 ROM 拒绝 → `establish()` 返回 null → 服务静默退出（v1.0.1 已修）
  - ⚠️ **establish() 返回的 TUN fd 默认非阻塞**（官方 setBlocking 文档原文）——读循环按阻塞语义设计就必须 `.setBlocking(true)`，否则空读抛 EAGAIN、读循环秒退、服务无提示自停（v1.0.3 才破案，此前三轮被误判为"establish 失败"）
  - 启动顺序：**先 `startForeground()` 再 `establish()`**（5 秒前台服务规则）
  - 终端失败必须写入 `DnsStats.onVpnError()`，UI 经 statusJson 的 `state`/`lastError` 展示，禁止静默 `stopSelf()`；读循环 EAGAIN 退避重试不退出，其他异常连续 20 次才判死
- 上游 socket 必须 `protect()` 防环路；每查询新建 socket
- 报文层纯手写：PacketParser（IPv4/UDP/TCP 解析）、Checksum、ResponseBuilder（IP 分片 + TCP RST）
- DNS 报文嫁接 adoptResponse（换 ID + 换问题区）；全失败 buildServfail
- 五种策略：all_traditional/all_doh/all_dot/smart_failover（默认 DoH→DoT→传统）/parallel_race
- DoH = OkHttp POST application/dns-message（RFC 8484）+ GET 回退；DoT = SSLSocket + 2 字节长度前缀（RFC 7858，OkHttp 无法承载裸 TCP 字节流，故不用 OkHttp）
- **bootstrap 解析（v1.1.0 起，勿删）**：VPN 启动后系统 DNS 已被自身接管，域名型 DoH/DoT 上游若走系统解析器解析自身主机名 = 自查询死循环。`dns/Bootstrapper` 用 protected socket 向传统 DNS（配置地址；回落 223.5.5.5/119.29.29.29）直发原生 A/AAAA 查询，引导服务器是 IP 字面量故环路断开；60s 缓存；OkHttpClient 挂 `BootstrapDns`；DoT 填主机名时设 SNI + 证书主机名校验，填 IP 时跳过
- DoT 服务器字段接受**主机名或 IP**（Validators.isHostOrIp）；传统 DNS 只能填 IP
- 传统 DNS / DoT 都有 `server`（v4 或主机名）与 `serverV6`（IPv6 备用）；都填时 v4 优先、失败回退 v6
- UI = WebView 加载 assets/www（index.html/app.css/app.js/privacy.html），window.NativeBridge 桥接；状态每 1.2s 经 onNativeStatus(jsonStr) 推送
- 前端防抖自动保存 400ms；开关 change 立即保存；预览模式（无 NativeBridge）降级 + 顶部 banner
- **按钮统一动效（方案「B 弹」，2026-10-13 落地）**：`app.css` 末尾 `:root` 定义 `--dur-press:110ms` / `--dur-release:420ms` / `--ease-press:cubic-bezier(.4,0,1,1)` / `--ease-release:cubic-bezier(.34,1.56,.64,1)`。**按下快、抬起慢**是「跟手」的关键。9 类按钮（power-btn/chip/seg button/text-btn/test-btn/bottom-nav button/modal .btns button/import-actions button/pr-use/pr-del）共用一条 `:active scale(.94)`；大面的 `.radio-card` 幅度减半 .97；开关滑块走 `transform:translateX(var(--tx)) scale(var(--sc))`（**不要改回 `left`**，布局属性动画掉帧）；`prefers-reduced-motion` 降 1ms。
  - ⚠️ 新增动效时：若目标元素已有 `transition` 简写，**不要**把它并进新的选择器组（简写会整体覆盖原规则）；只过渡 `transform`，不要在基础规则里混 background/color（`:active` 的 `transition-duration` 会一并改写）
- **自定义预设（v1.1.0）**：`config/DnsPreset.kt`（PresetType / DnsPreset / ImportReport / PresetLogic 纯逻辑）+ `config/PresetManager.kt`（SharedPreferences `dnsswitch_presets`，与 ConfigManager 分开）
  - txt 行格式 `地址 名字 分类`：分类 = 行末 token（`dns`/`doh`/`dot`，**不区分大小写**），名字 = 中间所有 token（可含空格）
  - 省略分类时按地址形态推断：http(s):// → DoH；IP → 传统 DNS；其他主机名 → DoT
  - **分类明确但地址形态不符 → 该行判无效**（不猜类型）
  - 合并键 = 「分类 + 名字」（小写），故 IPv4 行与 IPv6 行合成一条
  - txt 导入走 `MainActivity.onShowFileChooser` → `onActivityResult` 读文件（UTF-8 优先，含 U+FFFD 回退 GBK）→ `window.onPresetFileLoaded(text)` 回传 JS；**不要依赖 WebView 里的 FileReader 读 content:// URI**
  - 命名用自绘 modal，**不要用 `window.prompt`**（WebChromeClient 未覆写 onJsPrompt 时静默返回 null）

## 构建环境（本机固定）
- JDK17 `C:\Users\GeorgeChou\AndroidTools\jdk\jdk-17.0.2`；Gradle 8.7 `C:\Users\GeorgeChou\AndroidTools\gradle-8.7`；SDK `C:\Users\GeorgeChou\AndroidTools\sdk`
- 构建：`export JAVA_HOME=<jdk17> && ./gradlew assembleDebug assembleRelease`（wrapper distributionUrl 指向腾讯云镜像）
- Release 签名：`app/keystore/dnsswitch-release.jks`（三要素均 dnsswitch，已 gitignore，切勿提交）
- **local.properties 必须用正斜杠**（Properties 转义坑）
- 图标：`tools/gen_icons.py` 纯 Python 生成 5 密度 PNG；Release APK res 条目为 AAPT 短名

## 版本与交付
- versionCode 5 / versionName **1.1.0**（1.0.1 修 /24 路由；1.0.2 加诊断日志；1.0.3 修非阻塞 TUN fd 根因；**1.1.0 自定义预设 + txt 导入 + IPv4/IPv6 分别设置 + bootstrap 解析**）；debug applicationId 后缀 .debug 可与 release 共存，**debug 包 label =「DNSSwitch 调试」**（防桌面双图标点错旧包）
- 交付物在 `dist/`：源码 zip + debug/release APK
- MIT-0 协议；README 中英双语锚点跳转（含常见问题/排障一节）

## 真机排障要点（v1.0.2 诊断体系）
- 启动链路每一步都写日志页「系统」条目：收到启动指令 → 前台服务已就绪 → VPN 接口已建立 → 隧道读循环退出（含原因）；`Log` TAG 统一 "DNSSwitch" 供 adb 过滤
- 断链定位：无「收到启动指令」= startForegroundService 未拉起服务；缺「前台服务已就绪」= startForeground 抛异常；缺「VPN 接口已建立」= establish 失败（原因在状态区/红点系统日志）；establish 成功后又秒退 = TUN 读失败
- 本机 adb 可用：`C:\Users\GeorgeChou\AndroidTools\sdk\platform-tools\adb.exe`，手机连 USB 开调试可直接 logcat 定位

## 测试
59 个 JVM 单元测试（`./gradlew test`）：报文解析/校验和/分片/RST/配置校验/DNS 嫁接 + buildQuery/AAAA/formatIpv6 + IPv6 与主机名校验 + 预设行解析与合并（PresetLogicTest 15 例）。

## 代码卫生约定
- 通道展示名只保留 `ChannelType.label`；`DnsChannel` 接口**不要**再加 `label`（曾有三份实现全无人读）
- 策略描述文案只在 `index.html` 的 `.d` 里维护一份；`Strategy` 枚举不要再加 `desc`（双份必漂移）
- 查询报文统一用 `DnsMessage.buildQuery(name, type)`，不要为测试再固定 ID 的变体
- 改完 UI/逻辑后自查：新增的 CSS class 必须在 HTML/JS 真实出现，否则即死样式

## 待办（P2）
域名分流、开机自启、DNS 缓存、多配置切换；真机验证 VPN 长稳定性。
