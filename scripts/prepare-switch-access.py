#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Build Google's pinned open source Switch Access for isolated emulator acceptance only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent
PIN = 'fa67c12fce7242b97ce864c4c6b86a0b758e6332'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='Optionally install on this isolated emulator')
    args = parser.parse_args()
    source = ROOT / '.build/tools/talkback-switch-6.1.1'
    if not source.exists():
        source.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['git', 'clone', '--depth', '1', '--branch', '6.1.1',
                        'https://github.com/google/talkback.git', str(source)], check=True)
    head = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if head != PIN:
        raise SystemExit('The Switch Access checkout does not match its pinned public source.')
    changed = subprocess.check_output(['git', '-C', str(source), 'diff', '--name-only', '--', '*.java'], text=True)
    if changed.strip():
        raise SystemExit('Switch Access Java behaviour must remain identical to the pinned source.')
    modules = ['switchaccess', 'utils', 'compositor', 'accessibility_utils', 'proguard']
    (source / 'settings.gradle').write_text(
        "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\n"
        "rootProject.name='PocketSwitchAcceptance'\n" +
        '\n'.join(f"include ':{module}'" for module in modules) + '\n')
    (source / 'build.gradle').write_text('''plugins { id 'com.android.application' version '8.7.3'; id 'com.android.library' version '8.7.3' apply false }
allprojects { repositories { google(); mavenCentral() } }
android { namespace 'top.kuaner.pockete2.switchqa'; compileSdk 35
    defaultConfig { applicationId 'top.kuaner.pockete2.switchqa'; minSdk 26; targetSdk 28; versionName 'Google-source-6.1.1'; versionCode 1 }
    sourceSets { main { manifest.srcFile 'qa-manifest.xml'; java.srcDirs=[]; res.srcDirs=[] } }
    packaging { resources.excludes += ['META-INF/DEPENDENCIES', 'META-INF/LICENSE', 'META-INF/NOTICE'] }
}
dependencies { implementation project(':switchaccess') }
''')
    (source / 'qa-manifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.VIBRATE"/>
<application android:label="Switch Access source QA" android:theme="@android:style/Theme.Material.Light"/>
</manifest>
''')
    sdk = os.environ.get('ANDROID_SDK_ROOT', '/opt/homebrew/share/android-commandlinetools')
    (source / 'local.properties').write_text('sdk.dir=' + sdk + '\n')
    (source / 'gradle.properties').write_text(
        'android.nonTransitiveRClass=false\nandroid.nonFinalResIds=false\norg.gradle.jvmargs=-Xmx2g\n')
    (source / 'shared.gradle').write_text('''android { compileSdk 35; defaultConfig { minSdk 26; targetSdk 28 }; buildFeatures { buildConfig true } }
dependencies {
 implementation 'com.google.guava:guava:22.0'
 implementation 'com.android.support:support-v4:27.1.1'
 implementation 'com.android.support:appcompat-v7:27.1.1'
}
''')
    for module in modules:
        original = subprocess.check_output(['git', '-C', str(source), 'show',
                                             f'{PIN}:{module}/build.gradle'], text=True)
        original = original.replace('compile project', 'api project')
        manifest = source / module / 'src/main/AndroidManifest.xml'
        if manifest.exists():
            text = subprocess.check_output(['git', '-C', str(source), 'show',
                                              f'{PIN}:{module}/src/main/AndroidManifest.xml'], text=True)
            namespace = re.search(r'package="([^"]+)"', text)[1]
            manifest.write_text(re.sub(r'\s+package="[^"]+"', '', text))
            original += f"\nandroid {{ namespace '{namespace}'; compileSdk 35 }}\n"
        (source / module / 'build.gradle').write_text(original)
    service_path = 'switchaccess/src/main/res/xml/switch_access_accessibilityservice.xml'
    service_metadata = subprocess.check_output(
        ['git', '-C', str(source), 'show', f'{PIN}:{service_path}'], text=True)
    # Switch Access is an assistive service. API 34+ hides sensitive permission controls from
    # services without this API 31 declaration, which did not exist in the pinned release.
    (source / service_path).write_text(service_metadata.replace(
        'android:accessibilityEventTypes=', 'android:isAccessibilityTool="true"\n    android:accessibilityEventTypes=', 1))
    wrapper = source / 'gradle/wrapper/gradle-wrapper.properties'
    wrapper.write_text(wrapper.read_text().replace('gradle-4.3.1-bin.zip', 'gradle-8.9-bin.zip'))
    directory = ROOT / '.build/acceptance/accessibility'
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / 'switch-source-build.log').open('w') as log:
        subprocess.run([str(source / 'gradlew'), '--no-daemon', '--console', 'plain', 'assembleDebug'],
                       cwd=source, stdout=log, stderr=subprocess.STDOUT, check=True)
    apk = source / 'build/outputs/apk/debug/PocketSwitchAcceptance-debug.apk'
    (directory / 'switch-source.json').write_text(json.dumps({
        'repository': 'https://github.com/google/talkback', 'commit': PIN,
        'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'changes': 'Gradle, namespace, standalone QA manifest and isAccessibilityTool metadata; upstream Java is unchanged',
        'isAccessibilityTool': True,
        'scope': 'Legacy actual source service; current Google Play Switch Access remains separate'
    }, indent=2))
    if args.serial:
        if not args.serial.startswith('emulator-') or subprocess.check_output(
                ['adb', '-s', args.serial, 'shell', 'getprop', 'ro.kernel.qemu'], text=True).strip() != '1':
            raise SystemExit('This QA build may only be installed on an isolated emulator.')
        subprocess.run(['adb', '-s', args.serial, 'install', '-r', str(apk)], check=True)
    print(apk)


if __name__ == '__main__':
    main()
