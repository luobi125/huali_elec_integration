# 华立电表 · Home Assistant 集成

广东工业大学华立学院（华南农业大学珠江学院增城校区同源系统）宿舍电表的 Home Assistant
自定义集成，直接读取 **华立数研「智慧微平台」**（`dbwx.hlsywy.com`）的电费数据：

- 电表余额（元）
- 剩余电量（度，按当前电价折算）
- 抄表读数（度）
- 欠费金额（元）
- 最近一日用电量（度）
- 本月用电量（度）/ 本月电费（元）

支持校区：**增城校区**、云浮校区、江门本科校区、江门技师校区。

---

## 1. 安装

### 方式 A：HACS 自定义仓库（推荐）

1. HACS → 右上角 ⋮ → *自定义存储库*
2. 仓库地址填 `https://github.com/luobi/huali_elec`，类别选 *Integration*
3. 搜索「华立电表」安装，重启 Home Assistant

### 方式 B：手动

把 `custom_components/huali_elec` 整个目录复制到 Home Assistant 配置目录的
`custom_components/` 下，然后重启：

```bash
<你的 HA 配置目录>/custom_components/huali_elec/
├── __init__.py
├── api.py
├── config_flow.py
├── const.py
├── coordinator.py
├── diagnostics.py
├── manifest.json
├── sensor.py
├── strings.json
└── translations/
    ├── en.json
    └── zh-Hans.json
```

## 2. 添加设备

*设置 → 设备与服务 → 添加集成 → 华立电表*

| 字段 | 说明 |
| --- | --- |
| 校区 | 增城校区 / 云浮校区 / 江门本科校区 / 江门技师校区 |
| 房间号 | 平台里的户号，例如 `A1-105`（大小写不敏感） |

房间号就是微信端「用户管理 → 选择楼栋 → 房间号」下拉框里的那个值。不确定的话可以在
手机微信里打开 <https://dbwx.hlsywy.com/Web/MWeb/index.aspx>，进入「用户管理」，选好楼栋后
看房号（或直接扫电表上的条码）。

添加时会去平台校验一次：房号不存在会直接报「未查询到该房间」。

## 3. 实体

设备名：`<房号> 宿舍电表`

| 实体 | 单位 | 说明 |
| --- | --- | --- |
| `sensor.*_dian_biao_yu_e` | 元 | 电表余额（平台「剩余量」，华立各校区单位为元） |
| `sensor.*_sheng_yu_dian_liang` | 度 | 余额 ÷ 电价，方便估算还能用多少度 |
| `sensor.*_chao_biao_du_shu` | 度 | 电表累计读数 |
| `sensor.*_qian_fei_jin_e` | 元 | 欠费金额 |
| `sensor.*_zui_jin_yi_ri_yong_dian` | 度 | 最近一次日结算的用电量 |
| `sensor.*_ben_yue_yong_dian` | 度 | 本月累计用电量 |
| `sensor.*_ben_yue_dian_fei` | 元 | 本月用电量 × 电价 |
| `sensor.*_ri_jun_hua_fei` | 元/天 | 近 7 日日均用电 × 电价，用来估算花钱速度 |
| `sensor.*_yu_ji_ke_yong_tian_shu` | 天 | 余额 ÷ 日均花费，还能用几天 |

常用属性：

- 余额类：`price`（电价）、`balance_date`（结算时间）
- 读数类：`read_date`（抄表时间）、`meter_addr`（表地址）、`install_addr`
- 用电类：`recent_days`（最近 7 天逐日用量）、`average_7d`、`complete`（本月数据是否已覆盖整月）

刷新间隔在集成「配置 → 选项」里改，默认 30 分钟（最短 5 分钟，最长 12 小时）。
日用量历史默认每小时才拉取一次，避免频繁请求。

## 4. 工作原理

平台本身是「微信公众号 + openid」的 ASP.NET 站点，浏览器身份就是 `openid`。
关键的发现是：**绑定房间不需要密码**，房号（`UserCode`，如 `A1-105`）直接就能绑定，
而且**同一个房间可以绑定多个 openid**（互不影响），解绑也只需 openid + 房号。

