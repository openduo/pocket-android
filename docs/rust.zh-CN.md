# 共享 Rust 协议

[English](rust.md) · 简体中文

共享协议来自上游 [openduo/pocket-rs](https://github.com/openduo/pocket-rs)，通过 submodule
放在 `third_party/pocket-rs`，跟踪其 `main` 分支。克隆后运行
`git submodule update --init --recursive`；要更新到上游最新源码，运行
`git submodule update --remote third_party/pocket-rs`。验证协议变更后再提交更新的 submodule 指针。
每个 Android 提交都会记录实际集成的源码 revision，保证检出和发布构建可复现；依赖策略跟随上游
`main`，不固定 pocket-rs 发布版本。在原 JDK17／NDK `28.2.13676358` 基础上安装 Rust 1.100.0，
执行 `rustup target add aarch64-linux-android x86_64-linux-android`。Gradle 为 `:core:check`
构建宿主 JNI，为 APK 构建双 ABI JNI。本地联调可用 `-PpocketRustDir=/path/to/pocket-rs`，默认使用
Android 提交记录的 submodule revision。

`:protocol` 仅包含没有 Android API 依赖的 Java 桥；`RustProtocol` 映射领域类型、ABI 结果和既有
本地化错误。AmbientInput／AmbientOutput／PacketStream／Endpoint 和重试门面不再实现另一套
Java 协议规则。ChannelClient 在 Rust 中构建请求，SpeechReader 使用共享朗读请求／分帧；
controller 的 live 校验和媒体上下行也实际调用共享库。

Android 保留 UI、SQLite 原子事务、凭据／Tailscale 网络、麦克风／Opus／AudioTrack／焦点与前台
服务。OutboxSender／RoomLink 调度／取消这些平台副作用并调用共享重试规则，不会同时驱动另一个
Rust 聚合 Session。新 Linux 宿主可选择聚合入口。流线程在完成／取消时关闭解析器句柄；全局
所有者与 controller 同寿命。JNI 复制结果后统一释放 Rust 缓冲，错误路径也一样。

恢复后的 [Sight 适配](sight.zh-CN.md)修正了新版媒体服务暴露的 SDK 规则：`media=1` 只有上行
增加类型字节。共享 Ambient 与 JNI 输入完整保留下行 Opus，包括首字节为 `0x01` 的包；本机
loopback 服务也改为真实下行格式。Preview 3 使用了包含该修正的 pocket-rs 0.1.1 提交；当前 Android
源码已更新到上游 `main` 最新 revision。新增 12 条 JVM 断言后共 2,634 条通过，Rust workspace 共 21 个测试通过。

APK 为 arm64-v8a 与 x86_64 包含 `libpocket_core.so`／`libpocket_jni.so`，支持 API26 和
16 KiB 对齐，附带混淆保留规则及许可证。协议／JNI 不依赖 Go；既有 Go／Tailscale 只负责 Android
平台的网络。

相关验证为 `./gradlew :core:check :app:assembleDebug` 和相同签名的 Release 构建。E2 的显式入口
是 `POCKET_TEST_RUST_MIGRATION_ONLY=1 scripts/test-device.sh` 与
`POCKET_TEST_LOCKSCREEN_PLAYBACK_ONLY=1 scripts/test-device.sh`，用 `ANDROID_SERIAL` 选择
设备。仅使用 Try／本地预制内容和静音原生播放，不采集麦克风、不向真实房间发送，不跑历史全量矩阵。
已执行证据见[当前状态](status.zh-CN.md)，Linux 与未来 iOS 的范围见共享库的
[迁移计划](https://github.com/openduo/pocket-rs/blob/main/docs/migration.zh-CN.md)。

SessionViewer 的会话 watch／通知命令序列化与 Passport／Sight／地理相关协议保留既有功能所有者，
不计为本次 Chat／Ambient 协议迁移已完成的范围。
[可执行接入版本的 CI](https://github.com/openduo/pocket-android/actions/runs/43675746653) 已通过；
这是此前接入证据。Preview 3 增加上述媒体下行修正，发布检查见[当前状态](status.zh-CN.md)与发布说明。
