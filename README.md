# LeLeMusic

聚合 **QQ音乐 / 网易云音乐 / 酷狗音乐** 三平台排行榜的 Android 音乐播放器。
三平台 Top50 平铺在首页，点歌即播；在线全曲取不到时自动降级为试听片段，并把「为什么只能试听」摆在明面上。

- 包名：`com.lelemusic`
- 技术栈：Kotlin 1.9.22 + Jetpack Compose + Media3 (ExoPlayer) + OkHttp/Retrofit + 手工 DI
- 最低系统：Android 8.0（API 26），目标 Android 14（API 34）

---

## 一、编译环境要求

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | `app/build.gradle.kts` 里 `sourceCompatibility = VERSION_17`，低版本会直接编译失败 |
| Android SDK | **34**（compileSdk / targetSdk） | 需在 SDK Manager 里装好 `Android SDK Platform 34` 与 `Android SDK Build-Tools` |
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

macOS / Linux：

```properties
sdk.dir=/Users/YourName/Library/Android/sdk
sdk.dir=/home/yourname/Android/Sdk
```

`local.properties` 已被 `.gitignore` 忽略，不会被提交。

### 编译与安装

```bash
./gradlew :app:assembleDebug          # 出 APK
./gradlew :app:installDebug           # 装到已连接的设备 / 模拟器
```

Windows 用 `gradlew.bat`。

---

## 二、真机验证清单（拿到项目后按这个顺序走）

### 第 0 步：装好 App，授予通知权限

首次启动会弹一次「通知」权限请求。
**必须允许**——不给这个权限，播放通知一条都不会出现，「通知栏可播放/暂停/切歌」这条就废了。

### 第 1 步：确认首页能出歌

装好打开，默认落在 **QQ音乐 · 热歌榜**。

- 顶部是平台 Tab（QQ音乐 / 网易云音乐 / 酷狗音乐），下面是榜单 Tab（热歌榜 / 新歌榜 等）。
- 列表应显示约 50 首歌，每行：排名 + 封面 + 歌名 + 「歌手 · 专辑」+ 时长。
- 平台上方的「更新于 …」行右侧有刷新按钮，可强制重新拉榜。

**如果某个平台的 Tab 名字旁边挂了红色「维护中」角标**，说明启动时探活发现该平台榜单接口不可用。
置灰只是提示，**仍然可以点**——点进去会真的再试一次。

**如果整页是骨架屏转圈不出内容**：先确认手机能上网，再切到别的平台看看是不是单平台挂掉。

### 第 2 步：点一首歌，确认能播

随便点一行 → 进入全屏播放器并开始播放。

- 顶部右侧有「来源: QQ音乐」胶囊；
- 中间是大封面，下面是歌名、歌手 · 专辑；
- 底部是进度条、上一首 / 播放暂停 / 下一首、播放模式（列表循环 / 单曲循环 / 随机播放）。

**返回榜单页时播放不会中断**，底部会出现迷你播放条（封面 + 歌名 + 播放暂停 + 下一首）。

**如果歌名下面出现橙色「试听片段」胶囊**，说明这首歌只拿到了试听片段，这是**降级成功**而不是 bug。

**如果中间出现红色错误卡片**（「该歌曲当前无法播放」），卡片上有两个出口：

- 「重试」：清掉失败记录，重新走一次取链；
- 「跳转到 XX音乐 收听」：用浏览器打开该平台的官方页面。

### 第 3 步：进音源自检台（**最关键的一步**）

**这是本项目存在的意义**——QQ 全曲到底通不通，只有真机跑一次自检台才能定论。

**两个入口，任选其一：**

1. 首页**右上角齿轮图标** → 直接进入；
2. 首页**顶部标题「LeLeMusic」连点 7 次**（2 秒内点满）→ 彩蛋入口。

进入后点 **「开始自检」**。它会：

1. 每个平台从默认榜单选 3 首歌做样本；
2. 对每首歌跑该平台的**全部**取链策略（忽略优先级与开关，一个都不落下）；
3. 对每条拿到的直链用 `MediaMetadataRetriever` 读**真实音频时长**，与榜单声明时长比对；
4. 逐行输出：平台 / 策略 / 结果 / 错误码 / 声明时长 / 实测时长 / **差值** / 判定 / 耗时 / URL。

跑完大概需要十几秒到一分钟（每条直链都要真下载一段音频来测时长），期间有进度条。

