"""Fresh explicit emulator -> production TLS pairing -> disposable real Herdr session.

Only synthetic credentials are approved. No real account, session, or app data is used.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT.parent / 'herdr-bridge/bridge'
sys.path.insert(0, str(BRIDGE))
import bridge_common
import paired_server
from pairing_store import PairingStore

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-'): raise SystemExit('Fixture is emulator-only')
ADB = Path(os.environ.get('ANDROID_HOME', 'C:/SDK/Android')) / 'platform-tools/adb.exe'
name = 'paired-check-' + uuid.uuid4().hex[:12]
fixture_token, marker = secrets.token_hex(32), secrets.token_hex(8)
report_dir = ROOT / 'app/build/paired-bridge-check'
report_dir.mkdir(parents=True, exist_ok=True)
report = {'passed': False, 'session': name, 'serial': args.serial}
stop = threading.Event()
herdr = tls_server = run = None

with tempfile.TemporaryDirectory(prefix='herdr-paired-validation-') as temporary:
    home = Path(temporary)
    try:
        herdr = subprocess.Popen([bridge_common.HERDR, '--session', name, 'server'], cwd=home,
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            creationflags=subprocess.CREATE_NO_WINDOW)
        for _ in range(40):
            if bridge_common.run_cli(['--session', name, 'api', 'snapshot'], timeout=2).returncode == 0: break
            time.sleep(.25)
        else: raise RuntimeError('Disposable Herdr did not start')
        result = bridge_common.run_cli(['--session', name, 'workspace', 'create', '--cwd', str(home),
            '--label', 'Paired transport verification', '--no-focus'])
        assert result.returncode == 0, result.stderr
        def refresh():
            while not stop.is_set():
                try:
                    snapshot = json.loads(bridge_common.run_cli(['--session', name, 'api', 'snapshot']).stdout)['result']['snapshot']
                    cache = {'at': bridge_common.now_ms(), 'sessions': [{'name': name}],
                             'snapshots': {name: {'at': bridge_common.now_ms(), 'snapshot': snapshot}}}
                    pending = home / 'snapshot.next.json'
                    pending.write_text(json.dumps(cache), encoding='utf-8')
                    os.replace(pending, home / 'snapshot-cache.json')
                except Exception:
                    pass
                stop.wait(.5)
        threading.Thread(target=refresh, daemon=True).start()
        for _ in range(50):
            if (home / 'snapshot-cache.json').exists(): break
            time.sleep(.1)
        cert, key, fingerprint = paired_server.identity(home / 'paired')
        store = PairingStore(home / 'paired/devices.db')
        store.open_window()
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(cert, key)
        paired_server.discover_sessions = lambda: [{'name': name, 'reachable': True}]
        tls_server = paired_server.PairedServer(('127.0.0.1', 0), context, store,
            responder=BRIDGE / 'stdio-responder.py', bridge_home=home)
        threading.Thread(target=tls_server.serve_forever, daemon=True).start()
        for apk in [ROOT / 'app/build/outputs/apk/debug/app-debug.apk',
                    ROOT / 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']:
            subprocess.run([str(ADB), '-s', args.serial, 'install', '-r', str(apk)], check=True, capture_output=True)
        subprocess.run([str(ADB), '-s', args.serial, 'shell', 'pm', 'grant',
            'io.github.razvanmanolache.herdronthego', 'android.permission.POST_NOTIFICATIONS'], check=True, capture_output=True)
        command = [str(ADB), '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'io.github.razvanmanolache.herdronthego.PairedProtocolTest',
            '-e', 'pairedPort', str(tls_server.server_address[1]), '-e', 'pairedFingerprint', fingerprint,
            '-e', 'pairedSession', name, '-e', 'pairedMarker', marker, '-e', 'pairedFixtureToken', fixture_token,
            'io.github.razvanmanolache.herdronthego.test/io.github.razvanmanolache.herdronthego.IsolatedTestRunner']
        run = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        approved = revoked = False
        deadline = time.monotonic() + 150
        while run.poll() is None and time.monotonic() < deadline:
            read = subprocess.run([str(ADB), '-s', args.serial, 'exec-out', 'run-as',
                'io.github.razvanmanolache.herdronthego', 'cat', 'cache/paired-test-approval.json'], capture_output=True)
            try: state = json.loads(read.stdout)
            except (ValueError, UnicodeError): state = {}
            if state.get('fixture') == fixture_token:
                if 'code' in state and not approved:
                    pending = store.pending()
                    assert len(pending) == 1 and pending[0]['name'] == 'Synthetic test phone', 'Wrong-pin request reached the server'
                    assert store.approve(pending[0]['id'], state['code'])
                    approved = True
                if state.get('revoke') and not revoked:
                    store.revoke(state['revoke']); revoked = True
            time.sleep(.15)
        if run.poll() is None: run.kill()
        output = run.communicate(timeout=10)[0].decode(errors='replace')
        (report_dir / 'instrumentation.txt').write_text(output, encoding='utf-8')
        print(output)
        assert run.returncode == 0 and 'OK (2 tests)' in output and approved and revoked, output
        report.update(passed=True, tests=2, wrong_pin_rejected_before_pairing=True,
            phone_code_approved=True, rotation_retained=True, encrypted_credential_saved=True,
            real_cli_send_readback=True, active_revocation=True)
    finally:
        stop.set()
        if run and run.poll() is None: run.kill(); run.wait(timeout=5)
        if tls_server: tls_server.shutdown(); tls_server.server_close()
        if herdr:
            bridge_common.run_cli(['--session', name, 'server', 'stop'], timeout=10)
            try: herdr.wait(timeout=5)
            except subprocess.TimeoutExpired: herdr.kill(); herdr.wait()
            report['server_stopped'] = herdr.poll() is not None
        (report_dir / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
