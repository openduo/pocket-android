# 无障碍交互与实际服务验收

更新日期：2026-11-14。公共控件的语义、键盘路径和实际服务使用同一业务操作；所有无障碍相关尺寸、
View 语义和窗口适配留在 UI 层。全局无障碍设置和原始输入只由隔离模拟器 QA runner 修改。

## 已实现的交互

主对话、导入取消、回到底部和共享 sheet 关闭操作使用实际至少 48dp 的布局边界。单纯设置 minimum
不能覆盖父布局中固定的 44dp，因此旧的固定约束已删除；文字按钮可随大字增长，录音按钮可换行。
单行 EditText 在 setSingleLine 之后设置 View minimumHeight，避免原生 TextView 重置其高度。

公共文字/图标/行操作提供 Android Button 角色，底部导航提供 selected 状态并隐藏重复子标签。设置开关
以一行暴露标题、说明、checkable/checked/enabled，内部 Switch 与文字不再重复成为焦点。附件缩略图有名称
和键盘焦点，消息气泡提供具名“消息操作”长按 action，避免整行重复朗读正文。

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
八主页面（onboarding、pairing、conversation、ambient、settings、sessions、location、diagnostics）加
附件 sheet 的实际遍历通过。最初运行 240 项，最终可重复 runner 为 **241 项通过**；实际焦点停靠数量随
服务运行略变，完整轨迹保存在每次 JSON，不用固定总数代替覆盖。

QA 使用原始 evdev 触屏滑动，经过 InputReader 和 accessibility input filter，由 TalkBack 自己移动焦点。
ADB `input swipe` / InputManager 注入绕过了该过滤器，不能充当此类手势证据；直接 perform accessibility
focus 也没有被用来冒充遍历。`emulator-input.c` 将一次滑动的事件时间控制在约 105ms，避免 shell 的逐事件
延迟把滑动变成探索。Instrumentation 使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，记录真实
焦点事件并读取当前焦点；分组标签递归读取子文字。

证据：`.build/acceptance/accessibility/talkback/{run.json,screen-reader/}`。轨迹保留 class、label、bounds、
selected/checkable 状态和截图，检查可见且有标签、连续移动、导航/编辑/开关/地图操作可到达。

## 实际 Switch Access：旧版源码服务

Google 官方 API 35 镜像未提供 Switch Access，当前 Google Play APK 在本环境不可得。测试使用官方
[google/talkback 6.1.1](https://github.com/google/talkback/tree/fa67c12fce7242b97ce864c4c6b86a0b758e6332)
中的实际 Switch Access 服务，固定源码 SHA：`fa67c12fce7242b97ce864c4c6b86a0b758e6332`。

构建脚本只替换 Gradle、namespace 和独立 QA manifest，**未修改上游 Java 行为**。QA 包名为
`top.kuaner.pockete2.switchqa`，包含服务所需 WAKE_LOCK/VIBRATE，不进入产品 APK。使用 NDK 测试 helper
注册 uinput USB 两键键盘，A 为 next、B 为 select，经原始 InputReader 送入 Switch Access。

八页实际扫描和共享 sheet 恰好一次激活，**11 项通过**。扫描框来自实际服务，截图位置与 native View
的屏幕边界对齐；不是只对 node 调用 click。退出时关闭 uinput 文件描述符，移除测试键盘，恢复启用的服务。

证据：`.build/acceptance/accessibility/switch/{run.json,switch-ui/}`，源码和构建摘要在
`.build/acceptance/accessibility/switch-source.json`。这证明旧版服务的基本扫描/激活；
**不等于当前 Google Play Switch Access、全部滚动子树或所有系统对话框已验收**。

## 真 E2 原生专项

```sh
ANDROID_SERIAL=YOUR_IDLE_E2 POCKET_E2_TEST_ACCESSIBILITY_ONLY=1 POCKET_E2_TEST_CAPTURE=1 scripts/test-device.sh
```

**27 项原生断言通过**，包括真实 Activity、DPAD/AccessibilityNodeInfo、E2 麦克风和 JNI Opus、实际
48dp 控件测量及一次性 sheet 激活。第二次键盘激活把采集的 Opus 交给准备的本地 transport；无障碍取消
释放采集而不再产生 note。此测试不发送真实消息，仅本地 AudioTrack 静音，不改变系统音量。

权限 callback 由 adapter 准备，旧授权/detach 拒绝在 controller owner barrier 后检查；它不是系统权限
对话框测试。完整 E2 采集/相机/paired-Try 回归另通过 342 项。

## 复现

需要 API 35+、可 root 的 arm64 Google APIs 隔离模拟器和 Android NDK。TalkBack 必须已在系统镜像中安装；
`--installed` 只在已经装好匹配 app/probe 时使用。

```sh
scripts/build-android.sh
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service talkback
python3 scripts/prepare-switch-access.py --serial YOUR_ROOTED_API35_EMULATOR
python3 scripts/test-accessibility-services.py --serial YOUR_ROOTED_API35_EMULATOR --service switch
```

runner 拒绝真实设备，共享 UI 验收锁，保存/恢复原无障碍服务和临时授予的 TalkBack 通知权限。服务源码和
NDK helper 仅在 ignored `.build/` 编译，不是产品依赖。当前 TalkBack helper 使用此镜像的 touchscreen
`/dev/input/event1` 和坐标范围；换镜像需先核对 getevent 信息，不能无条件外推。

## 尚未覆盖

本次实际服务遍历覆盖上述页面的当前可到达内容和附件 sheet。所有历史消息/附件/长会话折叠、所有滚动层级、
所有弹层和系统权限对话框、跨外部 Activity 的返回/取消以及当前 Play 版 Switch Access 仍需扩大覆盖。
字体/窗口矩阵是独立 UI 验证，不能宣称每一组都执行了两种服务的完整组合。Escape 有实现，采集专项重点
执行了 DPAD click 与具名取消 action。自然声学 ASR 与可听回复仍单独开放。
