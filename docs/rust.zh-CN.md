# 共享 Rust 协议

[English](rust.md) · 简体中文

仓库在 `third_party/pocket-rs` 用 submodule 固定共享协议版本，克隆后运行
`git submodule update --init --recursive`。在原 JDK17／NDK `28.2.13676358` 基础上安装
Rust 1.100.0，执行 `rustup target add aarch64-linux-android x86_64-linux-android`。
Gradle 为 `:core:check` 构建宿主 JNI，为 APK 构建双 ABI JNI。本地联调可用
`-PpocketRustDir=/path/to/pocket-rs`，默认和 CI 使用提交固定的版本。

`:protocol` 仅包含没有 Android API 依赖的 Java 桥；`RustProtocol` 映射领域类型、ABI 结果和既有
本地化错误。AmbientInput／AmbientOutput／PacketStream／Endpoint 和重试门面不再实现另一套
Java 协议规则。ChannelClient 在 Rust 中构建请求，SpeechReader 使用共享朗读请求／分帧；
controller 的 live 校验和媒体上下行也实际调用共享库。

Android 保留 UI、SQLite 原子事务、凭据／Tailscale 网络、麦克风／Opus／AudioTrack／焦点与前台
服务。OutboxSender／RoomLink 调度／取消这些平台副作用并调用共享重试规则，不会同时驱动另一个
Rust 聚合 Session。新 Linux 宿主可选择聚合入口。流线程在完成／取消时关闭解析器句柄；全局
所有者与 controller 同寿命。JNI 复制结果后统一释放 Rust 缓冲，错误路径也一样。

APK 为 arm64-v8a 与 x86_64 包含 `libpocket_core.so`／`libpocket_jni.so`，支持 API26 和
16 KiB 对齐，附带混淆保留规则及许可证。协议／JNI 不依赖 Go；既有 Go／Tailscale 只负责 Android
平台的网络。

相关验证为 `./gradlew :core:check :app:assembleDebug` 和相同签名的 Release 构建。E2 的显式入口
是 `POCKET_TEST_RUST_MIGRATION_ONLY=1 scripts/test-device.sh` 与
`POCKET_TEST_LOCKSCREEN_PLAYBACK_ONLY=1 scripts/test-device.sh`，用 `ANDROID_SERIAL` 选择
设备。仅使用 Try／本地预制内容和静音原生播放，不采集麦克风、不向真实房间发送，不跑历史全量矩阵。
已执行证据见[当前状态](status.zh-CN.md)，Linux 与未来 iOS 的范围见共享库的
[迁移计划](https://github.com/openduo/pocket-rs/blob/main/docs/migration.zh-CN.md)。
