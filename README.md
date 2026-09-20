# LeLeMusic

聚合 **网易云音乐 / 酷狗音乐 / 哔哩哔哩** 三平台榜单 + **本地歌单** 的音乐播放器，Android + iOS 双端。

- Android：Kotlin 1.9.22 + Jetpack Compose + Media3 (ExoPlayer)，minSdk 26 / targetSdk 34，包名 `com.lelemusic`
- iOS：SwiftUI + AVPlayer（零第三方依赖），XcodeGen 管理工程，GitHub Actions 出 unsigned IPA

> QQ 音乐已于 2026-09 下线（接口被服务端拦截，无法稳定取链），不再作为音源支持。

---

## 一、功能特性

| 模块 | 说明 |
|---|---|
| 榜单 | 网易云（热歌榜/新歌榜）、酷狗（TOP500/飙升榜）、B站（热门视频），Top50 平铺首页，点歌即播 |
| 搜索 | 各平台歌曲搜索（网易云 cloudsearch / 酷狗 song_search_v2） |
| 取链 | 降级责任链：平台官方接口 → LX 代理源 → 兜底策略；全曲取不到时自动降级试听片段并明示 |
| 歌单 | 我的歌单：本地导入（SAF 多选）/ 网盘挂载，可建/改名/删除；歌单详情页可继续加歌 |
| 本地播放 | mp3 / flac / m4a / wav / aac / ogg / opus 直接播放 |
| 歌词 | 内嵌滚动歌词 + 桌面悬浮歌词；无词时点击「暂无歌词」联网自动匹配（网易云→酷狗）；点击歌词行跳播 |
| 播放 | 锁屏控制、后台播放、倍速、列表循环/单曲循环/随机、失败卡片（重试 + 跳官方收听） |
| 平板/车机 | ≥600dp 默认铺满到边（可关）；Expanded 横屏播放页双栏（左封面控制/右歌词）；手机锁定竖屏 |
| 设置 | 启动自动播放开关、运行期策略开关、**音源地址在线编辑**（查看/修改/恢复默认） |
| 自检台 | 每策略实测取链 + 真实音频时长探测，TSV 日志一键复制 |

---

## 二、编译环境要求（Android）

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | `app/build.gradle.kts` 里 `sourceCompatibility = VERSION_17`，低版本会直接编译失败 |
| Android SDK | **34**（compileSdk / targetSdk） | SDK Manager 里装好 `Android SDK Platform 34` 与 Build-Tools |
| Gradle | 由 wrapper 指定 | 直接跑 `./gradlew`，不要用自己的全局 Gradle |
| Kotlin | 1.9.22 | 已在 `gradle/libs.versions.toml` 锁定 |
| Compose Compiler | 1.5.8 | **必须与 Kotlin 1.9.22 精确配对**，改一个字符就整轮返工 |

### 第一步：建 `local.properties`

项目根目录有 `local.properties.example`。**复制一份改名**为 `local.properties`，再改里面的 `sdk.dir`。

> ⚠️ **Windows 路径必须写成 `\\`（双反斜杠）或 `/`（正斜杠）**。
> 单个 `\` 会被 properties 解析器当成转义符吃掉，表现为「Gradle 找不到 SDK」但路径看起来完全正确。

```properties
# ✅ 正确（两种写法都行）
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
sdk.dir=C:/Users/YourName/AppData/Local/Android/Sdk

