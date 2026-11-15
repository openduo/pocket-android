# 工程架构

业务协议、窗口策略和硬件适配分别建立构建边界。沿用原生 View，避免为了模块化重写交互或复制页面。

```mermaid
flowchart TD
    app[app: Android composition / controller / storage / View]
    core[core: pure Java protocol and reducers]
    ui[ui-policy: pure Java window metrics]
    input[input: pure Java actions and key gestures]
    device[device: framework audio and hardware interface]
    e2[device-e2: optional E2 integration]
    bridge[Go Tailscale / HTTP / WebSocket]
    app --> core
    app --> ui
    app --> input
    device --> input
    app --> device
    app --> e2
    app --> bridge
    e2 --> device
```

`:core` 不依赖 Android、窗口或硬件，包含 API v2、live/消息/附件/语音 framing、幂等/重试/取消、会话与位置 ledger 等纯模型。Android 的 `Settings`、KeyStore、SQLite、后台服务留在 app。JSON 在 JVM 使用固定的 `org.json`，在 Android 使用系统实现。

`:ui-policy` 的 `AdaptiveMetrics` 只接受当前窗口 dp 和字号比例。app 中 `InsetsFrame`、`AdaptivePage`、`AdaptiveColumn`、`PageStates` 和共享控件负责系统栏、IME、滚动、列宽和短期 View 状态。页面渲染 controller snapshot；尺寸变化不改变房间、outbox 或麦克风逻辑。

`:input` 定义统一动作、输入目标和按键边沿规则，不依赖 Android 或业务。`app/input` 管理 Android 事件、本地映射、权限和共享动作分发；触屏／无障碍控件也使用同一个目标，默认键位只由适配器决定。公共 UI 保留方向／确认／返回导航及明显焦点，见[按键与遥控器](input.zh-CN.md)。

`:device` 定义 `DeviceAdapter`，提供标准 `AudioRecord`、路由和能力发现。所有实现都经过同一个 `ExclusiveRecordingInput` lease，不能绕过它同时开启麦克风。`:device-e2` 是单向依赖通用接口的案例；`PocketApplication` 是唯一注册具体设备的组合入口。OEM 不能反向引用业务状态，也不决定窗口大小。智能音箱可先走默认适配器，只有厂商阵列/系统栏等差异才新增模块。

`PocketController` 是 Android 应用协调层。它与拆出的状态组件共用一个 state executor，拆分职责不会
增加房间状态的 writer；页面仍只观察不可变 snapshot。SQLite 事务拥有行／outbox／cursor 与待清理 receipt。

| 所有者 | 职责与资源生命周期 |
| --- | --- |
| `Composer`（`:core`） | 不可变草稿／引用／附件值、编辑代次与确认、导入交接和中断上传恢复 |
| `HistoryPager`（`:core`） | 可见行数、有限日期扫描、分页边界、请求去重和旧结果隔离 |
| `AttachmentTransfers`（`:core`） | 有界上传／下载任务、独立请求取消、房间代次和主线程结果接纳；Android `AttachmentStore` 实现三个文件存储操作 |
| `RoomLink`（`:core`） | live 代次、拨号／写队列、ping、接收二进制帧的复制和最后一条控制帧的关闭交接 |
| `VoiceNotes`（`:core`） | 录音字节、输入来源所有权、持久化交接与恢复，通过存储接口连接 SQLite |
| `OutboxSender`（`:core`） | 一次 HTTP 发送、已注册的请求范围、固定语音字节与发送参数、单调时钟重试预算，以及专属单线程／单等待位队列 |
| `SpeechReader`（`:core`） | 一次朗读的消息身份、Opus 增量分包、流取消与当前请求结果接纳 |
| `TailnetSession`（`:app`） | 网络授权意图／状态、登录／退出操作隔离和原生状态归一化 |
| `Microphone`（`:app`） | 采集实例／代次、就绪／停止回调和主动关闭状态；采集线程继续拥有原生资源释放 |
| `RoomPlayback`（`:app`） | 共享播放时间线、live 解码器、朗读流、播放代次／路由／静音及排空 |
| `ReadAloudOutput`（`:app`） | 在流线程创建／使用／释放 Android 解码器，通过可取消的有界背压接入共享 `SpeechPlayer` |
| `ConversationComposer`（`app/ui`） | 编辑框选择／确认、附件横向滚动、导入／录音控件和键盘生命周期；消息列表继续管理自己的行和阅读位置 |
| `PocketController`（`:app`） | 房间准入、数据库状态转换、前台服务、权限与音频焦点、业务副作用及 snapshot 发布 |

