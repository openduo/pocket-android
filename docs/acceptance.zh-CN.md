# 扩展验收记录

更新日期：2026-11-14。本记录对应四类扩展验收；当前实现收尾目标见 [`status.md`](status.md)。Push 和
Passport 暂缓。已通过只针对下列实际条件，不以断言数量替代完整场景覆盖。

| 范围 | 当前结果 | 仍开放的范围 |
| --- | --- | --- |
| UI 窗口和字体 | 22 组截图/真实 IME，9,616 项检查通过；真实显示旋转 20 项、自由窗口 8 项、系统双应用分屏 12 项通过 | OEM、刘海/折叠/外接显示器、所有外部操作中的配置变化及权限弹窗的字体/窗口完整组合 |
| 无障碍 | 实际 TalkBack 15.0 扩展运行 12 个场景、404 项；Google 源码 Switch Access 6.1.1 扫描/滚动/激活 113 项；两种服务的四类系统权限及 DocumentsUI 取消返回通过；E2 既有专项 27 项通过；新 APK 相机/麦克风/通知旋转和 TalkBack 文件取消重建通过 | 当前 Play 版 Switch Access、其余动态消息/滚动层级、一次性/永久拒绝/设置撤权；定位旋转未通过，其他外部 Activity 重建组合仍开放 |
| 真实服务 | 既有实际历史/文本/文件/会话读取、合成语音 ASR/brain/tool/静音播放；新增 API 35 实际 Geocoder 搜索/反查通过 | 自然人声 ASR 和可听回复、真实 Sight 协商、session notify 收件、physical visit 和 E2 geocoder |
| 身份、网络和生命周期 | 既有短 Wi-Fi 断开恢复、前后台、force-stop/cold-launch；新增 900 秒真 E2 idle 后台和 trim 请求后恢复 Online | 实际 logout/relogin、revoke/re-pair、两条真实网络切换、外部音频路由、过夜/长期 ambient、自然 LMK |

## 窗口和字体

API 35 Google APIs arm64 模拟器的 22 组组合全部通过：

- 手机 360×740dp 和平板 1280×800dp：字体 0.85、1.0、1.15、1.3、1.5、1.8、2.0。
- 窄窗 320×640、Medium 600×960、Expanded 840×600、横屏 740×360，以及 2 倍字体的 740×360、600×320。
- 2 倍字体下另有手机/平板的中文/浅色组合；其他组合为英文/深色。
- 八主页面及可滚动末端、Try 长回复/Markdown 表格/工具/文件、会话详情、附件和编辑 sheet；
  对话/会话/配对/位置/编辑弹层使用真实 IME。

矩阵不是字体、语言、主题和窗口的完整笛卡尔积。检查实际窗口 config 与请求一致，避免系统设置尚未落定或
复用旧 JSON 造成假通过。截图已抽查，修复了大字裁切、键盘遮挡、配对底栏挤压和地图高度变成零等问题。
结果：`.build/acceptance/ui/summary.json`，22/22，9,616 assertions。

实际旋转以 `setRequestedOrientation` 触发 Activity 重建，同时确认最大显示和当前窗口方向、边界一致：
740×360dp 横屏和 360×740dp 竖屏，20 项通过。最初模拟器忽略旋转，产生了竖屏中的横向信箱窗口；
状态保存检查有效，但不足以作为实际显示旋转证据。最终 runner 保存/临时调整/恢复模拟器 orientation policy，
重新验证了显示旋转、配对表单/选区、隐藏页面状态、controller 草稿和设置阅读位置。

自由窗口实际运行于 `isInMultiWindowMode=true`，600×600、920×700、360×660dp，8 项通过。分屏通过
系统 Recents 的 Split 操作选择 Settings + Pocket E2，早期 635×800dp pane、最终 runner 800×635dp pane，
两次均有八页配置/截图和草稿保留，分别 12 项通过。
结果：`.build/acceptance/ui/windows/summary.json`，各子目录保存截图与运行日志。

```sh
scripts/build-android.sh
python3 scripts/test-ui-matrix.py --serial YOUR_API35_EMULATOR
python3 scripts/test-window-ui.py --serial YOUR_API35_EMULATOR
```

两脚本拒绝真实设备，共享每个模拟器的独占锁，退出时恢复窗口、密度、字体、旋转与所改的开发设置。
`--installed` 仅用于已经安装匹配 app/probe 的本地迭代。真实 E2 的显示设置没有修改。

## 实际无障碍服务

实际 TalkBack 15.0.0.639625893 下，八页加四个弹层/详情的原始触屏滑动遍历通过，扩展 runner 为 404 项。
保留实际 accessibility focus 轨迹和截图。旧的直接 node/keyboard 证据单独保留，不能替代此次服务运行。

