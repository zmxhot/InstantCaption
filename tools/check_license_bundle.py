"""Verify retained license texts against their manifest and, when cached, upstream AARs."""
import hashlib, json, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LICENSES = ROOT / 'app/src/main/assets/licenses'

def check():
    manifest = json.loads((LICENSES / 'runtime-license-sources.json').read_text(encoding='utf8'))
    checked = 0
    for name, entry in manifest['files'].items():
        data = (LICENSES / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != entry['sha256']:
            raise RuntimeError('License snapshot changed: ' + name)
        if 'derivedFrom' in entry:
            source = (LICENSES / entry['derivedFrom']).read_bytes()
            if hashlib.sha256(source).hexdigest() != entry['sourceSha256']:
                raise RuntimeError('License PDF changed: ' + name)
        if 'artifactUrl' in entry:
            archive = ROOT / '.runtime-cache' / entry['artifactUrl'].rsplit('/', 1)[-1]
            if archive.exists():
                if hashlib.sha256(archive.read_bytes()).hexdigest() != entry['artifactSha256']:
                    raise RuntimeError('Upstream artifact changed: ' + str(archive))
                with zipfile.ZipFile(archive) as source:
                    if source.read(entry['entry']) != data:
                        raise RuntimeError('License differs from upstream artifact: ' + name)
                checked += 1
    print(f"Verified {len(manifest['files'])} license snapshots; {checked} matched cached artifact entries.")

if __name__ == '__main__': check()
