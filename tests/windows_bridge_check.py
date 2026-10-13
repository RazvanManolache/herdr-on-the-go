"""Android emulator -> synthetic SSH -> installed Windows bridge -> disposable Herdr.

Requires: built debug + androidTest APKs, running emulator, paramiko, installed
Windows herdr-bridge. Uses no real passwords/accounts/panes. Never targets phones.
Run: python tests/windows_bridge_check.py --serial emulator-5556
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid

import paramiko

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT.parent / 'herdr-bridge/bridge'
sys.path.insert(0, str(BRIDGE))
from bridge_common import HERDR, HOME, run_cli

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    raise SystemExit('This fixture is emulator-only; it does not install onto phones.')
SDK = Path(os.environ.get('ANDROID_HOME', 'C:/SDK/Android'))
ADB = SDK / 'platform-tools/adb.exe'
name = 'android-check-' + uuid.uuid4().hex[:12]
token = uuid.uuid4().hex[:10]
wrapper = HOME / 'sh-log.exe'
spec = importlib.util.spec_from_file_location('poller', BRIDGE / 'snapshot-poller.py')
poller = importlib.util.module_from_spec(spec)
spec.loader.exec_module(poller)
key = paramiko.RSAKey.generate(2048)
listener = socket.socket()
listener.bind(('127.0.0.1', 0))
listener.listen(10)
listener.settimeout(.5)
port = listener.getsockname()[1]
stop = threading.Event()
transports, children, mutations, dropped = [], [], [], set()
report = {'session': name, 'serial': args.serial, 'passed': False}


def execute(channel, command):
    proc = None
    try:
        allowed = command in (
            '[ -x ~/.local/bin/herdr-mobile-bridge ] && echo OK || echo NO',
            '~/.local/bin/herdr-mobile-bridge --version',
            '~/.local/bin/herdr-mobile-bridge sessions --json',
            f"~/.local/bin/herdr-mobile-bridge stdio --session '{name}'")
        if not allowed:
            raise RuntimeError('Unexpected fixture command')
        proc = subprocess.Popen([str(wrapper), '-c', command], stdin=subprocess.PIPE,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        children.append(proc)
        if ' stdio ' not in command:
            output, _ = proc.communicate(timeout=20)
            if 'sessions --json' in command:
                output = json.dumps([s for s in json.loads(output) if s['name'] == name]).encode() + b'\n'
            channel.sendall(output)
            channel.send_exit_status(proc.returncode)
            return

        def receive():
            pending = b''
            try:
                while not stop.is_set():
                    data = channel.recv(32768)
                    if not data:
                        break
                    pending += data
                    while b'\n' in pending:
                        line, pending = pending.split(b'\n', 1)
                        obj = json.loads(line)
                        assert obj['sessionId'] == name
                        if obj['type'] == 'pane.sendText':
                            mutations.append({'key': obj['payload']['idempotencyKey'],
                                              'drop': 'DROP_ONCE_' in obj['payload']['text']})
                        if obj['type'] == 'raw.read':
                            assert 1 <= obj['payload']['lines'] <= 2000
                        if obj['type'] == 'timeline.subscribe':
                            assert len(obj['payload']['paneIds']) <= 64
                        proc.stdin.write(line + b'\n')
                        proc.stdin.flush()
            except (OSError, EOFError):
                pass
            finally:
                try:
                    proc.stdin.close()
                except OSError:
                    pass

        threading.Thread(target=receive, daemon=True).start()
        for line in proc.stdout:
            frame = json.loads(line)
            # Lose one completed verdict after the real bridge has durably recorded it.
            if frame['type'] == 'command.result' and mutations and mutations[-1]['drop']:
                request_key = mutations[-1]['key']
                if request_key not in dropped:
                    dropped.add(request_key)
                    channel.close()
                    return
            channel.sendall(line)
        channel.send_exit_status(proc.wait(timeout=5))
    except (OSError, EOFError, paramiko.SSHException):
        pass
    except Exception as exc:
        report.setdefault('fixture_errors', []).append(str(exc))
    finally:
        channel.close()
        if proc and proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=5)


class Server(paramiko.ServerInterface):
    def get_allowed_auths(self, username):
        return 'password'

    def check_auth_password(self, username, password):
        if username in ('test-only-user', 'test-second-user') and password == 'synthetic-test-password':
            return paramiko.AUTH_SUCCESSFUL
        return paramiko.AUTH_FAILED

    def check_channel_request(self, kind, chanid):
        return paramiko.OPEN_SUCCEEDED if kind == 'session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_exec_request(self, channel, command):
        threading.Thread(target=execute, args=(channel, command.decode()), daemon=True).start()
        return True


def connection(client):
    transport = paramiko.Transport(client)
    channels = []  # Paramiko weakly retains channels; keep them alive through exec requests.
    transports.append(transport)
    try:
        transport.add_server_key(key)
        transport.start_server(server=Server())
        while transport.is_active() and not stop.wait(.05):
            channel = transport.accept(timeout=.1)
            if channel is not None:
                channels.append(channel)
            channels[:] = [c for c in channels if not c.closed]
    except (OSError, EOFError, paramiko.SSHException):
        pass
    finally:
        transport.close()


def serve():
    while not stop.is_set():
        try:
            client, _ = listener.accept()
            threading.Thread(target=connection, args=(client,), daemon=True).start()
        except socket.timeout:
            pass
        except OSError:
            return


server = None
build = ROOT / 'app/build/windows-bridge-check'
build.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='herdr-on-the-go-validation-') as cwd:
    try:
        server = subprocess.Popen([HERDR, '--session', name, 'server'], stdin=subprocess.DEVNULL,
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, cwd=cwd,
                                  creationflags=subprocess.CREATE_NO_WINDOW)
        for _ in range(40):
            if run_cli(['--session', name, 'api', 'snapshot'], timeout=2).returncode == 0:
                break
            time.sleep(.25)
        else:
            raise RuntimeError('Disposable Herdr session did not start')
        create = run_cli(['--session', name, 'workspace', 'create', '--cwd', cwd,
                          '--label', 'Android verification', '--no-focus'])
        assert create.returncode == 0, create.stderr
        other = run_cli(['--session', name, 'workspace', 'create', '--cwd', cwd,
                         '--label', 'Other verification space', '--no-focus'])
        assert other.returncode == 0, other.stderr
        poller.refresh()
        threading.Thread(target=serve, daemon=True).start()
        for apk in (ROOT / 'app/build/outputs/apk/debug/app-debug.apk',
                    ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
            subprocess.run([str(ADB), '-s', args.serial, 'install', '-r', str(apk)], check=True, capture_output=True)
        command = [str(ADB), '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                   '-e', 'class', 'io.github.razvanmanolache.herdronthego.ControllerTest',
                   '-e', 'bridgePort', str(port), '-e', 'bridgeSession', name,
                   '-e', 'bridgeToken', token,
                   'io.github.razvanmanolache.herdronthego.test/io.github.razvanmanolache.herdronthego.IsolatedTestRunner']
        run = subprocess.run(command, capture_output=True, text=True, timeout=240)
        (build / 'instrumentation.txt').write_text(run.stdout + run.stderr, encoding='utf-8')
        print(run.stdout, flush=True)
        assert run.returncode == 0 and re.search(r'OK \(\d+ tests?\)', run.stdout), run.stdout + run.stderr
        assert not report.get('fixture_errors'), report
        retries = [m['key'] for m in mutations if m['drop']]
        assert len(retries) == 2 and retries[0] == retries[1], retries
        time.sleep(.5)
        assert not any(t.is_active() for t in transports), 'Client leaked a live SSH transport'
        report.update(passed=True, tests=int(re.search(r'OK \((\d+) tests?\)', run.stdout)[1]),
                      lost_reply_retry_same_key=True, transports_closed=True,
                      cross_computer_space_navigation=True, mutation_count=len(mutations))
    finally:
        stop.set()
        listener.close()
        for transport in transports:
            transport.close()
        for child in children:
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=5)
        if server:
            run_cli(['--session', name, 'server', 'stop'], timeout=10)
            try:
                server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill()
                server.wait()
            poller.refresh()
            report['server_stopped'] = server.poll() is not None
        (build / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