# ❌ 错误：单个反斜杠会被当转义符
sdk.dir=C:\Users\YourName\AppData\Local\Android\Sdk
```

### 编译与安装

```bash
./gradlew assembleDebug          # 出 APK（LeleMusic-vYY.MM.DD.apk）
./gradlew installDebug           # 装到已连接的设备
```

Windows 用 `gradlew.bat`。APK 文件名由 `versionName`（YY.MM.DD 格式）自动生成。

---

## 三、iOS 版

`ios/` 目录是独立的 SwiftUI 工程（XcodeGen 定义，无 `.xcodeproj` 入库）。与 Android 端能力对齐：榜单/搜索/歌单/播放/歌词，`playlists.json` 与 Android **同 schema，两端文件可互拷**。

- **本机无 Mac 时**：push 到 GitHub 后 Actions（macos runner）自动出 **unsigned IPA** Artifact；
- **安装**：Windows 上用 [Sideloadly](https://sideloadly.io) + Apple ID 签名侧载（免费 Apple ID 7 天重签一次），步骤见 `ios/README-INSTALL.md`；
- 编译要求（有 Mac 时）：Xcode 16.2+（xcodegen 2.46 生成的项目格式 77 需要）、`brew install xcodegen`。

---

## 四、GitHub Releases 自动发布

`.github/workflows/release.yml`（对齐 local-sharing 模式）：

| 触发 | 行为 |
|---|---|
| push 到 main | Android APK + iOS IPA → Actions Artifacts（日常测试） |
| **push `v*` tag** | 双端构建 → 自动发布到 GitHub Releases（正式发布） |
| 手动 dispatch | Actions 页 Run workflow 随时触发 |

发版流程：改 `app/build.gradle.kts` 的 `versionName/versionCode`（YY.MM.DD）→ `git tag vYY.MM.DD` → `git push origin main --tags`。

最新包：[Releases](https://github.com/camel52zhang/LeleMusic/releases)。

---

## 五、真机验证清单

1. **装好 App，授予通知权限**——不给权限，播放通知与锁屏控制不可用；
2. **首页能出歌**：平台 Tab（网易云/酷狗/B站/我的歌单）+ 榜单 Tab，每行排名 + 封面 + 歌名 + 时长；平台不可用时 Tab 挂「维护中」角标（仍可点，进去会重试）；
3. **点歌能播**：全屏播放器，封面/歌名/进度/控制齐全；歌名下出现橙色「试听片段」是**降级成功**不是 bug；红色错误卡片有「重试」和「跳转到官方收听」两个出口；
4. **自检台**（首页右上角齿轮，或标题「LeLeMusic」2 秒内连点 7 次）：点「开始自检」对每个策略实测取链并用 `MediaMetadataRetriever` 读真实时长比对，逐行输出判定（全曲/试听片段/失败），右上角可复制 TSV 日志；
5. **运行期开关与音源地址**（自检台内）：策略开关下一次取链立即生效；每行铅笔按钮可查看/修改/恢复该策略的音源地址（App 重启后仍生效）；
6. **歌词**：播放页左滑进歌词页，滚动高亮 + 点击行跳播；本地无词歌曲点「暂无歌词」区域联网匹配；
7. **平板**：≥600dp 设备默认铺满到边，Expanded 横屏双栏布局。

---

## 六、已知限制

1. **网易云部分歌曲被拦截**（版权风控），依赖 LX 代理源与 GD 兜底策略，兜不住的歌曲出错误卡片；
2. **酷狗「飙升榜」rankid `6666` 为二手来源未实测**，空榜时在 `data/kugou/KugouModule.kt` 里把该条 `enabled = false` 或换其他榜单；
3. **网易「新歌榜」`3779629` 同为二手来源**（同上）；
4. **榜单只有进程内内存缓存**（退出即失）——网易直链 20 分钟过期，刻意不落盘；歌单/收藏走 `LibraryRepository`（playlists.json）持久化；
5. **首页只取 Top50**，不是完整榜单。

---

## 七、合规声明

> LeLeMusic 是一款第三方音乐榜单聚合工具。所有榜单数据与音频资源均来自各平台公开页面，版权归原平台及权利人所有。本 App 不提供音频文件的下载、转存或分发服务，所有播放均实时指向原始来源。如您是相关权利人并认为本 App 侵犯了您的权益，请通过 [联系方式] 与我们联系，我们将在 24 小时内处理。
>
> 本项目仅供学习与技术交流使用，请勿用于商业用途。

---

## 八、项目结构

```
app/src/main/kotlin/com/lelemusic/
├── LeLeMusicApp.kt              Application：初始化 AppGraph + 后台启动探活
├── core/
│   ├── common/                  常量 / 错误码 / 格式化 / 调度器
│   ├── data/AppSettings.kt      SharedPreferences（开关 / 端点覆盖 / 播放偏好）
│   ├── net/HttpStack.kt         OkHttp + Referer 注入
│   └── di/AppGraph.kt           手工 DI 容器（平台模块注册表）
├── model/                       Song / Platform / ChartDef / PlaybackMode / Playable / Lyric
├── data/
│   ├── source/                  RankSource / PlayUrlResolver / ResolverEndpoints（音源地址注册表）
│   ├── remote/                  Retrofit 接口 + DTO
│   ├── netease/ kugou/ bilibili/ 各平台数据源与取链策略
│   ├── local/                   LocalScanner（SAF 导入）/ LocalRankSource（歌单挂载为榜单）
│   ├── plugin/                  LX-Music 插件代理源（api.txt，globalThis.lx 契约）
│   ├── probe/                   直链真实时长探测（MediaMetadataRetriever）
│   └── library/                 LibraryRepository（playlists.json 歌单持久化）
├── repo/                        榜单聚合 / 取链降级责任链 / 启动探活
├── player/                      PlaybackService / PlaybackController / 内存表
└── ui/
    ├── RootNav.kt               NavHost + 迷你播放条
    ├── chart/ library/ player/ lab/  榜单 / 我的歌单 / 播放（含歌词）/ 自检台
    └── common/                  SongRow / 骨架屏 / 错误态 / 自绘图标 PlayerGlyphs.kt

ios/                             SwiftUI 工程（XcodeGen，project.yml）
.github/workflows/release.yml    Build & Release（Artifacts on push，Releases on v* tag）
docs/                            PRD / 架构文档 / 接口调研
```

音频直链**永不落盘**：`MediaItem` 里的 URI 是占位地址 `lelemusic://<uid>`，
真实 URL 由 `ResolveDataSpecResolver` 在每次读流时实时解析——网易直链 20 分钟过期，预取后长期持有必然失效。

---

## 九、文档

- `docs/PRD.md` —— 产品需求
- `docs/ARCHITECTURE.md` —— 系统设计（含有序任务列表 T01~T05）
- `docs/api-feasibility.md` —— 平台接口可行性调研
- `api.txt` —— LX-Music 插件 API 契约
- `ios/README-INSTALL.md` —— iOS 侧载安装说明
