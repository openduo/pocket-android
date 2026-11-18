# Device Adaptation Modules (`device/`)

本目录包含 Pocket Android 的通用硬件抽象层与具体设备适配器，遵循**硬件驱动与业务逻辑/窗口尺寸彻底隔离**的原则。

## 目录结构

| 模块 | Gradle 路径 | 职责 |
| :--- | :--- | :--- |
| `device/base/` | `:device:base` | **通用硬件抽象与默认实现**：定义 `DeviceAdapter` 接口、`DeviceRuntime` 注册中心、`DeviceCapabilities` 能力探测，以及默认 `AndroidDeviceAdapter`（标准 `AudioRecord` + 全局唯一麦克风锁 `ExclusiveRecordingInput`）。 |
| `device/e2/` | `:device:e2` | **E2 硬件适配案例**：继承 `AndroidDeviceAdapter`，针对 `E2 AI Recorder`（Android API 27 / 固件 04319）提供型号匹配、物理按键映射、系统栏例外与可选的 C/JNI 阵列录音适配。 |

## 可以适配什么？（`DeviceAdapter` 边界）

设备适配器**只负责纯硬件与固件差异**，严禁触碰房间状态、网络 Outbox 或 UI 窗口尺寸（窗口布局由 `:ui-policy` 的 `AdaptiveMetrics` 统一管理）：

1. **设备识别 (`supports(Context)`)**：通过 `Build.MODEL`、`Build.VERSION.SDK_INT` 等特征匹配目标设备；未命中时自动回退到 `:device:base` 的默认 `AndroidDeviceAdapter`。
2. **音频输入/阵列驱动 (`openInput(...)`)**：替换标准 `AudioRecord` 为厂商私有麦克风阵列 SDK/JNI，或在已知固件缺陷时拦截内置麦并抛出本地化错误提示。所有实现均通过 `ExclusiveRecordingInput` 统一管理麦克风租约与线程释放。
3. **实体按键默认映射 (`keyAction(keyCode, scanCode)`)**：将机身专有物理按键（如录音键、MARK 键、遥控器按键）映射为统一的 `InputAction`（如 `TOGGLE_VOICE`、`CANCEL_OR_STOP`），用户仍可在前台设置中自定义覆盖。
4. **系统栏渲染例外 (`requiresDarkSystemBars()`)**：声明固件是否无法正确渲染浅色状态栏深色图标。

*(相机、定位、蓝牙等通用外设能力由 `DeviceCapabilities` 运行时自动探测，无需在适配器中硬编码。)*

## 如何新增一台设备适配？

1. 在 `device/` 下新建子目录（例如 `device/mydevice/`），创建 `build.gradle` 并声明 `dependencies { api project(':device:base') }`。
2. 在根目录 `settings.gradle` 中加入 `':device:mydevice'`，并在 `android/build.gradle` 中添加 `implementation project(':device:mydevice')`。
3. 继承 `AndroidDeviceAdapter` 实现所需硬件差异，在 `android/.../PocketApplication.java` 的 `DeviceRuntime.install(this, ...)` 中注册。
4. 同步更新 `scripts/check-architecture.py` 中的模块列表以通过架构边界检查。

更多构建、固件打包与验收细节见 [docs/devices.md](../docs/devices.md)（[中文版](../docs/devices.zh-CN.md)）。
