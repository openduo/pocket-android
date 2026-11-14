# UI 适配与可维护性

Pocket E2 的页面使用原生 Android View，但页面业务类不再根据物理像素决定布局。尺寸策略集中在
`ui/AdaptiveMetrics.java`，页面容器集中在 `ui/AdaptivePage.java`，公共控件通过 `Ui` 暴露设计 token。
这样同一套页面可以在当前 E2 小屏、普通手机、横屏、多窗口和平板窗口中复用，连接、房间和草稿状态不需要
知道窗口尺寸。

## 窗口策略

窗口宽度使用 Android Window Size Class 的断点：

| 宽度 | 类别 | 页面行为 |
| --- | --- | --- |
| `< 600dp` | Compact | 页面占满窗口，优先保证单列和键盘可用 |
| `600–839dp` | Medium | 页面列和 bottom sheet 限制在 640dp 以内并居中 |
| `>= 840dp` | Expanded | 页面列和 sheet 限制在 720dp 以内并居中 |

`AdaptiveMetrics` 同时根据窗口高度和 `fontScale` 计算控件高度、最小行高、底部导航、viewfinder、
内联图片、二维码、键盘判定阈值和附件名称上限。所有触控目标至少 48dp；大字体提高行高和控件高度，
短屏会把 sheet 高度限制在当前窗口内。配置缺失或异常时会使用安全的 dp 和 fontScale 默认值。

`AdaptivePage` 在中等和宽窗口中只约束页面内容列，外层仍然占满窗口，因此弹层、系统栏和页面切换不会
依赖某一台设备的物理分辨率。页面只应使用 `Ui` 的 token（例如 `touchTarget()`、`rowHeight()`、
`pagePadding()`、`sheetWidth()`、`inlineImageWidth()`），不要读取 `DisplayMetrics.widthPixels` 或
写死用于交互的 48/50/52/56dp 高度。

## 已完成和验证

- 连接、历史、outbox、语音 handoff、ambient、Sight、附件、位置、arrival、后台上传、会话查看器和
  兼容性提示等 P0/P1 业务仍由原来的 controller/state owner 管理，页面容器只负责尺寸和可见性。
- 对话、环境、设置、会话、位置、配对、二维码、登录 sheet、底部导航和 hold-to-talk 已接入公共尺寸
  token；输入框、附件图片/名称、viewfinder、sheet 和二维码不再从页面读取物理像素。
- `tests/host/AdaptiveMetricsContracts.java` 覆盖 64/320/360/599/600/839/840dp 断点、宽度上限、
  48dp 目标、大字体、短屏 sheet、媒体和二维码边界。`scripts/test-host.sh` 当前通过 325 项 host
  contract assertions；架构、本地化和 Android APK 构建也通过。架构检查会阻止页面和组件直接读取
  物理窗口像素。
- 当前 E2 的约 259dp 应用窗口已经验证过紧凑布局、键盘、1.35 字体和中英文页面。适配层改造后，
  采集/相机/Try 回归通过 342 项，另一个包含系统导入器的 Try 回归通过 211 项；环境中断专项 39 项、
  朗读失败专项 27 项也通过。宽屏设备仍然没有实机证据。

## 仍未验证的范围

当前真机不是适配策略的全部证据。后续需要在普通手机和平板上验证：横屏、分屏/自由窗口、系统字体
`1.0–2.0`、动态状态栏/导航栏、硬件键盘、TalkBack 焦点顺序、外部显示器以及系统主题切换。还需要
检查极短横屏窗口中环境页和输入法的滚动边界，以及宽窗口中 Markdown 表格、长文件名和会话列表的
可读宽度。

这些属于 UI 和可访问性验收缺口，不影响已经完成的 P0/P1 协议、连接、存储和失败恢复工作。推送和
Passport 按用户要求继续暂缓；它们在 `docs/migration.md` 中保留为 1:1 差距。