跑完后点右上角 **「复制日志」**，可以把整张表（TSV 格式，可直接粘进 Excel）复制到剪贴板。

### 第 4 步：怎么看结果——QQ 全曲通没通

**先看最上面的结论横幅**，它会直接写：

- `QQ 全曲：已打通` —— 横幅是**主色（绿）**底；
- `QQ 全曲：未打通（当前只能播试听片段）` —— 横幅是**红色**底。

下面的结果表每行右侧有一个判定胶囊：

| 判定 | 含义 | 判定依据 |
|---|---|---|
| **全曲**（绿） | 拿到了完整歌曲 | 实测时长 ≥ 声明时长 × 90% |
| **试听片段**（橙） | 只有片段 | 实测时长明显短于声明时长 |
| **失败**（红） | 取链就失败了 | 看该行的「错误码」 |
| **未探测**（灰） | 取链成功但读不到真实时长 | 开 `qq.probe` 重跑 |

**判定的具体读法**（假设某首歌榜单声明 320 秒）：

```
声明 320s  ·  实测 320s  ·  差值 0s     → 全曲 ✅
声明 320s  ·  实测 60s   ·  差值 -260s  → 试听片段 ⚠️
声明 320s  ·  实测 —     ·  差值 —      → 未探测
错误码：E_NO_SOURCE retcode=104009       → qq.full 被服务端拒绝 ❌
```

#### 情况 A：QQ 全曲已打通 🎉

说明 `qq.full`（CgiGetVkey）或 `qq.trial`（C100）至少有一条拿到了真全曲。

- 如果是 `qq.trial` 判的全曲，说明 **C100 地址本身就是全曲**。此时可以关掉 `qq.full`（省一次请求）和 `qq.probe`（省一次音频探测），QQ 平台直接闭环。
- 关法见第 5 步。

#### 情况 B：QQ 全曲未打通

按顺序做：

1. **看 `qq.full` 那几行的错误码。**
   - `retcode=104009`（通常在 `E_NO_SOURCE retcode=104009` 里）——CgiGetVkey 被服务端拒绝。
     这是本项目最大的未决问题：三篇独立教程都指向「缺 `Referer: https://y.qq.com/`」，
     代码里已经在 `HttpStack` 按 host 统一注入了 Referer，但**需要真机数据才能证伪**。
   - `E_TIMEOUT` / `E_NET` —— 网络问题，换个网络重跑。
2. **看 `qq.trial` 那几行的实测时长。**
   - 实测 ≈ 声明 → C100 就是全曲，只是 UI 上还标着「试听片段」（因为服务端没明说）。
     → 把 `qq.probe` 保持开启，UI 就会正确标成全曲。
   - 实测明显短（如 60s）→ C100 确实只有片段，QQ 平台在 MVP 阶段就只能听片段。
3. **按结论切开关**（第 5 步），切完**不用重启**，直接回榜单页再点一首歌验证。
4. 想要更保险：切完开关后**再跑一次自检**，确认新的结论横幅符合预期。

### 第 5 步：切开关降级（改完立即生效）

自检台中部的「运行期开关」区，改完**下一次取链立即生效，不需要重启 App**。

| 开关 | 默认 | 作用 | 什么时候动它 |
|---|---|---|---|
| `qq.full` | 开 | CgiGetVkey 取全曲 | 一直报 `retcode=104009` → **关掉**，让 QQ 直接走 `qq.trial` |
| `qq.probe` | 开 | 探测 C100 直链真实时长 | 已确认 C100 是全曲 → **关掉**，省一次音频往返 |
| `qq.full` / `qq.trial` / `netease.320` / `kugou.playinfo` | 开 | 单个取链策略的启用覆盖 | 某策略确定无用 → 关掉 |

覆盖过的策略右边会出现「重置」按钮，点了就回落到该策略的内置默认值。

> ⚠️ 自检台**故意忽略这些开关**：它要把每个策略都跑一遍，否则你永远看不到「关掉它之前它到底是什么错」。

### 第 6 步：三条异常路径（可选但建议跑一遍）

| 路径 | 操作 | 期望 |
|---|---|---|
| 断网 | 关掉 Wi-Fi 和流量 → 切榜单 / 点歌 | 显示错误卡片 + 重试按钮，**不崩溃** |
| 单平台挂掉 | 只连一个能通的网络，或等某个平台被风控 | 该平台 Tab 挂「维护中」角标，其他两平台照常 |
| 单曲不可播 | 找一首付费/版权受限的歌 | 出错误卡片 + 「跳转到 XX音乐 收听」，**不崩溃** |

