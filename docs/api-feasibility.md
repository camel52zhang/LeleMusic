# LeLeMusic 数据源可行性调研报告

> **验证时间**：2026-08-29 22:00–22:30 (UTC+8)
> **验证人**：高见远（架构师）
> **项目**：LeLeMusic — 聚合 QQ音乐巅峰榜 / 网易云音乐排行榜 / 酷狗榜单 的 Android 原生播放器
> **技术栈**：Kotlin + Jetpack Compose + Material 3 + Media3/ExoPlayer + Retrofit/OkHttp + Hilt + Coroutines/Flow

---

## 0. 验证方法与置信度说明

### 0.1 我实际做了什么

| 阶段 | 手段 | 结果 |
|------|------|------|
| 阶段一 | `Bash` + `curl` 直连 | ✅ 成功拿到 QQ音乐榜单真实 JSON。**但随后 Bash 工具失效**（所有命令返回空 stdout，含 `echo`），无法继续使用 |
| 阶段二 | `WebFetch` 逐接口请求 | ✅ 完成全部剩余验证。所有标注「亲自验证」的结论均来自真实 HTTP 响应原文 |
| 阶段三 | `WebSearch` | 用于补充「WebFetch 无法自定义 Header」场景下的二手依据，均已标注来源 |

**诚实声明**：
- 标注 **✅亲自验证** = 我拿到了真实响应体，下方贴的是原文片段
- 标注 **⚠️二手来源** = 附有来源链接，未亲自请求成功
- 标注 **❓待实测** = 无任何可靠依据，未编造
- **WebFetch 无法自定义请求 Header（Referer / User-Agent / Cookie）**。这是一个真实的验证盲区，凡依赖 Header 的接口我都显式标注了该限制
- **我没有验证任何音频 URL 是否真的能解码播放**（WebFetch 无法处理二进制流）。所有直链结论的置信度上限是「接口返回了结构合法的 URL」，不等于「ExoPlayer 一定能播」

### 0.2 结论置信度总览

| 平台 | 榜单接口 | 全曲直链 | 置信度 |
|------|---------|---------|--------|
| QQ音乐 | ✅ 可用 | ⚠️ 未打通，需真机验证 | 榜单：高 / 直链：低 |
| 网易云 | ✅ 可用 | ✅ 可用（80% 命中） | 高 |
| 酷狗 | ✅ 可用 | ✅ 可用（128kbps 确认） | 高 |

---

## A. 榜单数据获取

### A1. QQ音乐巅峰榜

#### A1.1 榜单总览接口 ✅亲自验证

```http
GET https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg
    ?g_tk=5381&format=json&inCharset=utf-8&outCharset=utf-8
    &notice=0&uin=0&needNewCode=1&platform=h5
```

- **必需 Header**：无需特殊 Header（WebFetch 无 Header 也通了）
- **响应**（真实截取）：

```json
{"code":0,"subcode":0,"message":"","default":0,"data":{"topList":[
  {"id":4,  "listenCount":7953220, "topTitle":"巅峰榜·流行指数","type":0,
   "picUrl":"http://y.gtimg.cn/music/photo_new/T003R300x300M000003d80lU3RCewY.jpg",
   "songList":[{"singername":"张碧晨/侯明昊","songname":"甲乙丙丁 (你我怎么两清) (Live)"}, ...]},
  {"id":26, "listenCount":19500000,"topTitle":"巅峰榜·热歌","type":0, ...},
  {"id":27, "listenCount":447749,  "topTitle":"巅峰榜·新歌","type":0, ...},
  {"id":62, "listenCount":18633712,"topTitle":"飙升榜","type":0, ...}
]}}
```

- **关键字段路径**：
  - 状态：`$.code`（0 = 成功）
  - 榜单数组：`$.data.topList[]`
  - 单榜：`id` / `topTitle` / `picUrl` / `listenCount`
  - ⚠️ **每榜只返回 3 首预览歌**（`songList[]`，且只有 songname/singername，**没有 songmid**）→ 不能用于播放，只能做榜单封面墙

#### A1.2 榜单详情接口（真正需要的） ✅亲自验证

```http
GET https://u.y.qq.com/cgi-bin/musicu.fcg
    ?g_tk=5381&loginUin=0&hostUin=0&format=json
    &inCharset=utf8&outCharset=utf-8&notice=0
    &platform=yqq.json&needNewCode=0
    &data=<URL-encoded JSON>
```

`data` 参数原文（编码前）：

```json
{"detail":{"module":"musicToplist.ToplistInfoServer","method":"GetDetail",
            "param":{"topid":26,"offset":0,"num":5}},
 "comm":{"ct":24,"cv":0,"g_tk":5381,"uin":0,"format":"json","platform":"yqq"}}
```

- **响应**（真实截取，热歌榜 topid=26）：

```json
{"code":0,"ts":1788010856143,"detail":{"code":0,"data":{"data":{
  "topId":26,"title":"热歌榜","titleDetail":"热歌榜 第241天",
  "period":"2026-08-29","updateTime":"2026-08-29",
  "totalNum":300,"listenNum":19500000,
  "song":[{"rank":1,"songId":8136,"title":"我不难过","singerName":"孙燕姿",
           "albumMid":"004VSvF52mQoQp",
           "cover":"https://y.gtimg.cn/music/photo_new/T002R300x300M000004VSvF52mQoQp_5.jpg"}],
  "songInfoList":[{
     "id":8136,"mid":"001fsNdn1zuZnA","name":"我不难过","title":"我不难过",
     "singer":[{"id":109,"mid":"001pWERg3vFgg8","name":"孙燕姿"}],
     "album":{"id":585,"mid":"004VSvF52mQoQp","name":"未完成","pmid":"004VSvF52mQoQp_5"},
     "interval":320,
     "file":{"media_mid":"000ao2Na47ZtsG",
             "size_128mp3":5127719,"size_320mp3":12819004,"size_flac":35801703,
             "size_96aac":3874974,"size_192aac":7699138,
             "size_try":960887,"try_begin":61174,"try_end":120801,
             "b_30s":0,"e_30s":60000},
     "pay":{"pay_month":1,"price_track":200,"price_album":0,
            "pay_play":1,"pay_down":1,"pay_status":0,"time_free":0}
  }]
}}}}
```

