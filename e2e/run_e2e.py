#!/usr/bin/env python3
"""End-to-end launch test: drives the real launcher on a device or emulator into a running game.

It installs the debug launcher and the placeholder game (e2e/placeholder-game), presses Launch
in the launcher's UI and follows the whole launch through logcat: the launcher's
MinecraftLaunchTrace milestones, then the placeholder's own reports (libraries loaded, JNI calls,
startup probes into the stub MainActivity, rendered frames, lifecycle commands and input).
After the game is up it checks input delivery and a background/foreground cycle.

Requirements: adb on PATH or in the Android SDK, one device (or --serial), and the local launcher
setup described in e2e/README.md (Play Store check skip flag, Discord SDK library).
Uses only the Python standard library.

Usage: python e2e/run_e2e.py [--version 1.26.60.5] [--serial emulator-5554] [--skip-build]
"""
import argparse
import datetime
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
LAUNCHER_PACKAGE = 'org.levimc.launcher'
GAME_PACKAGE = 'com.mojang.minecraftpe'
LAUNCHER_APK = os.path.join(ROOT, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
GAME_APK = os.path.join(ROOT, 'e2e', 'placeholder-game', 'build', 'outputs', 'apk', 'debug', 'placeholder-game-debug.apk')
LOG_TAGS = ['MinecraftLaunchTrace:*', 'LeviPlaceholder:*', 'AndroidRuntime:E', 'libc:F', 'DEBUG:F']

# MinecraftLaunchTrace milestones of a successful launch, in order (see LaunchTrace usages).
MILESTONES = [
    'Launch requested',
    'Loading screen requested',
    'Launch screen ready',
    'Runtime preparation continuing',
    'Runtime preparation started',
    'Game files ready',
    'Native runtime ready',
    'Runtime preparation finished',
    'Entering game',
    'Minecraft activity ready',
]

# Lines that fail the run as soon as they appear.
FAILURE_PATTERNS = [
    re.compile(r'MinecraftLaunchTrace: .*(Launch failed|failed -|cancelled -)'),
    re.compile(r'LeviPlaceholder: .*(probe FAIL|egl FAIL|looper poll error)'),
    re.compile(r'AndroidRuntime: FATAL EXCEPTION'),
    re.compile(r'(libc|DEBUG)\s*: Fatal signal'),
]


class TestFailure(Exception):
    pass


def version_tuple(version):
    parts = [int(p) for p in version.replace('-beta', '').split('.')]
    return tuple(parts + [0] * (4 - len(parts)))


def find_adb():
    adb = shutil.which('adb')
    if adb:
        return adb
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or \
        os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')
    candidate = os.path.join(sdk, 'platform-tools', 'adb.exe' if os.name == 'nt' else 'adb')
    if os.path.exists(candidate):
        return candidate
    raise TestFailure('adb not found; put platform-tools on PATH or set ANDROID_HOME')


class Device:
    def __init__(self, adb, serial):
        self.adb = adb
        self.serial = serial

    def run(self, *args, check=True, timeout=120, binary=False):
        cmd = [self.adb] + (['-s', self.serial] if self.serial else []) + list(args)
        result = subprocess.run(cmd, capture_output=True, timeout=timeout)
        if check and result.returncode != 0:
            raise TestFailure(f'{" ".join(args)} failed: {result.stderr.decode(errors="replace").strip()}')
        return result.stdout if binary else result.stdout.decode(errors='replace')

    def shell(self, command, **kw):
        return self.run('shell', command, **kw)

    def popen(self, *args):
        cmd = [self.adb] + (['-s', self.serial] if self.serial else []) + list(args)
        return subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def top_activity(self):
        out = self.shell('dumpsys activity activities', check=False)
        m = re.search(r'topResumedActivity=ActivityRecord\{\S+ \S+ ([^ }]+)', out)
        return m.group(1) if m else ''

    def ui_nodes(self):
        self.shell('uiautomator dump /sdcard/levi_e2e_ui.xml', check=False, timeout=60)
        xml = self.shell('cat /sdcard/levi_e2e_ui.xml', check=False)
        start = xml.find('<?xml')
        if start < 0:
            return []
        try:
            return list(ET.fromstring(xml[start:]).iter('node'))
        except ET.ParseError:
            return []

    def tap_node(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
        self.shell(f'input tap {(x1 + x2) // 2} {(y1 + y2) // 2}')

    def screenshot(self, path):
        with open(path, 'wb') as fh:
            fh.write(self.run('exec-out', 'screencap', '-p', binary=True, check=False))


class LogWatcher:
    """Collects logcat lines in the background and waits for patterns in arrival order."""

    def __init__(self, device):
        self.lines = []
        self.lock = threading.Condition()
        self.proc = device.popen('logcat', '-v', 'threadtime', '-s', *LOG_TAGS)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for raw in self.proc.stdout:
            line = raw.decode(errors='replace').rstrip()
            with self.lock:
                self.lines.append(line)
                self.lock.notify_all()

    def check_failures(self, since=0):
        with self.lock:
            for line in self.lines[since:]:
                if any(p.search(line) for p in FAILURE_PATTERNS):
                    raise TestFailure(f'failure in logcat: {line}')

    def wait_for(self, pattern, timeout, since=0, describe=None):
        regex = re.compile(pattern)
        deadline = time.monotonic() + timeout
        with self.lock:
            index = since
            while True:
                while index < len(self.lines):
                    line = self.lines[index]
                    if any(p.search(line) for p in FAILURE_PATTERNS):
                        raise TestFailure(f'failure in logcat while waiting for {describe or pattern}: {line}')
                    m = regex.search(line)
                    index += 1
                    if m:
                        return index, m
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TestFailure(f'timed out after {timeout}s waiting for {describe or pattern}')
                self.lock.wait(remaining)

    def mark(self):
        with self.lock:
            return len(self.lines)

    def stop(self):
        self.proc.terminate()


class Run:
    def __init__(self, args):
        self.args = args
        stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
        self.out = os.path.join(ROOT, 'e2e', 'out', f'{stamp}-{args.version}')
        os.makedirs(self.out, exist_ok=True)
        self.device = Device(find_adb(), args.serial)
        self.steps = []
        self.watcher = None

    def step(self, name):
        print(f'[{len(self.steps) + 1:02d}] {name} ...', flush=True)
        self.steps.append(name)

    def ok(self, detail=''):
        print(f'     ok{": " + detail if detail else ""}', flush=True)

    # -- setup ---------------------------------------------------------------------------------
    def check_local_setup(self):
        self.step('Check local launcher setup')
        props = os.path.join(ROOT, 'local.properties')
        text = open(props, encoding='utf-8').read() if os.path.exists(props) else ''
        if not re.search(r'^\s*dev\.skip_play_store_check\s*=\s*true\s*$', text, re.M):
            raise TestFailure('local.properties needs dev.skip_play_store_check=true (see e2e/README.md)')
        aar = os.path.join(ROOT, 'app', 'libs', 'discord_partner_sdk.aar')
        if not os.path.exists(aar) or 'jni/arm64-v8a/libdiscord_partner_sdk.so' not in zipfile.ZipFile(aar).namelist():
            raise TestFailure('app/libs/discord_partner_sdk.aar has no jni/arm64-v8a/libdiscord_partner_sdk.so; '
                              'libgxcore.so cannot load without it (see e2e/README.md)')
        devices = [l.split()[0] for l in self.device.run('devices').splitlines()[1:] if l.strip().endswith('device')]
        if not devices:
            raise TestFailure('no device connected')
        if not self.device.serial:
            if len(devices) > 1:
                raise TestFailure(f'several devices connected ({", ".join(devices)}); pass --serial')
            self.device.serial = devices[0]
        abis = self.device.shell('getprop ro.product.cpu.abilist').strip()
        if 'arm64-v8a' not in abis.split(','):
            raise TestFailure(f'device cannot run arm64-v8a code (abilist={abis})')
        self.ok(f'device {self.device.serial}, abilist {abis}')

    def build(self):
        self.step('Build launcher and placeholder game')
        gradlew = os.path.join(ROOT, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
        cmd = [gradlew, '--console=plain', '-Plevi.e2e=true', f'-Pplaceholder.version={self.args.version}',
               ':app:assembleDebug', ':e2e:placeholder-game:assembleDebug']
        with open(os.path.join(self.out, 'gradle.log'), 'wb') as log:
            if subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT).returncode != 0:
                raise TestFailure(f'gradle build failed; see {log.name}')
        self.ok()

    def install(self):
        self.step('Install launcher and placeholder game')
        for apk in (LAUNCHER_APK, GAME_APK):
            if not os.path.exists(apk):
                raise TestFailure(f'missing {apk}; run without --skip-build')
        if not self._is_placeholder_or_absent():
            raise TestFailure(f'{GAME_PACKAGE} on the device is not the placeholder (a real Minecraft?). '
                              f'This test will not replace it; uninstall it yourself first.')
        self.device.run('install', '-r', LAUNCHER_APK, timeout=300)
        self.device.run('install', '-r', '-d', GAME_APK, timeout=300)
        name = re.search(r'versionName=(\S+)', self.device.shell(f'dumpsys package {GAME_PACKAGE}')).group(1)
        if name != self.args.version:
            raise TestFailure(f'installed placeholder reports {name}, expected {self.args.version}')
        self.ok(f'{GAME_PACKAGE} {name}')

    def _is_placeholder_or_absent(self):
        paths = [l.replace('package:', '').strip()
                 for l in self.device.shell(f'pm path {GAME_PACKAGE}', check=False).splitlines() if l.strip()]
        if not paths:
            return True
        # The real game's APKs add up to hundreds of MB; the placeholder's is a few MB.
        sizes = self.device.shell('stat -c %s ' + ' '.join(paths), check=False).split()
        return all(s.isdigit() for s in sizes) and sum(int(s) for s in sizes) < 20_000_000

    # -- launch --------------------------------------------------------------------------------
    def start_launcher(self):
        self.step('Start launcher')
        self.device.shell(f'am force-stop {LAUNCHER_PACKAGE}')
        self.device.run('logcat', '-c', check=False)
        self.watcher = LogWatcher(self.device)
        self.device.shell(f'monkey -p {LAUNCHER_PACKAGE} -c android.intent.category.LAUNCHER 1')
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.device.top_activity().endswith('.ui.activities.MainActivity'):
                button = self._find(f'{LAUNCHER_PACKAGE}:id/launch_button')
                if button is not None and button.get('enabled') == 'true':
                    self.ok(self.device.top_activity())
                    return button
            time.sleep(1)
        raise TestFailure(f'launcher main screen with an enabled Launch button did not appear (top: {self.device.top_activity()})')

    def _find(self, resource_id):
        return next((n for n in self.device.ui_nodes() if n.get('resource-id') == resource_id), None)

    def press_launch(self, button):
        self.step('Press Launch')
        start = self.watcher.mark()
        self.device.tap_node(button)
        try:
            self.watcher.wait_for(r'MinecraftLaunchTrace: .*Launch requested', 10, start)
        except TestFailure:
            texts = [n.get('text') for n in self.device.ui_nodes() if n.get('text')]
            raise TestFailure(f'pressing Launch did not start a launch; screen shows: {texts[:12]}')
        self.ok()
        return start

    def follow_milestones(self, start):
        self.step('Follow launch milestones')
        index = start
        for milestone in MILESTONES:
            index, m = self.watcher.wait_for(r'MinecraftLaunchTrace: \[\w+\] \+(\d+)ms ' + re.escape(milestone),
                                             self.args.timeout, index, describe=f'milestone "{milestone}"')
            print(f'     +{m.group(1):>6}ms {milestone}', flush=True)
        return index

    def check_game_libraries(self, start):
        self.step('Check game libraries loaded')
        expected = ['libfmod.so', 'libMediaDecoders_Android.so', 'libminecraftpe.so']
        if version_tuple(self.args.version) >= (1, 26, 70, 0):
            expected.append('libMallocRedirect.so')
        for lib in expected:
            _, m = self.watcher.wait_for(r'LeviPlaceholder: lib loaded ' + re.escape(lib) + r'(?: version=(\S+))?',
                                         15, start, describe=f'{lib} loaded')
            if lib == 'libminecraftpe.so' and m.group(1) != self.args.version:
                raise TestFailure(f'launcher loaded libminecraftpe.so built as {m.group(1)}, expected {self.args.version}')
        self.ok(', '.join(expected))

    def check_game_running(self, start):
        self.step('Check game main loop')
        self.watcher.wait_for(r'LeviPlaceholder: android_main start version=' + re.escape(self.args.version), 20, start,
                              describe='android_main start')
        _, m = self.watcher.wait_for(r'LeviPlaceholder: probes done ok=(\d+) fail=(\d+)', 20, start, describe='startup probes')
        if m.group(2) != '0':
            raise TestFailure(f'{m.group(2)} startup probes into the stub MainActivity failed')
        probes = m.group(1)
        self.watcher.wait_for(r'LeviPlaceholder: egl ready', 20, start, describe='EGL surface')
        self.watcher.wait_for(r'LeviPlaceholder: cmd RESUME', 20, start, describe='RESUME command')
        self.watcher.wait_for(r'LeviPlaceholder: frame first', 20, start, describe='first frame')
        frames = self.frames_advancing(start)
        top = self.device.top_activity()
        if not top.endswith('.core.minecraft.MinecraftActivity'):
            raise TestFailure(f'game activity is not in front (top: {top})')
        self.device.screenshot(os.path.join(self.out, 'game-running.png'))
        self.ok(f'{probes} probes, {frames} frames in heartbeat, top {top}')

    def frames_advancing(self, start):
        index, first = self.watcher.wait_for(r'LeviPlaceholder: heartbeat frames=(\d+)', 15, start, describe='heartbeat')
        _, second = self.watcher.wait_for(r'LeviPlaceholder: heartbeat frames=(\d+)', 15, index, describe='second heartbeat')
        if int(second.group(1)) <= int(first.group(1)):
            raise TestFailure(f'game stopped rendering (frames {first.group(1)} -> {second.group(1)})')
        return int(second.group(1))

    def check_input(self):
        self.step('Check input reaches the game')
        start = self.watcher.mark()
        self.device.shell('input keyevent KEYCODE_A')
        self.watcher.wait_for(r'LeviPlaceholder: (input key code=29 |jni com\.mojang\.minecraftpe\.MainActivity\.nativeKeyHandler\(29,)',
                              10, start, describe='key event in the game')
        start = self.watcher.mark()
        self.device.shell('input tap 600 500')
        self.watcher.wait_for(r'LeviPlaceholder: input motion', 10, start, describe='touch event in the game')
        self.ok()

    def check_background_cycle(self):
        self.step('Check background and foreground cycle')
        start = self.watcher.mark()
        self.device.shell('input keyevent KEYCODE_HOME')
        index, _ = self.watcher.wait_for(r'LeviPlaceholder: cmd PAUSE', 10, start, describe='PAUSE command')
        self.watcher.wait_for(r'LeviPlaceholder: jni com\.mojang\.minecraftpe\.MainActivity\.nativeSuspend\(\)', 10, start,
                              describe='nativeSuspend')
        self.watcher.wait_for(r'LeviPlaceholder: cmd STOP', 10, start, describe='STOP command')
        time.sleep(2)
        start = self.watcher.mark()
        self.device.shell(f'monkey -p {LAUNCHER_PACKAGE} -c android.intent.category.LAUNCHER 1')
        self.watcher.wait_for(r'LeviPlaceholder: cmd RESUME', 15, start, describe='RESUME after returning')
        frames = self.frames_advancing(start)
        top = self.device.top_activity()
        if not top.endswith('.core.minecraft.MinecraftActivity'):
            raise TestFailure(f'returning to the launcher task did not resume the game (top: {top})')
        self.ok(f'rendering again, {frames} frames')

    def check_stability(self):
        self.step(f'Check game stays up for {self.args.soak}s')
        start = self.watcher.mark()
        end = time.monotonic() + self.args.soak
        while time.monotonic() < end:
            time.sleep(1)
            self.watcher.check_failures(start)
        frames = self.frames_advancing(start)
        crashes = self.device.run('logcat', '-d', '-b', 'crash', check=False)
        if LAUNCHER_PACKAGE in crashes:
            raise TestFailure('crash buffer has entries for the launcher process')
        self.ok(f'{frames} frames')

    # -- driver --------------------------------------------------------------------------------
    def execute(self):
        try:
            self.check_local_setup()
            if not self.args.skip_build:
                self.build()
            self.install()
            button = self.start_launcher()
            start = self.press_launch(button)
            self.follow_milestones(start)
            self.check_game_libraries(start)
            self.check_game_running(start)
            self.check_input()
            self.check_background_cycle()
            self.check_stability()
            print(f'\nPASS: {len(self.steps)} steps, game {self.args.version}. Artifacts: {self.out}')
            return 0
        except TestFailure as error:
            print(f'     FAIL: {error}', flush=True)
            try:
                self.device.screenshot(os.path.join(self.out, 'failure.png'))
            except Exception:
                pass
            print(f'\nFAIL at step "{self.steps[-1] if self.steps else "setup"}". Artifacts: {self.out}')
            return 1
        finally:
            if self.watcher:
                self.watcher.stop()
                with open(os.path.join(self.out, 'logcat-filtered.txt'), 'w', encoding='utf-8') as fh:
                    fh.write('\n'.join(self.watcher.lines))
            try:
                full = self.device.run('logcat', '-d', '-v', 'threadtime', check=False, timeout=60)
                with open(os.path.join(self.out, 'logcat-full.txt'), 'w', encoding='utf-8') as fh:
                    fh.write(full)
            except Exception:
                pass


def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--version', default='1.26.60.5', help='game version the placeholder reports (default 1.26.60.5)')
    ap.add_argument('--serial', help='adb serial of the device to use')
    ap.add_argument('--skip-build', action='store_true', help='install the APKs from the last build')
    ap.add_argument('--timeout', type=int, default=60, help='seconds allowed for each launch milestone')
    ap.add_argument('--soak', type=int, default=10, help='seconds the running game must stay healthy')
    args = ap.parse_args()
    sys.exit(Run(args).execute())


if __name__ == '__main__':
    main()