所以集成在添加时会为这个配置项**随机生成一个独立 openid**，用它去 `login_HN.aspx` 绑定房间，
不碰你微信里的绑定；删除集成时会把这条绑定解掉。

用到的接口（全部为表单 POST，返回 JSON）：

| 用途 | 接口 |
| --- | --- |
| 绑定房间 | `POST /Web/MWeb/login_HN.aspx?memid=&openid=&operatorcode=` |
| 电表快照 | `POST /Web/MWeb/payusereqlist.aspx?_r=show` |
| 日用电历史 | `POST /Web/MWeb/amountlist.aspx?_r=show` |
| 解绑 | `POST /Web/MWeb/member.aspx?_r=delbind` |

校区参数：

| 校区 | memid | operatorcode |
| --- | --- | --- |
| 增城校区 | 208 | ZCSSDB |
| 云浮校区 | 250 | YFSSDB |
| 江门本科校区 | 208 | JMBKSSDB |
| 江门技师校区 | 179 | JMJSSSDB |

## 4.5 群晖 Docker 版 HA 的一键部署

HA Core 容器版没有加载项商店，不能靠网页上传文件。如果 HA 跑在群晖 Docker 里：

```bash
python tools/deploy_dsm.py --user <群晖账号> --password <群晖密码> [--config-dir /volume1/data/homeassistant/config]
```

脚本会走 DSM File Station API 把 `custom_components/huali_elec` 传到 HA 配置目录，然后调用 HA API 重启。

## 4.6 余额不足提醒（示例自动化）

仓库里的 `examples/automation_low_credit.yaml` 是一个现成例子：余额低于 20 元或预计可用不足 3 天时弹提醒，充值恢复后自动清掉提醒。把里面的 `entity_id` 改成你自己的实体即可（自动化编辑器里直接粘贴 YAML 也行）。

```yaml
  triggers:
    - trigger: numeric_state
      entity_id: sensor.<房号>_balance
      below: 20
    - trigger: numeric_state
      entity_id: sensor.<房号>_days_remaining
      below: 3
```

想要手机推送，把 `persistent_notification.create` 换成 `notify.notify`（或 Companion App 的 `notify.mobile_app_xxx`）。

## 5. 命令行自检工具

不想开 Home Assistant 也能验证房号：

```bash
pip install aiohttp
python tools/check.py --campus zengcheng --room A1-105
```

脚本会临时绑定一个 openid、打印电表快照和最近 20 天用量，然后自动解绑。

## 6. 注意事项

- **短暂断网不会立刻失效**：获取失败时集成会保留上一次的数值，并在 5 分钟后快速重试，连续 3 次失败才会把实体标记为 `unavailable`，恢复后自动回到正常刷新间隔（日志里能看到 `保留上次数据` / `已恢复` 的提示）。
- **单位**：平台对华立各校区（memid 179/208/250）的「剩余量」按 **元** 显示，三相电表例外（按度）。
  `sensor.*_sheng_yu_dian_liang` 是「余额 ÷ 电价」的估算值，三相电表上会不准，看余额就好。
- **频率**：数据来自学校服务器，默认 30 分钟刷新已经足够，不建议改到 5 分钟以下。
- **学生微信绑定**：集成用的是自己生成的 openid，正常不会影响你在微信里的绑定；
  如果哪天平台策略改成「一个房间只允许一个 openid」，那么添加集成后需要重新在微信里绑定一次。
- **数据延迟**：平台每天抄表结算一次（一般在清晨），因此「最近一日用电」通常显示的是当天凌晨
  结算出来的前一日用量；余额也会滞后一点。

## 7. 排错

| 现象 | 处理 |
| --- | --- |
| 提示「未查询到该房间」 | 房号写错，确认楼栋前缀，例如 `A1-105`、`B9-506` |
| 提示「无法连接」 | 检查 HA 出口网络能否访问 `dbwx.hlsywy.com`（需要 HTTPS 出网） |
| 实体变成 unavailable | 平台偶尔维护，集成会自动重试；持续超过 1 小时可下载「诊断」信息看返回内容 |
| 数值不对 | 打开 `tools/check.py` 对比平台网页上的数字，并附诊断信息提 issue |

诊断信息可以在 *设置 → 设备与服务 → 华立电表 → 下载诊断* 中获取（openid 已脱敏）。
