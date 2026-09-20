# LeleMusic iOS（MVP）

Kotlin + Compose 的 Android 端对应的 SwiftUI 重实现。代码在 Windows 上编写，
**编译与出包全部走 GitHub Actions 的 macOS runner**，产物为 **未签名 IPA**，
由你在 Windows 上用 Sideloadly 签名侧载到 iPhone。

## 首版范围（MVP 核心链路）
- 榜单：网易云（热歌/新歌）+ 酷狗（TOP500/飙升榜），下拉刷新
- 搜索：网易云 cloudsearch / 酷狗 song_search_v2
- 播放：AVPlayer + 取链降级责任链（网易 eapi→320→LX 代理→GD；酷狗 playinfo→LX）
- 我的歌单：新建/重命名/删除/加歌（文件 App 导入音频，自动拷贝进沙盒）/去重/侧滑删歌
- 歌词：LRC 解析滚动高亮、**点击歌词行跳播**、无词时**点击「暂无歌词」联网匹配**
  （网易云→酷狗，支持「歌名@歌手」文件名拆分）
- 锁屏控制 + 后台播放（`UIBackgroundModes: audio`）
- eapi 的 AES-ECB/MD5 用系统 CommonCrypto 实现，零第三方依赖

## 每次更新的出包流程
1. 提交 `ios/` 变更并 push 到 main → GitHub Actions「iOS Build (unsigned IPA)」自动构建
2. Actions 页面下载 Artifact `LeleMusic-unsigned-ipa`（30 天有效）
3. Windows 安装 Sideloadly（https://sideloadly.io，需先装 iTunes/iCloud 官方驱动或商店版 Apple Devices）
4. iPhone 连电脑 → Sideloadly 拖入 IPA → 输入 Apple ID → Start
5. iPhone 上 设置→通用→VPN与设备管理 → 信任你的 Apple ID 开发者证书

## 免费自签的限制（务必知道）
- 签名 **7 天过期**，过期后需重跑流程重签（数据不丢，只是打不开）
- 每台设备同时最多 **3 个自签 App**
- Sideloadly 会自动改 Bundle ID（加 `.xxxx` 后缀）绕开 App ID 冲突
- 升级付费开发者账号（¥688/年）后签名长期有效，流程不变

## 工程结构
```
ios/
  project.yml               # XcodeGen 工程定义（CI 用它生成 .xcodeproj）
  LeleMusic/
    App/                    # @main 入口
    Models/                 # Song/Playlist/ResolvedTrack（schema 对齐 Android）
    Core/                   # Http 栈 / URL 规范化 / CommonCrypto（eapi 加密）
    Data/                   # Kugou/Netease 服务 + ResolveEngine 降级链 +
                            # LyricLoader 联网匹配 + LibraryRepository(playlists.json)
    Player/                 # AVPlayer 队列控制器 + 锁屏控制
    UI/                     # 榜单/搜索/歌单/播放页（SwiftUI）
```

## 与 Android 端的对齐点
- `playlists.json` 字段与 Android 完全一致（platformId/platformSongId/...），
  两端歌单文件可互拷
- 端点常量与降级策略 ID（`netease.eapi`/`netease.320`/`kugou.playinfo`/`lx.wy.onrender`…）
  一一对应，`ResolverEndpoints` 覆盖表语义一致（UserDefaults 持久化）

## 本地生成 Xcode 工程（有 Mac 时）
```bash
brew install xcodegen
cd ios && xcodegen generate
open LeleMusic.xcodeproj
```