- **关键字段路径**（这是播放链路的命脉）：
  - 成功标志：`$.code == 0` 且 `$.detail.code == 0`
  - 歌曲数组：`$.detail.data.data.songInfoList[]`
  - **歌曲 mid**（vkey 用）：`songInfoList[].mid`
  - **媒体 mid**（拼 filename 用）：`songInfoList[].file.media_mid`
  - 时长（秒）：`songInfoList[].interval`
  - 专辑图：`songInfoList[].album.pmid` → 拼 `https://y.gtimg.cn/music/photo_new/T002R300x300M000{album.pmid}.jpg`
  - **付费墙判断**：`songInfoList[].pay.pay_play`（1=需付费）、`pay.time_free`（1=限时免费）
  - 试听区间：`file.try_begin` / `file.try_end`（毫秒）/ `file.size_try`

> **重要**：`songInfoList[].mid`（如 `001fsNdn1zuZnA`）与 `file.media_mid`（如 `000ao2Na47ZtsG`）**是两个不同的值**。老教程里混用会直接导致 vkey 失败。

#### A1.3 榜单 ID 常量表 ✅亲自验证（来自 A1.1 真实响应）

| ID | 榜单名 | 产品需求对应 |
|----|--------|-------------|
| **26** | 巅峰榜·热歌 | 热歌榜 |
| **27** | 巅峰榜·新歌 | 新歌榜 |
| **4** | 巅峰榜·流行指数 | 流行指数榜 |
| **62** | 飙升榜 | 飙升榜 |
| 28 | 巅峰榜·网络歌曲 | 备选 |
| 60 | 抖音热歌榜 | 备选 |
| 58 | 说唱榜 | 备选 |
| 57 | 电音榜 | 备选 |
| 78 | 国乐榜 | 备选 |
| 65 | 国风热歌榜 | 备选 |
| 63 | DJ舞曲榜 | 备选 |
| 64 | 综艺新歌榜 | 备选 |
| 73 | 游戏音乐榜 | 备选 |
| 72 | 动漫音乐榜 | 备选 |
| 29 | 巅峰榜·影视金曲 | 备选 |
| 5 / 3 / 59 / 61 / 16 / 17 | 内地 / 欧美 / 香港 / 台湾 / 韩国 / 日本 | 备选 |
| 36 | 巅峰榜·K歌金曲 | 备选 |
| 67 | 听歌识曲榜 | 备选 |
| 75 | 有声榜 | 非音乐，建议排除 |
| 201 | 巅峰榜·MV | 非音频，建议排除 |

#### A1.4 已失效接口 ❌亲自验证

```http
GET https://c.y.qq.com/v8/fcg-bin/fcg_toplist_cp.fcg?...&topid=4
→ 404 Not Found，空响应体
```

（老教程中广泛引用的 `fcg_toplist_cp.fcg` 已下线，**不要使用**）

---

### A2. 网易云音乐

#### A2.1 榜单接口 ✅亲自验证 —— **无需自建 Node 服务**

```http
GET https://music.163.com/api/playlist/detail?id=3778678
```

- **无加密、无 weapi、无 eapi、无 Cookie**（这是关键发现）
- **响应**（真实截取）：

```json
{"result":{
  "tracks":[{
     "id":3399839173,"name":"甲乙丙丁 (你我怎么两清)",
     "artists":[{"id":...,"name":"李佳薇"}],
     "album":{"name":"甲乙丙丁","picUrl":"..."},
     "duration":210461,
     "fee":8
  }]
}}
```

- **关键字段路径**：
  - 榜单数组：`$.result.tracks[]`
  - 歌曲 id：`tracks[].id`
  - 歌名：`tracks[].name`
  - 歌手：`tracks[].artists[].name`
  - 专辑图：`tracks[].album.picUrl`
  - 时长（**毫秒**）：`tracks[].duration`
  - **付费标记**：`tracks[].fee`（见下方编码表）
- ⚠️ `result.name`（榜单名）字段在实测响应中被截断未取到，**建议榜单名由 App 本地常量表硬编码**，不依赖接口

**fee 字段编码含义** ⚠️二手来源 + 实测反推

| fee | 含义 | 能否拿直链（实测） |
|-----|------|------------------|
| 0 | 免费 | ✅ 能（33894312 实测通过） |
| 8 | VIP 专享 / 会员高音质 | ✅ **能**（4 首实测全部通过，见 B2） |
| 1 | 付费单曲 / 数字专辑 | ❌ 不能（返回 `code:-110`） |
| 4 | 付费专辑 | ❓待实测（推测同 1） |

> 实测来源：本报告 B2 节，热歌榜前 5 首批量查询，fee=8 的 4 首全部返回有效 320kbps 直链，唯一 fee=1 的返回 null。

#### A2.2 榜单 ID 常量表 ⚠️二手来源（与 NeteaseCloudMusicApi 官方常量一致，尚未逐个实测）

| ID | 榜单名 | 产品需求对应 |
|----|--------|-------------|
| 3778678 | 云音乐热歌榜 | 热歌榜 ✅已实测 |
| 3779629 | 云音乐新歌榜 | 新歌榜 |
| 19723756 | 云音乐飙升榜 | 飙升榜 ✅已实测 |
| 2884035 | 网易原创歌曲榜 | 原创榜 |

> 来源：Binaryify/NeteaseCloudMusicApi 项目 `/top/list` 接口文档中 `id` 参数说明表（榜单 ID 为网易云长期稳定常量，多年未变）。
> 已实测的两个（3778678、19723756）均返回真实数据，推断其余两个同样可用，**但建议在 T01 基建任务中补测**。

#### A2.3 关于 NeteaseCloudMusicApi（Binaryify）是否必须自建

**结论：本项目不需要自建。**

依据（亲自验证）：
- `music.163.com/api/playlist/detail` — 明文 GET，直接可用
- `music.163.com/api/song/enhance/player/url` — 明文 GET，直接可用（见 B2）

NeteaseCloudMusicApi 的 weapi/eapi 加密是为**新版接口**（`/weapi/...`、`/eapi/...`）设计的。本项目用的 `/api/...` 老接口是网易云 Web 端历史遗留端点，**不校验加密参数**。

---

### A3. 酷狗音乐

#### A3.1 榜单详情接口 ✅亲自验证

```http
GET https://m.kugou.com/rank/info/?rankid=8888&page=1&json=true
```

- **响应**（真实截取）：

```json
{"info":{"rankid":8888,"rankname":"TOP500","ranktype":2,
         "rank_id_publish_date":"2026-08-29 08:30:00",
         "total":500,"intro":"数据来源：全曲库歌曲\r\n排序方式：按歌曲喜爱用户的总量排序\r\n更新频率：每天"},
 "songs":{"total":500,"page":1,"pagesize":30,"timestamp":1788003601,
   "list":[{
      "hash":"9B181325FAF26A3C93D0ECC3D471F8E5",
      "filename":"知更鸟、HOYO-MiX、Chevy - 唯有追赶风的方向 (Only By Chasing the Wind)",
      "songname":"唯有追赶风的方向 (Only By Chasing the Wind)",
      "singername":"知更鸟、HOYO-MiX、Chevy",
      "320hash":"...","sqhash":"..."
   }, ...]}}
```