发送有明确的持久化交接：`prepare()` 先注册取消能力，但不排入 I/O；controller 提交 `SENDING` 后
才调用 `dispatch()`。数据库提交失败就取消准备中的请求。实时／历史日志、生命周期回执及丢弃操作
先更新数据库，再取消已结束的发送；历史入库也走同一条结算路径。旧 HTTP、准备任务和重试回调不能
结束新请求。重试沿用同一份字节、幂等键和发送参数。语音 25 秒总预算涵盖准备、等待 worker、HTTP
与退避。Passport 传入的是**剩余时长**，不跨层传递不同单调时钟基准上的绝对时间；请求超时和重试
等待同时受其约束。

朗读区分 HTTP 结束与播放排空。`SpeechReader` 在流结束后保留消息 ID，`RoomPlayback` 等 `SpeechPlayer`
没有待播音频后才清除。取消先撤销当前请求，再停止其原生流，因此排队任务、迟到音频及旧请求的
未授权响应不能影响下一次朗读。每个 worker 自己创建和关闭 `ReadAloudOutput`，controller 不跨线程
释放它的解码器。首包前失败会停止播放；部分失败保留已接纳的音频并显示已有的不完整提示。

已经开始的播放不由 Activity 可见性决定。`AmbientOutput` 在音频分段之间保留当前接收生命周期，
等待匹配的完成时长；原生播放排空是另一个条件，缺失回执则有有限的空闲期限。`RoomPlayback`
把待播状态交给应用策略。Android 会话服务使用 `mediaPlayback` 与仅供播放的 CPU 局部唤醒锁，
采集、上传和外设仍保留各自的服务需求；播放完成后释放其余空闲后台任务。细节见[音频说明](audio.md)。

移除／替换附件会取消其独立上传请求，旧响应不能再修改草稿。切房间会撤销已经排到 Android 主线程
的文件结果；缓存／本地预览仍可在没有有效网络凭据时使用。历史最多扫描十四个空日期，保留已扫描
边界，迟到的当天历史不会将边界向前推回。附件任务也不再占用普通 HTTP 线程池。

这些组件在既有模块内拥有真实状态和资源，不需要设备分支或大而全的 controller 转发接口。新增输入
适配仍只分发统一动作，无需实现房间、重试或播放逻辑。

## 停止点与所有权审查

2026-11-15 的后续拆分在检查 controller 剩余职责及较大的生产类后停止，不以文件行数作为完成标准。
controller 保留应用命令入口、不可变 snapshot 投影、组件装配，以及跨组件事务／策略的执行顺序，
不重复保存已拆出组件的可变状态。

| 已检查范围 | 保留决定与理由 |
| --- | --- |
| 配对、Try 进出、凭据失效 | 保留应用层顺序：撤销旧任务、保留／选择存储、切换通道，再恢复草稿／录音并连接。另建 facade 仍需通过回调取回同一批组件。 |
| live ready／row／生命周期接纳与 HTTP 元数据 | 数据库与业务副作用的顺序继续在单状态所有者执行；传输代次和任务生命周期已在 RoomLink／RoomAccess／OutboxSender／HistoryPager。 |
| ambient／按住说话／焦点／前台服务决策 | 保留麦克风、录音、播放、网络和系统服务之间的协调；原生资源及 wire reducer 已有独立所有者。 |
| 会话、Sight、到访和 Passport 入口 | 保留简短应用命令；SessionViewer、SightSession、ArrivalMonitor 和 PassportCoordinator 已拥有功能状态，再加一层只会转发。 |
| RoomRepository | 保留行／outbox／cursor／导入回执的事务边界。按表拆 DAO 会隐藏原子性约束，没有新的独立生命周期收益。 |
| ConversationPage 与 ConversationComposer | 编辑状态已与列表状态分开；消息单元／操作／trace 渲染继续与列表共用行 key、展开状态和滚动锚点。 |
| TryChannel、BLE、相机采集及导入协调 | 保留各自内聚的通道／资源／任务所有者；预设脚本、BLE reducer、原生采集和导入 inbox 规则已有独立边界。 |
| 自动生成的 GraphemeData 及纯 reducer／值对象 | 保留生成数据和内聚类型，拆短文件不会产生新的所有权边界。 |

后续只有在新功能带来独立状态／资源，或出现可证实的跨边界耦合时再拆。不为移动方法引入通用运行时
容器／service locator、空模块、重复字段或巨大的 Host 接口。架构检查也会阻止 controller 再直接
创建 capture／decoder／player／发送线程资源。

构建使用 Gradle wrapper 8.13、AGP 8.13.2 和 CMake。Go bridge 通过有输入/输出声明的任务构建，JNI 包名与 Java 一致，Opus 的固定源码压缩包经过 SHA-256 校验。Release 签名不进入仓库，公共构建无 E2 私有 payload。CI 运行静态边界、纯 Java 契约、Go 检查和通用 Debug APK 构建；设备探针保持显式 opt-in。

`scripts/check-architecture.py` 检查模块纯度、UI/业务、窗口像素和 OEM 型号边界。Gradle 模块同时阻止纯协议误引用 Android 或 UI。新增设备见[设备指南](devices.md)，新增布局规则见[UI 指南](ui.md)。
