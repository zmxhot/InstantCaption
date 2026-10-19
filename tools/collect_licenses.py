"""Refresh upstream license snapshots deliberately; review changes before releasing."""
from pathlib import Path
import hashlib, json, urllib.request, shutil, base64

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'app/src/main/assets/licenses'
SOURCES = {
    'GENIEX-BSD-3-Clause.txt': 'https://raw.githubusercontent.com/qualcomm/GenieX/main/LICENSE',
    'FunASR-MODEL-LICENSE.txt': 'https://api.github.com/repos/modelscope/FunASR/contents/MODEL_LICENSE',
    'CC-BY-4.0.txt': 'https://creativecommons.org/licenses/by/4.0/legalcode.txt',
    'GENIEX-0.8.0-POM.xml': 'https://repo.maven.apache.org/maven2/com/qualcomm/qti/geniex-android/0.8.0/geniex-android-0.8.0.pom',
}
def main():
    inventory = {}
    for name, url in SOURCES.items():
        request = urllib.request.Request(url, headers={'User-Agent': 'InstantCaption-license-review'})
        with urllib.request.urlopen(request, timeout=45) as response: data = response.read()
        if 'api.github.com' in url: data = base64.b64decode(json.loads(data)['content'])
        if len(data) < 200: raise RuntimeError('Empty license: ' + name)
        (DEST / name).write_bytes(data)
        inventory[name] = {'url': url, 'sha256': hashlib.sha256(data).hexdigest(), 'retrieved': '2026-10-18'}
    (DEST / 'snapshot-sources.json').write_text(json.dumps(inventory, indent=2) + '\n', encoding='utf8')
    shutil.copyfile(ROOT / 'THIRD_PARTY/X-ASR-LICENSE.txt', ROOT / 'app/src/main/assets/X-ASR-LICENSE.txt')
    print('Saved four license/metadata snapshots and X-ASR attribution.')
if __name__ == '__main__': main()
