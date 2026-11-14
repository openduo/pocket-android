# Historical test-package checkpoint

This Chinese archive records earlier `top.kuaner.pockete2` builds with distinct APK hashes. It does not imply that the current renamed package reran every scenario. See [current status](status.md); extended matrices and long background tasks are no longer the milestone.

# 当前状态与后续交接

更新日期：2026-11-14。当前目标是收尾约定的 P0/P1 实现、提高可维护性和支持一般 Android 窗口。
用户已明确不把完整 1:1 作为本阶段的交付门槛；Push 和 Passport 继续暂缓。

**实现与 UI 适配已经落地，但不能据此宣称核心流程稳定性已完成验收。** 用户发现的后台断网恢复故障已修复并
更新真机；长期后台恢复仍未完整验收。下表分别说明实现和验证状态，
不能把准备的协议响应、合成语音或 Try 模式当成自然使用的端到端验收。本文是当前入口，逐项上游差异在
[`migration.md`](migration.md)，测试条件和证据在 [`acceptance.md`](acceptance.md)。

## 已经完成

| 范围 | 已实现和修复 | 验证边界 |
| --- | --- | --- |
| P0 连接与身份边界 | 独立 live connector 和发送队列、有限退避、网络变化重绑、前后台恢复；统一凭据 generation、取消旧 HTTP/语音请求、拒绝后的 outbox 暂停 | 实际配对/历史/文本回复、短 Wi-Fi 断开恢复和 scoped HTTP 已验收；实际注销/撤销/重新配对尚未验收 |
| P0 消息、草稿和恢复 | SQLite 行/outbox/cursor 事务、稳定幂等键和原始 surface、文件清理 receipt、录音 durable inbox、历史答案关联与重放去重 | 有实际 force-stop/cold-launch、原生存储和准备的失败测试；实际服务在途入库断电/回放与自然低内存杀进程未验收 |
| P0 语音与环境模式 | 独占输入、原生 Opus、上传重试与后台 service、静音/打断/朗读策略、故障后释放和恢复资源 | 真 E2 采集和实际合成语音 ASR → brain/tool → 回复 → 静音 AudioTrack 已通过；自然人声识别和可听回复仍需验收 |
| P1 Sight、会话与位置 | Camera2/H.264、协商状态和拒绝恢复、会话列表/详情/通知状态、地图/坐标插入、有限时 geocoder、arrival ledger | 原生相机加准备的控制帧、真实会话读取、坐标插入、API 35 真实正反地理编码已通过；真实 Sight 协商、session notify 投递、physical visit 未通过完整验收 |
| UI 适配与状态 | 解除强制竖屏、Compact/Medium/Expanded 窗口策略、列宽上限、Insets/IME、短窗口容器、可滚动表单/编辑器、原生每页状态保存 | 22 组窗口/字体/语言/主题矩阵、实际显示旋转、自由窗口和系统分屏通过；E2 小屏回归通过 |
| 无障碍 | 至少 48dp 公共操作目标、按钮/选择/开关语义、具名消息操作、录音的键盘/无障碍开始/发送/取消、地图/图片的非手势操作 | 实际 TalkBack、旧版 Google 源码 Switch Access、四类系统权限和文件选择器取消返回通过；当前 Play 版服务及其他动态内容/系统对话框仍需扩大覆盖 |
| 工程维护 | 纯协议与业务 owner、窗口策略/容器/页面状态/控件分层、架构边界检查、本地化检查、可重复模拟器验收脚本、文档入口 | Host 325 项检查点、架构/617 条本地化 catalog 通过；本次网络修复的 Go vet/test 和 Android 构建通过，远端 CI 和发布分发未执行 |

UI 方案沿用项目的原生 View 技术栈，使用 Android 的窗口尺寸类别、WindowInsets、原生 View 状态保存和
滚动布局。尺寸、字体、键盘与系统栏适配集中在 `ui/`；`PocketController` 继续拥有房间、草稿、消息、
outbox 和设备资源，不读取屏幕尺寸。新增适配规则时首先修改公共策略/容器，避免在业务逻辑中添加设备分支。
具体职责、断点和扩展方式见 [`ui.md`](ui.md)。

## 本轮已执行的验证

