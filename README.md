# 华立宿舍电费 · Home Assistant 集成 + 安卓 App

给**广州华立学院（增城 / 云浮 / 江门校区）宿舍电表**做的一套工具，数据直接来自学校的
「华立数研智慧微平台」（`dbwx.hlsywy.com`）：

- 🏠 **Home Assistant 自定义集成**：余额、读数、日/月用电、日均花费、预计可用天数，带低余额自动化
- 📱 **安卓 App**：一个 98 KB 的小 App，带桌面小组件、深色模式、低余额通知、自绘悬浮胶囊

<p>
<img src="docs/images/icon.png" width="96" alt="icon">
</p>

> 纯粹的民间适配，非官方项目。所有数据来自学校平台，请勿高频请求。

---

## 目录

- [功能](#功能)
- [快速开始](#快速开始)
- [Home Assistant 集成](#home-assistant-集成)
- [安卓 App](#安卓-app)
- [平台接口记录](#平台接口记录)
- [仓库结构](#仓库结构)
- [开发与打包](#开发与打包)
- [已知限制](#已知限制)
- [发布到 GitHub](#发布到-github)
- [License](#license)

---

## 功能

| | Home Assistant 集成 | 安卓 App |
| --- | --- | --- |
| 电表余额（元） | ✅ | ✅ |
| 剩余电量（度，按电价折算） | ✅ | ✅ |
| 抄表读数 + 抄表时间 | ✅ | ✅ |
| 欠费金额 | ✅ | ✅ |
| 最近一日 / 本月用电 | ✅ | ✅ |
| 本月电费 / 日均花费 / 预计可用天数 | ✅ | ✅ |
| 深色模式 | —（跟随主题） | ✅ |
| 桌面小组件 | —（用 Lovelace 卡片） | ✅ |
| 低余额提醒 | ✅ 自动化 | ✅ 通知 + 悬浮胶囊 |
| 应用内检查更新 | —（HACS 管） | ✅ 读 GitHub Release |
| 多校区 | ✅ 4 个 | ✅ 4 个 |
| 免密码 | ✅ 绑定房号即可 | ✅ 同左 |

支持校区（`memid` / `operatorcode`）：

| 校区 | memid | operatorcode |
| --- | --- | --- |
| 增城校区 | 208 | ZCSSDB |
| 云浮校区 | 250 | YFSSDB |
| 江门本科校区 | 208 | JMBKSSDB |
| 江门技师校区 | 179 | JMJSSSDB |

房号就是平台里的户号，例如 `L9-418`、`A1-105`。

---

## 快速开始

### 1. Home Assistant（HACS）

HACS → 右上角 ⋮ → *自定义存储库* → 填 luobi125/huali_elec_integration，类别选 **Integration** → 安装 → 重启 HA →
*设置 → 设备与服务 → 添加集成 → 华立电表* → 选校区 + 房号。

手动安装：把 `custom_components/huali_elec` 复制到 HA 配置目录的 `custom_components/` 下，重启。

### 2. 安卓 App

从 [Releases](../../releases) 下载 `HualiElec.apk` 安装（Android 6.0+，不需要 root）：

```bash
adb install -r HualiElec.apk
adb shell pm grant com.luobi.hualielec android.permission.POST_NOTIFICATIONS   # Android 13+ 才需要
```

首次打开会进引导页：**校区 → 楼栋/楼层 → 房间**，后两级都是可搜索列表。

---

## Home Assistant 集成

### 实体

设备名 `<房号> 宿舍电表`，实体 ID 形如 `sensor.l9_418_su_she_dian_biao_balance`。

| 实体 | 单位 | 来源 / 算法 |
| --- | --- | --- |
| 电表余额 | 元 | 平台 `Remnant` |
| 剩余电量 | 度 | 余额 ÷ 电价 |
| 抄表读数 | 度 | 平台 `ReadNumber`（属性带抄表时间、表地址） |
| 欠费金额 | 元 | 平台 `FareMoney` |
| 最近一日用电 | 度 | 日用量最新一条（属性带近 7 天明细、7 日均值） |
| 本月用电 / 本月电费 | 度 / 元 | 日用量按月汇总 × 电价 |
| 日均花费 | 元/天 | 近 7 日日均用电 × 电价 |
| 预计可用天数 | 天 | 余额 ÷ 日均花费 |

### 选项

设置间隔默认 **30 分钟**（5–720 可调）；日用量历史每小时拉一次。
平台每天清晨（约 06:30）抄表一次，所以数值一天内只在那个时间点变。

### 容错

平台偶发 DNS 超时时不会立刻把实体标成不可用：失败时保留上一次的值并在 **5 分钟**后快速重试，
连续失败 3 次才报错，恢复后自动回到正常间隔。

### 自动化示例

`examples/automation_low_credit.yaml`：余额 < 20 元或预计可用 < 3 天时弹提醒，充值恢复后自动清掉。

---

## 安卓 App

### 界面

主界面一屏显示 9 个指标，每 5 分钟自动刷新，底部三个按钮：**刷新 / 房号 / 提醒设置**。

### 引导与房号选择

- 首次启动自动进入引导页（`SetupActivity`），之后「房号」按钮也能进
- 楼栋/楼层、房间都是**可搜索列表**（一层楼动辄上百间，直接搜 `418` 就行）
- 楼栋默认落在第一个「宿舍 / 公寓」；平台拉不到时可勾「手动输入房号」兜底

### 桌面小组件

**2×1 迷你小组件**：大号余额 + 一行小字（房号 · 可用天数 · 日均花费），点一下进 App，
数值来自缓存（不联网也能看）。想再大一点可以横向拉伸。

### 深色模式

`values/` + `values-night/` 两套配色，主界面运行时按系统深浅色切换调色板，小组件用资源色自动跟随。

### 低余额通知 + 悬浮胶囊

「提醒设置」里可改：开关、余额阈值（默认 20 元）、可用天数阈值（默认 3 天）、检查间隔（默认 6 小时）。

- 通知走 `IMPORTANCE_MAX` 渠道 + `CATEGORY_REMINDER`，充值恢复后自动取消旧通知
- 同时弹一个**自绘悬浮胶囊**（`⚡ 15.00 元 · 可用 2.4 天 L9-418`），6.5 秒淡出，点击进 App，
  需要「显示在其他应用上层」权限
- `AlarmManager` 定时检查 + `BootReceiver` 开机重排

调试用：

```bash
adb shell am broadcast -a com.luobi.hualielec.TEST -n com.luobi.hualielec/.CheckReceiver
```

### 检查更新

App 内置更新检查，更新源就是本仓库的 GitHub Release：

- 启动时自动检查（**24 小时最多一次**，避免打扰，也避免打爆 GitHub 匿名 API 限额）
- 底部「当前版本 x.y.z · 检查更新」那行**点一下可手动检查**
- 发现新版本弹窗显示版本号 + Release 说明，三个按钮：**去下载**（直接下 APK）／**发布页**／**稍后**
- 只认**正式版**：GitHub 的 releases/latest 接口不返回预发布版，所以打了 prerelease 的版本不会被推送

实现见 `android/app/src/com/luobi/hualielec/Updater.java`：

```
GET https://api.github.com/repos/luobi125/huali_elec_integration/releases/latest
  → tag_name 与 versionName 做语义化比较（v1.2.3 / 1.2.3-beta 都能解析）
  → 从 assets 里取第一个 *.apk 的 browser_download_url
```

### 澎湃超级岛 / 焦点通知（已跑通）

HyperOS 上电量告警会**直接进系统超级岛**，不用自绘悬浮窗。关键有两点：

1. **别塞 RemoteViews**。`miui.focus.rv` 会让 SystemUI 走 inflate 流程，而 inflate 那步有签名白名单校验
   （`MIUI系统UI插件` 里的 `SignatureChecker` 比对小米云控名单），自签名 APK 必定 `onAuthFailed`。
2. **用官方 JSON 模板**：`miui.focus.param`（字符串）+ `miui.focus.pics`（图标 Bundle），
   由 SystemUI 自己渲染岛内布局，完全不经过 inflate。

模板结构（`param_v2`，UTF-8 上限 3072 字节）：

```json
{
  "param_v2": {
    "protocol": 1,
    "business": "huali_elec_bill",
    "updatable": true,
    "enableFloat": false,
    "ticker": "2.4天 · 15.00元",
    "aodTitle": "2.4天",
    "param_island": {
      "islandProperty": 1,
      "bigIslandArea": {
        "imageTextInfoLeft": {
          "type": 1,
          "picInfo": {"type": 1, "pic": "miui.focus.pic_icon"},
          "textInfo": {"title": "2.4天"}
        },
        "imageTextInfoRight": {
          "type": 2,
          "textInfo": {"title": "15.00元"}
        }
      }
    },
    "baseInfo": {"type": 2, "title": "宿舍电费 L9-418", "content": "余额 15.00 元 · 可用 2.4 天"}
  }
}
```

配套的 `miui.focus.pics` 是 `Bundle{ "miui.focus.pic_icon" -> Icon.createWithResource(ctx, R.mipmap.ic_launcher) }`。

运行时先问一次系统放不放行（binder 调到 SystemUI，缓存 1 分钟）：

```
content://miui.statusbar.notification.public → call("canShowFocus", bundle{package})
Settings.System.getInt(cr, "notification_focus_protocol")  // 1=HyperOS1 2=HyperOS2 3=HyperOS3(超级岛)
```

实测（Xiaomi 14 / HyperOS OS3.0 / Android 16）：

```
I HualiElec: 焦点通知: protocol=3 canShowFocus=true
I HualiElec: 焦点通知模板已附加(492 bytes): 15.00元
D EmptyNotif: requestFocusNotificationView: 0|com.luobi.hualielec|919 content: FocusNotificationContentImpl@8308187
```

- 小岛：`⚡ 2.4天      15.00元`（左区=可用天数，右区=余额）
- 展开：`宿舍电费 L9-418` / `余额 15.00 元 · 可用 2.4 天`（`baseInfo`）

| 区域 | 内容 | 模板字段 |
|---|---|---|
| 小岛左 | 可用天数（带应用图标） | `bigIslandArea.imageTextInfoLeft` |
| 小岛右 | 余额 | `bigIslandArea.imageTextInfoRight` |

> **右侧 `type` 必须是 `2`**（`3`/`4` 也有效）。写 `1` 或 `5` 时右侧永远是空白 ——
> SystemUI 里 `IslandTemplateFactory.chooseModule()` 的 `area_right` 分支会先把 `type == 1` / `type == 5`
> 直接跳到 generic 分支，右侧模块压根不会创建（宽度仍被保留，所以看起来像「留了空位没内容」）。
> 另外「小岛」是从 `bigIslandArea` 派生的，`smallIslandArea` 里再写一遍右侧是无效的。

> 左右两段的对齐和间距**改不了**：SystemUI 给小岛左右各一个固定宽度槽位（实测 210px / 76dp），
> 左段文字贴槽位左缘、右段贴右缘，中间固定留 ~41dp。`ImageTextInfo` / `TextInfo` /
> `SameWidthDigitInfo` / `FixedWidthDigitInfo` 这些模型里都没有 gravity / padding 字段，
> `IslandRightTextViewHolder.updateWidth()` 的 case1~case7 只控制 frontTitle / title / content
> 三段文字显示哪几段，不涉及对齐。试过往两侧补空格、加长文案、换 `islandProperty`、
> 给右侧加图标，空隙始终在 88~114px 之间（胶囊宽度 485/605/657 分档跳变，内容一长就跳档把空隙补回来）。

放行规则（`canShowFocus`）：国内版 HyperOS 一般是应用商店上架 + 审核后放行；本机实测侧载包也返回 true。
拿不到放行时，App 会自动退回**自绘悬浮胶囊**（`Island.java`），保证任何设备都有提醒。

> 模板格式参考：Xiaomi HyperOS 开放文档 `pId=2131` / 限额 `pId=2146`，以及开源项目
> [lxsfful/dsh-go](https://github.com/lxsfful/dsh-go) 的 `notify/XiaomiFocus.kt` 实现。

---

## 平台接口记录

平台是微信 openid 体系的 ASP.NET 站点。**绑定房间不需要密码**，而且**一个房间可以绑定多个
openid**（互不影响），所以 App / 集成都可以用自己生成的 openid，不碰你在微信里的绑定。

| 用途 | 接口 |
| --- | --- |
| 微信登录后入口 | `GET /Web/MWeb/basewxpage.aspx?memid=&openid=&operatorcode=` |
| 绑定房号 | `GET/POST /Web/MWeb/login_HN.aspx?memid=&openid=&operatorcode=`（需带 `__VIEWSTATE` 等隐藏域） |
| 电表快照 | `POST /Web/MWeb/payusereqlist.aspx?_r=show` |
| 日用电历史 | `POST /Web/MWeb/amountlist.aspx?_r=show` |
| 缴费记录 | `POST /Web/MWeb/paylist.aspx?_r=show` |
| 解绑 | `POST /Web/MWeb/member.aspx?_r=delbind` |
| 楼栋树 | `POST /api/MiniApp/GetDeptsTree?operatorCode=&MemId=` |
| 房号列表 | `POST /api/MiniApp/GetUserList?operatorCode=&DeptID=&MemId=` |
| 焦点通知准入 | `content://statusbar.notification/canShowFocus` |

字段要点（`payusereqlist` 返回的 `MeterDataBody[0]`）：

- `Remnant` 华立各校区是**元**（三相电表是度）、`ReadNumber` 总读数（度）、`ReadDate` 抄表时间
- `FareMoney` 欠费、`Price` 电价、`PayType` 缴费类型（`4` = 预付费按金额）
- 日用量 `FreezeMonth` 是结算日（覆盖前 24h），`UseNumber` 当日用量

---

## 仓库结构

```
.
├── custom_components/huali_elec/     # Home Assistant 集成（HACS 读取这里）
│   ├── __init__.py  api.py  config_flow.py  coordinator.py
│   ├── sensor.py  diagnostics.py  const.py
│   ├── manifest.json  strings.json  translations/
├── android/                          # 安卓 App（不用 Gradle）
│   ├── app/AndroidManifest.xml
│   ├── app/src/com/luobi/hualielec/  # 8 个 java
│   ├── app/res/                      # 布局 / 图标 / 深色资源
│   └── build_apk.py                  # aapt2 → javac → d8 → zipalign → apksigner
├── tools/
│   ├── check.py                      # 命令行验证房号（不装 HA 也能用）
│   └── deploy_dsm.py                 # 群晖 Docker 版 HA 一键部署集成
├── examples/automation_low_credit.yaml
├── docs/images/
├── hacs.json
└── README.md
```

---

## 开发与打包

### 命令行验证房号

```bash
pip install aiohttp
python tools/check.py --campus zengcheng --room L9-418
```

会临时绑定一个 openid、打印电表快照和近 20 天用量，然后自动解绑。

### 群晖 Docker 版 HA 部署集成

```bash
python tools/deploy_dsm.py --user <群晖账号> --password <群晖密码> \
       --config-dir /volume1/data/homeassistant/config
```

### 构建 APK

需要 Android SDK（`build-tools;34.0.0` + `platforms;android-35`）和 JDK 17：

```bash
export ANDROID_SDK=/path/to/sdk
export JAVA_HOME=/path/to/jdk17
python android/build_apk.py      # 产物 android/HualiElec.apk
```

---

## 已知限制

- 平台每天清晨抄表一次，App / 集成的数值一天内只在那个时间点变
- MIUI / HyperOS 在**锁屏、息屏**时会拦截 App 联网（系统级 DNS 拒绝），解锁后正常；
  通知横幅还需要在系统设置里给 App 打开「悬浮通知」
- 华立各校区平台的「剩余量」单位是**元**（三相电表例外，按度），「剩余电量」是折算值
- 仅适配宿舍电表；教学办公楼、商铺等其它电表类型未验证

---

## 发布到 GitHub

```bash
# 1. 建仓库（网页或 gh cli），然后把本目录推上去
git init
git add .
git commit -m "feat: HA integration + Android app for Huali dorm meter"
git branch -M main
git remote add origin https://github.com/luobi125/huali_elec_integration.git
git push -u origin main

# 2. 打个 tag + Release，把 APK 作为附件上传
git tag v1.0.0 && git push --tags
# Releases → Draft a new release → 选 v1.0.0 → 上传 android/HualiElec.apk
```

发布后：

- HACS 里用「自定义存储库」填 `<用户名>/huali_elec` 就能装集成
- 仓库里 `.github/workflows/validate.yml` 会自动跑 HACS 与 hassfest 校验
- `manifest.json` / `hacs.json` 里的仓库地址记得改成你自己的

---

## License

[MIT](LICENSE)。仅用于个人查询自己的宿舍电费，请勿用于批量抓取或商业用途。
