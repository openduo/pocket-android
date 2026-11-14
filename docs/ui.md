# UI 适配与可维护性

本阶段采用原生 Android View 的窗口适配方案：Window Size Class、窗口内 Insets、内容宽度上限、
原生 View 状态保存和可滚动布局。没有为 E2、手机和平板复制三套业务页面，也没有按设备型号在 controller
中分支。E2 固件的状态栏例外留在 UI 系统适配器中。

## 分层与职责

| 层 | 实现 | 责任 |
| --- | --- | --- |
| 业务与协议 | `PocketController`、纯 Java reducer/codec、SQLite、Go transport | 房间、权限、消息、草稿、outbox、连接与设备资源；不读取窗口尺寸 |
| 纯尺寸策略 | `ui/AdaptiveMetrics` | 当前窗口 dp、字体、宽高类别、控件/媒体/sheet 的设计 token；不依赖 Android 或业务状态 |
| 窗口与容器 | `AdaptivePage`、`AdaptiveColumn`、`InsetsFrame` | 列宽、短窗口/IME 下的页头与编辑器布局、系统栏/键盘 inset |
| 页面 UI 状态 | `PageStates`、原生 View 的固定资源 ID | 旋转/窗口重建时保存每个页面的表单、阅读位置、焦点和选区；业务草稿仍以 controller 为准 |
| 公共控件 | `Ui`、`Sheets`、`ViewportNavigation` | 字体/颜色、至少 48dp 的公共操作控件、可增长按钮、弹层，以及地图/图片的键盘与无障碍手势替代 |
| 页面 | `ui/pages` | 展示 snapshot、转发用户操作、组合上述容器；不自行读取物理面板像素 |

`scripts/check-architecture.py` 检查纯策略不引入 Android、适配容器不引入业务/协议 owner、controller
不引入窗口尺寸，并阻止页面/组件直接读取物理像素。窗口和 IME 策略不应重新散落进业务代码。

## 窗口与字体策略

| 当前窗口宽度 | 类别 | 页面行为 |
| --- | --- | --- |
| `< 600dp` | Compact | 单列占满窗口，包括约 259dp 的 E2 |
| `600–839dp` | Medium | 页面列最多 640dp，居中 |
| `>= 840dp` | Expanded | 页面列最多 720dp，居中 |

底部导航与正文遵守相同列宽上限；sheet 在手机中占满可用宽度，在宽窗口中限制为 640/720dp。页面高度
由父窗口测量，不以物理屏幕高度替代。应用允许旋转和窗口调整。

API 30+ 使用 `WindowInsets` 的 systemBars/displayCutout/ime 和 `isVisible(ime)`。API 26–29 使用
本窗口可见区域的兼容计算；E2 返回零 stable top inset 时保留原有状态栏预留。没有使用整块 display 的
`getRealSize()` 推断分屏键盘。

短窗口或打开 IME 后，`AdaptiveColumn` 会收起辅助页头、将编辑器压到一行；关闭键盘/放大窗口后恢复。
草稿和选区不随布局改变。composer footer 可以滚动，配对表单和位置页也在滚动内容中，地图保留最小可用
高度。按钮和行高允许随文字增长；单行 EditText 使用 View 的 minimumHeight，避免原生 `setSingleLine()`
覆盖 TextView 的最小高度。

## 后续修改方式

1. 新增尺寸或字体规则时，在 `AdaptiveMetrics` 定义有用途的 token，通过 `Ui` 提供给页面。使用当前窗口
   的测量和配置；不加入设备型号、物理分辨率或业务 snapshot 的尺寸分支。
2. 新页面先组合公共列宽、短窗口容器和滚动内容。需要随 IME 移动的编辑器交给公共容器，避免在页面里再
   监听全屏高度。表单保持自然高度，重要操作可滚动到达；宽窗口的正文与导航使用相同列宽。
3. 需要重建后恢复的表单、滚动容器和编辑器使用稳定且唯一的资源 ID，由 `PageStates` 保存原生状态。
   发送内容、待发队列和业务草稿继续由 controller/持久层拥有，不能从旧 View 恢复并覆盖更新的业务值。
4. 页面只渲染 snapshot、转发操作并保存短期 UI 状态。公共控件提供标签、角色、选中状态和键盘/无障碍
   action；需要拖动的自定义 View 同时提供非手势操作。不要把网络、数据库或采集资源交给页面管理。
5. 公共布局策略修改后运行窗口/字体及旋转/多窗口 runner；语义和焦点修改后运行实际无障碍服务专项。
   真实 E2 的输入、相机或生命周期变化另做对应专项，保留各份报告的 APK 摘要与真实/准备响应边界。

