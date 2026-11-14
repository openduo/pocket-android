# Accessibility behavior and evidence

English · [详细中文历史记录](accessibility.zh-CN.md)

Shared actions have at least 48dp targets, labels and roles. Recording supports keyboard/accessibility start, send and cancel actions. Message actions have names; map/image controls provide alternatives to gestures. Text, forms, sheets and navigation use the common adaptive containers.

Earlier test-package evidence includes actual TalkBack 15.0 navigation and Google-source Switch Access 6.1.1 scanning/activation, actual camera/microphone/location/notification authorization and external DocumentsUI cancellation. The Switch build is a pinned legacy source QA build, not current Google Play acceptance. Permission callbacks after Activity recreation were fixed. Exact scenes, counts, APK identities, failures and remaining combinations are retained in the [Chinese evidence archive](accessibility.zh-CN.md).

The renamed/module-based release was checked with limited representative windows and native capture; the complete accessibility matrix was not repeated. Current Play services, all OEMs, dynamic content and every external-dialog/recreation combination remain unverified. The historical scope is not an instruction to keep expanding tests. See [current verification](acceptance.md) and [UI boundaries](ui.md).
