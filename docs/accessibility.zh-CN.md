# 无障碍交互与实际服务验收

更新日期：2026-11-14。公共控件的语义、键盘路径和实际服务使用同一业务操作；所有无障碍相关尺寸、
View 语义和窗口适配留在 UI 层。全局无障碍设置和原始输入只由隔离模拟器 QA runner 修改。

## 已实现的交互

主对话、导入取消、回到底部和共享 sheet 关闭操作使用实际至少 48dp 的布局边界。单纯设置 minimum
不能覆盖父布局中固定的 44dp，因此旧的固定约束已删除；文字按钮可随大字增长，录音按钮可换行。
单行 EditText 在 setSingleLine 之后设置 View minimumHeight，避免原生 TextView 重置其高度。

公共文字/图标/行操作提供 Android Button 角色，底部导航提供 selected 状态并隐藏重复子标签。设置开关
以一行暴露标题、说明、checkable/checked/enabled，内部 Switch 与文字不再重复成为焦点。附件缩略图有名称
和键盘焦点，消息气泡提供具名“消息操作”长按 action。对话 feed 保留触摸焦点，但不作为独立朗读节点，
避免先朗读整段历史再逐条重复；子消息仍可分别访问。

地图和图片由公共 `ViewportNavigation` 提供方向与缩放 action，DPAD 和 +/-/PageUp/PageDown 也可操作。
不要求用户必须使用拖动或双指手势。

| 录音输入方式 | 行为 |
| --- | --- |
| 普通触摸 | 按住录音，移出进入取消状态，松开发送或取消 |
| 键盘/无障碍 click | 激活开始，再激活结束并发送 |
| 具名“取消录音”action / Escape | 结束当前录音，不发送 |
| View detach / 窗口失焦 | 取消录音和待处理权限意图 |

录音权限 callback 绑定一次 activation generation，旧授权不能启动后来意图，View detach 后不能取得
麦克风。键盘/开关激活无需启用 touch exploration；普通触摸松开也不会额外触发一次新录音。

## 实际 TalkBack

在 API 35 Google APIs arm64 隔离模拟器启用真实 **TalkBack 15.0.0.639625893**。
八主页面（onboarding、pairing、conversation、ambient、settings、sessions、location、diagnostics）、
附件 sheet、消息操作、会话详情和编辑 sheet 共 **12 个场景、404 项检查通过**。准备四轮 Try 长内容，
包含工具、Markdown 表格和文件；逐页要求关键控件可到达且首次完整阅读顺序连续。旧基线为 241 项。
实际焦点停靠数量随服务运行略变，完整轨迹保存在每次 JSON，不用固定总数代替覆盖。

QA 使用原始 evdev 触屏滑动，经过 InputReader 和 accessibility input filter，由 TalkBack 自己移动焦点。
ADB `input swipe` / InputManager 注入绕过了该过滤器，不能充当此类手势证据；直接 perform accessibility
focus 也没有被用来冒充遍历。`emulator-input.c` 将一次滑动的事件时间控制在约 105ms，避免 shell 的逐事件
延迟把滑动变成探索。Instrumentation 使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，记录真实
焦点事件并读取当前焦点；分组标签递归读取子文字。

双击与双击并按住同样经过原始触屏输入，位置取当前真实 accessibility focus 的显示边界。实际完成了打开
消息菜单、关闭消息菜单/附件 sheet、提交编辑文本和体验模式会话通知；通知只进入 Try transport，不是实际
session notify 投递验收。消息操作专项另有 36 项通过。早期在固定显示中心长按没有打开菜单，已修正输入
位置；设置页到末端后平台可回绕到可见后缀，因此顺序检查针对首次完整 pass，所有关键控件仍必须到达。

证据：`.build/acceptance/accessibility/talkback/{run.json,screen-reader/}`。轨迹保留 class、label、bounds、
selected/checkable 状态和截图，检查可见且有标签、连续移动、导航/编辑/开关/地图操作可到达。

## 实际 Switch Access：旧版源码服务