这是在现有原生 View 技术栈内使用 Android 窗口和状态机制的分层方案。未来更换 UI 技术时，纯业务 reducer、
transport 和持久层不需要依赖新的窗口组件；验收脚本和差距文档可以继续作为迁移依据。

## 验证结果

- API 35 arm64：22 组真实 Activity 截图和 IME 验收，9,616 项几何检查通过。手机 360×740dp 与平板
  1280×800dp 覆盖字体 0.85、1.0、1.15、1.3、1.5、1.8、2.0；另含 320×640、600×960、840×600、
  740×360、600×320，以及中英文/明暗主题组合。内容包括 Try 的长回复、Markdown 表格、工具与文件，
  八个页面、会话详情、附件 sheet、编辑 sheet，以及对话/会话/配对/位置/弹层的真实 IME。
- 真实 Activity 重建和显示旋转的 20 项检查通过：配对字段、选区、隐藏页面表单、controller 草稿和设置阅读位置。
  横屏 740×360dp、竖屏 360×740dp，当前窗口边界与最大显示边界一致，排除了竖屏中的信箱窗口。
- 真正的自由窗口调整的 8 项检查通过：600×600、920×700、360×660dp，`isInMultiWindowMode=true`，
  草稿保留。系统 Recents 的真实双应用分屏的 12 项检查通过：早期 635×800dp、最终 runner 800×635dp，
  均留八个页面的实际 pane 配置和截图。
- 实际 TalkBack 15.0 扩展运行的 404 项检查通过，包含 12 个场景及原始触屏双击/长按；实际 Google 开源
  Switch Access 6.1.1 的 113 项页面扫描/顺序、长内容、服务滚动及详情/弹层激活通过。后者不是当前
  Google Play 版本。服务、原始输入、焦点轨迹与限制见 [`accessibility.md`](accessibility.md)。
- 两种实际服务另通过相机、麦克风、定位、通知权限的拒绝/重试/返回和粗略位置升级；真实 DocumentsUI
  两次返回取消保留草稿/选区，分别 25 项通过。测试服务补充现代 Android 的辅助工具声明；详细检查数、
  原始证据和一次性/永久拒绝/外部操作重建等未覆盖边界见 [`accessibility.md`](accessibility.md)。
- 325 项 host contracts、架构/本地化、Go vet/test 和 Android 构建通过。上一 APK 检查点的 E2 完整采集/相机/Try
  回归 342 项通过；移除重复 feed 朗读节点后，当时 APK 无障碍专项重新通过 27 项。最新权限重建修复仅装到
  模拟器，E2 8 小时后台任务保留旧包。真实 E2 的显示密度、
  overscan、launcher 和 E2Studio 配置保持原状。
- 后续修复重建后实际完整授权被误报拒绝的问题；两服务相机/麦克风/通知旋转和 TalkBack 文件取消重建通过。
  定位旋转未通过，其余未跑组合继续开放；详细 APK 身份与边界见 [`accessibility.md`](accessibility.md)。

这轮验收实际修复了强制竖屏、宽屏导航被拉满、短横屏大字按钮裁字、IME 遮住编辑器、地图高度被挤成零、
配对固定底栏挤掉表单，以及旋转丢失 UI 状态的问题。首次完整 E2 回归遇到音频 probe 先检查 Listening、
后立即要求首包已到的竞态；等待条件现与原断言一致，仍使用原来的 7 秒期限，随后 342 项通过。

## 复现与后续边界

```sh
scripts/test-host.sh
scripts/build-android.sh
python3 scripts/test-ui-matrix.py --serial YOUR_EMULATOR
python3 scripts/test-window-ui.py --serial YOUR_EMULATOR
```

模拟器脚本拒绝物理设备，并在退出时恢复所改的窗口、字体、旋转和窗口开发设置。一个模拟器只能被一个
验收脚本占用。截图、JSON、APK 摘要和失败记录保存在 ignored 的 `.build/acceptance/`。

本阶段验证了这些具体窗口和服务条件，尚未覆盖所有 OEM、刘海/折叠屏、外接显示器、所有硬件键盘组合、
所有弹层/系统权限对话框的无障碍返回，以及进行中的配对/外部操作在任意配置切换下的全部生命周期。
它们作为未来兼容性验收记录保留，不需要在业务 controller 中加入设备尺寸分支。当前实现和剩余功能差异见
[`status.md`](status.md) 与 [`migration.md`](migration.md)。
