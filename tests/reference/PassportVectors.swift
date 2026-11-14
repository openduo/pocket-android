// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
// Executes the unmodified, pinned PocketCore implementation; no Android-derived expectations.
import Foundation
import PocketCore

func data(_ hex: String) -> Data {
    var result = Data(); var at = hex.startIndex
    while at < hex.endIndex { let end = hex.index(at, offsetBy: 2); result.append(UInt8(hex[at..<end], radix: 16)!); at = end }
    return result
}
func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
func phone(_ a: [String: Any]) -> PhoneMessage {
    let id = (a["id"] as? NSNumber)?.uint32Value ?? 0
    switch a["type"] as! String {
    case "result": return .result(pressID: UInt16(id), code: ResultCode(rawValue: UInt8(a["code"] as! Int))!, text: a["text"] as? String ?? "")
    case "reply": return .reply(replyID: id, final: a["final"] as? Bool ?? false, text: a["text"] as? String ?? "")
    case "done": return .replyDone(replyID: id)
    case "app": return .appState(AppState(rawValue: UInt8(a["state"] as! Int))!, language: (a["language"] as? Int).flatMap { UILanguage(rawValue: UInt8($0)) })
    default: return .work(phase: WorkPhase(rawValue: UInt8(a["phase"] as! Int))!, label: a["text"] as? String ?? "")
    }
}
func outputs(_ messages: [PhoneMessage], tracker: ReplyTracker, foreground: Bool) -> [[String: Any]] {
    messages.map { m in
        var quiet = false
        if case .reply(let id, _, _) = m { quiet = tracker.isQuiet(replyID: id, display: foreground ? .phone : .passport) }
        let fitted = m.fitted(maxMessage: 4096)
        return ["type": Int(m.kind.rawValue), "payload": hex(fitted.payload()), "quiet": quiet]
    }
}
let input = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
let cases = try JSONSerialization.jsonObject(with: input) as! [[String: Any]]
var vectors: [[String: Any]] = []
for item in cases {
    let kind = item["kind"] as! String
    var record = item
    switch kind {
    case "wire":
        let m = phone(item["message"] as! [String: Any]); let mtu = item["mtu"] as! Int
        record["expected"] = try m.fragments(attPayload: mtu - 3, quiet: item["quiet"] as? Bool ?? false).map(hex)
    case "receive":
        var device = DeviceLinkDecoder(); var phone = PhoneLinkDecoder(); var results: [Any] = []; var errors = 0
        for value in item["pdus"] as! [String] {
            do {
                if item["direction"] as! String == "device" {
                    if let m = try device.push(data(value)) { results.append(["type": Int(m.kind.rawValue), "payload": hex(m.payload()), "quiet": false]) } else { results.append(NSNull()) }
                } else {
                    if let m = try phone.push(data(value)) { results.append(["type": Int(m.kind.rawValue), "payload": hex(m.payload()), "quiet": phone.quiet]) } else { results.append(NSNull()) }
                }
            } catch { errors += 1; results.append(NSNull()) }
        }
        record["expected"] = results; record["errors"] = errors
    case "press":
        var assembler = PressAssembler(); var steps: [[String: Any]] = []
        for action in item["actions"] as! [[String: Any]] {
            let events: [PressAssembler.Event]
            if action["op"] as! String == "lost" { events = assembler.linkLost() }
            else { events = assembler.handle(try DeviceMessage.decode(type: UInt8(action["type"] as! Int), payload: data(action["payload"] as! String))) }
            var completed: [[String: Any]] = []
            for event in events {
                if case .completed(let c) = event { completed.append(["id": Int(c.pressID), "packets": c.packets.map(hex), "gaps": c.stats.gaps, "duplicates": c.stats.duplicates, "declared": c.stats.declaredCount ?? -1, "implicit": c.stats.implicitStart, "end": c.stats.end.rawValue]) }
            }
            steps.append(["active": assembler.activePressID.map(Int.init) ?? -1, "completed": completed])
        }
        record["expected"] = steps
    case "reply":
        var tracker = ReplyTracker(); var steps: [[String: Any]] = []
        for action in item["actions"] as! [[String: Any]] {
            let now = UInt32(action["now"] as? Int ?? 1000); tracker.clock = { now }
            let foreground = action["foreground"] as? Bool ?? false
            var messages: [PhoneMessage] = []
            switch action["op"] as! String {
            case "expect": tracker.expect(messageID: action["id"] as? String)
            case "frame": messages = tracker.handle(frame: action["frame"] as! [String: Any])
            case "rows":
                let encoded = try JSONSerialization.data(withJSONObject: action["rows"]!)
                messages = tracker.rows(try JSONDecoder().decode([ImlogEntry].self, from: encoded))
            case "restore_turn":
                let f = action["turn"] as! [String: Any]
                messages = tracker.restore(turn: LiveEvent.TurnSnapshot(messageIDs: f["message_ids"] as? [String] ?? [], phase: f["phase"] as? String ?? ""))
            case "failed": messages = tracker.messageFailed(action["id"] as! String)
            case "new_press": tracker.stopExpecting()
            case "forget": tracker.forgetDelivered()
            case "hand": tracker.handedToLink(replyID: UInt32(action["id"] as! Int))
            case "link": if let a = tracker.answerForNewLink { messages = [.reply(replyID: a.replyID, final: true, text: a.text), .replyDone(replyID: a.replyID)] }
            case "relaunch":
                let memory = try JSONEncoder().encode(tracker.memory); tracker = ReplyTracker()
                tracker.memory = try JSONDecoder().decode(ReplyTracker.Memory.self, from: memory)
            default: fatalError("Unknown reply action")
            }
            steps.append(["outputs": outputs(messages, tracker: tracker, foreground: foreground), "waiting": tracker.awaiting, "serial": Int(tracker.memory.lastReplyID), "latestId": Int(tracker.lastAnswer?.replyID ?? 0), "latestText": tracker.lastAnswer?.text ?? ""])
        }
        record["expected"] = steps
    case "result":
        var wait = VoiceResultWait(messageID: "note"); var steps: [[String: Any]] = []
        for action in item["actions"] as! [[String: Any]] {
            var result: VoiceResultWait.Result?
            switch action["op"] as! String {
            case "accepted": result = wait.accepted()
            case "expire": result = wait.expired(connected: action["connected"] as! Bool)
            default: if let event = LiveEvent.decode(action["frame"] as! [String: Any]) { result = wait.handle(event) }
            }
            let value: Any = result.map { ["code": Int($0.code.rawValue), "text": $0.text] as [String: Any] } ?? NSNull()
            steps.append(["result": value, "done": wait.done, "rowText": wait.rowText as Any? ?? NSNull()])
        }
        record["expected"] = steps
    default: fatalError("Unknown vector kind")
    }
    vectors.append(record)
}
let output: [String: Any] = ["upstream": "https://github.com/openduo/pocket-ios", "commit": "b9d91bb9b77a12c3e59e6f359af1e4537a4ded39", "protocol": "1.3", "vectors": vectors]
let encoded = try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys, .prettyPrinted, .withoutEscapingSlashes])
try encoded.write(to: URL(fileURLWithPath: CommandLine.arguments[2]))
print("Generated \(vectors.count) Passport reference scenarios from unmodified upstream PocketCore")
