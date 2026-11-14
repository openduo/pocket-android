# Passport 协议与 Android 接入

[English](passport.md) · 简体中文

当前源码实现 Passport **1.3** 协议，对照 Pocket iOS
[`b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`](https://github.com/openduo/pocket-ios/tree/b9d91bb9b77a12c3e59e6f359af1e4537a4ded39)。
2026-11-15 恢复 Passport 范围；Sight 和 Android 即时推送继续暂缓。
本次改动尚未包含在公开 Preview 2 APK 中。

## 模块职责与行为

| 所属层 | 职责 |
| --- | --- |
| `:core` / `PocketProtocol` | Service/TX/RX UUID、小端字节布局、方向/长度校验、4 KB 重组、ATT 分片和 quiet 标识 |
| `:core` / `PressAssembler` | 显式/隐式按压开始、序号回绕、重复/丢包统计、按压替代和断连完成 |
| `:core` / `VoiceResultWait`、`PassportResults` | 识别与接收回执的先后顺序、RESULT 0–4、重连代次、25 秒单调时限、每条语音只结算一次 |
| `:core` / `ReplyTracker` | 多条等待语音、WORK 阶段、部分/最终回答、共用回答 ID、live/log 去重、重放选择、quiet 规则与送交记忆 |
| `:core` / `PassportOutbox` | 整条消息依次分片发送、替换排队中的旧部分文字、有界背压 |
| `:core` / text | 设备显示使用 CommonMark 纯文本，按 Unicode 字素边界限制 UTF-8 长度；朗读继续使用独立过滤规则 |
| `:app` / `PassportLink` | 单独 BLE 线程上的扫描/连接、系统数字确认配对、MTU/CCCD、有限操作超时、重连和权限处理 |
| `:app` / `PassportCoordinator` | controller 所在线程拥有 reducer；按房间持久化、APP_STATE 语言/可达状态、后台唤醒/补取及 Try 隔离 |
| `:app` / `PassportPage` | 可滚动设置、配对入口、固件/协议/电量与最新回答展示；视图不拥有协议状态 |

设备上行的 `INFO`、`PRESS_START`、`AUDIO`、`PRESS_END`、`STATUS`、`KEEPALIVE`，以及下行的
`RESULT`、`REPLY`、`REPLY_DONE`、`APP_STATE`、`WORK` 均已接入。主版本相同即可兼容，包含旧次版本；
不兼容的主版本返回升级状态并禁止按压转发。未知充电状态保留为未知，中英文语言随应用设置发送给设备。

识别成功后才开始等待回答，HTTP 接收回执本身不代表识别完成。识别事件可以早于接收回调，重连时也能从
日志行恢复识别结果。HTTP 上传共用按压结束后的剩余 RESULT 时限。设备按压对应的回答始终提醒；其他回答
在应用前台时使用 quiet。重放优先选择正在等待的答案，否则只选择最新的合格答案。最终答案在与 iOS
相同的“进入 link 队列”边界记录已送交；无响应写入不代表设备已收到。

## 上游差分验证

`tests/fixtures/passport/cases.json` 是合成输入；`tests/reference/PassportVectors.swift` 调用
**未修改的固定版 PocketCore 源码**生成 `upstream-vectors.json`，不以 Android 自己的实现作为预期值。
生成器会拒绝有改动的参考源码，并固定 swift-markdown 0.9.0。

```sh
# 仅重新生成冻结预期值时需要 macOS、Swift 和 iOS checkout。
POCKET_IOS_DIR=../pocket-ios scripts/generate-passport-vectors.sh

# 日常验证读取已提交的预期值，不需要 Swift 或蓝牙。
./gradlew :core:check

# 可选：只在明确选定的 Android 运行时执行纯协议。
ANDROID_SERIAL=<serial> scripts/test-passport-protocol.sh
```

2026-11-15 的结果为：**60 个上游场景 / 1,050 个比较**，加上 **1,093 个 Unicode 16 字素边界官方用例**，
在 JVM 和 E2 真机 ART 上均通过。范围包括字节/MTU/quiet、损坏/交错/超长 PDU、按压序号、Markdown/Unicode
截断、WORK/等待、重放/去重/持久化和识别结果。包含其他保留契约的 host 总计 2,484 项通过，Release 构建与
源码/国际化检查通过。

Android 检查只把小型 DEX runner 与向量复制到临时 shell 目录，结束后删除；不会安装或启动 APP、连接蓝牙、
修改应用数据、录音、播放或访问房间。私有运行证据保留在 ignored `.build/passport-device/`。

E2 的旧正则引擎曾把家庭 ZWJ emoji 拆开，因此协议文本截断改用固定 Unicode 16 UAX #29 规则，避免 OEM
正则/ICU 版本差异。生成的数据和官方用例可通过 `scripts/generate-grapheme-data.py` 重建，生成器校验官方
源文件的 SHA-256。许可证随源码和 APK 打包，见 `third_party/Unicode-LICENSE.txt`。

## 尚未进行的真实硬件验收

本次没有配对或断开真实 Passport，未接管 E2Studio 的蓝牙连接。协议和 ART 验证证明上述协议行为，不代表
无线和声学链路验收。有可用于本应用的外设后，从设置 → Passport 进入，授予蓝牙权限（API 26–30 扫描需要
定位权限），在两端通过系统界面核对数字。应用不会自动确认配对。在应用内忘记设备会停止重连；过期的系统
配对记录仍由用户在系统设置中移除。

后续只需先做短验收：真实配对/CCCD 与重连、一条实体按键语音到识别和答案显示/提醒、一次后台 KEEPALIVE
唤醒。目标设备需要时再检查现代 Android 的 connected-device 限制。距离/OEM/长期后台和固件日志导出尚未
验收；不需要为保存这次协议迁移恢复设备矩阵或通宵测试。
