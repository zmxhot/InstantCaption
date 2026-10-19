"""Import upstream QNN release assets into per-SoC download manifests.

Only recipes implemented by the app are enabled. The fetched release inventory
also records other packages for review; upstream support is not a handset test.
Downloads are hash checked against GitHub's published asset digest, and every
installed member receives its own hash. Existing installed-model IDs are stable.
"""
from pathlib import Path, PurePosixPath
import argparse, concurrent.futures, copy, hashlib, json, re, shutil, tarfile, urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'
CACHE = ROOT / '.runtime-cache/npu-catalog'
RELEASE = 'https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/asr-models-qnn-binary'
CHIPS = {
    'SM8350': ('Snapdragon 888', 'snapdragon-888', '68'),
    'SM8450': ('Snapdragon 8 Gen 1', 'snapdragon-8-gen1', '69'),
    'SM8475': ('Snapdragon 8+ Gen 1', 'snapdragon-8-plus-gen1', '69'),
    'SM8550': ('Snapdragon 8 Gen 2', 'snapdragon-8-gen2', '73'),
    'SM8650': ('Snapdragon 8 Gen 3', 'snapdragon-8-gen3', '75'),
}
RECIPES = {
    'x-asr': ('x-asr-streaming-zipformer-transducer-zh-en-punct-2026-06-05-chunk-size-480ms', ['encoder.bin', 'decoder.bin', 'joiner.bin', 'tokens.txt'], '480ms'),
    'sensevoice': ('5-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8', ['model.bin', 'tokens.txt'], '5s'),
    'zipformer': ('streaming-zipformer-transducer-zh-en-2023-03-20-chunk-size-32', ['encoder.bin', 'decoder.bin', 'joiner.bin', 'tokens.txt'], '32'),
}

def digest(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def read_url(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'InstantCaption-catalog-maintenance'}), timeout=90)

def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8')
    tmp.replace(path)

