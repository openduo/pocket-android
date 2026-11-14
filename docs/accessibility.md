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
`top.kuaner.pockete2.switchqa`，包含服务所需 WAKE_LOCK/VIBRATE，不进入产品 APK。使用 NDK 测试 helper
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

## 实际系统相机权限

另一个专项通过实际服务激活配对页的扫码按钮，进入原生 Permission Controller，遍历“使用应用时允许”、
“仅限这一次”和“拒绝”的顺序；先拒绝并关闭 app 的权限说明，再次请求、实际授权，打开扫码相机面板并
关闭返回配对表单。**TalkBack 46 项、Switch Access 33 项通过**。记录真实系统节点、原始输入和截图，
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

证据：`.build/acceptance/accessibility/{talkback-permissions,switch-permissions}/`。专项保留 app 草稿和
未配对状态；退出时恢复原相机授权和 USER_SET/USER_FIXED 决定标记、无障碍服务及临时通知权限。此项只覆盖
系统相机权限的拒绝/前台授权与返回，不接受真实 Sight、麦克风/地点/通知等其他系统权限流程。

## 真 E2 原生专项

```sh
ANDROID_SERIAL=YOUR_IDLE_E2 POCKET_E2_TEST_ACCESSIBILITY_ONLY=1 POCKET_E2_TEST_CAPTURE=1 scripts/test-device.sh
```

**27 项原生断言通过**，包括真实 Activity、DPAD/AccessibilityNodeInfo、E2 麦克风和 JNI Opus、实际
48dp 控件测量及一次性 sheet 激活。第二次键盘激活把采集的 Opus 交给准备的本地 transport；无障碍取消
释放采集而不再产生 note。此测试不发送真实消息，仅本地 AudioTrack 静音，不改变系统音量。

权限 callback 由 adapter 准备，旧授权/detach 拒绝在 controller owner barrier 后检查；它不是系统权限
对话框测试。完整 E2 采集/相机/paired-Try 回归在上一 APK 检查点通过 342 项；移除重复 feed 朗读节点后，
当前 APK 的原生无障碍专项重新通过 27 项，没有把此前 342 项归到新摘要下。

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
```

runner 拒绝真实设备，共享 UI 验收锁，保存/恢复原无障碍服务和临时授予的 TalkBack 通知权限。服务源码和
NDK helper 仅在 ignored `.build/` 编译，不是产品依赖。当前 TalkBack helper 使用此镜像的 touchscreen
`/dev/input/event1` 和坐标范围；换镜像需先核对 getevent 信息，不能无条件外推。
每次起跑删除旧场景缓存；超时会停止隔离模拟器中的目标 app，保留失败报告，退出时清理服务和测试键盘。
runner 另记录实际安装的服务 APK 摘要；早期报告的服务摘要作为独立 `service-identity.json` 记录，
不回写成当时 runner 已经产生的字段。

## 尚未覆盖

本次实际服务遍历覆盖上述 12 个场景及准备的长内容。其余历史消息/附件/长会话折叠、所有滚动层级、
其他弹层和其余系统权限对话框、跨外部 Activity 的返回/取消以及当前 Play 版 Switch Access 仍需扩大覆盖。
字体/窗口矩阵是独立 UI 验证，不能宣称每一组都执行了两种服务的完整组合。Escape 有实现，采集专项重点
执行了 DPAD click 与具名取消 action。自然声学 ASR 与可听回复仍单独开放。
