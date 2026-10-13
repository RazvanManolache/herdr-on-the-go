"""Create a private local Android signing identity, outside this repository.

Run once. Never prints secrets. Windows: the parent directory should be private
to the publishing account. Existing keys are reused and never overwritten.
"""
import os
from pathlib import Path
import secrets
import subprocess

directory = Path.home() / '.android/signing/herdr-on-the-go'
directory.mkdir(parents=True, exist_ok=True)
store = directory / 'release.p12'
properties = directory / 'release.properties'
if store.exists() or properties.exists():
    if not (store.exists() and properties.exists()):
        raise SystemExit('Incomplete existing signing identity: inspect it manually; nothing overwritten.')
    print('Existing signing identity reused.')
else:
    password = secrets.token_urlsafe(48)
    env = dict(os.environ, HERDR_KEYSTORE_PASSWORD=password)
    keytool = Path(os.environ['JAVA_HOME']) / 'bin/keytool.exe'
    subprocess.run([str(keytool), '-genkeypair', '-keystore', str(store), '-storetype', 'PKCS12',
                    '-storepass:env', 'HERDR_KEYSTORE_PASSWORD', '-keypass:env', 'HERDR_KEYSTORE_PASSWORD',
                    '-alias', 'herdr-on-the-go', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                    '-dname', 'CN=Razvan Manolache, OU=Herdr on the Go, O=Independent Open Source', '-noprompt'],
                   env=env, check=True, capture_output=True)
    properties.write_text(f'storeFile={store.as_posix()}\nstorePassword={password}\n'
                          f'keyAlias=herdr-on-the-go\nkeyPassword={password}\n', encoding='utf-8')
    print('Created signing identity outside the checkout. Back it up privately for future updates.')
print('Set HERDR_SIGNING_PROPERTIES to ' + str(properties))