def package(item):
    chip, mid, asset = item
    path = CACHE / asset['name']
    expected = asset.get('digest', '').removeprefix('sha256:')
    if not re.fullmatch('[a-f0-9]{64}', expected):
        raise ValueError('Upstream asset has no SHA-256: ' + asset['name'])
    if not path.exists():
        partial = path.with_suffix(path.suffix + '.part')
        print('Downloading', chip, mid, flush=True)
        with read_url(asset['browser_download_url']) as src, partial.open('wb') as out:
            shutil.copyfileobj(src, out, 1024 * 1024)
        if partial.stat().st_size != asset['size'] or digest(partial) != expected:
            raise ValueError('Upstream checksum mismatch: ' + asset['name'])
        partial.replace(path)
    if path.stat().st_size != asset['size'] or digest(path) != expected:
        raise ValueError('Cached checksum mismatch: ' + asset['name'])
    files = {}
    with tarfile.open(path, 'r|bz2') as archive:
        for member in archive:
            name = PurePosixPath(member.name).name
            if name not in RECIPES[mid][1]:
                continue
            if not member.isfile() or '..' in PurePosixPath(member.name).parts or name in files:
                raise ValueError('Invalid/duplicate model member: ' + member.name)
            with archive.extractfile(member) as src:
                sha = hashlib.file_digest(src, 'sha256').hexdigest()
            files[name] = {'name': name, 'size': member.size, 'sha256': sha}
    if set(files) != set(RECIPES[mid][1]):
        raise ValueError('Missing model members: ' + asset['name'])
    url = asset['browser_download_url']
    spec = {'directory': f'qnn-{mid}-{chip.lower()}-{RECIPES[mid][2]}',
            'upstream': {'release': RELEASE, 'assetId': asset['id'], 'chipset': chip, 'modelFormat': 'qnn-context-binary'},
            'files': [files[n] for n in RECIPES[mid][1]],
            'download': {'format': 'tar.bz2', 'size': asset['size'], 'sha256': expected,
                         'urls': [{'name': 'GitHub', 'url': url},
                                  {'name': 'GitHub mirror · gh-proxy.org', 'url': 'https://gh-proxy.org/' + url},
                                  {'name': 'GitHub mirror · ghfast.top', 'url': 'https://ghfast.top/' + url}]}}
    print('Verified', chip, mid, flush=True)
    return chip, mid, spec

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--refresh', action='store_true', help='Refresh the upstream release snapshot')
    parser.add_argument('--prepare', action='store_true', help='Download supported recipes and update the app catalog')
    args = parser.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    snapshot = CACHE / 'sherpa-release.json'
    if args.refresh or not snapshot.exists():
        with read_url(RELEASE) as response:
            release = json.load(response)
        write_json(snapshot, release)
    release = json.loads(snapshot.read_text(encoding='utf8'))
    assets = {a['name']: a for a in release['assets']}
    selected = []
    for chip in CHIPS:
        for mid, (suffix, _, _) in RECIPES.items():
            name = f'sherpa-onnx-qnn-{chip}-binary-{suffix}.tar.bz2'
            if name in assets:
                selected.append((chip, mid, assets[name]))
    inventory = {'schemaVersion': 1, 'source': RELEASE, 'releaseId': release['id'],
                 'mobileChipsets': sorted({m.group() for a in assets if (m := re.search(r'SM\d+', a))}),
                 'packages': [{'name': a['name'], 'url': a['browser_download_url'], 'size': a['size'], 'sha256': (a.get('digest') or '').removeprefix('sha256:')}
                              for a in assets.values() if re.search(r'SM\d+', a['name'])],
                 'selectedAdditionalPackages': [{'chipset': c, 'model': m, 'asset': a['name']} for c, m, a in selected]}
    write_json(ROOT / 'docs/catalog/sherpa-upstream.json', inventory)
    if not args.prepare:
        print(json.dumps({'upstreamPackages': len(inventory['packages']), 'additionalPackages': len(selected)}))
        return
    manifests = {chip: {} for chip in CHIPS}
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for chip, mid, spec in pool.map(package, selected):
            manifests[chip][mid] = spec
    catalog_path = ASSETS / 'device-model-catalog.json'
    catalog = json.loads(catalog_path.read_text(encoding='utf8'))
    templates = {m['id']: m for p in catalog['profiles'] if p['id'] == 'sm8750' for m in p['models']}
    catalog['profiles'] = [p for p in catalog['profiles'] if p['id'] not in {c.lower() for c in CHIPS}]
    catalog['chipsets'] = [c for c in catalog['chipsets'] if c['id'] not in {v[1] for v in CHIPS.values()}]
    chip_rules = []
    for chip, (label, rule_id, arch) in CHIPS.items():
        manifest = manifests[chip]
        if not manifest:
            continue
        asset_name = f'npu-models-{chip.lower()}.json'
        write_json(ASSETS / asset_name, manifest)
        models = []
        for mid, spec in manifest.items():
            rule = copy.deepcopy(templates[mid]);rule['manifest'] = asset_name;rule['directory'] = spec['directory']
            # Android 12 exposes the SoC ID used by these chip-specific binaries.
            rule['minAndroid'] = 31;rule['androidVersion'] = '12'
            models.append(rule)
        catalog['profiles'].append({'id': chip.lower(), 'extends': 'portable', 'npuChip': chip,
            'documentationBased': True,
            'requiredLibraries': [f'libQnnHtpV{arch}Stub.so', f'libQnnHtpV{arch}Skel.so', 'libQnnHtp.so', 'libQnnSystem.so'],
            'models': models})
        marketing = re.escape(label.upper()).replace(r'\ ', ' ')
        chip_rules.append({'id': rule_id, 'name': label, 'profile': chip.lower(), 'match': {'soc': [
            rf'(?:QUALCOMM(?: TECHNOLOGIES,? INC\.?)? |QTI )?{chip}(?:-[A-Z0-9]+)*',
            rf'(?:QUALCOMM )?{marketing}(?: FOR GALAXY)?(?: MOBILE PLATFORM)?']}})
    catalog['chipsets'] = chip_rules + catalog['chipsets']
    if RELEASE not in catalog['sources']:
        catalog['sources'].append(RELEASE)
    write_json(catalog_path, catalog)
    print(json.dumps({'preparedPackages': len(selected), 'newNpuProfiles': len(manifests)}))

if __name__ == '__main__':
    main()