Google 官方镜像不含 Switch Access，当前 Play APK 不可得；从未修改 Java 行为的官方开源 6.1.1 构建独立
QA 服务，原始 uinput 键盘完成八页扫描、滚动和详情/弹层激活，扩展 113 项通过。这是旧版实际服务证据，
当前 Google Play 版本仍未验收。完整复现、源码 pin、输入路径和覆盖限制见
[`accessibility.md`](accessibility.md)。

证据：`.build/acceptance/accessibility/{talkback,switch}/run.json`。

### 扩展交互补验

继续四类 goal 时，TalkBack 测试增加四轮 Try 长内容、八主页面的明确控件阅读顺序、消息操作、会话详情和
编辑 sheet。专项已通过 36 项：实际服务的双击并按住打开消息操作，顺序遍历 Copy/Share/Reply/Read aloud，
再双击关闭。输入 helper 使用当前焦点控件在真实显示上的位置；早期固定在显示中心触摸没有打开菜单，
该失败没有计为通过。证据：`.build/acceptance/accessibility/talkback-message-actions/`。

完整扩展运行 **404 项全部通过**，还检查实际双击关闭附件 sheet、发送体验模式通知和提交编辑文本；通知只进入 Try transport，
不作为实际 session notify 证据。设置页的首次完整阅读顺序与滚动后回绕分别处理：到末端后 TalkBack 可以从
当前可见后缀继续，顺序 gate 校验首次完整 pass 且要求所有关键控件可到达。初次 gate 将平台回绕误判为
乱序，修正检查后完整复跑通过。

Switch Access 扩展运行 **113 项全部通过**：A/B 为 next/select，C/D 映射服务原生向前/向后滚动。
实际执行设置页两次向前滚动到末端，长对话、八页关键操作、会话详情和编辑/操作 sheet 都有明确可达性与
顺序断言，并实际选择体验模式通知、确认编辑和恰好一次操作回调。没有直接 `View.scrollTo` 冒充服务滚动。
扫描框按 native View 的可见裁剪区域和 Dialog 的实际显示偏移匹配；长对话扫描预算不再在到达输入框前
结束。会话测试同时等待 controller loaded 与真实编辑器渲染，仍保留原 5 秒期限。
两种服务报告均为 schema 2；runner 删除旧证据、校验完整报告，异常/超时会停止隔离模拟器中的 app 并清理。

### 实际系统权限补验

早期系统相机权限专项中，TalkBack 46 项、Switch Access 33 项通过。实际服务从配对页请求权限，按可见顺序
遍历三个系统决定；拒绝后返回 app 的权限说明，关闭、再次请求并授权，打开扫码面板再返回原配对表单。
不注入权限 callback，也不直接 click 系统节点。保留草稿与未配对状态，恢复原相机授权和用户决定标记。

旧版 Switch QA 服务最初未声明 `isAccessibilityTool`，受 API 35 的敏感节点限制而只能扫描自身菜单。
构建脚本现补充 API 31 服务声明，上游 Java 保持原样；新专项和完整 113 项页面回归均通过。实际 Android 第二次请求使用不同的拒绝
按钮 ID，保留原始 ID 并按拒绝选项校验。证据和旧构建失败边界见 [`accessibility.md`](accessibility.md)。
扩展 runner 又实际通过麦克风、定位、通知和文件选择器流程，逐类检查数在
[`accessibility.md`](accessibility.md)：

- 麦克风：先拒绝再授权，权限弹窗导致窗口失焦，旧 activation 不会启动采集；重新由服务激活后能采集，
  打开附件 sheet 会取消录音，未产生消息或待发送项，草稿保留。使用 Try transport，不接受自然人声 ASR。
- 定位：实际选择粗略授权，确认到达检测仍因精度/后台要求暂停；实际再次打开精度升级弹窗并授予精确位置，
  app 单独说明后台定位要求。没有提供位置 fix、授予后台定位或发送 visit。
- 通知：实际开关请求、拒绝、说明关闭、从状态行重试并授权，保留通知偏好与返回焦点；没有发送通知。
- 文件选择器：两种服务分别通过 25 项。由服务打开真实 DocumentsUI，再用系统 Back 取消，连续两次保留
  原生编辑器选区/草稿，附件、导入和 outbox 均为空；返回后服务可重新打开并关闭附件菜单。Back 是平台键，
  没有把它记为 TalkBack 手势或 Switch Access 的系统返回菜单操作。

