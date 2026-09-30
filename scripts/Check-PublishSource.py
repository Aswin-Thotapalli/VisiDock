"""Reject client API keys and local-only configuration in the staged Git source."""
import re
import subprocess

blocked = {'app/google-services.json', 'backend/auth/public-config.json', 'backend/auth/public/app.js'}
entries = subprocess.check_output(['git', 'ls-files', '--stage', '-z']).split(b'\0')
failures = []
for entry in entries:
    if not entry:
        continue
    metadata, raw_path = entry.split(b'\t', 1)
    path = raw_path.decode('utf-8')
    if path in blocked or path.lower().endswith(('.aab', '.apk', '.apks', '.p12', '.jks', '.keystore')):
        failures.append(path)
        continue
    blob = metadata.split()[1].decode('ascii')
    data = subprocess.check_output(['git', 'cat-file', 'blob', blob])
    if re.search(rb'AIza[0-9A-Za-z_-]{30,}', data):
        failures.append(path)
if failures:
    print('Publication blocked. Remove local-only configuration or API keys from:')
    print('\n'.join(failures))
    raise SystemExit(1)
print('Staged source contains no Google API key patterns or prohibited local files.')