| 验证 | 结果 | 本机 ignored 证据入口 |
| --- | --- | --- |
| API 35 窗口/字体矩阵 | 22 组全部通过，共 9,616 项检查；字体 0.85–2.0，手机/平板/短横屏，中英文和明暗组合 | `.build/acceptance/ui/summary.json` |
| 实际显示旋转 | 20 项通过；横屏 740×360dp、竖屏 360×740dp，显示与 Activity 边界相等；表单、隐藏页面、选区、草稿和阅读位置保留 | `.build/acceptance/ui/windows/rotation/` |
| 自由窗口/系统分屏 | 分别 8/12 项通过；实际 multi-window，八页分屏截图和草稿保留 | `.build/acceptance/ui/windows/` |
| 实际 TalkBack 15.0 | 扩展运行 404 项通过，八页加四个弹层/详情；原始触屏滑动、双击/长按、明确控件顺序及真实焦点轨迹 | `.build/acceptance/accessibility/talkback/` |
| 实际 Switch Access 源码 6.1.1 | 扩展 113 项通过，八页关键控件/顺序、长对话、两次设置滚动、会话与编辑/操作 sheet 激活；不是当前 Google Play 版本 | `.build/acceptance/accessibility/switch/` |
| 实际系统权限 | 两种服务下相机、麦克风、定位、通知均通过；覆盖拒绝/重试、粗略位置升级、授权后的待执行操作和返回焦点。逐类检查数见无障碍文档 | `.build/acceptance/accessibility/*-permissions*/` |
| 实际外部文件选择器 | 两种服务各 25 项通过；服务打开 DocumentsUI，连续两次系统返回取消，保留草稿/选区，清理待导入状态，服务可再次打开附件菜单 | `.build/acceptance/accessibility/{talkback-picker,switch-picker}/` |
| 权限/外部窗口打开时旋转 | 修复重建后实际授权被误报拒绝；相机、麦克风、通知各通过两种实际服务，TalkBack 文件取消/重建通过。定位旋转未通过，未宣称完整组合验收 | `.build/acceptance/accessibility/*-recreation/` |
| 真 E2 采集/相机/paired-Try | 较早 APK 完整回归 342 项通过；feed 修复检查点无障碍专项 27 项通过。最新包含网络/权限修复的构建已安装，未重复完整回归 | `.build/acceptance/e2-final-regression.txt`、`e2-accessibility-expanded.txt` |
| 实际系统 Geocoder | API 35 公共地标搜索/反查均返回结果，5,664ms；没有替换后端或注入位置 | `.build/acceptance/ui/location-service/` |
| E2 地点能力查询 | API 27 `Geocoder.isPresent=false`，仅 passive/gps 且无 enabled provider；真实查询为 NOT_RUN，不计通过 | `.build/acceptance/e2-location-service/`、`e2-geocoder-run.txt` |
| 真 E2 后台寿命 | 900 秒 idle 后台，60 次采样进程均存在，RUNNING_CRITICAL 请求后回前台恢复 Online | `.build/acceptance/lifetime/` |
| 用户报告的实际连不上房间 | 捕获 Wi-Fi 已恢复而 tsnet 仍报 network down；修复恢复时遗漏的 netmon 重采样，更新真机后同进程后台断网/恢复约 2.7 秒回到在线，本机 Tailscale 同时确认节点在线 | `.build/connection-incident/` |

完整矩阵和后续补验记录各自的 APK SHA；重新构建的 ZIP 时间戳会改变 APK 摘要。不要把不同报告的摘要
合并成“同一字节 APK 全部通过”。截图、日志、录音和实际房间信息只保存在 ignored 目录。
后台测试是 15 分钟 idle 和一次 trim 请求，另留一条只读 Activity 后台观察；当前证据并没有逐采样核对
Activity 前后台，也没有制造自然 LMK。它不等于过夜待机、真实内存耗尽或长期 ambient 验收。

继续 goal 的补验已得到两个实际服务结论：Sight 探针取得真实 capture master，但服务器未确认 `media=1`，
因此为 NOT_RUN、没有发送视频；当前自有 room 再次查询仍不是可通知目标，没有发送通知。9 项 Sight
前置/清理/麦克风恢复检查通过，不能据此接受视频协商。8 小时后台观察在约 98 分钟时因 App 回到前台而提前结束，未通过；
无障碍扩展的长内容、明确控件顺序和真实服务手势/滚动已通过，结果见 [`acceptance.md`](acceptance.md)。

等待后台任务期间又补齐了四类系统权限及真实文件选择器取消返回。麦克风授权返回不会意外开始录音，
重新激活后能采集，打开另一窗口会取消采集并保留草稿；位置权限可从粗略升级为精确，到达检测仍等待
独立后台权限。这些检查使用隔离模拟器和必要时的 Try transport，不能替代自然人声、真实 visit、
Sight 或通知收件验收。该批正常流程检查未修改生产 APK；失败轨迹和完整复现见
[`accessibility.md`](accessibility.md)。

随后实际旋转暴露了权限回调问题：旧 Activity 的操作回调已销毁，新页面却把系统完整授权误报为拒绝。
现改为刷新权限状态并提示“权限已允许，请再次操作”，避免恢复引用旧 View 的扫码/录音操作。构建和
Host 检查通过；该专项时仅安装到隔离模拟器，后续网络修复的合并构建已更新 E2。E2 的 8 小时任务使用修复前 APK，期间未安装更新。
两种服务的相机/麦克风/通知旋转及 TalkBack 文件取消重建通过；Switch 定位旋转在粗略授权后的提示断言
失败，保留未通过状态，其他未跑组合继续开放。