- **关键字段路径**：
  - 榜单元信息：`$.info`（`rankname` / `rank_id_publish_date`）
  - 歌曲数组：`$.songs.list[]`
  - **分页**：`$.songs.total`（该榜总数）、`page` / `pagesize` 参数
  - **播放 hash**：`songs.list[].hash`（128k）、`.320hash`（320k）、`.sqhash`（无损）
  - 歌名：`songname`；歌手：`singername`
  - ⚠️ **无专辑图字段**，需用 `hash` 再调 `getSongInfo` 拿 `album_img`

#### A3.2 榜单 ID 常量表 ⚠️二手来源（已实测 8888 可用，其余待补测）

| rankid | 榜单名 | 产品需求对应 |
|--------|--------|-------------|
| 8888 | 酷狗TOP500 | TOP500 ✅已实测 |
| 6666 | 酷狗飙升榜 | 飙升榜 |
| 52144 | 抖音热歌榜 | 备选 |
| 52767 | 快手热歌榜 | 备选 |
| 24971 | DJ热歌榜 | 备选 |
| 31308 | 内地榜 | 备选 |
| 59703 | 蜂鸟流行音乐榜 | 备选 |

> 来源：[CSDN - 酷狗音乐榜单存储本地](https://blog.csdn.net/weixin_59748320/article/details/147009902)（rank_options 常量表）
> + [GitHub BrockChen/-Api 酷狗音乐web端数据接口](https://github.com/BrockChen/-Api/blob/master/%E9%85%B7%E7%8B%97%E9%9F%B3%E4%B9%90web%E7%AB%AF%E6%8E%A5%E5%8F%A3.md)
> ⚠️ 需求中的「**网络红歌榜**」**未找到可靠 rankid**，标 ❓待实测。建议 T01 阶段调 `rank/list` 接口枚举补齐。

#### A3.3 已失效/受限接口 ❌亲自验证

```http
GET https://m.kugou.com/plist/rank/home?json=true
→ "Access Deny ! No Actions !" （反爬拦截）
```

榜单列表枚举接口 `m.kugou.com/rank/list?json=true` ⚠️二手来源（来源同上 GitHub 文档），未亲自验证。

---

## B. 全曲音源直链（最关键环节）

### B1. QQ音乐 —— ⚠️ 未打通，本项目最大技术风险

#### B1.1 老方案实测结果：全部失败

**方案一：`fcg_music_express_mobile3.fcg`（songmid → vkey）** ❌亲自验证

```http
GET https://c.y.qq.com/base/fcgi-bin/fcg_music_express_mobile3.fcg
    ?g_tk=5381&format=json&platform=yqq&needNewCode=0
    &cid=205361747&uin=0
    &songmid=001fsNdn1zuZnA
    &filename=M500001fsNdn1zuZnA.mp3
    &guid=1234567890
```

真实响应：
```json
{"code":104003,"cid":205361747,"errinfo":"merror",
 "data":{"items":[{"songmid":"","filename":"","vkey":""}]}}
```
→ `code=104003 / errinfo=merror`，**vkey 为空**

**方案二：`musicu.fcg` + `CgiGetVkey` 模块** ❌亲自验证

```json
// data 参数（编码前）
{"req_0":{"module":"vkey.GetVkeyServer","method":"CgiGetVkey",
          "param":{"guid":"1234567890","songmid":["001fsNdn1zuZnA"],
                   "songtype":[0],"uin":"0","loginflag":1,"platform":"20"}},
 "comm":{"uin":0,"format":"json","ct":24,"cv":0}}
```

真实响应：
```json
{"code":0,"req_0":{"code":1000,"data":{
  "retcode":104009,"msg":"103.254.78.180;invalidq;",
  "sip":[],
  "midurlinfo":[{"songmid":"001fsNdn1zuZnA","filename":"C400000ao2Na47ZtsG.m4a",
                 "purl":"","vkey":"","opi30surl":"","ekey":""}]}}}
```

**关键观察**：
1. 服务端**识别了歌曲**（返回了正确的 `filename: C400000ao2Na47ZtsG.m4a`，注意用的是 `media_mid` 而非 `songmid`）→ 请求格式正确
2. 但 `purl` / `vkey` / `sip` **全部为空**
3. `retcode=104009`，`msg` 含 `invalidq`
4. **对照实验**：换成明确免费的歌曲（`mid=000M3Yxt2tIuHZ`，`pay.pay_play=0`、`time_free=1`）重测 → **同样返回 `104009 invalidq`**（IP 变成 `111.225.237.168`，说明每次请求出口 IP 不同）

> 因为付费歌和免费歌返回完全相同的错误，**排除「付费墙」解释**，更可能是鉴权/风控层面的拦截。

#### B1.2 失败原因分析 ⚠️二手来源

`WebSearch` 检索到的三篇独立教程**全部明确要求设置 `Referer` 请求头**：

| 来源 | 关键描述 |
|------|---------|
| [Vue-music- (GitMemories)](https://gitmemories.com/helloforrestworld/Vue-music-) | `axios.post(url, req.body, {headers: {referer: 'https://y.qq.com/', origin: 'https://y.qq.com', 'Content-type': 'application/x-www-form-urlencoded'}})` |
| [QQ音乐MV下载技术解析](https://www.hqwc.cn/a/1093981.html) | 「请求头(Headers)：需要包含 `Referer: https://y.qq.com/`，以及常见的 User-Agent」 |
| [深度解析QQ音乐API逆向工程 (CSDN)](https://blog.csdn.net/gitblog_00505/article/details/161654198) | 「QQ音乐API对请求头有严格的验证机制，包括 Referer、Cookie、User-Agent 等字段」 |

**我的判断**：`invalidq`（invalid query）大概率是**服务端校验 Referer/Origin 失败**，而非接口下线。

**但这是 WebFetch 的验证盲区** —— 我无法设置 Header，所以**不能证伪「接口已死」**。这是本项目需要在真机上优先验证的第一件事。

#### B1.3 唯一打通的播放地址 ✅亲自验证（但疑似试听片段）

```http
GET https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg
    ?songmid=001fsNdn1zuZnA&platform=yqq&format=json
```

真实响应（关键部分）：
```json
{"code":0,
 "data":[{"mid":"001fsNdn1zuZnA","name":"我不难过",
           "pay":{"pay_play":1,"pay_down":1,"price_track":200,"time_free":0},
           "file":{"media_mid":"000ao2Na47ZtsG","size_try":960887,
                   "try_begin":61174,"try_end":120801,
                   "size_128mp3":5127719,"size_320mp3":12819004,"size_flac":35801703}}],
 "url":{"8136":"ws.stream.qqmusic.qq.com/C100001fsNdn1zuZnA.m4a?fromtag=38"}}
```

**关键发现**：
- 返回一个**无 vkey** 的地址：`ws.stream.qqmusic.qq.com/C100001fsNdn1zuZnA.m4a?fromtag=38`
- **付费歌**（pay_play=1）和**免费歌**（time_free=1）**返回的都是 `C100` 前缀，格式完全一致**
- QQ音乐 filename 前缀约定（⚠️二手来源：[scode.org.cn QQ音乐API的使用](https://scode.org.cn/article.html?id=18)、[CSDN 获取QQ音乐歌曲播放源地址](https://blog.csdn.net/cnhongbinli/article/details/82121348)）：

| 前缀 | 格式 | 含义 |
|------|------|------|
| `C100` | m4a | **疑似试听片段**（128k 以下） |
| `C400` | m4a | 标准 128kbps（教程中最常用） |
| `M500` | mp3 | 128kbps |
| `M800` | mp3 | 320kbps |
| `F000` | flac | 无损 |

- **我的推断**：`C100` + `?fromtag=38` + 无 vkey + 不区分付费状态 → **极可能是 30 秒试听片段，不是全曲**
- ❓**待实测**：我没有验证该 URL 的实际音频时长。这是第二件需要真机验证的事（下载后用 `MediaMetadataRetriever` 读 duration，对比 `interval` 字段）

#### B1.4 QQ音乐全曲直链结论

| 路径 | 状态 | 说明 |
|------|------|------|
| `fcg_music_express_mobile3.fcg` | ❌ 亲自验证失败 | `104003 merror` |
| `CgiGetVkey`（musicu.fcg） | ❌ 亲自验证失败 | `104009 invalidq`，疑似缺 Referer |
| `fcg_play_single_song.fcg` | ⚠️ 通但疑似试听 | 返回 C100 地址，付费/免费无差别 |
| **可行性评级** | **⚠️ 部分可用（降级为试听）** | 全曲需真机补测 Referer 方案 |

#### B1.5 QQ音乐降级路径（建议）

1. **首选降级**：用 `fcg_play_single_song.fcg` 的 C100 地址播 **30 秒试听**，UI 明确标注「试听片段」
2. **次选降级**：深链跳转 QQ音乐 App / `https://y.qq.com/n/ryqq/song/{mid}`
3. **付费墙预判**：用 `songInfoList[].pay.pay_play` 提前灰显，避免用户点了才失败

---

### B2. 网易云音乐 —— ✅ 可用（本项目最稳的音源）

#### B2.1 直链接口 ✅亲自验证

```http
GET https://music.163.com/api/song/enhance/player/url?ids=[...]&br=320000
```

- **无加密、无 Cookie、支持批量**（一次最多可传多个 id）
- 支持指定码率：`br=128000` / `192000` / `320000`

**实测一：混合付费状态批量查询**（ids = `447926067, 405998841, 33894312, 347230`）

真实响应（关键字段）：
```json
{"data":[
 {"id":33894312,
  "url":"http://m10.music.126.net/20260829220905/53db784f899e1611dc2e18d16c599132/ymusic/0fd6/4f65/43ed/a8772889f38dfcb91c04da915b301617.mp3?vuutv=QhzH8U9mOKT2W99FmURujA0eyH+aqVOXokOC2kuSE2RwLROtoMkuYMMN8mYXHLwJVYfhmnDQkDC/hcoZgXdsOmWuirKH+ChEhxmk7nHb8uuSxev6m1l09izGUMhuZWbD76hrOOCIMOLEOGhVD4aCi4B4R9SGV0INJlEjCP5pleRiLWlG5JzfV3tzFr1X4txOV48bOqXQVB3BHR9zjpe7i5mzATSSQvxheljAIkpdjUD1lBKeiEqZ3wMpTGL62HlOUglN8mnFaMJr3J3uVS1WUpdOHdK7C+Vuq0mmQ6bB1XUslYIYeqb7qBZCMkHg0m9vNqh3koOtk5j70Ve43z4/aiPVbtk6fKgO//dnaJx59UU=&cdntag=bWFyaz1vc193ZWIscXVhbGl0eV9leGhpZ2g",
  "br":320000,"size":10691439,"type":"mp3","code":200,
  "fee":0,"level":"exhigh","encodeType":"mp3","expi":1200},
 {"id":447926067,"url":null,"code":-110,"fee":1,"level":null},
 {"id":405998841,"url":null,"code":-110,"fee":1,"level":null},
 {"id":347230,   "url":null,"code":-110,"fee":1,"level":null}
],"code":200}
```

**实测二：真实热歌榜前 5 首批量查询**（ids = 热歌榜 3778678 的前 5 首）

| # | id | 歌名 | fee | url | code | level | br |
|---|-----|------|-----|-----|------|-------|-----|
| 1 | 3399839173 | 甲乙丙丁 (你我怎么两清) | 8 | ✅ 有效 | 200 | exhigh | 320000 |
| 2 | 1973665667 | 海屿你 | 8 | ✅ 有效 | 200 | exhigh | 320000 |
| 3 | 1851652156 | 忘不掉的你 | 8 | ✅ 有效 | 200 | exhigh | 320000 |
| 4 | 2108845007 | 讴歌 | 8 | ✅ 有效 | 200 | exhigh | 320000 |
| 5 | 2097443876 | 碎碎念 | 1 | ❌ null | -110 | null | 0 |

**命中率：4/5 = 80%**

#### B2.2 关键字段语义 ✅亲自验证

| 字段 | 含义 |
|------|------|
| `code`（顶层） | 200 = 接口调用成功（**注意：不代表这首歌能播**） |
| `data[].code` | **200 = 拿到直链；-110 = 拿不到（版权/付费限制）** |
| `data[].url` | 直链；`null` = 无音源 |
| `data[].fee` | 0=免费 ✅ / 8=VIP ✅实测可播 / 1=付费 ❌ |
| `data[].level` | `standard`(128k) / `exhigh`(320k) / `lossless`(flac) |
| `data[].br` | 实际码率（我传 320000 就返回 320000） |
| `data[].expi` | **有效期 1200 秒（20 分钟）** |
| `data[].freeTrialInfo` | 试听信息；实测中未出现非 null 值 |
| `data[].freeTimeTrialPrivilege` | 限时试听特权 |

**`freeTrialInfo` 的含义**（⚠️二手来源，来源：NeteaseCloudMusicApi 文档）：当账号为未登录/低等级时，VIP 歌曲会返回该字段，表示只能试听片段。
→ **本项目实测中 `freeTrialInfo` 全为 null 且仍拿到 320k 全曲**，说明当前匿名调用未触发试听降级。**这是有利的，但也说明该行为不稳定，随时可能收紧。**

#### B2.3 工程注意事项 ⚠️

1. **URL 是 `http://` 明文**！Android 9 (API 28)+ 默认禁止 cleartext traffic。
   → 必须二选一：配置 `network_security_config.xml` 允许 `music.126.net` 明文；**或**把 URL 改写成 `https://` 再交给 ExoPlayer（推荐，实测域名支持 https）
2. **URL 20 分钟过期**（`expi:1200`）
   → 必须「播放前实时取链」，**绝对不能缓存 URL 到数据库**。在 Media3 的 `MediaSource.Factory` 里做懒解析，或播放前调一次接口
3. **必须处理 -110**：约 20% 歌曲拿不到，UI 要提前用 `fee` 字段灰显，而不是等播放失败

#### B2.4 降级路径

1. 用 `tracks[].fee` 预判：`fee==1` 直接标记为「不可播放」
2. `-110` 降码率重试（320000 → 128000），有时低码率有音源
3. 最终降级：跳转 `https://music.163.com/#/song?id={id}`

---

### B3. 酷狗音乐 —— ✅ 可用（全曲，128kbps 已确认）

#### B3.1 已失效方案 ❌亲自验证

```http
GET https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=8AC4F90B1E3DF716D216D74223016E17
→ {"data":[],"status":0,"err_code":20010}

GET https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=...&album_id=2606360
    &dfid=1nDkHI3pWJTL2HEcFD2ZQqVN&mid=1nDkHI3pWJTL2HEcFD2ZQqVN&platid=4&appid=1014
→ {"data":{"SSA-HMID":"...","is_publish":1,"privilege":0},"status":0,"err_code":30020}
```

→ 需求中提到的 `/yy/index.php?r=play/getdata` **已废弃**（err_code 20010 / 30020）。

#### B3.2 有效方案 ✅亲自验证

```http
GET https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash={hash}
```

真实响应（关键部分，周杰伦《千山万水》，hash 来自 A3.1 榜单/songsearch 实测）：

```json
{"status":1,"errcode":0,
 "hash":"8AC4F90B1E3DF716D216D74223016E17",
 "songName":"千山万水","singerName":"周杰伦",
 "fileName":"周杰伦 - 千山万水",
 "url":"https://sharefs.kugou.com/202608292153/e175516686fe7ce3310adfef3f2a6d60/v3/8ac4f90b1e3df716d216d74223016e17/yp/full/ap1000_us0_pi409_s3522266565.mp3",
 "backup_url":["https://sharefs.tx.kugou.com/202608292153/9f8aa78762ef394398f404d1deb14564/v3/8ac4f90b1e3df716d216d74223016e17/yp/full/ap1000_us0_pi409_s3522266565.mp3"],
 "timeLength":247,"bitRate":128,"fileSize":3964978,"extName":"mp3",
 "album_img":"http://imge.kugou.com/stdmusic/{size}/20250125/20250125121629695890.jpg",
 "privilege":0,"pay_type":0,
 "128privilege":0,"320privilege":0,"sqprivilege":0,
 "extra":{"128hash":"8AC4F90B1E3DF716D216D74223016E17",
          "320hash":"5BFD0161C4BE57FA4C355B76386233EF",
          "sqhash":"6C5C7B66CEBA518DA71FDA7EA2E4559A",
          "128filesize":3964978,"320filesize":9912120,"sqfilesize":32865886},
 "climax_info":{"timelength":"34000","start_time":"73400","end_time":"107400"}}
```

**关键判断依据**：
- `url` 路径中包含 **`/yp/full/`** → `full` 表示**完整全曲**（酷狗试听链接路径为 `/yp/cut/` 或带 `climax` 段）
- `timeLength: 247` 秒，与榜单/搜索返回的 `Duration: 247` **完全一致** → 确认是完整时长，非片段
- `privilege:0` / `pay_type:0` → 无版权限制
- 提供 `backup_url` 备用域名 → 主域名挂了可自动切换

#### B3.3 码率问题 ⚠️实测发现

- 实测返回 `bitRate: 128`（128kbps mp3）✅ 可用
- **尝试传 320 的 hash 无效**：用 `320hash`（`5BFD0161C4BE57FA4C355B76386233EF`）请求，服务端仍返回 `bitRate:128` 且 url 仍是 128 版的 hash
  → 说明 `getSongInfo.php` 默认只出 128k，**320k/无损的取法需另行研究**，标 ❓待实测
- `extra` 里提供了 `320hash` / `sqhash`，说明高码率资源**存在**，只是当前接口路径没走通

#### B3.4 降级路径

1. 用 `climax_info`（高潮片段，实测 `timelength:34000` = 34 秒）作为试听降级
2. `backup_url` 备用域名自动切换
3. `privilege != 0` 时提前灰显

---

## C. 工程与法律风险

### C1. Android 端直连是否可行

**结论：可行，Android 比浏览器宽松得多。**

| 关注点 | 结论 | 说明 |
|--------|------|------|
| **CORS** | ✅ 无影响 | CORS 是**浏览器**的同源策略，OkHttp 是原生 HTTP 客户端，**完全不受 CORS 约束**。这是本项目最大的环境优势 |
| **Referer 校验** | ✅ 可满足 | 所有平台的服务端 Referer/Origin 校验，OkHttp 都能用 `Interceptor` 或 `@Header` 任意伪造。这正是 WebFetch 做不到而 Android 能做到的关键差异点 |
| **User-Agent 伪装** | ✅ 可满足 | OkHttp 默认 UA 需改，否则一眼被识别为爬虫 |
| **明文 HTTP** | ⚠️ 需处理 | 网易云直链返回 `http://`，Android 9+ 默认禁 cleartext。见 C4 |
| **TLS / 证书固定** | ✅ 未发现 | 三个平台均未启用证书固定，无 MITM 检测 |
| **频率限制** | ⚠️ 真实存在 | 酷狗 `plist/rank/home` 已返回 "Access Deny"。需做限流 + 缓存 |

### C2. 加密/签名在 Kotlin 端的实现难度

| 平台 | 是否需加密 | Kotlin 实现难度 |
|------|-----------|----------------|
| **QQ音乐** | **不需要**。`g_tk` 对匿名请求（`uin=0`）可硬编码 `5381` | ⭐ 无难度 |
| **网易云** | **不需要**（走 `/api/...` 老接口）。若要用新版 `/weapi` 才需要 AES+RSA 双层加密 | ⭐ 无难度（老接口）/ ⭐⭐⭐⭐ 高（weapi） |
| **酷狗** | **不需要**。hash 直接从榜单接口拿 | ⭐ 无难度 |

**重大利好**：由于本项目三平台**全部走免加密的老接口**，Kotlin 端**不需要实现任何逆向加密算法**。
→ 原本预估的「网易云 weapi 加密移植到 Kotlin」这个高风险项（需移植 AES-CBC + RSA + 随机数填充，**约 300 行 Kotlin**，且随官方更新而失效）**可以直接砍掉**。

### C3. 是否需要自建 Node 中转服务

**结论：一期不需要自建；建议架构预留抽象层，二期按需引入。**

| 维度 | Android 端直连 | 自建 NeteaseCloudMusicApi |
|------|---------------|--------------------------|
| 交付速度 | ✅ 快，无运维 | ❌ 需服务器 + 域名 + 部署 + 监控 |
| 单点故障 | ✅ 无（用户各自直连） | ❌ 服务器挂 = 全量用户挂 |
| 接口变更响应 | ❌ 需发版（Google Play 审核） | ✅ 改服务端即时生效 |
| 绕过 IP 风控 | ❌ 用户 IP 被封就没辙 | ✅ 服务端 IP 固定，可加代理池 |
| 加密复杂度 | ✅ 无需（老接口） | ✅ 库已实现 |
| 合规暴露面 | 分散 | ❌ 集中，风险更高 |
| **成本** | ✅ 0 | ❌ 服务器 + 时间 |

**建议**：
- **一期**（MVP）：纯 Android 端直连。三平台老接口均免加密，实测可用，没有理由引入服务端成本
- **二期**：若出现以下信号，再引入中转 —— (a) QQ音乐 Referer 方案在真机也失败；(b) 匿名调用被大规模限流；(c) 需要突破 VIP 音源
- **架构预留**：数据层用 Repository 接口 + `DataSource` 抽象（Remote/Local/Proxy 三种实现可替换），届时只需新增一个 `ProxyDataSource`，不改业务代码

### C4. 必须处理的 Android 工程约束

1. **cleartext traffic**：网易云直链是 `http://`。
   - 推荐：URL 字符串替换为 `https://` 前缀后交给 ExoPlayer
   - 兜底：`res/xml/network_security_config.xml` 中对 `music.126.net` 开 `cleartextTrafficPermitted="true"`
2. **直链 20 分钟过期**：网易云 `expi=1200`。
   - 禁止把 `url` 持久化到 Room
   - 在 Media3 的 `MediaSource.Factory` 或播放前的 ViewModel 里实时解析
3. **UA 伪装**：OkHttp 全局 Interceptor 统一注入移动端 UA
4. **限流与缓存**：榜单数据用 Room 缓存 + `Cache-Control`，避免重复请求触发风控
5. **并发取链**：网易云支持批量 `ids=[...]`，进播放列表时应批量取而非逐首

### C5. 合规提示

> ⚠️ **本项目仅供个人学习与技术研习使用，不得用于任何商业用途，不得分发，不得用于规避正版会员付费机制。**
> 上述接口均为各平台非公开/历史遗留端点，其可用性、稳定性与合法性均无保障，随时可能变更或关停。App 内应保留数据来源署名，并在必要时引导用户前往官方正版平台。

---

## D. 结论与建议

### D1. 三平台可行性总表

| 平台 | 榜单接口 | 榜单评级 | 全曲直链 | 直链评级 | 主要依据 |
|------|---------|---------|---------|---------|---------|
| **QQ音乐** | `c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg`<br>+ `u.y.qq.com/cgi-bin/musicu.fcg` (GetDetail) | ✅ **可用**<br>（26 个榜单，含 songmid + 付费标记） | `fcg_play_single_song.fcg` 给 C100<br>`CgiGetVkey` 返回 `104009 invalidq` | ⚠️ **部分可用**<br>（仅试听） | 榜单真实响应 ✅<br>vkey 实测失败，疑似缺 Referer |
| **网易云** | `music.163.com/api/playlist/detail?id=` | ✅ **可用**<br>（免加密） | `music.163.com/api/song/enhance/player/url` | ✅ **可用**<br>（320kbps，80% 命中） | 实测 4/5 拿到有效 320k 直链 |
| **酷狗** | `m.kugou.com/rank/info/?rankid=&json=true` | ✅ **可用**<br>（含 hash/320hash/sqhash） | `m.kugou.com/app/i/getSongInfo.php?cmd=playInfo` | ✅ **可用**<br>（128kbps 全曲已确认） | url 含 `/yp/full/`，时长与 Duration 一致 |

### D2. 推荐方案：**Android 端直连（一期）**

**一句话**：三平台**全部走免加密的公开老接口，Android 端 OkHttp 直连，一期不建中转服务；QQ音乐按「试听降级」处理，其全曲方案列入真机验证清单。**

**选择理由**：
1. 三平台均**无需签名/加密** → Kotlin 端零逆向成本，原本预估的最大工作量消失
2. Android 无 CORS，且可自由伪造 Referer/UA → 恰好补上 WebFetch 验证失败的短板
3. 网易云 + 酷狗已实测拿到**全曲直链** → 两个平台的核心链路已闭环，占需求的 2/3
4. 无服务器成本、无单点故障

**不选中转服务的理由**：唯一需要它的理由（网易云 weapi 加密）在本项目中**不成立**，因为老接口不需要加密。

### D3. 对架构的影响（给后续设计轮的输入）

1. **数据源抽象层是刚需**：三平台字段结构差异极大（QQ 用 `mid`/`media_mid` 双 ID，网易云用数字 `id`，酷狗用 `hash`），必须定义统一领域模型 + 每平台一个 Mapper
2. **两阶段取链模型**：
   - 网易云/酷狗：榜单直接带播放凭证（id / hash），播放时再换 URL → **两跳**
   - QQ音乐：榜单带 `mid` → `fcg_play_single_song` → URL → **两跳但结果降级**
   - 统一抽象为 `RankSource` + `PlayUrlResolver` 两个接口
3. **播放 URL 必须懒解析 + 短时效**：ExoPlayer 的 `MediaSource` 需在每次播放前实时解析，不能预取后长期持有（网易云 20 分钟过期）
4. **失败与降级是一等公民**：网易云约 20% 拿不到、QQ音乐全曲待定 → 数据模型需有 `PlayableStatus`（AVAILABLE / TRIAL_ONLY / UNAVAILABLE）三态，UI 按态渲染
5. **需一个「接口健康度自检」机制**：这类接口随时失效，建议 App 启动时轻量探活，失败时该平台 Tab 显示「数据源维护中」而非白屏

### D4. 最大技术风险点

> 🔴 **QQ音乐全曲直链未打通（本项目第一风险）**
>
> - 老 vkey 方案（`fcg_music_express_mobile3.fcg`）实测 `104003 merror`，已失效
> - 新方案（`CgiGetVkey`）实测 `104009 invalidq`，付费歌与免费歌表现一致，**排除付费墙原因**
> - 三篇独立教程指向「缺 `Referer: https://y.qq.com/`」，但 **WebFetch 无法设置 Header，我无法验证该假设**
> - 若真机补测仍失败 → QQ音乐只能播 30 秒试听，**需求「在线播放完整歌曲」在 QQ 平台不成立**
>
> **缓解措施（按优先级）**：
> 1. T01 基建任务中，用 OkHttp 带 `Referer: https://y.qq.com/` + 移动端 UA 实测 `CgiGetVkey`（**验证优先级最高**）
> 2. 并行验证 `fcg_play_single_song.fcg` 返回的 C100 URL 的真实时长（下载后用 `MediaMetadataRetriever` 读 duration，与 `interval` 对比）—— 若 C100 其实是全曲，则风险直接解除
> 3. 若两者皆失败：QQ音乐 Tab 降级为「试听 + 跳转官方」，并在 PRD 中明确标注该平台限制
> 4. 兜底：评估引入第三方音源匹配（如按 歌名+歌手 去酷狗/网易云搜同名曲播放）—— 跨源兜底，但匹配准确率是新的不确定性

**次要风险**：
- 🟠 接口时效：全部为非公开端点，无任何 SLA，随时失效
- 🟠 网易云 20% 不可播 + `http://` 明文 + 20 分钟过期，工程上需仔细处理
- 🟡 酷狗「网络红歌榜」rankid 未找到；酷狗 320kbps 取法未通
- 🟡 酷狗 `plist/rank/home` 已反爬，说明酷狗风控在收紧

---

## 附录：证据清单

### 亲自验证成功（✅，响应原文已在正文引用）

| # | 接口 | 结果 |
|---|------|------|
| 1 | `c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg` | `code:0`，26 个榜单 |
| 2 | `u.y.qq.com/cgi-bin/musicu.fcg` (GetDetail, topid=26) | `code:0`，完整 songInfoList + pay 标记 |
| 3 | `c.y.qq.com/v8/fcg-bin/fcg_toplist_cp.fcg` | ❌ 404 已失效 |
| 4 | `c.y.qq.com/base/fcgi-bin/fcg_music_express_mobile3.fcg` | ❌ `104003 merror` |
| 5 | `u.y.qq.com/cgi-bin/musicu.fcg` (CgiGetVkey, 付费歌) | ❌ `104009 invalidq` |
| 6 | `u.y.qq.com/cgi-bin/musicu.fcg` (CgiGetVkey, 免费歌) | ❌ `104009 invalidq`（对照实验） |
| 7 | `c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg` (付费歌) | ✅ C100 地址 |
| 8 | `c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg` (免费歌) | ✅ C100 地址（与付费歌无差别） |
| 9 | `music.163.com/api/song/enhance/player/url` (4 首混合) | ✅ 1 首有效 320k，3 首 `code:-110` |
| 10 | `music.163.com/api/song/enhance/player/url` (热歌榜前 5) | ✅ **4/5 有效 320k，命中率 80%** |
| 11 | `music.163.com/api/playlist/detail?id=3778678` | ✅ 热歌榜真实曲目 + `fee` 字段 |
| 12 | `music.163.com/api/playlist/detail?id=19723756` | ✅ 飙升榜真实曲目 + `fee` 字段 |
| 13 | `m.kugou.com/plist/rank/home?json=true` | ❌ "Access Deny ! No Actions !" |
| 14 | `m.kugou.com/rank/info/?rankid=8888&page=1&json=true` | ✅ TOP500，含 hash/320hash/sqhash |
| 15 | `songsearch.kugou.com/song_search_v2` | ✅ 真实 hash + 三档码率 |
| 16 | `wwwapi.kugou.com/yy/index.php?r=play/getdata` | ❌ `err_code:20010` |
| 17 | 同上 + 完整参数（dfid/mid/platid/appid） | ❌ `err_code:30020` |
| 18 | `m.kugou.com/app/i/getSongInfo.php?cmd=playInfo` | ✅ **全曲直链，`/yp/full/`，247s** |
| 19 | 同上 + 320hash | ⚠️ 仍返回 128kbps |

### 二手来源（⚠️，均已在正文标注链接）

| # | 内容 | 来源 |
|---|------|------|
| 1 | 网易云榜单 ID（3779629 / 2884035） | Binaryify/NeteaseCloudMusicApi 文档 |
| 2 | 酷狗榜单 rankid 表（6666 / 52144 / 52767 / 24971 / 31308 / 59703） | [CSDN](https://blog.csdn.net/weixin_59748320/article/details/147009902)、[GitHub BrockChen/-Api](https://github.com/BrockChen/-Api/blob/master/%E9%85%B7%E7%8B%97%E9%9F%B3%E4%B9%90web%E7%AB%AF%E6%8E%A5%E5%8F%A3.md) |
| 3 | QQ音乐 vkey 需 `Referer: https://y.qq.com/` | [GitMemories](https://gitmemories.com/helloforrestworld/Vue-music-)、[hqwc.cn](https://www.hqwc.cn/a/1093981.html)、[CSDN](https://blog.csdn.net/gitblog_00505/article/details/161654198) |
| 4 | QQ音乐 filename 前缀约定（C100/C400/M500/M800/F000） | [scode.org.cn](https://scode.org.cn/article.html?id=18)、[CSDN](https://blog.csdn.net/cnhongbinli/article/details/82121348) |
| 5 | 网易云 `freeTrialInfo` 含义 | NeteaseCloudMusicApi 文档 |

### 待实测（❓，无可靠依据，未编造）

| # | 内容 | 建议验证时机 |
|---|------|-------------|
| 1 | QQ音乐 `CgiGetVkey` + Referer 是否可行 | **T01 基建，最高优先级** |
| 2 | `fcg_play_single_song` 的 C100 是否全曲（真实时长） | T01 基建，最高优先级 |
| 3 | 网易云新歌榜 3779629 / 原创榜 2884035 可用性 | T01 基建 |
| 4 | 酷狗网络红歌榜 rankid | T01 基建（枚举 `rank/list`） |
| 5 | 酷狗 320kbps / 无损取法 | T02 数据层 |
| 6 | 各接口实际限流阈值 | T05 压测 |
| 7 | 所有音频 URL 能否被 ExoPlayer 正常解码 | T03 播放器集成 |

---

*报告完 — 高见远 / 2026-08-29*

---

# 附录：可搜索/可取链第三方 API 调研（2026-09-11，齐活林亲自 curl 验证）

背景：MusicFree/LX 脚本插件需 JS 引擎（已否决，QuickJS 不排空微任务），需要「纯 HTTP、可搜索、可给播放直链」的 API 补齐搜索能力。

## 实测结论汇总

| 候选 | 搜索 | 取链 | 歌词 | VIP 绕过 | 判定 |
|------|------|------|------|---------|------|
| GD Studio `music-api.gdstudio.xyz/api.php` | ✅ netease/kuwo | ✅ 仅 netease（320k 直链，Range 206） | ✅ 完整 LRC | ❌ VIP 歌空 url | **可用，作备选取链** |
| Meting 公共实例（injahow/qjqq） | ❌ 返回空 | ❌ | — | — | 已失效 |
| kuwo 官方 www API | ❌ csrf 校验拒绝 | ❌ | — | — | 无浏览器 cookie 不可用 |
| LX onrender 代理 `/url/{src}/{id}/{q}` | ❌ 无 search | ✅ kg/wy 128-320k | — | 部分 | 已接入（u 版） |
| 网易官方搜索 `music.163.com/api/search/get/web` | ✅ 匿名可用 | —（走原生取链链） | — | ❌ | 已在 App 内使用（歌词搜索） |

## GD Studio API 明细（全部 2026-09-11 实测）

- 搜索：`GET /api.php?types=search&source=netease&name=周杰伦&count=20&pages=1`
  → `[{id,name,artist[],album,pic_id,url_id,lyric_id,source}]`
- 取链：`GET /api.php?types=url&source=netease&id=5257138&br=320`
  → `{"url":"https://m801.music.126.net/...mp3","br":320,"size":12764517}`（Range 拉流 206）
- 歌词：`GET /api.php?types=lyric&source=netease&id=5257138` → 完整 LRC
- **限制**：source 白名单当前仅 netease/kuwo/joox（tencent/kugou/migu 返回 not supported）；
  kuwo 取链当前失效（恒空 url）；VIP 歌（晴天 186016）netease 源也拿不到——**不是 VIP 绕过器**。
- 稳定性风险：个人维护的免费聚合 API，随时可能限流/失效，只可作降级策略，不可作主链。

## 建议落地路径（无需 JS 引擎，全部复用现有架构）

1. 新增**搜索页**：网易官方搜索接口（匿名可用，已在歌词搜索链路验证）出结果，
   复用 `SongRow` + 现有播放链（`netease.320` → `lx.wy.onrender` 降级）。
2. 新增 `netease.gd` 备选取链策略（priority 91，LX 代理之后）：GD netease url 端点，
   对原生 320 给试听而 GD 给全曲的歌是有效补充（实测屋顶 5257138 GD 给 320k 全曲）。
3. 灰色地带声明不变：接口随时可能失效，失败走既有错误卡片/自动跳过。

*齐活林 / 2026-09-11*

---

# 附录 2：GitHub 源码级调研（2026-09-11 晚，齐活林）

方法：GitHub REST API 搜索 + 直接抓源码分析（gh CLI 未装，用 curl+api.github.com；raw.githubusercontent 被重置须走 contents API base64）。

## 找到的关键仓库

| 仓库 | 星 | 动态 | 实测/分析结论 |
|---|---|---|---|
| `Suxiaoqinx/Netease_url` | 2788 | 2026-08 活跃 | **最有价值**。源码给出完整网易 eapi 配方：`POST interface3.music.163.com/eapi/song/enhance/player/url/v1`，AES-ECB key=`e82ckenh8dichen8`，digest=`md5("nobody{path}use{payload}md5forencrypt")`，params=`{path}-36cd479b6b5-{payload}-36cd479b6b5-{digest}`，匿名 Cookie 仅需 `os=pc; deviceId=pyncm!`。可**纯 Kotlin 原生移植**（javax.crypto 即可，零第三方依赖）。免费歌 standard/exhigh 可用；**lossless 需黑胶 Cookie**（仓库用扫码登录）。配套搜索端点 `music.163.com/api/cloudsearch/pc`（匿名）。其在线体验站 wyapi.toubiec.cn 已 404，不可依赖。 |
| `metowolf/Meting` / `ELDment/Meting-Fixed` | 2185 / 492 | 维护中 | Meting 框架（PHP），**需自建托管**；所有公共实例已死（injahow/qjqq 实测返回空）。 |
| `qyhqiu/kuwoMusicApi` | 248 | 2023 停更 | 酷我 www API 配方（固定 Cookie+Secret 流密码头）**已失效**：实测返回 `The request is illegal!`——酷我已加固，Hm_Iuvt cookie 轮换，无浏览器拿不到新值。放弃。 |
| `GSQZ/KuwoMusicApi` | 25 | 已删码停运 | 不可用。 |
| `7878gyc/gdstudio-lx-source` | 38 | 2026-02 | GD 音乐台 API 的 LX 源封装，佐证 GD API 用法；GD 托管 API 本身已实测可用（见附录 1）。 |
| `Superheroff/musicapi` | 208 | 2026-05 | QQ/酷狗/网易/酷我歌单 API，需自建 Node 服务，无公共端点。 |

## 最终收敛：本项目应做的三件事（全部原生、零托管依赖）

1. **搜索页**：网易 `POST music.163.com/api/cloudsearch/pc`（匿名，云搜索结果质量高于旧 search/get）出结果 → 复用现有播放链。
2. **原生 eapi 取链**：把 Suxiaoqinx 的 eapi 配方移植成 Kotlin `NeteaseEapiResolver`（priority 11，替代/前置现有 `netease.320`），level=exhigh；将来接入扫码登录可升 lossless/hires。
3. **GD 兜底**：`netease.gd`（priority 91，LX 代理之后），仅网易源。

已验证不可行/放弃：Meting 公共实例、酷我原生签名、toubiec 实例、tencent/kugou/migu 第三方源（GD 不支持）。

*齐活林 / 2026-09-11*
