"""Test production SshBridge against ephemeral SSH servers; never uses app data.

Run after Gradle assembleDebug. Requires Python + paramiko and JDK 17.
JAVA_HOME/ANDROID_HOME may override the local defaults. Output is under app/build.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time

import paramiko

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'app/build/security-probe'
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
JDK = Path(os.environ.get('JAVA_HOME', 'C:/SDK/jdk-17.0.17.10-hotspot')) / 'bin'
SDK = Path(os.environ.get('ANDROID_HOME', 'C:/SDK/Android'))
JSCH = next((CACHE / 'com.github.mwiede/jsch/0.2.21').rglob('*.jar'))
KOTLIN = next((CACHE / 'org.jetbrains.kotlin/kotlin-stdlib/2.0.21').rglob('*.jar'))
BUILD.mkdir(parents=True, exist_ok=True)
CP = os.pathsep.join(map(str, [BUILD, ROOT / 'app/build/tmp/kotlin-classes/debug', JSCH,
                               KOTLIN, SDK / 'platforms/android-35/android.jar']))
subprocess.run([str(JDK / 'javac.exe'), '-cp', CP, '-d', str(BUILD),
                str(ROOT / 'tests/HostKeyProbe.java')], check=True)
key = paramiko.RSAKey.generate(2048)
expected = 'SHA256:' + base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')
results = []

for mode in ('changed', 'unknown-rejected', 'accepted', 'known'):
    seen = []

    class Server(paramiko.ServerInterface):
        def get_allowed_auths(self, username):
            return 'password'

        def check_auth_password(self, username, password):
            seen.append(username == 'test-only-user' and password == 'synthetic-test-password')
            return paramiko.AUTH_SUCCESSFUL

    listener = socket.socket()
    listener.bind(('127.0.0.1', 0))
    listener.listen(1)
    listener.settimeout(15)
    port = listener.getsockname()[1]

    def serve():
        transport = None
        try:
            client, _ = listener.accept()
            transport = paramiko.Transport(client)
            transport.add_server_key(key)
            transport.start_server(server=Server())
            while transport.is_active():
                time.sleep(.02)
        except (EOFError, OSError, paramiko.SSHException):
            pass
        finally:
            if transport:
                transport.close()
            listener.close()

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()
    run = subprocess.run([str(JDK / 'java.exe'), '-cp', CP, 'HostKeyProbe', str(port), mode,
                          key.get_base64()], capture_output=True, text=True, timeout=20)
    thread.join(5)
    assert run.returncode == 0, run.stdout + run.stderr
    accepted = mode in ('accepted', 'known')
    assert len(seen) == int(accepted) and all(seen), (mode, seen)
    prompts = [line[7:] for line in run.stdout.splitlines() if line.startswith('PROMPT ')]
    assert not prompts or prompts == [expected], prompts
    results.append({'scenario': mode, 'authentication_calls': len(seen),
                    'standard_fingerprint': not prompts or prompts == [expected], 'passed': True})

(BUILD / 'results.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
print(json.dumps(results, indent=2))
