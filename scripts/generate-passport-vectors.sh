#!/usr/bin/env bash
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
ios_dir="${POCKET_IOS_DIR:-$project_dir/../pocket-ios}"
reference=b9d91bb9b77a12c3e59e6f359af1e4537a4ded39
[[ -d "$ios_dir/.git" ]] || { echo 'Set POCKET_IOS_DIR to an openduo/pocket-ios checkout.' >&2; exit 1; }
git -C "$ios_dir" diff --quiet "$reference" -- Packages/PocketKit/Sources/PocketCore Packages/PocketKit/Package.resolved || { echo 'Reference sources differ from the pinned upstream commit.' >&2; exit 1; }
build="$project_dir/.build/passport-reference"
mkdir -p "$build/Sources/PassportVectors"
rm -rf "$build/Sources/PocketCore"
cp -R "$ios_dir/Packages/PocketKit/Sources/PocketCore" "$build/Sources/PocketCore"
cp "$ios_dir/Packages/PocketKit/Package.resolved" "$build/Package.resolved"
cp "$project_dir/tests/reference/PassportVectors.swift" "$build/Sources/PassportVectors/main.swift"
cat > "$build/Package.swift" <<'SWIFT'
// swift-tools-version:5.9
import PackageDescription
let package = Package(name: "PassportReference", platforms: [.macOS(.v14)], dependencies: [
    .package(url: "https://github.com/swiftlang/swift-markdown.git", exact: "0.9.0")
], targets: [
    .target(name: "PocketCore", dependencies: [.product(name: "Markdown", package: "swift-markdown")]),
    .executableTarget(name: "PassportVectors", dependencies: ["PocketCore"])
])
SWIFT
swift run --package-path "$build" PassportVectors \
    "$project_dir/tests/fixtures/passport/cases.json" "$project_dir/tests/fixtures/passport/upstream-vectors.json"