---

## 三、已知限制

1. **QQ 全曲待真机验证。**
   `CgiGetVkey`（`qq.full`）在调研阶段实测返回 `retcode=104009 / msg="...invalidq;"` 且 `purl`/`vkey`/`sip` 全空。
   代码已按「带 Referer + 移动端 UA + 持久化 guid」实现，但**是否打通必须以真机自检台的实测时长为准**。
   降级路径（`qq.trial` C100 地址）已验证可用，最差情况 QQ 平台只能播试听片段。

2. **酷狗「网络红歌榜」的 `rankid` 未确认。**
   需求里要的「网络红歌榜」没找到确定的 rankid，当前用 **飙升榜 `6666`** 占位（二手来源，未实测）。
   真机跑一次如果是空榜，直接在 `data/source/ChartCatalog.kt` 里把该条 `enabled = false`，或换成同平台其他榜单。

3. **网易「新歌榜」`3779629` 为二手来源，未实测**（同 `ChartCatalog.kt`，处理方式同上）。

4. **无本地缓存。**
   榜单只有进程内内存缓存（退出 App 即失），歌曲、收藏、播放历史都不落盘。
   这是刻意的：网易直链 20 分钟过期，落盘只会带来一堆过期数据。

5. **MVP 不做独立榜单详情页。** Top50 直接平铺首页，切榜靠横向 Tab（PRD 5.2 已确认的取舍）。

6. **首页只取 Top50**，不是完整 Top100。

7. 旋转已通过 `android:screenOrientation="portrait"` 锁竖屏，横屏布局非 MVP 需求。

---

## 四、合规声明

> LeLeMusic 是一款第三方音乐榜单聚合工具。所有榜单数据与音频资源均来自各平台公开页面，版权归原平台及权利人所有。本 App 不提供音频文件的下载、转存或分发服务，所有播放均实时指向原始来源。如您是相关权利人并认为本 App 侵犯了您的权益，请通过 [联系方式] 与我们联系，我们将在 24 小时内处理。
>
> 本项目仅供学习与技术交流使用，请勿用于商业用途。

---

## 五、项目结构

```
app/src/main/kotlin/com/lelemusic/
├── LeLeMusicApp.kt              Application：初始化 AppGraph + 后台启动探活
├── core/
│   ├── common/                  常量 / 错误码 / 格式化 / 调度器
│   ├── data/AppSettings.kt      SharedPreferences（运行期开关）
│   ├── net/HttpStack.kt         OkHttp + Referer 注入
│   └── di/AppGraph.kt           手工 DI 容器
├── model/                       Song / Platform / ChartDef / PlaybackMode / Playable
├── data/
│   ├── source/                  RankSource / PlayUrlResolver / ChartCatalog
│   ├── remote/                  Retrofit 接口 + DTO
│   ├── qq/ netease/ kugou/      三平台数据源与取链策略
│   └── probe/UrlDurationProbe   直链真实时长探测（MediaMetadataRetriever）
├── repo/
│   ├── ChartRepository.kt       榜单聚合 + 故障隔离 + 内存缓存
│   ├── PlayUrlResolveUseCase.kt 取链降级责任链
│   └── SourceHealthRepository.kt 启动探活
├── player/                      PlaybackService / PlaybackController / 内存表
└── ui/
    ├── RootNav.kt               NavHost（chart / lab / player）+ 迷你播放条
    ├── chart/ player/ lab/      三个页面
    └── common/                  SongRow / 骨架屏 / 错误态 / 空态 / 自绘图标
```

音频直链**永不落盘**：`MediaItem` 里的 URI 是占位地址 `lelemusic://<uid>`，
真实 URL 由 `ResolveDataSpecResolver` 在每次读流时实时解析——网易直链 20 分钟过期，预取后长期持有必然失效。

图标说明：`material3` 只传递依赖 `material-icons-core`（约 50 个图标），
`Pause` / `SkipNext` / `Repeat` / `Shuffle` 等都在未声明的 `material-icons-extended` 里，
因此播放器这几个图标全部用 Canvas 自绘（见 `ui/common/PlayerGlyphs.kt`），零额外依赖。

---

## 六、文档

- `docs/PRD.md` —— 产品需求
- `docs/ARCHITECTURE.md` —— 系统设计（含有序任务列表 T01~T05）
- `docs/api-feasibility.md` —— 三平台接口可行性调研