测试保存/恢复语言、相关偏好、原运行时授权和 USER_SET/USER_FIXED 标记，并清理服务/QA 键盘。
扩展 runner 核对实际安装的 app、probe 和服务 APK 摘要；较早结果保留当时已有字段，不回写新字段。
相机扩展脚本另行复跑，早期相机报告单独保留。一次性授权实际选择、永久拒绝、设置撤权、其他系统弹窗、
外部操作中的配置变化仍开放；这些结果不接受真实 Sight、visit 或通知收件。

### 实际旋转后的权限回调修复

新 APK 修复系统完整授权返回重建 Activity 后被误报为拒绝的问题：回调已随旧页面失效时，刷新设置并
提示重新操作。两种实际服务均通过相机、麦克风和通知的拒绝/重试/授权旋转流程；TalkBack 还通过真实
DocumentsUI 两次取消及返回重建。Switch 定位旋转在粗略授权后的通用提示断言失败，未完成精度升级；
TalkBack 定位旋转、新 APK 的 Switch 文件重建未运行。没有为补齐组合继续复跑。

该权限专项时只安装到模拟器，后续网络修复的合并构建已更新 E2；已终止的 8 小时任务使用旧 APK，
不能把旧 E2/UI 完整回归归到最新产物。
具体摘要、失败记录、ADB 中断后的清理恢复和复现方式见 [`accessibility.md`](accessibility.md)。

## 真 E2 与真实 Geocoder

较早 APK 检查点的采集/相机/paired-Try 完整回归通过 342 项；移除 feed 重复朗读节点后，当时 APK 的
无障碍专项重新通过 27 项。两份报告保留各自 APK 摘要，最新权限重建修复未在 E2 复跑。使用真实 E2 microphone、
JNI Opus、Camera2、Activity/SQLite；业务失败场景中的准备响应保持单独标注。完整回归曾发现音频测试在
Listening 早于首包到达时提前断言的竞态，现等待其原断言的两个条件，原 7 秒期限未变，随后完整运行通过。

API 35 模拟器的实际系统 `Geocoder` 公共地标搜索/反查各返回一条结果，耗时 5,664ms，没有替换后端或
注入 fix。此时 enabled providers 仍为空；geocoder 可用不代表 GPS fix/physical visit 已验收。E2 之前查询
为 `passive,gps` 且 enabled providers 为空。最终 E2 只读专项确认 `Geocoder.isPresent=false`，结果为
`NOT_RUN actual geocoder unavailable`，没有计为通过；当前 E2 可继续手动坐标/地图选点，地点搜索和实际到达
需要可用后端/位置源。证据：`.build/acceptance/e2-location-service/result.json`、`e2-geocoder-run.txt`。

```sh
ANDROID_SERIAL=YOUR_IDLE_E2 POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_CAMERA=1 POCKET_E2_TEST_PAIRED_TRY=1 scripts/test-device.sh
ANDROID_SERIAL=YOUR_IDLE_E2 POCKET_E2_TEST_ACCESSIBILITY_ONLY=1 POCKET_E2_TEST_CAPTURE=1 scripts/test-device.sh
ANDROID_SERIAL=YOUR_DEVICE POCKET_E2_TEST_LOCATION_SERVICE_ONLY=1 scripts/test-device.sh
```

## 有明确时长的真 E2 后台

真实配对 E2 在 Online 后按 Home，观察 **900.0 秒**；60 次 pid 采样均存在，中间向系统请求
`RUNNING_CRITICAL` trim，回前台后实际 UI 再次为 Online。期间没有运行其他 instrumentation、打开本 app、
重启或改变显示/息屏配置。另留一次只读 Activity 状态确认仍在后台。

证据：`.build/acceptance/lifetime/result.json`、`during-background-check.json` 和前后 UI XML。
本次已运行代码只逐采样保存 processPresent，未逐采样保存 Activity 前后台；最终脚本已增加后者 guard，
不能反过来宣称旧记录包含它。trim 是系统回调请求，不是自然内存不足或真实 LMK。此次没有持续收音，
不能外推过夜 standby、长期 ambient 或电量表现。

```sh
python3 scripts/test-background-lifetime.py --serial YOUR_IDLE_E2 --seconds 900
```

## 用户报告的实际后台断网恢复故障

真实故障时，App 的本地 Tailscale 为 Running，但房间一直重试；本机 Tailscale 看到 E2 离线、房间主机在线，
房间健康接口为 HTTP 200。App 已记录网络恢复，tsnet 却持续报 `magicsock: network down`。原重连只做
socket rebind/restun，没有要求网络监视器重新采样。修复为先调用 `NetMon.InjectEvent()`，按真实接口
状态恢复引擎。诊断时仅重启 Pocket E2 即恢复原连接，未注销或重新配对。