Google 官方 API 35 镜像未提供 Switch Access，当前 Google Play APK 在本环境不可得。测试使用官方
[google/talkback 6.1.1](https://github.com/google/talkback/tree/fa67c12fce7242b97ce864c4c6b86a0b758e6332)
中的实际 Switch Access 服务，固定源码 SHA：`fa67c12fce7242b97ce864c4c6b86a0b758e6332`。

构建脚本调整 Gradle、namespace 和独立 QA manifest，并补充现代 Android 的 `isAccessibilityTool=true`
服务声明，**未修改上游 Java 扫描行为**。QA 包名为
`dev.openduo.pocket.switchqa`，包含服务所需 WAKE_LOCK/VIBRATE，不进入产品 APK。使用 NDK 测试 helper
注册 uinput USB 键盘，A 为 next、B 为 select，C/D 映射服务原生向前/向后滚动，经原始 InputReader
送入 Switch Access。本次实际执行向前滚动，不以直接调用 `View.scrollTo` 替代服务操作。

扩展运行 **113 项通过**：八页关键控件可达性和顺序、长对话的编辑器/发送/导航、设置页两次服务滚动至末端，
会话详情、编辑 sheet 和操作 sheet。实际选择体验模式通知、编辑确认和恰好一次操作回调；通知不作为真实
session notify 验收。旧基线为 11 项。

扫描框来自实际服务，匹配 native View 的可见裁剪边界并转换到显示坐标，兼容页面和独立 Dialog 窗口。
长内容保留较大的有限扫描预算，除了扫描顺序，还要求每个关键操作实际出现；不把“有多个焦点”当成完整
页面通过。退出时关闭 uinput 文件描述符，移除测试键盘，恢复启用的服务。

证据：`.build/acceptance/accessibility/switch/{run.json,switch-ui/}`，源码和构建摘要在
`.build/acceptance/accessibility/switch-source.json`。这证明旧版服务在上述页面的扫描/滚动/激活；
**不等于当前 Google Play Switch Access、全部滚动子树或所有系统对话框已验收**。位置页未选点时的
“使用这个位置”按钮是禁用状态，未将其计为可扫描操作；只读设置说明也不要求成为 Switch 停靠点。

## 实际系统权限与文件选择器

另一个专项通过实际服务激活配对页的扫码按钮，进入原生 Permission Controller，遍历“使用应用时允许”、
“仅限这一次”和“拒绝”的顺序；先拒绝并关闭 app 的权限说明，再次请求、实际授权，打开扫码相机面板并
关闭返回配对表单。早期为 TalkBack 46 项、Switch Access 33 项；扩展 runner 复跑为 **50/37 项通过**。
记录真实系统节点、原始输入和截图，
没有用准备的权限 callback 或直接 node click 替代。

Android 第二次请求把拒绝控件改为 `permission_deny_and_dont_ask_again_button`，测试保留实际 ID，按同一
拒绝选项校验顺序。配对字段的 hint 和较深的原生层级也纳入返回条件，不以截短的聚合文字判断页面消失。
这些是测试检测方式的修正，实际授权决定仍由 Permission Controller 处理。

最初旧版 Switch QA 服务只有自身菜单可扫描，无法取得权限决策节点。
[Android 14 的敏感无障碍节点](https://developer.android.com/reference/android/R.attr#accessibilityDataSensitive)
仅向声明为辅助工具的服务提供；固定源码发布时尚无 API 31 的 `isAccessibilityTool` 属性。补充该真实
辅助服务的元数据后，三个选项和实际拒绝/授权流程通过。失败轨迹在 ignored
`.build/acceptance/accessibility/switch-permissions-legacy-metadata-trial/`；旧构建摘要保存在
`switch-source-before-tool-metadata.json`，新元数据由 `prepare-switch-access.py` 可重复生成。
同一新构建随后完整页面回归再次通过 113 项，旧完整结果另存 `switch-before-tool-metadata/`。

扩展 `PermissionServiceProbe` 共通过下列专项。检查数包含实际扫描的系统焦点，随一次遍历稍有变化，
不能据此推导覆盖率。下面这批正常流程检查没有修改生产 APK。

| 实际流程 | TalkBack | Switch Access | 已执行边界 |
| --- | --- | --- | --- |
| 相机权限 | 50 | 37 | 系统拒绝、app 说明、重试前台授权、真实扫码 sheet 关闭返回 |
| 麦克风权限与取消 | 54 | 42 | 拒绝/重试，授权返回不自动录音，新激活采集，打开附件 sheet 取消并保留草稿 |
| 定位权限与精度升级 | 82 | 65 | 拒绝/重试、粗略授权、实际升级精确授权、后台权限单独提示和返回 |
| 通知权限 | 45 | 33 | 开关请求、拒绝、关闭说明、状态行重试授权、偏好和焦点保留 |
| 外部文件选择器 | 25 | 25 | 服务打开 DocumentsUI，两次系统 Back 取消，选区/草稿/空导入状态及菜单重新可达 |

麦克风和文件专项使用一次性 Try 房间，保留原房间草稿，拒绝已配置真实房间的模拟器。授权窗口失焦后旧的
录音 activation 不会复活；实际服务重新激活后能采集，打开另一个窗口取消采集，没有产生消息或待发送项。
这是实际权限/原生采集/取消证据，没有自然人声或真实 ASR/回复验收。

到达检测按现有精确停留规则要求 fine + background；粗略授权后保持暂停符合规则。随后真实 Permission
Controller 提供“Change to precise location”升级决定；这个弹窗没有初次申请的精度 radio 控件，测试按
真实可见决定操作，验证系统最终授予 fine，并由 app 单独提示后台定位。没有授予后台权限、注入 fix 或
发送 visit。通知专项同样没有触发任何实际通知投递。

文件取消通过平台 Back 键返回真实外部 Activity，没有准备 ActivityResult；Back 未记作服务手势或服务
系统返回菜单操作。两轮后原生编辑器选区和业务草稿保留，chips/imports/outbox 为空，真实服务焦点能返回
app，并可再次打开和关闭附件菜单。选择文件、相机外部 Activity 及其重建仍属于其他验收。

证据根目录：`.build/acceptance/accessibility/`。相机沿用 `{talkback,switch}-permissions/`；其他权限为
`{talkback,switch}-permissions-{microphone,location,notifications}/`；文件为 `{talkback,switch}-picker/`。
每个目录保留 `run.json`、原生节点 ID/焦点轨迹、截图和 instrumentation 输出。早期相机报告存于
`*-permissions-camera-baseline/`，没有把复跑计数回写进早期报告。
本轮 10 个专项的报告索引为 ignored `permissions-summary.json`，共 458 项；不与完整页面遍历计数合并。

退出时恢复语言、相关设置/到达 ledger、原权限授权和 USER_SET/USER_FIXED 标记、无障碍服务及临时通知权限，
并移除测试键盘。清理命令全部尝试执行，失败会把最终运行判为失败。最终相机/定位复跑记录 cleanup 完成；其余
较早报告保留当时字段，最终权限/服务恢复状态另存 ignored `permissions-restoration.json`。

扩展过程中的 QA 失败原样保留：通知首次缺少服务滚动，随后只读到返回过程的第一个空扫描焦点；TalkBack
始终向前移动会跳过仍然聚焦的开关，回绕到当前可见后缀后找不到它；定位升级弹窗本身没有初次申请的 radio。
这些分别通过服务滚动、有限返回焦点扫描、先检查当前焦点及读取真实升级控件修正。没有修改产品代码来绕过
测试。证据位于 `*-no-scroll-trial/`、`*-single-focus-trial/`、`*-current-focus-trial/`、
`*-upgrade-control-trial/`。
清理步骤另有一次误用消息 revision 等待配置完成的失败，保留于 `*-cleanup-barrier-trial/`；最终改为
在 controller 自身状态队列中等待停用到达检测、原样恢复偏好并核对全部键值。该队列访问只用于 QA 收尾，
系统权限决定仍全部通过实际服务输入完成。

## 权限与外部窗口中的实际旋转

后续 `--recreate` 专项在真实 Permission Controller 或 DocumentsUI 打开时旋转实际显示，核对替换后的
Activity、旧实例销毁、窗口边界及未发送表单状态。DocumentsUI 下的后台 Activity 可能延后到返回时重建，
按实际生命周期核对，不要求后台立即重建。

这次发现并修复产品问题：完整授权返回重建后的 Activity 时，旧的 `permissionAction` 已不存在，原实现
因此显示拒绝说明。现在刷新设置状态并提示“权限已允许，请再次操作”，由新页面重新激活操作，避免复活
引用旧 View 的扫码或录音回调。新 APK 的摘要为
`ac81daba246f9291feda24fbff27685fa5bac03e3de4225a992502a6a9e93642`。

| 重建流程 | TalkBack | Switch Access | 实际边界 |
| --- | --- | --- | --- |
| 相机拒绝/重试/授权 | 63 项通过 | 53 项通过 | 授权提示、无错误拒绝说明、重新激活扫码、表单/选区保留 |
| 麦克风拒绝/重试/授权 | 64 项通过 | 54 项通过 | 旧采集意图不复活，新激活采集和取消保留草稿 |
| 通知拒绝/重试/授权 | 57 项通过 | 46 项通过 | 实际授权提示、设置状态刷新、偏好保留 |
| DocumentsUI 两次取消 | 39 项通过 | 新 APK 未运行 | 实际显示旋转、返回重建、草稿/选区保留 |
| 定位粗略授权/精度升级 | 未运行 | 未通过 | 未收到通用完整授权重试提示，后续升级断言未执行 |

定位失败保留于 `switch-permissions-location-recreation/`：初次实际选择粗略授权，fine 仍拒绝，产品
按“全部所需权限获准”判断，因此不进入完整授权提示分支。这份失败不计为通过，也不推导后续精确升级
或 visit 可用。旧 APK 的 `switch-picker-recreation/` 曾通过 39 项，不能归到新 APK。
各目录清理均已完成，恢复原权限/标记、服务和旋转策略。当前停止扩展和复跑，不把此表补成完整组合。

TalkBack 另有两次 instrumentation 后 ADB 短暂断开：一次采集报告失败，一次检查本身通过但拉取/清理失败。
原运行均保留失败，恢复后按原值完成清理，另写 `cleanup-recovery.json`；最终成功运行不覆盖这些证据。
目录为 `talkback-permissions-recreation-transport-failure/` 和
`talkback-picker-recreation-collection-failure/`。runner 仅对明确设备暂不可用的 ADB 命令做有限重试并记录，
不重试 instrumentation；失败或清理不全仍判失败。

该权限专项时 E2 未安装这次修复；后续网络恢复修复的合并构建已更新 E2，只验证连接恢复，没有重复权限矩阵。
已终止的后台任务使用旧 APK
`4c5adef70e036b9ec7b768809532564a5bab69dd190c35d341f36e5074652e72`。
后台观察约 98 分钟时因 App 回到前台提前结束，不重启；不计 8 小时通过。旧包和身份说明在 ignored
`.build/acceptance/pre-permission-recreation/`。以前的完整页面遍历、E2 回归和
正常权限流程均保留各自 APK 身份，不能合并为最新 APK 的完整回归。

## 真 E2 原生专项

```sh
ANDROID_SERIAL=YOUR_IDLE_E2 POCKET_E2_TEST_ACCESSIBILITY_ONLY=1 POCKET_E2_TEST_CAPTURE=1 scripts/test-device.sh
```

**27 项原生断言通过**，包括真实 Activity、DPAD/AccessibilityNodeInfo、E2 麦克风和 JNI Opus、实际
48dp 控件测量及一次性 sheet 激活。第二次键盘激活把采集的 Opus 交给准备的本地 transport；无障碍取消
释放采集而不再产生 note。此测试不发送真实消息，仅本地 AudioTrack 静音，不改变系统音量。

权限 callback 由 adapter 准备，旧授权/detach 拒绝在 controller owner barrier 后检查；它不是系统权限
对话框测试。完整 E2 采集/相机/paired-Try 回归在上一 APK 检查点通过 342 项；移除重复 feed 朗读节点后，
当时 APK 的原生无障碍专项重新通过 27 项，没有把此前 342 项归到新摘要下。最新权限重建修复未在 E2 复跑。

## 复现

需要 API 35+、可 root 的 arm64 Google APIs 隔离模拟器和 Android NDK。TalkBack 必须已在系统镜像中安装；
`--installed` 只在已经装好匹配 app/probe 时使用。

```sh
scripts/build-android.sh
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback
# Focused raw-input message-menu check; the full run already includes this scenario.
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback --scene message-actions
python3 scripts/prepare-switch-access.py --serial YOUR_ROOTED_API35_EMULATOR
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service switch
# Actual camera permission denial, grant and app return through either service.
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback --scene permissions
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service switch --scene permissions
# Other native dialogs: use the same --permission value with either service.
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback --scene permissions --permission microphone
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback --scene permissions --permission location
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback --scene permissions --permission notifications
# Native external picker cancellation with either service.
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback --scene picker
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service switch --scene picker
# Rotate the real display while the native dialog or picker is open.
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback --scene permissions --recreate
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback --scene picker --recreate
```

runner 拒绝真实设备，共享 UI 验收锁，保存/恢复原无障碍服务和临时授予的 TalkBack 通知权限。服务源码和
NDK helper 仅在 ignored `.build/` 编译，不是产品依赖。当前 TalkBack helper 使用此镜像的 touchscreen
`/dev/input/event1` 和坐标范围；换镜像需先核对 getevent 信息，不能无条件外推。
每次起跑删除旧场景缓存；超时会停止隔离模拟器中的目标 app，保留失败报告，退出时清理服务和测试键盘。
runner 核对实际安装 app/probe 与本地构建摘要相同，另记录实际服务 APK 摘要；退出清理完成后才输出 PASS。
旧报告只保留起跑时已经实现的字段；早期服务摘要作为独立 `service-identity.json` 记录，
不回写成当时 runner 已经产生的字段。

## 尚未覆盖

本次实际服务遍历覆盖上述 12 个场景及准备的长内容。其余历史消息/附件/长会话折叠、所有滚动层级、
其他弹层、一次性授权实际选择/永久拒绝/设置撤权/后台位置授权等权限组合、外部 Activity 配置变化以及当前
Play 版 Switch Access 仍需扩大覆盖。现有文件专项不接受完整 DocumentsUI 操作或真实文件选中。
字体/窗口矩阵是独立 UI 验证，不能宣称每一组都执行了两种服务的完整组合。Escape 有实现，采集专项重点
执行了 DPAD click 与具名取消 action。自然声学 ASR 与可听回复仍单独开放。
