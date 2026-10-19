"""Create a reviewable source archive with an explicit allowlist, no local binaries/secrets."""
from pathlib import Path
import hashlib, json, re, zipfile

ROOT = Path(__file__).resolve().parents[1]
ROOT_FILES = ['README.md','README.zh-CN.md','LICENSE','NOTICE','PRIVACY.md','SECURITY.md',
              'CONTRIBUTING.md','CHANGELOG.md','.gitignore','build.gradle','settings.gradle',
              'gradle.properties','gradlew','gradlew.bat','build.ps1','prepare_qwen_runtime.py',
              'prepare_translation_runtime.py','prepare_litert_runtime.py','prepare_cpu_runtime.py']
TREES = ['.github','docs','tools','gradle/wrapper','app/src/main','app/src/androidTest/java',
         'app/src/npu','app/src/npuNoNetwork','app/src/offline','app/src/full','app/src/portable']
SECRET = re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{50,}|AKIA[A-Z0-9]{16}')

def source_files():
    paths = [ROOT/p for p in ROOT_FILES]+[ROOT/'app/build.gradle']
    for folder in TREES:
        paths += [p for p in (ROOT/folder).rglob('*') if p.is_file()]
    result = []
    for path in sorted(set(paths)):
        relative = path.relative_to(ROOT)
        if '__pycache__' in relative.parts or 'jniLibs' in relative.parts: continue
        if path.suffix.lower() in {'.pyc','.so','.aar','.apk','.aab','.p12','.jks','.keystore','.wav','.bin'}: continue
        if relative.as_posix() == 'app/src/npu/assets/X-ASR-LICENSE.txt': continue  # now in main
        if not path.is_file(): raise RuntimeError('Required source missing: ' + str(relative))
        if SECRET.search(path.read_bytes()): raise RuntimeError('Potential credential in ' + str(relative))
        if path.stat().st_size > 5_000_000: raise RuntimeError('Unexpected large source: ' + str(relative))
        result.append(path)
    return result

def main():
    out=ROOT/'dist';out.mkdir(exist_ok=True)
    records=[]
    version=re.search(r"versionName '([^']+)'",(ROOT/'app/build.gradle').read_text(encoding='utf8')).group(1)
    with zipfile.ZipFile(out/f'instant-caption-{version}-source.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for path in source_files():
            data=path.read_bytes();name=path.relative_to(ROOT).as_posix()
            entry=zipfile.ZipInfo('InstantCaption/'+name,date_time=(2026,10,18,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED
            entry.external_attr=(0o100755 if name=='gradlew' else 0o100644)<<16
            archive.writestr(entry,data)
            records.append({'file':name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
    (out/'source-manifest.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf8')
    print(f'Exported {len(records)} reviewed source paths; local recordings, keys, device logs and runtime caches excluded.')

if __name__=='__main__':main()