修复构建 `ac0c339043ef93776a5f219ecc6068b173d46167c7b6b5503bb8752880fab001` 已覆盖安装到 E2。
仅执行一次相关实际路径：后台断开 Wi-Fi、后台恢复 Wi-Fi、回前台；原进程未重启，约 2,698ms 后房间在线，
本机 Tailscale 确认 E2 在线。Wi-Fi 原设置已恢复；未发送消息/通知或开始采集。Go vet/test 和构建通过，
没有重复 UI/无障碍或完整功能回归。证据为 ignored `.build/connection-incident/fixed-recovery.json`、
前后 UI XML 及本地诊断。短时恢复通过不能外推长时间待机；8 小时未完成记录保持不变。

## 仍需真实条件的验收

| 场景 | 当前缺口及继续条件 |
| --- | --- |
| 自然语音 | 需要真实人声进入麦克风，验证识别和可听回复；既有合成 fixture、静音播放和 Try 不替代 |
| Sight | 新增实际服务探针到达真实 master seat 并上传静音，服务器仍未确认 media=1，结果 NOT_RUN、0 视频帧；需服务支持后再做 ask/帧/关闭和可识别画面 |
| Session notify | 重新查询当前自有 room 仍不满足通知资格，probe 为 NOT_RUN，未发出通知；需明确自有可收件目标 |
| 地点和到达 | E2 当前缺少系统 Geocoder、无 enabled provider；需可用位置源/后端与真实停留，验证 arrival header/settlement 和后台行为 |
| 身份恢复 | 当前一次性配对链接已消费，未在缺少恢复路径时注销或撤销现有凭据 |
| 跨网络与音频 | 当前没有第二条实际网络和连接的外部音频设备；不能以断网重连或模拟路由替代 |
| 长期后台/低内存 | 短时后台、trim 回调、force-stop 是不同证据；仍需过夜/长期 ambient、自然进程回收和能耗测试 |

实际 Sight 复现：

```sh
ANDROID_SERIAL=YOUR_IDLE_E2 POCKET_E2_TEST_SIGHT_ROOM_ONLY=1 POCKET_E2_TEST_CAPTURE=1 POCKET_E2_TEST_CAMERA=1 scripts/test-device.sh
```

探针不注入 camera 控制，不发送消息；音频字节替换为静音，仅收到真实 ask 并 opt-in 后才允许原生视频通过。
本次 9 项前置/资源释放/房间麦克风恢复检查通过，但视频能力未提供，因此整体为 NOT_RUN。
证据：`.build/acceptance/sight-room/result.json`、`sight-room-run.txt`；通知资格复查在
`.build/acceptance/notify-room-recheck.txt`。没有为验证通知而改选其他人的会话。

8 小时真实 E2 idle 后台观察**提前终止、未通过，不重启**，独立证据在 `.build/acceptance/lifetime-8h/`。
387 次采样进程均存在；最后一次在 5,887.24 秒（约 98 分钟）检测到 App 前台 Activity，runner 以 exit 1
结束。上一次后台样本为 5,871.97 秒；尚未请求计划中的 4 小时 trim，也未执行 8 小时末 Online 检查。
原始 `result.json` 不含完成结果，终止输出另存 `terminal-status.json`；没有回填通过或猜测回前台的原因。
每个样本同时检查进程和实际 Activity 后台状态，不干扰真实 E2 显示设置。这是连接 USB/ADB 的定时观察，
不能等同拔线能耗、自然 LMK 或持续收音。首次启动时 UIAutomator 没有生成快照，探针现于原 40 秒期限内
重试并仍要求真实 Online 节点；该失败保留为 `initial-launch-failure.json`。

```sh
python3 scripts/test-background-lifetime.py --serial YOUR_IDLE_E2 --seconds 28800 --output .build/acceptance/lifetime-8h
```

## 证据约定

- 每个 runner 报告记录 APK SHA；截图、节点树、日志、实际服务标识和录音保存在 ignored `.build/` 或 `dist/`。
  不把不同摘要报告宣称为同一字节 APK 的全部证据；不提交个人数据。
- 模拟器设置只在隔离模拟器上修改并恢复。真实 E2 的密度、overscan、launcher、息屏时间和 E2Studio 配置保持原状。
- E2 采集前确认 E2Studio 无 RecordingService/WorkService；不抢其 Passport BLE。
- 真实服务发送使用明确标记和一次性 journal，重复运行不重复发送；身份变更先具备可用恢复路径。
- UI、协议准备响应、合成语音、自然声学、无障碍服务和生命周期证据分别记录，保留失败条件与原 deadline。

此前逐功能实现和证据边界保留在 [`migration.md`](migration.md)，UI 架构见 [`ui.md`](ui.md)。