按用户反馈停止扩展测试。已通过的检查不重复执行；后续只针对产品改动、实际失败或尚未解决的问题做必要验证。
当前收尾保存实现、证据边界与差距文档，不为补齐矩阵继续新增场景。
后台任务现已终止，不重启：387 次采样进程均存在，最后一次在 5,887.24 秒检测到前台 Activity，
尚未执行计划中的 4 小时 trim 请求和末尾 Online 恢复检查。记录无法判断回前台的原因，不据此认定产品缺陷。
原始记录与独立终止说明保存在 `.build/acceptance/lifetime-8h/`。

用户随后报告 App 连不上房间，本机 Tailscale 确认 E2 离线，房间主机在线且健康接口返回 200。实际日志显示
Wi-Fi 断开后已恢复，但 tsnet 一直报 `magicsock: network down`；原恢复动作只做 socket rebind/restun，
没有刷新网络监视器。现在先触发真实接口重采样，再恢复通道。修复已覆盖安装到 E2，一次同进程后台
Wi-Fi 断开/恢复和回前台验证通过，房间约 2.7 秒在线，本机确认节点在线；没有发送消息或更改配对。
这次故障说明此前短断网与准备的失败测试不足以宣称连接稳定；不重复其他矩阵，也不重启长期任务。
诊断、修复和验证条件见 [`connection.md`](connection.md)。

## 尚未完成的验收与建议顺序

以下是已知实现的真实使用验证缺口。本阶段不为追求 1:1 无边界增加新功能。

| 顺序 | 下一步 | 所需条件与通过标准 |
| --- | --- | --- |
| P0 验收 | 自然人声语音/环境模式 | 用户实际对 E2 说话，确认自然 ASR、单条消息/工具回复、可听输出、取消/打断和资源释放；现有合成语音不能替代 |
| P0 验收 | 身份与恢复 | 有有效登录与新配对恢复路径后，实际 logout/relogin、revoke/re-pair，确认旧任务取消及原消息/草稿语义；当前一次性配对链接已消费，不盲目注销 |
| P0 验收 | 网络、外部音频与持续寿命 | 两条可切换的实际网络、实际耳机/外部麦克风，以及过夜/长期 ambient、自然进程回收；短断网、trim 回调和 force-stop 分别记录 |
| P1 验收 | 真实 Sight | 当前服务器未确认 media=1；需实际服务支持后，再做 ask/参数协商、可识别画面和关闭/重启。Camera2 和准备控制帧不能替代 |
| P1 验收 | 真实 session notify | 明确自有且可收件的测试会话，验证单次发送、确认和收件端记录；当前配对 room 不满足资格，未向其他会话发送 |
| P1 验收 | E2 地点与真实到达 | 当前 E2 无系统 Geocoder、无 enabled provider，继续依赖手动坐标/地图选点；需可用位置源及地理编码后端再做真实停留/到达。API 35 查询通过不能替代 |
| 后续兼容性 | 无障碍和更多设备 | 当前 Play Switch Access、其余滚动层级/长会话、一次性/永久拒绝及系统设置撤权等权限组合、OEM/折叠/刘海/外接显示器、外部操作中的重建 |

## 距离完整 1:1 仍有什么

对照基线为 `../pocket-ios` 的 `b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`。没有使用功能百分比：
相同入口并不代表相同的服务、生命周期和失败语义。完整逐功能 owner、已知差异和证据边界在
[`migration.md`](migration.md)，主要剩余类别为：

- **主动暂缓的能力**：Android 即时推送的注册/relay/receiver，上游推送 AES-GCM/HMAC/ledger 与固定向量；
  Passport 外部 BLE、唤醒和固件日志。保留现有独立用途的本地加密/arrival ledger 与旧 Passport 实现。
- **真实场景验收**：上表的自然语音、Sight/notify/visit、身份/跨网/路由/持续寿命。
- **细节与平台差异**：附件限额/失效 provider/存储不足、极端历史/工具关联、长列表、相机异常/热控制、
  Android arrival 可靠性与 iOS visit 行为、现代 Android 后台和权限规则。
- **分发与覆盖**：目前仅 arm64-v8a，本地签名与人工 APK 安装；远端 CI、正式更新入口、能耗压力测试和
  最终逐项功能/视觉审计仍未完成。

## 继续开发入口

- 构建、安装及常用检查：[`README.md`](../README.md)。产物为 ignored `dist/PocketE2.apk`。
- UI 架构与矩阵：[`ui.md`](ui.md)；实际服务无障碍复现：[`accessibility.md`](accessibility.md)。
- 当前验收记录：[`acceptance.md`](acceptance.md)；上游差异与历次证据：[`migration.md`](migration.md)。
- 各业务边界：[`connection.md`](connection.md)、[`audio.md`](audio.md)、[`storage.md`](storage.md)、
  [`history.md`](history.md)、[`imports.md`](imports.md)、[`location.md`](location.md)。

不要更改真实 E2 的密度、overscan、launcher、系统息屏时间或 E2Studio 设置；采集前确认 E2Studio 没有录音
或工作服务。保持真实房间、草稿和凭据；实际 host、token、标识、截图及录音不得提交。
